package io.github.andriibus.labus;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Расписание из Transitous — та же логика, что в веб-версии (data-layer.js). */
public final class Api {
    private static final String TAG = "LABus";
    static final String API = "https://api.transitous.org";
    static final String[] VERSIONS = {"v6", "v5", "v4", "v3", "v2", "v1"};
    static final String AACHEN = "50.7753,6.0839";
    static final int WINDOW_MIN = 45;
    static final int TIMEOUT_MS = 12_000;
    static final long STOP_CACHE_MS = 14L * 86_400_000L;

    static final class Stop {
        final String key, name, query;
        final Pattern match;
        Stop(String key, String name, String query, String re) {
            this.key = key; this.name = name; this.query = query;
            this.match = Pattern.compile(re, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }
        boolean is(String s) { return s != null && match.matcher(s).find(); }
    }

    static final Map<String, Stop> STOPS = new HashMap<>();
    static {
        STOPS.put("schanz", new Stop("schanz", "Schanz", "Schanz (Jakobstraße) Aachen", "schanz.*jakob"));
        STOPS.put("bushof", new Stop("bushof", "Bushof", "Bushof Aachen", "bushof"));
        STOPS.put("kaiserplatz", new Stop("kaiserplatz", "Kaiserplatz", "Kaiserplatz Aachen", "kaiserplatz"));
    }

    static String name(String key) { Stop s = STOPS.get(key); return s == null ? key : s.name; }

    public static final class Trip {
        public String key, line, headsign, track;
        public long dep, depSched, arr;
        public boolean realTime, cancelled;
        public int via;

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("key", key).put("line", line).put("headsign", headsign).put("track", track)
                    .put("dep", dep).put("depSched", depSched).put("arr", arr)
                    .put("realTime", realTime).put("cancelled", cancelled).put("via", via);
        }

        static Trip fromJson(JSONObject o) {
            Trip t = new Trip();
            t.key = o.optString("key"); t.line = o.optString("line"); t.headsign = o.optString("headsign");
            t.track = o.optString("track"); t.dep = o.optLong("dep"); t.depSched = o.optLong("depSched");
            t.arr = o.optLong("arr"); t.realTime = o.optBoolean("realTime"); t.cancelled = o.optBoolean("cancelled");
            t.via = o.optInt("via");
            return t;
        }

        /** Опоздание в минутах (0, если по расписанию). */
        public int lateMin() { return (int) Math.round((dep - depSched) / 60000.0); }
    }

    /** Результат загрузки направления: рейсы + время получения. */
    public static final class Board {
        public final List<Trip> trips;
        public final long at;
        Board(List<Trip> trips, long at) { this.trips = trips; this.at = at; }
    }

    static final class HttpErr extends IOException {
        int status; boolean network, apiError;
        HttpErr(String m) { super(m); }
    }

    // ===== Сеть =====
    static Object getJSON(String url) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(TIMEOUT_MS);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("User-Agent", "LABus-Android/" + BuildConfig.VERSION_NAME);
                int code;
                try {
                    code = c.getResponseCode();
                } catch (IOException e) {
                    HttpErr err = new HttpErr("network: " + e.getMessage());
                    err.network = true;
                    last = err;
                    if (attempt == 0) sleep(1500);
                    continue;
                }
                if (code >= 200 && code < 300) {
                    String body = read(c.getInputStream());
                    try { return new JSONTokener(body).nextValue(); }
                    catch (JSONException e) { throw new IOException("bad json"); }
                }
                HttpErr err = new HttpErr("HTTP " + code);
                err.status = code;
                try {
                    String body = read(c.getErrorStream());
                    Object o = new JSONTokener(body).nextValue();
                    if (o instanceof JSONObject && ((JSONObject) o).has("error")) {
                        err.apiError = true;
                    }
                } catch (Exception ignored) { }
                if (code < 500) throw err;
                last = err;
                if (attempt == 0) sleep(1500);
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw last;
    }

    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    static String query(Map<String, String> p) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    static Object apiGet(Context ctx, String endpoint, Map<String, String> params) throws IOException {
        Prefs p = Prefs.of(ctx);
        String known = p.getString("ver:" + endpoint, null);
        List<String> order = new ArrayList<>();
        if (known != null) order.add(known);
        for (String v : VERSIONS) if (!v.equals(known)) order.add(v);
        IOException last = null;
        for (String v : order) {
            try {
                Object data = getJSON(API + "/api/" + v + "/" + endpoint + "?" + query(params));
                if (!v.equals(known)) p.putString("ver:" + endpoint, v);
                return data;
            } catch (HttpErr e) {
                last = e;
                if (!(e.status == 404 && !e.apiError)) break;
            }
        }
        throw last != null ? last : new IOException("no api");
    }

    // ===== ID остановки (с кэшем на 14 дней) =====
    static String resolveStop(Context ctx, String key, boolean force) throws IOException {
        Prefs p = Prefs.of(ctx);
        String ck = "stop3:" + key;
        if (!force) {
            try {
                JSONObject c = new JSONObject(p.getString(ck, "{}"));
                if (c.has("id") && System.currentTimeMillis() - c.optLong("t") < STOP_CACHE_MS) return c.getString("id");
            } catch (JSONException ignored) { }
        }
        Stop s = STOPS.get(key);
        String url = API + "/api/v1/geocode?type=STOP&numResults=20&place=" + AACHEN + "&text=" + enc(s.query);
        Object res = getJSON(url);
        JSONArray arr = res instanceof JSONArray ? (JSONArray) res : new JSONArray();
        JSONObject hit = null, firstDe = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null || !s.is(m.optString("name"))) continue;
            boolean inAachen = false;
            JSONArray areas = m.optJSONArray("areas");
            if (areas != null) for (int j = 0; j < areas.length(); j++) {
                JSONObject a = areas.optJSONObject(j);
                if (a != null && "aachen".equalsIgnoreCase(a.optString("name"))) inAachen = true;
            }
            if (!inAachen) continue;
            if (hit == null) hit = m;
            if (firstDe == null && m.optString("id").startsWith("de-")) firstDe = m;
        }
        if (firstDe != null) hit = firstDe;
        if (hit == null) throw new IOException("Остановка «" + s.name + "» не найдена");
        try {
            p.putString(ck, new JSONObject().put("id", hit.optString("id")).put("t", System.currentTimeMillis()).toString());
        } catch (JSONException ignored) { }
        return hit.optString("id");
    }

    static long t(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try { return Instant.parse(iso).toEpochMilli(); }
        catch (Exception e) {
            try { return OffsetDateTime.parse(iso).toInstant().toEpochMilli(); }
            catch (Exception e2) { return 0; }
        }
    }

    static String str(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return "";
        return o.optString(k, "");
    }

    // ===== Способ 1: табло отправлений + следующие остановки =====
    static List<Trip> boardTrips(Context ctx, String fromId, String fromKey, String toKey, String time) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("stopId", fromId);
        params.put("time", time);
        params.put("n", "30");
        params.put("window", String.valueOf(WINDOW_MIN * 60));
        params.put("mode", "BUS");
        params.put("fetchStops", "true");
        Object data = apiGet(ctx, "stoptimes", params);
        JSONArray list = data instanceof JSONObject ? ((JSONObject) data).optJSONArray("stopTimes") : null;
        if (list == null) list = new JSONArray();
        boolean anyStops = false;
        for (int i = 0; i < list.length(); i++) {
            JSONObject x = list.optJSONObject(i);
            if (x != null && x.optJSONArray("nextStops") != null) { anyStops = true; break; }
        }
        if (list.length() > 0 && !anyStops) throw new IOException("сервер не вернул следующие остановки");
        Stop from = STOPS.get(fromKey), to = STOPS.get(toKey);
        List<Trip> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject x = list.optJSONObject(i);
            if (x == null) continue;
            JSONObject p = x.optJSONObject("place");
            if (p == null || !from.is(str(p, "name"))) continue;
            if ("NOT_ALLOWED".equals(str(x, "pickupDropoffType"))) continue;
            JSONArray ns = x.optJSONArray("nextStops");
            int di = -1;
            JSONObject d = null;
            if (ns != null) for (int j = 0; j < ns.length(); j++) {
                JSONObject n = ns.optJSONObject(j);
                if (n != null && to.is(str(n, "name"))) { di = j; d = n; break; }
            }
            String depS = str(p, "departure");
            if (d == null || depS.isEmpty()) continue;
            Trip tr = new Trip();
            String line = str(x, "routeShortName");
            if (line.isEmpty()) line = str(x, "displayName");
            if (line.isEmpty()) line = "Bus";
            String sched = str(p, "scheduledDeparture");
            if (sched.isEmpty()) sched = depS;
            tr.key = str(x, "routeShortName") + "|" + sched;
            tr.line = line;
            tr.dep = t(depS);
            tr.depSched = t(sched);
            String arr = str(d, "arrival");
            if (arr.isEmpty()) arr = str(d, "departure");
            tr.arr = t(arr);
            tr.realTime = x.optBoolean("realTime");
            tr.cancelled = x.optBoolean("cancelled") || x.optBoolean("tripCancelled") || d.optBoolean("cancelled");
            tr.headsign = str(x, "headsign");
            String trk = str(p, "track");
            if (trk.isEmpty()) trk = str(p, "scheduledTrack");
            tr.track = trk;
            tr.via = di;
            out.add(tr);
        }
        return finish(out);
    }

    // ===== Способ 2 (запасной): поиск маршрутов, только прямые автобусы =====
    static List<Trip> planTrips(Context ctx, String fromKey, String toKey, String time) throws IOException {
        String fromId = resolveStop(ctx, fromKey, false), toId = resolveStop(ctx, toKey, false);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("fromPlace", fromId);
        params.put("toPlace", toId);
        params.put("time", time);
        params.put("maxTransfers", "0");
        params.put("transitModes", "BUS");
        params.put("searchWindow", String.valueOf(WINDOW_MIN * 60));
        params.put("numItineraries", "10");
        Object data = apiGet(ctx, "plan", params);
        JSONArray its = data instanceof JSONObject ? ((JSONObject) data).optJSONArray("itineraries") : null;
        Stop from = STOPS.get(fromKey), to = STOPS.get(toKey);
        List<Trip> out = new ArrayList<>();
        if (its != null) for (int i = 0; i < its.length(); i++) {
            JSONObject it = its.optJSONObject(i);
            JSONArray legs = it == null ? null : it.optJSONArray("legs");
            if (legs == null) continue;
            JSONObject l = null;
            int transit = 0;
            for (int j = 0; j < legs.length(); j++) {
                JSONObject g = legs.optJSONObject(j);
                if (g != null && !"WALK".equals(str(g, "mode"))) { transit++; l = g; }
            }
            if (transit != 1) continue;
            JSONObject lf = l.optJSONObject("from"), lt = l.optJSONObject("to");
            if (!from.is(str(lf, "name")) || !to.is(str(lt, "name"))) continue;
            Trip tr = new Trip();
            String line = str(l, "routeShortName");
            if (line.isEmpty()) line = str(l, "displayName");
            if (line.isEmpty()) line = "Bus";
            tr.key = str(l, "routeShortName") + "|" + str(l, "scheduledStartTime");
            tr.line = line;
            tr.dep = t(str(l, "startTime"));
            tr.depSched = t(str(l, "scheduledStartTime"));
            if (tr.depSched == 0) tr.depSched = tr.dep;
            tr.arr = t(str(l, "endTime"));
            tr.realTime = l.optBoolean("realTime");
            tr.cancelled = l.optBoolean("cancelled");
            tr.headsign = str(l, "headsign");
            String trk = str(lf, "track");
            if (trk.isEmpty()) trk = str(lf, "scheduledTrack");
            tr.track = trk;
            JSONArray inter = l.optJSONArray("intermediateStops");
            tr.via = inter != null ? inter.length() : 3;
            out.add(tr);
        }
        return finish(out);
    }

    /** Дубликаты убираем, рейсы «в обход по кольцу» не показываем, сортируем по отправлению. */
    static List<Trip> finish(List<Trip> in) {
        Set<String> seen = new HashSet<>();
        List<Trip> uniq = new ArrayList<>();
        for (Trip t : in) if (t.dep > 0 && t.arr > 0 && seen.add(t.key)) uniq.add(t);
        Map<String, Trip> byArr = new LinkedHashMap<>();
        for (Trip t : uniq) {
            String k = t.line + "|" + t.arr;
            Trip prev = byArr.get(k);
            if (prev == null || t.dep > prev.dep) byArr.put(k, t);
        }
        List<Trip> u = new ArrayList<>(byArr.values());
        double min = 0;
        boolean has = false;
        for (Trip t : u) {
            if (t.cancelled) continue;
            double r = (t.arr - t.dep) / 60000.0;
            if (r > 0 && (!has || r < min)) { min = r; has = true; }
        }
        double limit = Math.max(2 * min, min + 8);
        List<Trip> out = new ArrayList<>();
        for (Trip t : u) if (!has || (t.arr - t.dep) / 60000.0 <= limit) out.add(t);
        out.sort((a, b) -> Long.compare(a.dep, b.dep));
        return out;
    }

    // ===== Загрузка направления с запасными путями и кэшем =====
    public static Board load(Context ctx, String from, String to) throws IOException {
        String time = Instant.ofEpochMilli(System.currentTimeMillis() - 60_000).toString();
        List<Trip> trips;
        try {
            trips = boardTrips(ctx, resolveStop(ctx, from, false), from, to, time);
        } catch (IOException e1) {
            if (e1 instanceof HttpErr && ((HttpErr) e1).network) throw e1;
            Log.w(TAG, "способ 1: " + e1.getMessage());
            try {
                trips = boardTrips(ctx, resolveStop(ctx, from, true), from, to, time);
            } catch (IOException e2) {
                if (e2 instanceof HttpErr && ((HttpErr) e2).network) throw e2;
                Log.w(TAG, "способ 1, повтор: " + e2.getMessage());
                trips = planTrips(ctx, from, to, time);
            }
        }
        Board b = new Board(trips, System.currentTimeMillis());
        save(ctx, from, to, b);
        return b;
    }

    /** Загрузить, а при ошибке вернуть сохранённое (или null). */
    public static Board loadOrCached(Context ctx, String from, String to) {
        try { return load(ctx, from, to); }
        catch (Exception e) {
            Log.w(TAG, "load " + from + ">" + to + ": " + e.getMessage());
            return cached(ctx, from, to);
        }
    }

    static void save(Context ctx, String from, String to, Board b) {
        try {
            JSONArray a = new JSONArray();
            for (Trip t : b.trips) a.put(t.toJson());
            Prefs.of(ctx).putString("board:" + from + ">" + to, new JSONObject().put("at", b.at).put("trips", a).toString());
        } catch (JSONException ignored) { }
    }

    public static Board cached(Context ctx, String from, String to) {
        String s = Prefs.of(ctx).getString("board:" + from + ">" + to, null);
        if (s == null) return null;
        try {
            JSONObject o = new JSONObject(s);
            JSONArray a = o.getJSONArray("trips");
            List<Trip> list = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) list.add(Trip.fromJson(a.getJSONObject(i)));
            return new Board(list, o.optLong("at"));
        } catch (JSONException e) { return null; }
    }

    /** Ближайшие ещё не ушедшие и не отменённые рейсы. */
    public static List<Trip> upcoming(Board b, long now, long minLeadMs) {
        List<Trip> out = new ArrayList<>();
        if (b == null) return out;
        for (Trip t : b.trips) if (!t.cancelled && t.dep - now >= minLeadMs) out.add(t);
        return out;
    }
}

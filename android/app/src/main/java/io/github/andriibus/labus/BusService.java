package io.github.andriibus.labus;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Следит за выбранным рейсом: раз в минуту обновляет расписание (учитывая опоздание),
 * держит в шторке обратный отсчёт и присылает напоминание за выбранное число минут.
 * Режим "test" — проверочное уведомление (используется при сборке).
 */
public class BusService extends Service {
    static final String TAG = "LABus";
    static final String ACTION_STOP = "io.github.andriibus.labus.STOP";
    static final int NID = 1, NID_LEAVE = 2;
    static final long FETCH_MS = 60_000, TICK_MS = 15_000;

    public static void start(Context ctx, String mode) {
        Intent i = new Intent(ctx, BusService.class).putExtra("mode", mode);
        try { ctx.startForegroundService(i); }
        catch (Exception e) { Log.w(TAG, "не удалось запустить слежение: " + e); }
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wl;
    private String mode, from, to, key;
    private long endAt, lastFetch;
    private boolean fetching, testAlertSent;
    private Api.Board board;

    @Override public IBinder onBind(Intent intent) { return null; }

    private void startFg(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NID, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notif.channels(this);
        // startForeground — сразу, в любом случае (иначе система закроет приложение)
        startFg(Notif.trip(this, "LA Bus", "Загружаю расписание…", false));

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if ("rem".equals(mode) || mode == null) Prefs.of(this).clearReminder();
            finish();
            return START_NOT_STICKY;
        }
        String m = intent == null ? "rem" : intent.getStringExtra("mode");
        if (m == null) m = "rem";
        long now = System.currentTimeMillis();
        Prefs p = Prefs.of(this);
        if ("test".equals(m)) {
            mode = "test";
            from = Prefs.HOME; to = p.work(); key = null;
            endAt = now + 3 * 60_000;
            testAlertSent = false;
        } else {
            JSONObject r = p.reminder();
            if (r == null) { finish(); return START_NOT_STICKY; }
            mode = "rem";
            loadReminder(r);
            if (now > endAt) { p.clearReminder(); finish(); return START_NOT_STICKY; }
        }
        board = Api.cached(this, from, to);
        lastFetch = 0;

        if (wl == null) {
            wl = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LABus:trip");
            wl.setReferenceCounted(false);
        }
        wl.acquire(Math.max(60_000, Math.min(endAt - now + 60_000, 2 * 3_600_000L)));

        h.removeCallbacks(tick);
        h.post(tick);
        Log.i(TAG, "слежение " + mode + " " + from + ">" + to + " " + (key == null ? "" : key));
        return START_NOT_STICKY;
    }

    private void loadReminder(JSONObject r) {
        key = r.optString("key");
        from = r.optString("from", Prefs.HOME);
        to = r.optString("to", "bushof");
        endAt = r.optLong("dep") + 2 * 60_000;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            if ("rem".equals(mode)) {
                JSONObject r = Prefs.of(BusService.this).reminder();
                if (r == null) { finish(); return; }                       // напоминание убрали
                if (!r.optString("key").equals(key)) { loadReminder(r); board = Api.cached(BusService.this, from, to); lastFetch = 0; }
                if (now >= endAt) { Prefs.of(BusService.this).clearReminder(); finish(); return; }
            } else if (now >= endAt) { finish(); return; }

            if (!fetching && now - lastFetch >= FETCH_MS) {
                fetching = true;
                final String f = from, t = to;
                io.execute(() -> {
                    Api.Board b = Api.loadOrCached(getApplicationContext(), f, t);
                    h.post(() -> {
                        fetching = false;
                        lastFetch = System.currentTimeMillis();
                        if (b != null && f.equals(from) && t.equals(to)) board = b;
                        update();
                        WidgetProvider.redraw(BusService.this);
                    });
                });
            }
            update();
            if (mode != null) h.postDelayed(this, TICK_MS);
        }
    };

    private void update() {
        if (mode == null) return;
        if ("test".equals(mode)) { updateTest(); return; }
        Prefs p = Prefs.of(this);
        JSONObject r = p.reminder();
        if (r == null) { finish(); return; }
        NotificationManager nm = getSystemService(NotificationManager.class);
        long now = System.currentTimeMillis();
        int lead = r.optInt("lead", 5);

        // актуальные данные рейса (опоздание, отмена, стоянка)
        Api.Trip t = null;
        if (board != null) for (Api.Trip x : board.trips) if (key.equals(x.key)) { t = x; break; }
        long dep = r.optLong("dep");
        String line = r.optString("line"), track = shortTrack(r.optString("track"));
        int late = 0;
        if (t != null) {
            dep = t.dep;
            line = t.line;
            if (!t.trackShort().isEmpty()) track = t.trackShort();
            late = t.lateMin();
            if (dep != r.optLong("dep")) {
                try { r.put("dep", dep); p.setReminder(r); } catch (JSONException ignored) { }
                endAt = dep + 2 * 60_000;
            }
        }
        String dest = Api.name(to);
        String head = line + " → " + dest + " в " + Notif.hm(dep) + (late > 0 ? " (+" + late + ")" : "");

        if (t != null && t.cancelled) {
            nm.notify(NID_LEAVE, Notif.leave(this, "Рейс отменён ✕", head + " отменён — открой LA Bus и выбери другой", 15 * 60_000));
            p.clearReminder();
            finish();
            return;
        }
        long left = dep - now;
        int mins = (int) Math.max(0, Math.floor(left / 60000.0));
        if (left <= lead * 60_000L) {
            String body = head + " · через " + mins + " мин" + (track.isEmpty() ? "" : " · " + track);
            nm.notify(NID_LEAVE, Notif.leave(this, "Пора выходить 🚌", body, Math.max(60_000, left + 60_000)));
            p.clearReminder();
            finish();
            return;
        }
        String text = "через " + mins + " мин · напомню за " + lead + " мин" + (track.isEmpty() ? "" : " · " + track);
        nm.notify(NID, Notif.trip(this, "🔔 " + head, text, true));
    }

    private void updateTest() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        long now = System.currentTimeMillis();
        List<Api.Trip> up = Api.upcoming(board, now, 0);
        String dest = Api.name(to);
        if (up.isEmpty()) {
            nm.notify(NID, Notif.trip(this, "Проверка", board == null ? "Нет связи" : "Нет автобусов в ближайшие 45 мин", false));
            return;
        }
        Api.Trip t = up.get(0);
        int mins = (int) Math.max(0, Math.floor((t.dep - now) / 60000.0));
        String body = t.line + " → " + dest + " в " + Notif.hm(t.dep) + " · через " + mins + " мин"
                + (t.trackShort().isEmpty() ? "" : " · " + t.trackShort());
        nm.notify(NID, Notif.trip(this, "🔔 " + t.line + " → " + dest + " в " + Notif.hm(t.dep), "через " + mins + " мин · проверка", true));
        if (!testAlertSent && lastFetch > 0) {
            testAlertSent = true;
            nm.notify(NID_LEAVE, Notif.leave(this, "Проверка: пора выходить 🚌", body, 3 * 60_000));
        }
    }

    static String shortTrack(String s) {
        return s == null ? "" : s.replaceAll("\\s*\\(.*\\)\\s*$", "").trim();
    }

    private void finish() {
        h.removeCallbacksAndMessages(null);
        mode = null;
        if (wl != null && wl.isHeld()) wl.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        Scheduler.reschedule(this);
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (wl != null && wl.isHeld()) wl.release();
        io.shutdownNow();
        super.onDestroy();
    }
}

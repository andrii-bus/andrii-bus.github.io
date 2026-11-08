package io.github.andriibus.labus;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.ZoneId;

/** Настройки и кэш приложения. */
public final class Prefs {
    static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    static final String HOME = "schanz";

    private final SharedPreferences sp;
    private Prefs(Context ctx) { sp = ctx.getApplicationContext().getSharedPreferences("labus", Context.MODE_PRIVATE); }
    public static Prefs of(Context ctx) { return new Prefs(ctx); }

    public String getString(String k, String def) { return sp.getString(k, def); }
    public void putString(String k, String v) { sp.edit().putString(k, v).apply(); }
    public long getLong(String k, long def) { return sp.getLong(k, def); }
    public void putLong(String k, long v) { sp.edit().putLong(k, v).apply(); }

    public String work() {
        String w = sp.getString("work", "bushof");
        return "kaiserplatz".equals(w) ? "kaiserplatz" : "bushof";
    }
    public void setWork(String w) {
        if ("bushof".equals(w) || "kaiserplatz".equals(w)) sp.edit().putString("work", w).apply();
    }

    /** Работа для виджета (переключается кнопкой ⇄ на самом виджете). */
    public String widgetWork() {
        String w = sp.getString("wwork", null);
        if (w == null) return work();
        return "kaiserplatz".equals(w) ? "kaiserplatz" : "bushof";
    }
    public String toggleWidgetWork() {
        String w = "bushof".equals(widgetWork()) ? "kaiserplatz" : "bushof";
        sp.edit().putString("wwork", w).apply();
        return w;
    }

    static JSONObject slotDefault(String from, String to) throws JSONException {
        return new JSONObject().put("on", false).put("from", from).put("to", to)
                .put("days", new JSONArray().put(1).put(2).put(3).put(4).put(5));
    }

    /** Настройки уведомлений (JSON, общий формат с веб-частью). */
    public JSONObject settings() {
        JSONObject d;
        try {
            d = new JSONObject()
                    .put("morning", slotDefault("07:30", "08:30"))
                    .put("evening", slotDefault("16:30", "17:30"))
                    .put("walkHome", 4).put("walkWork", 4).put("leave", true);
        } catch (JSONException e) { d = new JSONObject(); }
        try {
            JSONObject s = new JSONObject(sp.getString("settings", "{}"));
            for (java.util.Iterator<String> it = s.keys(); it.hasNext(); ) {
                String k = it.next();
                d.put(k, s.get(k));
            }
        } catch (JSONException ignored) { }
        return d;
    }

    public void saveSettings(String json) {
        try { new JSONObject(json); sp.edit().putString("settings", json).apply(); }
        catch (JSONException ignored) { }
    }

    /** Направление окна: утро — из дома на работу, вечер — с работы домой. */
    public String[] dirOf(String slot) {
        String w = work();
        return "evening".equals(slot) ? new String[]{w, HOME} : new String[]{HOME, w};
    }

    public int walkMin(String slot) {
        JSONObject s = settings();
        return Math.max(0, Math.min(60, "evening".equals(slot) ? s.optInt("walkWork", 4) : s.optInt("walkHome", 4)));
    }
}

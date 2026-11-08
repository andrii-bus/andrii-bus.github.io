package io.github.andriibus.labus;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Set;

/** Окна поездок («утро», «вечер») и будильники для них. */
public final class Scheduler {
    static final String ACTION_WINDOW = "io.github.andriibus.labus.WINDOW";
    static final String[] SLOTS = {"morning", "evening"};

    static LocalTime time(String s, LocalTime def) {
        try { return LocalTime.parse(s); } catch (Exception e) { return def; }
    }

    /** Первое окно, которое ещё не закончилось к моменту {@code after}: [начало, конец] или null. */
    static long[] nextWindow(Context ctx, String slot, long after) {
        Prefs p = Prefs.of(ctx);
        JSONObject s = p.settings().optJSONObject(slot);
        if (s == null || !s.optBoolean("on")) return null;
        LocalTime from = time(s.optString("from"), LocalTime.of(7, 30));
        LocalTime to = time(s.optString("to"), LocalTime.of(8, 30));
        if (!to.isAfter(from)) return null;
        Set<Integer> days = new HashSet<>();
        JSONArray d = s.optJSONArray("days");
        if (d != null) for (int i = 0; i < d.length(); i++) days.add(d.optInt(i));
        if (days.isEmpty()) return null;
        String skip = p.getString("skip:" + slot, "");
        LocalDate today = Instant.ofEpochMilli(after).atZone(Prefs.ZONE).toLocalDate();
        for (int i = 0; i < 9; i++) {
            LocalDate day = today.plusDays(i);
            if (!days.contains(day.getDayOfWeek().getValue())) continue;
            if (day.toString().equals(skip)) continue;
            long st = ZonedDateTime.of(day, from, Prefs.ZONE).toInstant().toEpochMilli();
            long en = ZonedDateTime.of(day, to, Prefs.ZONE).toInstant().toEpochMilli();
            if (after < en) return new long[]{st, en};
        }
        return null;
    }

    static boolean isActive(Context ctx, String slot, long now) {
        long[] w = nextWindow(ctx, slot, now);
        return w != null && now >= w[0] - 60_000 && now < w[1];
    }

    static String today() { return LocalDate.now(Prefs.ZONE).toString(); }

    static PendingIntent windowIntent(Context ctx, String slot) {
        Intent i = new Intent(ctx, Receiver.class).setAction(ACTION_WINDOW).putExtra("slot", slot);
        return PendingIntent.getBroadcast(ctx, "evening".equals(slot) ? 2 : 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void setExact(AlarmManager am, int type, long at, PendingIntent pi) {
        try { am.setExactAndAllowWhileIdle(type, at, pi); }
        catch (SecurityException e) { am.setAndAllowWhileIdle(type, at, pi); }
    }

    /**
     * Ставит будильники на начало следующих окон.
     * @param startActive если окно идёт прямо сейчас — запустить его (через пару секунд);
     *                    иначе будильник ставится на следующее окно после текущего.
     */
    public static void reschedule(Context ctx, boolean startActive) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        long now = System.currentTimeMillis();
        for (String slot : SLOTS) {
            PendingIntent pi = windowIntent(ctx, slot);
            am.cancel(pi);
            long[] w = nextWindow(ctx, slot, now);
            if (w == null) continue;
            boolean activeNow = now >= w[0] - 60_000;
            if (activeNow && !startActive) {
                w = nextWindow(ctx, slot, w[1]);
                if (w == null) continue;
            }
            long at = Math.max(w[0], now + 1500);
            setExact(am, AlarmManager.RTC_WAKEUP, at, pi);
            Log.i("LABus", "окно " + slot + " → " + Instant.ofEpochMilli(at));
        }
    }
}

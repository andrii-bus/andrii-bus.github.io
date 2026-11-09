package io.github.andriibus.labus;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Будильник для напоминания о конкретном рейсе.
 * Слежение начинается за 15 минут до «момента напоминания» (рейс может опаздывать),
 * а само напоминание приходит, когда до фактического отправления остаётся выбранное число минут.
 */
public final class Scheduler {
    static final String ACTION_REMIND = "io.github.andriibus.labus.REMIND";
    /** Старые будильники «утро/вечер» из первых версий — только чтобы их снять. */
    static final String ACTION_WINDOW = "io.github.andriibus.labus.WINDOW";
    static final long TRACK_BEFORE_MS = 15 * 60_000L;

    static String today() { return LocalDate.now(Prefs.ZONE).toString(); }

    static PendingIntent remindIntent(Context ctx) {
        Intent i = new Intent(ctx, Receiver.class).setAction(ACTION_REMIND);
        return PendingIntent.getBroadcast(ctx, 3, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent legacyWindow(Context ctx, int code) {
        Intent i = new Intent(ctx, Receiver.class).setAction(ACTION_WINDOW);
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void setExact(AlarmManager am, int type, long at, PendingIntent pi) {
        try { am.setExactAndAllowWhileIdle(type, at, pi); }
        catch (SecurityException e) { am.setAndAllowWhileIdle(type, at, pi); }
    }

    /** Ставит (или снимает) будильник напоминания. Вызывается при любом изменении и после перезагрузки. */
    public static void reschedule(Context ctx) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        am.cancel(legacyWindow(ctx, 1));
        am.cancel(legacyWindow(ctx, 2));
        PendingIntent pi = remindIntent(ctx);
        am.cancel(pi);
        Prefs p = Prefs.of(ctx);
        JSONObject r = p.reminder();
        if (r == null) return;
        long now = System.currentTimeMillis();
        long dep = r.optLong("dep");
        if (dep <= 0 || now > dep + 2 * 60_000) { p.clearReminder(); return; }
        long alertAt = dep - r.optInt("lead", 5) * 60_000L;
        long at = Math.max(now + 1500, alertAt - TRACK_BEFORE_MS);
        setExact(am, AlarmManager.RTC_WAKEUP, at, pi);
        Log.i("LABus", "напоминание " + r.optString("key") + ": слежение с " + Instant.ofEpochMilli(at));
    }
}

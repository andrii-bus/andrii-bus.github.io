package io.github.andriibus.labus;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import java.util.List;

/** Виджет: две строки, как две карточки приложения. Минуты тикают сами (обратный отсчёт). */
public class WidgetProvider extends AppWidgetProvider {

    static final int[] LABEL = {R.id.r0_label, R.id.r1_label};
    static final int[] NEXT = {R.id.r0_next, R.id.r1_next};
    static final int[] TIME = {R.id.r0_time, R.id.r1_time};
    static final int[] LATE = {R.id.r0_late, R.id.r1_late};
    static final int[] CD = {R.id.r0_cd, R.id.r1_cd};
    static final int[] LINE = {R.id.r0_line, R.id.r1_line};
    static final int[] TRACK = {R.id.r0_track, R.id.r1_track};

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        redraw(ctx);
        RefreshJob.runNow(ctx);
    }

    @Override
    public void onEnabled(Context ctx) {
        RefreshJob.schedulePeriodic(ctx);
    }

    @Override
    public void onDisabled(Context ctx) {
        RefreshJob.cancelPeriodic(ctx);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, Bundle opts) {
        redraw(ctx);
    }

    static boolean hasWidgets(Context ctx) {
        int[] ids = AppWidgetManager.getInstance(ctx).getAppWidgetIds(new ComponentName(ctx, WidgetProvider.class));
        return ids != null && ids.length > 0;
    }

    public static void redraw(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] ids = m.getAppWidgetIds(new ComponentName(ctx, WidgetProvider.class));
        if (ids == null || ids.length == 0) { scheduleRoll(ctx, Long.MAX_VALUE); return; }
        m.updateAppWidget(ids, build(ctx, true));
    }

    static RemoteViews build(Context ctx, boolean roll) {
        String w = Prefs.of(ctx).work();
        long now = System.currentTimeMillis();
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        long next = Math.min(fill(ctx, v, 0, Prefs.HOME, w, now), fill(ctx, v, 1, w, Prefs.HOME, now));
        v.setOnClickPendingIntent(R.id.w_root, Notif.open(ctx));
        if (roll) scheduleRoll(ctx, next);
        return v;
    }

    /** Заполняет строку; возвращает момент, когда её надо перерисовать (уход ближайшего автобуса). */
    static long fill(Context ctx, RemoteViews v, int i, String from, String to, long now) {
        Api.Board b = Api.cached(ctx, from, to);
        List<Api.Trip> up = Api.upcoming(b, now, 0);
        v.setTextViewText(LABEL[i], Api.name(from) + " → " + Api.name(to));
        if (up.isEmpty()) {
            v.setTextViewText(TIME[i], "—");
            v.setViewVisibility(CD[i], View.GONE);
            v.setViewVisibility(LATE[i], View.GONE);
            v.setViewVisibility(LINE[i], View.GONE);
            v.setViewVisibility(TRACK[i], View.GONE);
            v.setTextViewText(NEXT[i], b == null ? "нет данных" : "нет рейсов");
            return Long.MAX_VALUE;
        }
        Api.Trip t = up.get(0);
        v.setTextViewText(TIME[i], Notif.hm(t.dep));
        int late = t.lateMin();
        if (late > 0) {
            v.setTextViewText(LATE[i], "+" + late);
            v.setViewVisibility(LATE[i], View.VISIBLE);
        } else {
            v.setViewVisibility(LATE[i], View.GONE);
        }
        long mins = Math.max(0, (t.dep - now) / 60_000);
        v.setTextViewText(CD[i], mins == 0 ? "сейчас" : "через " + mins + " мин");
        v.setViewVisibility(CD[i], View.VISIBLE);
        v.setTextViewText(LINE[i], t.line);
        v.setViewVisibility(LINE[i], View.VISIBLE);
        if (!t.trackShort().isEmpty()) {
            v.setTextViewText(TRACK[i], t.trackShort());
            v.setViewVisibility(TRACK[i], View.VISIBLE);
        } else {
            v.setViewVisibility(TRACK[i], View.GONE);
        }
        v.setTextViewText(NEXT[i], up.size() > 1 ? "потом " + Notif.hm(up.get(1).dep) : "");
        // перерисовать к следующей минуте (минуты в строке) или к уходу автобуса
        long nextMinute = (now / 60_000 + 1) * 60_000 + 500;
        return Math.min(nextMinute, t.dep + 5_000);
    }

    static void scheduleRoll(Context ctx, long at) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        Intent i = new Intent(ctx, Receiver.class).setAction(Receiver.ACTION_ROLL);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 10, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        if (at == Long.MAX_VALUE) return;
        // без пробуждения: сработает, как только экран включён
        try { am.setExact(AlarmManager.RTC, at, pi); }
        catch (SecurityException e) { am.set(AlarmManager.RTC, at, pi); }
    }
}

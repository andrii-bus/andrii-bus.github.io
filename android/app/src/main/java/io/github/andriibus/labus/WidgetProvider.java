package io.github.andriibus.labus;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Виджет. Два вида, Android сам выбирает по размеру:
 *  — полный (от 4×2): две строки, как карточки приложения;
 *  — компактный (от 2×1): две тонкие строки «На работу» / «Домой».
 * Нажатие на ярлык с ⇄ переключает работу: Bushof ⇄ Kaiserplatz.
 */
public class WidgetProvider extends AppWidgetProvider {

    /** Набор id одной строки в разметке. */
    static final class Row {
        final int label, next, time, late, cd, line, track;
        Row(int label, int next, int time, int late, int cd, int line, int track) {
            this.label = label; this.next = next; this.time = time; this.late = late;
            this.cd = cd; this.line = line; this.track = track;
        }
    }

    static final Row[] FULL = {
            new Row(R.id.r0_label, R.id.r0_next, R.id.r0_time, R.id.r0_late, R.id.r0_cd, R.id.r0_line, R.id.r0_track),
            new Row(R.id.r1_label, R.id.r1_next, R.id.r1_time, R.id.r1_late, R.id.r1_cd, R.id.r1_line, R.id.r1_track),
    };
    static final Row[] COMPACT = {
            new Row(R.id.c0_label, 0, R.id.c0_time, R.id.c0_late, R.id.c0_cd, R.id.c0_line, 0),
            new Row(R.id.c1_label, 0, R.id.c1_time, R.id.c1_late, R.id.c1_cd, R.id.c1_line, 0),
    };

    static final Row[] SQUARE = {
            new Row(R.id.s0_label, 0, R.id.s0_time, R.id.s0_late, R.id.s0_cd, R.id.s0_line, 0),
            new Row(R.id.s1_label, 0, R.id.s1_time, R.id.s1_late, R.id.s1_cd, R.id.s1_line, 0),
    };

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
        if (hasWidgets(ctx)) return;  // остался виджет другого вида
        RefreshJob.cancelPeriodic(ctx);
        scheduleRoll(ctx, Long.MAX_VALUE);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, Bundle opts) {
        redraw(ctx);
    }

    /** Все виджеты LA Bus: большой и компактный. */
    static int[] allIds(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] a = m.getAppWidgetIds(new ComponentName(ctx, WidgetProvider.class));
        int[] b = m.getAppWidgetIds(new ComponentName(ctx, WidgetCompactProvider.class));
        if (a == null) a = new int[0];
        if (b == null) b = new int[0];
        int[] all = new int[a.length + b.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    static boolean hasWidgets(Context ctx) { return allIds(ctx).length > 0; }

    public static void redraw(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] ids = allIds(ctx);
        if (ids.length == 0) { scheduleRoll(ctx, Long.MAX_VALUE); return; }
        long now = System.currentTimeMillis();
        long[] next = {Long.MAX_VALUE};
        RemoteViews full = buildFull(ctx, now, next);
        RemoteViews compact = buildCompact(ctx, now, next);
        if (Build.VERSION.SDK_INT >= 31) {
            Map<SizeF, RemoteViews> map = new HashMap<>();
            map.put(new SizeF(140f, 40f), buildNarrow(ctx, now, next));   // 2×1
            map.put(new SizeF(220f, 40f), compact);                       // 3×1, 4×1
            map.put(new SizeF(140f, 100f), buildSquare(ctx, now, next));  // 2×2, 3×2
            map.put(new SizeF(240f, 100f), full);                         // 4×2
            m.updateAppWidget(ids, new RemoteViews(map));
        } else {
            for (int id : ids) {
                Bundle o = m.getAppWidgetOptions(id);
                int h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110);
                int w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
                m.updateAppWidget(id, h >= 100 && w >= 240 ? buildFull(ctx, now, next) : buildCompact(ctx, now, next));
            }
        }
        scheduleRoll(ctx, next[0]);
    }

    static RemoteViews buildFull(Context ctx, long now, long[] next) {
        String w = Prefs.of(ctx).widgetWork();
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget);
        String n0 = Api.name(Prefs.HOME) + " → " + Api.name(w) + "  ⇄";
        String n1 = Api.name(w) + " → " + Api.name(Prefs.HOME) + "  ⇄";
        next[0] = Math.min(next[0], fill(ctx, v, FULL[0], n0, Prefs.HOME, w, now, false, true));
        next[0] = Math.min(next[0], fill(ctx, v, FULL[1], n1, w, Prefs.HOME, now, false, true));
        clicks(ctx, v, R.id.w_root, FULL);
        return v;
    }

    static RemoteViews buildCompact(Context ctx, long now, long[] next) {
        String w = Prefs.of(ctx).widgetWork();
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_compact);
        next[0] = Math.min(next[0], fill(ctx, v, COMPACT[0], "На " + Api.name(w) + " ⇄", Prefs.HOME, w, now, true, true));
        next[0] = Math.min(next[0], fill(ctx, v, COMPACT[1], "Домой", w, Prefs.HOME, now, true, true));
        clicks(ctx, v, R.id.cw_root, COMPACT);
        return v;
    }

    /** 2×1: короткие подписи, без минут — только время отправления и номер. */
    static RemoteViews buildNarrow(Context ctx, long now, long[] next) {
        String w = Prefs.of(ctx).widgetWork();
        String shortW = "kaiserplatz".equals(w) ? "Kaiser" : "Bushof";
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_compact);
        next[0] = Math.min(next[0], fill(ctx, v, COMPACT[0], shortW + " ⇄", Prefs.HOME, w, now, true, false));
        next[0] = Math.min(next[0], fill(ctx, v, COMPACT[1], "Домой", w, Prefs.HOME, now, true, false));
        clicks(ctx, v, R.id.cw_root, COMPACT);
        return v;
    }

    /** 2×2: две карточки друг под другом, без «потом» и платформы. */
    static RemoteViews buildSquare(Context ctx, long now, long[] next) {
        String w = Prefs.of(ctx).widgetWork();
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_square);
        next[0] = Math.min(next[0], fill(ctx, v, SQUARE[0], "На " + Api.name(w) + " ⇄", Prefs.HOME, w, now, true, true));
        next[0] = Math.min(next[0], fill(ctx, v, SQUARE[1], "Домой", w, Prefs.HOME, now, true, true));
        clicks(ctx, v, R.id.sw_root, SQUARE);
        return v;
    }

    static void clicks(Context ctx, RemoteViews v, int root, Row[] rows) {
        v.setOnClickPendingIntent(root, Notif.open(ctx));
        Intent t = new Intent(ctx, Receiver.class).setAction(Receiver.ACTION_TOGGLE);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 11, t, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        for (Row r : rows) v.setOnClickPendingIntent(r.label, pi);
    }

    /** Заполняет строку; возвращает момент, когда её надо перерисовать. */
    static long fill(Context ctx, RemoteViews v, Row r, String label, String from, String to, long now,
                     boolean compact, boolean showMins) {
        Api.Board b = Api.cached(ctx, from, to);
        List<Api.Trip> up = Api.upcoming(b, now, 0);
        v.setTextViewText(r.label, label);
        if (up.isEmpty()) {
            v.setTextViewText(r.time, "—");
            v.setTextViewText(r.cd, b == null ? "нет данных" : "нет рейсов");
            v.setViewVisibility(r.cd, View.VISIBLE);
            v.setViewVisibility(r.late, View.GONE);
            v.setViewVisibility(r.line, View.GONE);
            if (r.track != 0) v.setViewVisibility(r.track, View.GONE);
            if (r.next != 0) v.setTextViewText(r.next, "");
            return Long.MAX_VALUE;
        }
        Api.Trip t = up.get(0);
        v.setTextViewText(r.time, Notif.hm(t.dep));
        int late = t.lateMin();
        if (late > 0) {
            v.setTextViewText(r.late, "+" + late);
            v.setViewVisibility(r.late, View.VISIBLE);
        } else {
            v.setViewVisibility(r.late, View.GONE);
        }
        long mins = Math.max(0, (t.dep - now) / 60_000);
        String cd = mins == 0 ? "сейчас" : (compact ? mins + " мин" : "через " + mins + " мин");
        v.setTextViewText(r.cd, cd);
        v.setViewVisibility(r.cd, showMins ? View.VISIBLE : View.GONE);
        v.setTextViewText(r.line, t.line);
        v.setViewVisibility(r.line, View.VISIBLE);
        if (r.track != 0) {
            if (!t.trackShort().isEmpty()) {
                v.setTextViewText(r.track, t.trackShort());
                v.setViewVisibility(r.track, View.VISIBLE);
            } else {
                v.setViewVisibility(r.track, View.GONE);
            }
        }
        if (r.next != 0) v.setTextViewText(r.next, up.size() > 1 ? "потом " + Notif.hm(up.get(1).dep) : "");
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

package io.github.andriibus.labus;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

final class Notif {
    static final String CH_TRIP = "trip", CH_LEAVE = "leave";
    static final int COLOR = 0xFF6F80D9;
    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm").withZone(Prefs.ZONE);

    static String hm(long t) { return HM.format(Instant.ofEpochMilli(t)); }

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel trip = new NotificationChannel(CH_TRIP, "Табло поездки", NotificationManager.IMPORTANCE_DEFAULT);
        trip.setDescription("Ближайший автобус и обратный отсчёт во время поездки");
        trip.setSound(null, null);
        trip.enableVibration(true);
        trip.setShowBadge(false);
        NotificationChannel leave = new NotificationChannel(CH_LEAVE, "Пора выходить", NotificationManager.IMPORTANCE_HIGH);
        leave.setDescription("Сигнал, когда пора выходить к остановке");
        leave.enableVibration(true);
        leave.setVibrationPattern(new long[]{0, 400, 200, 400, 200, 400});
        nm.createNotificationChannels(Arrays.asList(trip, leave));
    }

    static PendingIntent open(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent stopToday(Context c) {
        Intent i = new Intent(c, BusService.class).setAction(BusService.ACTION_STOP);
        return PendingIntent.getService(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Постоянное уведомление-табло (с обратным отсчётом до отправления, если when > 0). */
    static Notification trip(Context c, String title, String text, long when) {
        Notification.Builder b = new Notification.Builder(c, CH_TRIP)
                .setSmallIcon(R.drawable.ic_stat_bus)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setColor(COLOR)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(open(c))
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(c, R.drawable.ic_stat_bus), "Стоп на сегодня", stopToday(c)).build());
        if (when > 0) {
            b.setWhen(when).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true);
        } else {
            b.setShowWhen(false);
        }
        return b.build();
    }

    /** Разовый сигнал «Пора выходить» — с вибрацией, приходит и на часы. */
    static Notification leave(Context c, String title, String text, long timeoutMs) {
        Notification.Builder b = new Notification.Builder(c, CH_LEAVE)
                .setSmallIcon(R.drawable.ic_stat_bus)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setColor(COLOR)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(open(c));
        if (timeoutMs > 0) b.setTimeoutAfter(timeoutMs);
        return b.build();
    }
}

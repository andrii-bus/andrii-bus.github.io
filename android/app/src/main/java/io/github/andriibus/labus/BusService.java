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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Работает только во время окна поездки: раз в минуту обновляет расписание,
 * держит уведомление-табло с обратным отсчётом и подаёт сигнал «Пора выходить».
 */
public class BusService extends Service {
    static final String TAG = "LABus";
    static final String ACTION_STOP = "io.github.andriibus.labus.STOP";
    static final int NID = 1, NID_LEAVE = 2;
    static final long FETCH_MS = 60_000, TICK_MS = 15_000;

    public static void start(Context ctx, String slot) {
        Intent i = new Intent(ctx, BusService.class).putExtra("slot", slot);
        try { ctx.startForegroundService(i); }
        catch (Exception e) { Log.w(TAG, "не удалось запустить табло: " + e); }
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wl;
    private String slot, from, to;
    private long endAt, lastFetch;
    private boolean fetching, test, testAlertSent;
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
        startFg(Notif.trip(this, "LA Bus", "Загружаю расписание…", 0));

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (slot != null && !test) Prefs.of(this).putString("skip:" + slot, Scheduler.today());
            finish();
            return START_NOT_STICKY;
        }
        String s = intent == null ? null : intent.getStringExtra("slot");
        if (s == null) { finish(); return START_NOT_STICKY; }

        long now = System.currentTimeMillis();
        Prefs p = Prefs.of(this);
        test = "test".equals(s);
        String[] dir;
        if (test) {
            endAt = now + 3 * 60_000;
            dir = p.dirOf("morning");
        } else {
            long[] w = Scheduler.nextWindow(this, s, now);
            if (w == null || now < w[0] - 60_000) { finish(); return START_NOT_STICKY; }
            endAt = w[1];
            dir = p.dirOf(s);
        }
        slot = s;
        from = dir[0];
        to = dir[1];
        board = Api.cached(this, from, to);
        lastFetch = 0;
        testAlertSent = false;

        if (wl == null) {
            wl = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LABus:trip");
            wl.setReferenceCounted(false);
        }
        wl.acquire(Math.max(60_000, endAt - now + 60_000));

        h.removeCallbacks(tick);
        h.post(tick);
        Log.i(TAG, "табло " + slot + " " + from + ">" + to + " до " + Notif.hm(endAt));
        return START_NOT_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            if (now >= endAt || (!test && !Scheduler.isActive(BusService.this, slot, now))) { finish(); return; }
            if (!fetching && now - lastFetch >= FETCH_MS) {
                fetching = true;
                final String f = from, t = to;
                io.execute(() -> {
                    Api.Board b = Api.loadOrCached(getApplicationContext(), f, t);
                    h.post(() -> {
                        fetching = false;
                        lastFetch = System.currentTimeMillis();
                        if (b != null) board = b;
                        update();
                        WidgetProvider.redraw(BusService.this);
                    });
                });
            }
            update();
            h.postDelayed(this, TICK_MS);
        }
    };

    private void update() {
        if (slot == null) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        Prefs p = Prefs.of(this);
        long now = System.currentTimeMillis();
        int walk = p.walkMin(test ? "morning" : slot);
        String src = Api.name(from), dest = Api.name(to);
        List<Api.Trip> up = Api.upcoming(board, now, walk * 60_000L);
        if (up.isEmpty()) {
            String msg = board == null ? "Нет связи — пробую ещё раз" : "Нет автобусов в ближайшие 45 мин";
            nm.notify(NID, Notif.trip(this, src + " → " + dest, msg, 0));
            return;
        }
        Api.Trip t = up.get(0);
        Api.Trip next = up.size() > 1 ? up.get(1) : null;
        long leaveAt = t.dep - walk * 60_000L;
        int leaveIn = (int) Math.max(0, Math.floor((leaveAt - now) / 60000.0));
        int late = t.lateMin();
        int depIn = (int) Math.max(0, Math.floor((t.dep - now) / 60000.0));

        String title = t.line + " → " + dest + " · " + Notif.hm(t.dep) + (late > 0 ? " (+" + late + ")" : "")
                + " · через " + depIn + " мин";
        StringBuilder text = new StringBuilder(leaveIn <= 0 ? "Пора выходить" : "Выходи через " + leaveIn + " мин");
        if (!t.trackShort().isEmpty()) text.append(" · ").append(t.trackShort());
        if (next != null) text.append(" · дальше ").append(Notif.hm(next.dep));
        nm.notify(NID, Notif.trip(this, title, text.toString(), 0));

        if (!p.settings().optBoolean("leave", true)) return;
        String body = t.line + " → " + dest + " в " + Notif.hm(t.dep) + " · через " + depIn + " мин"
                + (!t.trackShort().isEmpty() ? " · " + t.trackShort() : "");
        if (test) {
            if (!testAlertSent && lastFetch > 0) {
                testAlertSent = true;
                nm.notify(NID_LEAVE, Notif.leave(this, "Проверка: пора выходить 🚌", body, 3 * 60_000));
            }
            return;
        }
        if (leaveAt - now <= 60_000 && !t.key.equals(p.getString("alerted", ""))) {
            p.putString("alerted", t.key);
            nm.notify(NID_LEAVE, Notif.leave(this, "Пора выходить 🚌", body, Math.max(60_000, t.dep - now + 60_000)));
        }
    }

    private void finish() {
        h.removeCallbacksAndMessages(null);
        slot = null;
        if (wl != null && wl.isHeld()) wl.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        Scheduler.reschedule(this, false);
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (wl != null && wl.isHeld()) wl.release();
        io.shutdownNow();
        super.onDestroy();
    }
}

package io.github.andriibus.labus;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.time.ZonedDateTime;

/** Обновление данных для виджета: раз в 15 минут и по запросу. */
public class RefreshJob extends JobService {
    static final int ID_PERIODIC = 100, ID_NOW = 101;

    static void schedulePeriodic(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js.getPendingJob(ID_PERIODIC) != null) return;
        js.schedule(new JobInfo.Builder(ID_PERIODIC, new ComponentName(ctx, RefreshJob.class))
                .setPeriodic(15 * 60_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    static void cancelPeriodic(Context ctx) {
        ctx.getSystemService(JobScheduler.class).cancel(ID_PERIODIC);
    }

    static void runNow(Context ctx) {
        ctx.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(ID_NOW, new ComponentName(ctx, RefreshJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(10_000)
                .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            Context c = getApplicationContext();
            if (!WidgetProvider.hasWidgets(c)) {
                if (params.getJobId() == ID_PERIODIC) cancelPeriodic(c);
                jobFinished(params, false);
                return;
            }
            int hour = ZonedDateTime.now(Prefs.ZONE).getHour();
            boolean night = params.getJobId() == ID_PERIODIC && hour >= 1 && hour < 5;  // ночью не тревожим сервер
            if (!night) {
                String w = Prefs.of(c).widgetWork();
                Api.loadOrCached(c, Prefs.HOME, w);
                Api.loadOrCached(c, w, Prefs.HOME);
            }
            WidgetProvider.redraw(c);
            jobFinished(params, false);
        }, "labus-refresh").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}

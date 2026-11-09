package io.github.andriibus.labus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Будильники окон, перерисовка виджета, перезагрузка телефона, смена времени. */
public class Receiver extends BroadcastReceiver {
    static final String ACTION_ROLL = "io.github.andriibus.labus.ROLL";
    static final String ACTION_REFRESH = "io.github.andriibus.labus.REFRESH";
    static final String ACTION_TOGGLE = "io.github.andriibus.labus.TOGGLE";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (a == null) return;
        switch (a) {
            case Scheduler.ACTION_REMIND:
                if (Prefs.of(ctx).reminder() != null) BusService.start(ctx, "rem");
                break;
            case Scheduler.ACTION_WINDOW:      // будильники старых версий — просто снимаем
                Scheduler.reschedule(ctx);
                break;
            case ACTION_ROLL:
                WidgetProvider.redraw(ctx);
                break;
            case ACTION_TOGGLE:
                Prefs.of(ctx).toggleWidgetWork();
                WidgetProvider.redraw(ctx);
                RefreshJob.runNow(ctx);
                break;
            case ACTION_REFRESH:
                RefreshJob.runNow(ctx);
                break;
            default: // загрузка телефона, обновление приложения, смена времени/пояса
                Scheduler.reschedule(ctx);
                if (WidgetProvider.hasWidgets(ctx)) RefreshJob.schedulePeriodic(ctx);
                WidgetProvider.redraw(ctx);
        }
    }
}

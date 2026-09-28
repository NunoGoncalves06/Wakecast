package io.github.wakebrief;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Fires one minute before the phone's alarm and starts the briefing service. */
public class AlarmTriggerReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        long alarmTime = intent.getLongExtra(Scheduler.EXTRA_ALARM_TIME, 0);
        long now = System.currentTimeMillis();
        AlarmManager.AlarmClockInfo next = ctx.getSystemService(AlarmManager.class).getNextAlarmClock();

        boolean matchesNextAlarm = next != null && Math.abs(next.getTriggerTime() - alarmTime) <= 90_000;
        // If exact alarms aren't allowed we may arrive late, after the alarm already went off.
        boolean justRang = alarmTime > 0 && alarmTime <= now && now - alarmTime < 30L * 60 * 1000;
        if (!matchesNextAlarm && !justRang) {
            // The alarm was changed or turned off since we armed this trigger.
            Scheduler.reschedule(ctx);
            return;
        }

        Intent s = new Intent(ctx, BriefingService.class)
                .setAction(BriefingService.ACTION_AUTO)
                .putExtra(Scheduler.EXTRA_ALARM_TIME, alarmTime);
        try {
            ctx.startForegroundService(s);
        } catch (RuntimeException e) {
            Log.e("WakeBriefing", "Could not start briefing service", e);
            BriefingService.postTapToHear(ctx);
        }
    }
}

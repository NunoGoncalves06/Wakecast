package io.github.wakebrief;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Re-arms the briefing whenever an alarm is added, changed or removed in any clock app,
 * after a reboot, an app update, or a time/timezone change.
 */
public class SystemEventsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Scheduler.reschedule(ctx);
        Scheduler.ensurePeriodicCheck(ctx);
    }
}

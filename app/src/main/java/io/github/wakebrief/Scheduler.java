package io.github.wakebrief;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Follows the phone's next alarm (whatever clock app set it) and arms our own trigger one
 * minute before it, so the data is already downloaded when the alarm rings.
 * No alarm set -> nothing scheduled -> no briefing.
 */
final class Scheduler {

    static final String EXTRA_ALARM_TIME = "alarm_time";
    /** Start this long before the alarm to fetch weather/news while you sleep. */
    static final long LEAD_MS = 60_000;
    private static final int JOB_ID = 1;

    private Scheduler() {}

    /** What the scheduler decided, for the home screen. */
    static final class Status {
        enum State { OFF, NO_ALARM, OUTSIDE_WINDOW, DONE_TODAY, ARMED }

        final State state;
        final long alarmTime;  // 0 when there's no alarm
        final boolean exact;

        Status(State state, long alarmTime, boolean exact) {
            this.state = state;
            this.alarmTime = alarmTime;
            this.exact = exact;
        }
    }

    /** Re-reads the next alarm and (re)arms or cancels our trigger. */
    static Status reschedule(Context ctx) {
        Prefs p = new Prefs(ctx);
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        AlarmManager.AlarmClockInfo next = am.getNextAlarmClock();
        long t = next == null ? 0 : next.getTriggerTime();

        Status.State state;
        if (!p.enabled()) {
            state = Status.State.OFF;
        } else if (next == null) {
            state = Status.State.NO_ALARM;
        } else {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(t);
            int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            if (minute < p.windowStart() || minute >= p.windowEnd()) {
                state = Status.State.OUTSIDE_WINDOW;
            } else if (p.oncePerDay() && dateKey(t).equals(p.lastBriefDate())) {
                state = Status.State.DONE_TODAY;
            } else {
                state = Status.State.ARMED;
            }
        }

        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (state != Status.State.ARMED) {
            am.cancel(pending(ctx, 0));
            return new Status(state, t, exact);
        }

        long fireAt = Math.max(t - LEAD_MS, System.currentTimeMillis() + 1000);
        PendingIntent pi = pending(ctx, t);
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi);
        }
        return new Status(state, t, exact);
    }

    /** Safety net: a periodic job re-checks the alarm every few hours in case a broadcast was missed. */
    static void ensurePeriodicCheck(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, RescheduleJob.class))
                .setPeriodic(3L * 60 * 60 * 1000)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    /** Friendly name of the app that owns the next alarm ("Clock"), or "" if unknown. */
    static String nextAlarmOwner(Context ctx) {
        AlarmManager.AlarmClockInfo next = ctx.getSystemService(AlarmManager.class).getNextAlarmClock();
        if (next == null || next.getShowIntent() == null) return "";
        String pkg = next.getShowIntent().getCreatorPackage();
        if (pkg == null) return "";
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static String describe(long t) {
        return new SimpleDateFormat("EEE d MMM, HH:mm", Locale.US).format(new Date(t));
    }

    static String dateKey(long t) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(t));
    }

    private static PendingIntent pending(Context ctx, long alarmTime) {
        Intent i = new Intent(ctx, AlarmTriggerReceiver.class).putExtra(EXTRA_ALARM_TIME, alarmTime);
        return PendingIntent.getBroadcast(ctx, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}

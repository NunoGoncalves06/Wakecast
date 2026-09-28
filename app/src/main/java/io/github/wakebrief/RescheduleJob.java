package io.github.wakebrief;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Periodic safety net in case the system dropped an alarm-changed broadcast. */
public class RescheduleJob extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        Scheduler.reschedule(this);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}

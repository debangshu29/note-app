package com.debangshu.note;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Background job (about twice a day, only when online): looks for a new version of the app. */
public class UpdateJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try { Updater.backgroundCheck(getApplicationContext()); } catch (Exception ignored) { }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}

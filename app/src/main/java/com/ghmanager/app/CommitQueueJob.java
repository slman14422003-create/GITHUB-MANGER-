package com.ghmanager.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

public class CommitQueueJob extends JobService {
    static final int JOB_ID = 7421;

    private volatile Thread worker;

    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null || CommitQueue.pendingCount(c) == 0) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, CommitQueueJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(60000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build());
        } catch (Exception ignored) {
        }
    }

    static void cancel(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID);
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context c = getApplicationContext();
        Thread t = new Thread(() -> {
            boolean again = false;
            try {
                CommitQueue.Outcome o = CommitQueue.run(c);
                again = o.retry > 0 && !o.unauthorized;
            } catch (Exception ignored) {
            } finally {
                jobFinished(params, again);
            }
        }, "commit-queue");
        worker = t;
        t.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Thread t = worker;
        if (t != null) t.interrupt();
        return true;
    }
}

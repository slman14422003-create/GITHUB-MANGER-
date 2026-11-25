package com.ghmanager.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class WidgetRefreshJob extends JobService {
    static final int PERIODIC_ID = 7411;
    static final int ONCE_ID = 7412;
    static final long PERIOD_MS = 30L * 60L * 1000L;

    private volatile Thread worker;

    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null || js.getPendingJob(PERIODIC_ID) != null) return;
            if (BuildWidgetProvider.ids(c).length == 0) return;
            JobInfo.Builder b = new JobInfo.Builder(PERIODIC_ID, new ComponentName(c, WidgetRefreshJob.class))
                    .setPeriodic(PERIOD_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
            if (Build.VERSION.SDK_INT >= 26) b.setRequiresBatteryNotLow(true);
            js.schedule(b.build());
        } catch (Exception ignored) {
        }
    }

    static void runOnce(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            js.schedule(new JobInfo.Builder(ONCE_ID, new ComponentName(c, WidgetRefreshJob.class))
                    .setOverrideDeadline(1000)
                    .build());
        } catch (Exception ignored) {
        }
    }

    static void cancelAll(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            js.cancel(PERIODIC_ID);
            js.cancel(ONCE_ID);
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context c = getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                refreshAll(c);
            } catch (Exception ignored) {
            } finally {
                jobFinished(params, false);
            }
        }, "widget-refresh");
        worker = t;
        t.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Thread t = worker;
        if (t != null) t.interrupt();
        return false;
    }

    static void refreshAll(Context c) {
        if (Accounts.active(c) == null) {
            BuildWidgetProvider.onSignedOut(c);
            return;
        }
        int[] ids = BuildWidgetProvider.ids(c);
        if (ids.length == 0) return;
        GitHubApi api = new GitHubApi(c);
        Map<String, Object[]> done = new HashMap<>();
        for (int id : ids) {
            JSONObject cfg = BuildWidgetProvider.config(c, id);
            if (cfg == null) continue;
            String key = cfg.optString("owner") + "/" + cfg.optString("repo") + "#" + cfg.optLong("id");
            Object[] res = done.get(key);
            if (res == null) {
                res = fetch(api, cfg);
                done.put(key, res);
            }
            apply(c, id, res);
        }
        BuildWidgetProvider.renderAll(c);
    }

    private static Object[] fetch(GitHubApi api, JSONObject cfg) {
        try {
            JSONArray a = api.listRuns(cfg.optString("owner"), cfg.optString("repo"), cfg.optLong("id"), null, null,
                    1, 1).optJSONArray("workflow_runs");
            JSONObject d = new JSONObject();
            d.put("at", System.currentTimeMillis());
            if (a != null && a.length() > 0) {
                JSONObject r = a.getJSONObject(0);
                boolean active = Status.isActive(r.optString("status"));
                long s = Fmt.parse(Fmt.s(r, "run_started_at"));
                long e = active ? System.currentTimeMillis() : Fmt.parse(Fmt.s(r, "updated_at"));
                d.put("has", true);
                d.put("runId", r.optLong("id"));
                d.put("number", r.optInt("run_number"));
                d.put("status", r.optString("status"));
                d.put("conclusion", r.isNull("conclusion") ? "" : r.optString("conclusion"));
                d.put("branch", Fmt.s(r, "head_branch"));
                d.put("created", Fmt.parse(Fmt.s(r, "created_at")));
                d.put("ms", s > 0 && e >= s ? e - s : 0);
            } else {
                d.put("has", false);
            }
            return new Object[]{"ok", d};
        } catch (GitHubApi.ApiException e) {
            return new Object[]{e.code == 401 ? "expired" : "offline", null};
        } catch (Exception e) {
            return new Object[]{"offline", null};
        }
    }

    private static void apply(Context c, int id, Object[] res) {
        String state = (String) res[0];
        if ("ok".equals(state)) {
            BuildWidgetProvider.saveCache(c, id, (JSONObject) res[1]);
            BuildWidgetProvider.setFlag(c, id, null);
        } else {
            BuildWidgetProvider.setFlag(c, id, state);
        }
    }
}

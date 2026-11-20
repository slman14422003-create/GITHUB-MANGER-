package com.ghmanager.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class RunWatcher {
    private RunWatcher() {
    }

    private static final String CHANNEL = "runs_done";
    private static final long PERIOD_MS = 8000;
    private static final long GIVE_UP_MS = 3L * 60L * 60L * 1000L;

    private static final class Watch {
        final String owner;
        final String repo;
        final long runId;
        final long since = System.currentTimeMillis();
        int errors;

        Watch(String owner, String repo, long runId) {
            this.owner = owner;
            this.repo = repo;
            this.runId = runId;
        }

        String key() {
            return owner + "/" + repo + "#" + runId;
        }
    }

    private static final Map<String, Watch> WATCHES = new ConcurrentHashMap<>();
    private static ScheduledExecutorService exec;

    static int count() {
        return WATCHES.size();
    }

    static boolean isWatching(String owner, String repo, long runId) {
        return WATCHES.containsKey(owner + "/" + repo + "#" + runId);
    }

    static synchronized void watch(Context c, String owner, String repo, long runId) {
        if (owner == null || repo == null || runId <= 0) return;
        Watch w = new Watch(owner, repo, runId);
        if (WATCHES.containsKey(w.key())) return;
        final Context app = c.getApplicationContext();
        WATCHES.put(w.key(), w);
        TransferService.begin(app);
        if (exec == null) {
            exec = Executors.newSingleThreadScheduledExecutor();
            exec.scheduleWithFixedDelay(() -> tick(app), PERIOD_MS, PERIOD_MS, TimeUnit.MILLISECONDS);
        }
    }

    static void stop(String owner, String repo, long runId) {
        WATCHES.remove(owner + "/" + repo + "#" + runId);
        if (WATCHES.isEmpty()) TransferService.idle();
    }

    private static void tick(Context app) {
        if (WATCHES.isEmpty()) return;
        GitHubApi api = new GitHubApi(app);
        for (Watch w : new ArrayList<>(WATCHES.values())) {
            try {
                JSONObject run = api.getRun(w.owner, w.repo, w.runId);
                w.errors = 0;
                if (!Status.isActive(run.optString("status"))) {
                    WATCHES.remove(w.key());
                    done(app, w, run);
                }
            } catch (GitHubApi.ApiException e) {
                if (e.code == 401 || e.code == 404 || ++w.errors > 10) WATCHES.remove(w.key());
            } catch (Exception e) {
                if (++w.errors > 40) WATCHES.remove(w.key());
            }
            if (System.currentTimeMillis() - w.since > GIVE_UP_MS) WATCHES.remove(w.key());
        }
        if (WATCHES.isEmpty()) TransferService.idle();
    }

    private static void done(Context app, Watch w, JSONObject run) {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, app.getString(R.string.rw_channel),
                        NotificationManager.IMPORTANCE_DEFAULT));
            }
            String status = run.optString("status");
            String conclusion = run.optString("conclusion");
            boolean ok = "success".equals(conclusion);
            String label = Status.label(status, conclusion);
            String name = Fmt.s(run, "name");
            String title = app.getString(ok ? R.string.rw_ok : R.string.rw_done, run.optInt("run_number"), label);
            StringBuilder text = new StringBuilder(w.repo);
            if (!name.isEmpty()) text.append(" · ").append(name);
            String branch = Fmt.s(run, "head_branch");
            if (!branch.isEmpty()) text.append(" · ").append(branch);
            long a = Fmt.parse(Fmt.s(run, "run_started_at"));
            long b = Fmt.parse(Fmt.s(run, "updated_at"));
            if (a > 0 && b >= a) text.append(" · ").append(Fmt.duration(b - a));
            Intent open = new Intent(app, RunDetailActivity.class);
            open.putExtra("owner", w.owner);
            open.putExtra("repo", w.repo);
            open.putExtra("branch", branch);
            open.putExtra("runId", w.runId);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int id = 9000 + (int) (w.runId % 100000L);
            NotificationCompat.Builder nb = new NotificationCompat.Builder(app, CHANNEL)
                    .setSmallIcon(ok ? R.drawable.ic_check : R.drawable.ic_cancel)
                    .setContentTitle(title)
                    .setContentText(text.toString())
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text.toString()))
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(app, id, open,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            nm.notify(id, nb.build());
        } catch (Exception ignored) {
        }
    }
}

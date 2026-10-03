package com.ghmanager.app;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** One workflow run: summary, jobs with their steps, artifacts, and run-level actions. */
public class RunDetailActivity extends BaseRepoActivity {
    private long runId;
    private LinearLayout content;
    private JSONObject run;
    private JSONArray jobs = new JSONArray();
    private JSONArray artifacts = new JSONArray();
    private boolean resumed = false;
    private boolean firstLoad = true;

    private final Runnable poll = () -> {
        if (resumed) load(true);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        runId = getIntent().getLongExtra("runId", 0);
        bindHeader(getString(R.string.run_title), repo);
        content = findViewById(R.id.content);
        btnRefresh.setOnClickListener(v -> load(false));
        action(btnA1, R.drawable.ic_open, R.string.open_in_github, v -> {
            if (run != null) openUrl(Fmt.s(run, "html_url"));
        });
        action(btnA2, R.drawable.ic_more, R.string.more, v -> moreMenu());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        load(!firstLoad);
        firstLoad = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(poll);
    }

    private void load(final boolean silent) {
        if (!silent) loading(true);
        io.execute(() -> {
            try {
                final JSONObject r = api.getRun(owner, repo, runId);
                JSONArray j = api.listJobs(owner, repo, runId);
                JSONArray a = null;
                try {
                    a = api.listRunArtifacts(owner, repo, runId);
                } catch (Exception ignored) {
                }
                final JSONArray fj = j == null ? new JSONArray() : j;
                final JSONArray fa = a == null ? new JSONArray() : a;
                post(() -> {
                    loading(false);
                    run = r;
                    jobs = fj;
                    artifacts = fa;
                    render();
                    ui.removeCallbacks(poll);
                    if (resumed && Status.isActive(r.optString("status"))) ui.postDelayed(poll, 6000);
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void render() {
        if (run == null) return;
        final String s = run.optString("status");
        final String c = run.optString("conclusion");
        titleView.setText("#" + run.optInt("run_number") + " " + Fmt.s(run, "name"));
        setSubtitle(Fmt.s(run, "head_branch"));
        content.removeAllViews();

        String dt = Fmt.s(run, "display_title");
        TextView t = Ui.body(this, dt.isEmpty() ? Fmt.s(run, "name") : dt, 17, R.color.text_primary);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(t);

        int color = Status.color(this, s, c);
        StringBuilder sub = new StringBuilder();
        sub.append(Fmt.s(run, "event"));
        JSONObject actor = run.optJSONObject("actor");
        if (actor != null) sub.append(" · ").append(actor.optString("login"));
        sub.append(" · ").append(Fmt.ago(Fmt.s(run, "created_at")));
        content.addView(Ui.rowView(this, content,
                new Row(Status.icon(s, c), false, Status.label(s, c), sub.toString(), false, false).tint(color), null));

        StringBuilder det = new StringBuilder();
        JSONObject hc = run.optJSONObject("head_commit");
        String sha = Fmt.shortSha(Fmt.s(run, "head_sha"));
        if (!sha.isEmpty()) {
            det.append("Commit: ").append(sha);
            if (hc != null) det.append(" — ").append(Fmt.firstLine(hc.optString("message")));
            det.append('\n');
        }
        String started = Fmt.s(run, "run_started_at");
        if (!started.isEmpty()) {
            det.append(getString(R.string.started_at, Fmt.date(started)));
            if ("completed".equals(s)) {
                long a = Fmt.parse(started);
                long b = Fmt.parse(Fmt.s(run, "updated_at"));
                if (a > 0 && b >= a) det.append("\n").append(getString(R.string.duration_label, Fmt.duration(b - a)));
            }
        }
        int attempt = run.optInt("run_attempt", 1);
        if (attempt > 1) det.append("\n").append(getString(R.string.attempt_label, attempt));
        content.addView(Ui.body(this, det.toString().trim(), 13, R.color.text_secondary));

        addButtons(s, c);

        // jobs
        content.addView(Ui.sectionTitle(this, getString(R.string.jobs_count, jobs.length())));
        if (jobs.length() == 0) {
            content.addView(Ui.body(this, getString(R.string.no_jobs), 14, R.color.text_hint));
        }
        for (int i = 0; i < jobs.length(); i++) {
            final JSONObject job = jobs.optJSONObject(i);
            if (job == null) continue;
            String js = job.optString("status");
            String jc = job.optString("conclusion");
            int jcol = Status.color(this, js, jc);
            JSONArray steps = job.optJSONArray("steps");
            StringBuilder jsub = new StringBuilder();
            long a = Fmt.parse(Fmt.s(job, "started_at"));
            long b = Fmt.parse(Fmt.s(job, "completed_at"));
            if (a > 0 && b >= a) jsub.append(Fmt.duration(b - a));
            String runner = Fmt.s(job, "runner_name");
            if (!runner.isEmpty()) jsub.append(jsub.length() > 0 ? " · " : "").append(runner);
            Row jr = new Row(Status.icon(js, jc), false, job.optString("name"), jsub.toString(), false, true)
                    .tint(jcol).badge(Status.label(js, jc), jcol);
            content.addView(Ui.rowView(this, content, jr, v -> jobMenu(job)));
            if (steps != null && steps.length() > 0) {
                TextView st = Ui.body(this, stepsText(steps), 12, R.color.text_secondary);
                st.setTypeface(Typeface.MONOSPACE);
                st.setPadding(Ui.dp(this, 30), 0, Ui.dp(this, 22), Ui.dp(this, 8));
                content.addView(st);
            }
        }

        // artifacts
        if (artifacts.length() > 0) {
            content.addView(Ui.sectionTitle(this, getString(R.string.artifacts_count, artifacts.length())));
            for (int i = 0; i < artifacts.length(); i++) {
                final JSONObject art = artifacts.optJSONObject(i);
                if (art == null) continue;
                boolean expired = art.optBoolean("expired");
                Row ar = new Row(R.drawable.ic_package, false, art.optString("name"),
                        Fmt.size(art.optLong("size_in_bytes")), false, true);
                if (expired) ar.badge(getString(R.string.expired), Ui.color(this, R.color.warn));
                content.addView(Ui.rowView(this, content, ar, v -> artifactMenu(art)));
            }
        }
    }

    private CharSequence stepsText(JSONArray steps) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int n = steps.length();
        for (int i = 0; i < n; i++) {
            JSONObject st = steps.optJSONObject(i);
            if (st == null) continue;
            String s = st.optString("status");
            String c = st.optString("conclusion");
            StringBuilder line = new StringBuilder();
            line.append(Status.glyph(s, c)).append(' ').append(st.optInt("number")).append(". ")
                    .append(st.optString("name"));
            long a = Fmt.parse(Fmt.s(st, "started_at"));
            long b = Fmt.parse(Fmt.s(st, "completed_at"));
            if (a > 0 && b >= a && b - a >= 1000) line.append("  (").append(Fmt.duration(b - a)).append(')');
            int start = sb.length();
            sb.append(line);
            int end = sb.length();
            String state = Status.state(s, c);
            if ("failure".equals(state) || "timed_out".equals(state)) {
                sb.setSpan(new ForegroundColorSpan(Ui.color(this, R.color.bad)), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if ("in_progress".equals(state)) {
                sb.setSpan(new ForegroundColorSpan(Ui.color(this, R.color.info)), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (i < n - 1) sb.append('\n');
        }
        return sb;
    }

    private void addButtons(String s, String c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        boolean any = false;
        boolean isFailure = false;
        if (Status.isActive(s)) {
            Button cancel = Ui.button(this, R.string.cancel_run, false);
            cancel.setOnClickListener(v -> doRunAction("cancel"));
            row.addView(cancel, weighted());
            any = true;
        } else {
            Button rerun = Ui.button(this, R.string.rerun, true);
            rerun.setOnClickListener(v -> doRunAction("rerun"));
            row.addView(rerun, weighted());
            any = true;
            String state = Status.state(s, c);
            if ("failure".equals(state) || "timed_out".equals(state)) {
                Button failed = Ui.button(this, R.string.rerun_failed, false);
                failed.setOnClickListener(v -> doRunAction("rerun_failed"));
                row.addView(failed, weighted());
                isFailure = true;
            }
        }
        if (any) content.addView(row);
        if (isFailure) {
            LinearLayout back = new LinearLayout(this);
            back.setOrientation(LinearLayout.HORIZONTAL);
            back.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
            Button rollback = Ui.button(this, R.string.rollback_before_run, false);
            rollback.setOnClickListener(v -> rollbackBeforeRun());
            back.addView(rollback, weighted());
            content.addView(back);
        }
    }

    /** The run failed: put the branch back to the version just before the commit that was built. */
    private void rollbackBeforeRun() {
        if (run == null) return;
        final String headSha = Fmt.s(run, "head_sha");
        if (headSha.isEmpty()) return;
        final String runBranch = Fmt.s(run, "head_branch").isEmpty() ? branch : Fmt.s(run, "head_branch");
        JSONObject hc = run.optJSONObject("head_commit");
        final String detail = getString(R.string.rollback_detail, Fmt.shortSha(headSha),
                hc == null ? "" : Fmt.firstLine(hc.optString("message")));
        loading(true);
        io.execute(() -> {
            try {
                final String parent = api.getParentSha(owner, repo, headSha);
                post(() -> {
                    loading(false);
                    if (parent == null) {
                        toast(R.string.no_previous_version);
                        return;
                    }
                    restoreVersion(runBranch, parent, Fmt.shortSha(parent), detail, null);
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        return lp;
    }

    private void doRunAction(final String what) {
        bg(() -> {
            if ("cancel".equals(what)) api.cancelRun(owner, repo, runId, false);
            else if ("force".equals(what)) api.cancelRun(owner, repo, runId, true);
            else if ("rerun_failed".equals(what)) api.rerunFailed(owner, repo, runId);
            else api.rerunRun(owner, repo, runId);
            post(() -> {
                toast(R.string.done_ok);
                ui.postDelayed(() -> load(true), 1500);
            });
        });
    }

    private void jobMenu(final JSONObject job) {
        final long jobId = job.optLong("id");
        final boolean done = "completed".equals(job.optString("status"));
        String[] items = {
                getString(R.string.view_log),
                getString(R.string.rerun_job),
                getString(R.string.open_in_github)};
        choose(job.optString("name"), items, (d, which) -> {
            if (which == 0) {
                Intent i = repoIntent(LogActivity.class);
                i.putExtra("jobId", jobId);
                i.putExtra("jobName", job.optString("name"));
                startActivity(i);
            } else if (which == 1) {
                if (!done) {
                    toast(R.string.job_not_finished);
                    return;
                }
                bg(() -> {
                    api.rerunJob(owner, repo, jobId);
                    post(() -> {
                        toast(R.string.done_ok);
                        ui.postDelayed(() -> load(true), 1500);
                    });
                });
            } else {
                openUrl(Fmt.s(job, "html_url"));
            }
        });
    }

    private void artifactMenu(final JSONObject art) {
        final long id = art.optLong("id");
        final String name = art.optString("name");
        String[] items = {getString(R.string.download), getString(R.string.delete)};
        choose(name, items, (d, which) -> {
            if (which == 0) {
                if (art.optBoolean("expired")) {
                    toast(R.string.artifact_expired);
                    return;
                }
                saveAs(name + ".zip", () -> api.openDownload(api.artifactZipPath(owner, repo, id),
                        "application/vnd.github+json"));
            } else {
                confirm(getString(R.string.delete), getString(R.string.delete_msg, name), R.string.delete, () ->
                        bg(() -> {
                            api.deleteArtifact(owner, repo, id);
                            post(() -> load(true));
                        }));
            }
        });
    }

    private void moreMenu() {
        final boolean active = run != null && Status.isActive(run.optString("status"));
        String[] items = {
                getString(R.string.download_all_logs),
                getString(R.string.copy_link),
                getString(active ? R.string.force_cancel : R.string.delete_run)};
        choose(getString(R.string.more), items, (d, which) -> {
            if (which == 0) {
                saveAs("run-" + runId + "-logs.zip", () -> api.openDownload(api.runLogsPath(owner, repo, runId),
                        "application/vnd.github+json"));
            } else if (which == 1) {
                if (run != null) copy("run", Fmt.s(run, "html_url"));
            } else if (active) {
                confirm(getString(R.string.force_cancel), getString(R.string.force_cancel_msg), R.string.confirm_ok,
                        () -> doRunAction("force"));
            } else {
                confirm(getString(R.string.delete_run), getString(R.string.delete_run_msg), R.string.delete, () ->
                        bg(() -> {
                            api.deleteRun(owner, repo, runId);
                            post(this::finish);
                        }));
            }
        });
    }
}

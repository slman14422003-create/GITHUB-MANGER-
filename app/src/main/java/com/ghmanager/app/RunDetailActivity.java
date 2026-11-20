package com.ghmanager.app;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
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
    private boolean silentRender = false;
    private LiveBoard board;

    private static final long POLL_MS = 2500;

    private final Runnable poll = () -> {
        if (resumed) load(true);
    };

    /** Keeps the running clocks of the live board ticking every second. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            if (board != null) board.tick();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        runId = getIntent().getLongExtra("runId", 0);
        bindHeader(getString(R.string.run_title), repo);
        content = findViewById(R.id.content);
        board = new LiveBoard(this, new LiveBoard.Listener() {
            @Override
            public void onJob(JSONObject job) {
                openLog(job, 0);
            }

            @Override
            public void onStep(JSONObject job, int number) {
                openLog(job, number);
            }

            @Override
            public void onJobMenu(JSONObject job) {
                jobMenu(job);
            }
        });
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
        ui.removeCallbacks(ticker);
        ui.postDelayed(ticker, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(poll);
        ui.removeCallbacks(ticker);
    }

    private void updateExtra(JSONObject r) {
        String st = Status.state(r.optString("status"), r.optString("conclusion"));
        boolean active = Status.isActive(r.optString("status"));
        if (active) {
            if (Store.runNotify(this)) {
                Perms.ensureNotifications(this);
                RunWatcher.watch(this, owner, repo, runId);
            }
            action(btnA3, R.drawable.ic_bell, R.string.rw_notify_me, v -> {
                Perms.ensureNotifications(this);
                RunWatcher.watch(this, owner, repo, runId);
                toast(R.string.rw_watching_toast);
            });
        } else if ("failure".equals(st) || "timed_out".equals(st) || "startup_failure".equals(st)) {
            action(btnA3, R.drawable.ic_sparkles, R.string.ex_btn, v -> explainRun(runId, null));
        } else {
            btnA3.setVisibility(View.GONE);
        }
    }

    private void load(final boolean silent) {
        if (!silent) loading(true);
        io.execute(() -> {
            try {
                final JSONObject r = api.getRun(owner, repo, runId);
                JSONArray j = api.listJobs(owner, repo, runId);
                // artifacts only change when the run ends: skip them on the quick polls of a running run
                // (one request less every 2.5 s keeps the GitHub rate limit healthy)
                JSONArray a = null;
                boolean fetchedArtifacts = false;
                if (!silent || !Status.isActive(r.optString("status"))) {
                    fetchedArtifacts = true;
                    try {
                        a = api.listRunArtifacts(owner, repo, runId);
                    } catch (Exception ignored) {
                    }
                }
                final JSONArray fj = j == null ? new JSONArray() : j;
                final boolean keepArtifacts = !fetchedArtifacts;
                final JSONArray fa = a == null ? new JSONArray() : a;
                post(() -> {
                    loading(false);
                    run = r;
                    jobs = fj;
                    if (!keepArtifacts) artifacts = fa;
                    silentRender = silent;
                    board.update(r, fj);
                    render();
                    updateExtra(r);
                    ui.removeCallbacks(poll);
                    if (resumed && Status.isActive(r.optString("status"))) ui.postDelayed(poll, POLL_MS);
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

        content.addView(hero(s, c));

        // the live board survives re-renders: it is detached above and attached again here
        content.addView(board);
        board.animate().cancel();
        board.setAlpha(1f);
        board.setTranslationY(0f);

        StringBuilder det = new StringBuilder();
        det.append(Fmt.s(run, "event"));
        JSONObject actor = run.optJSONObject("actor");
        if (actor != null) det.append(" · ").append(actor.optString("login"));
        det.append(" · ").append(Fmt.ago(Fmt.s(run, "created_at"))).append('\n');
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
                content.addView(Ui.rowView(this, content, ar, v -> artifactMenu(art, () -> load(true))));
            }
        }

        // a silent refresh must not replay the "slide in" animation of every block
        if (silentRender) {
            for (int i = 0; i < content.getChildCount(); i++) {
                View v = content.getChildAt(i);
                v.animate().cancel();
                v.setAlpha(1f);
                v.setTranslationY(0f);
            }
        }
    }

    private View hero(String s, String c) {
        int color = Status.color(this, s, c);
        boolean active = Status.isActive(s);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        card.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{(color & 0x00FFFFFF) | 0x33000000, (color & 0x00FFFFFF) | 0x0F000000});
        bg.setCornerRadius(Ui.radius(this, R.dimen.radius_m));
        bg.setStroke(Ui.dp(this, 1), (color & 0x00FFFFFF) | 0x66000000);
        card.setBackground(bg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        card.setLayoutParams(clp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(Status.icon(s, c));
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        android.graphics.drawable.GradientDrawable tile = new android.graphics.drawable.GradientDrawable();
        tile.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        tile.setColor((color & 0x00FFFFFF) | 0x33000000);
        icon.setBackground(tile);
        icon.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        top.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 54)));
        Fx.pulse(icon, active);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(Status.label(s, c));
        label.setTextColor(color);
        label.setTextSize(13);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        texts.addView(label);
        String dt = Fmt.s(run, "display_title");
        TextView title = new TextView(this);
        title.setText(dt.isEmpty() ? Fmt.s(run, "name") : dt);
        title.setTextColor(Ui.color(this, R.color.text_primary));
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(3);
        title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        texts.addView(title);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(Ui.dp(this, 14));
        top.addView(texts, tlp);
        card.addView(top);

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setPadding(0, Ui.dp(this, 14), 0, 0);
        int jobsN = jobs == null ? 0 : jobs.length();
        stats.addView(statTile(String.valueOf(jobsN), getString(R.string.rd_jobs), jobsN, color));
        long a = Fmt.parse(Fmt.s(run, "run_started_at"));
        long b = active ? System.currentTimeMillis() : Fmt.parse(Fmt.s(run, "updated_at"));
        if (a > 0 && b >= a) stats.addView(statTile(Fmt.duration(b - a), getString(R.string.rd_duration), -1, color));
        int attempt = run.optInt("run_attempt", 1);
        stats.addView(statTile(String.valueOf(attempt), getString(R.string.rd_attempt), attempt, color));
        card.addView(stats);
        Ui.enter(card, 0);
        return card;
    }

    private View statTile(String value, String caption, int count, int color) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(android.view.Gravity.CENTER);
        int ph = Ui.dp(this, 10);
        tile.setPadding(ph, Ui.dp(this, 8), ph, Ui.dp(this, 8));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(Ui.radius(this, R.dimen.radius_s));
        g.setColor(Ui.color(this, R.color.surface));
        tile.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(Ui.dp(this, 8));
        tile.setLayoutParams(lp);
        TextView v = new TextView(this);
        v.setTextColor(Ui.color(this, R.color.text_primary));
        v.setTextSize(18);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        if (count >= 0 && !silentRender) Fx.countUp(v, count, "");
        else v.setText(value);
        tile.addView(v);
        TextView c = new TextView(this);
        c.setText(caption);
        c.setTextColor(Ui.color(this, R.color.text_secondary));
        c.setTextSize(11);
        tile.addView(c);
        return tile;
    }

    private void openLog(JSONObject job, int step) {
        Intent i = repoIntent(LogActivity.class);
        i.putExtra("jobId", job.optLong("id"));
        i.putExtra("jobName", job.optString("name"));
        i.putExtra("active", Status.isActive(job.optString("status")));
        i.putExtra("step", step);
        startActivity(i);
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
                openLog(job, 0);
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

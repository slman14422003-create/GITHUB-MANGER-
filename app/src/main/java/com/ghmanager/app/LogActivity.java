package com.ghmanager.app;

import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Live viewer for a job's log: follows the output while the job runs, highlights errors and lets the
 * user copy everything (or only the errors) with one tap.
 */
public class LogActivity extends BaseRepoActivity {
    private static final int MAX_BYTES = 300 * 1024;
    private static final int MAX_CLIP_CHARS = 150000;
    private static final long POLL_MS = 3000;

    private long jobId;
    private String raw = "";
    private boolean truncated = false;
    private int mode = 0;
    private boolean active = false;
    private boolean resumed = false;
    private boolean firstLoad = true;
    private String statusLabel = "";
    private TextView text;
    private ScrollView scroll;

    private final Runnable poll = () -> {
        if (resumed && active) load(true);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);
        jobId = getIntent().getLongExtra("jobId", 0);
        active = getIntent().getBooleanExtra("active", false);
        String jobName = getIntent().getStringExtra("jobName");
        bindHeader(jobName == null ? getString(R.string.view_log) : jobName, getString(R.string.log_title));
        text = findViewById(R.id.text);
        scroll = findViewById(R.id.scroll);
        chipRow = findViewById(R.id.chipRow);
        filterScroll = findViewById(R.id.filterScroll);

        btnRefresh.setOnClickListener(v -> load(false));
        action(btnA1, R.drawable.ic_copy, R.string.lg_copy_all, v -> copyLog(0));
        action(btnA2, R.drawable.ic_more, R.string.more, v -> moreMenu());
        findViewById(R.id.btnCopyAll).setOnClickListener(v -> copyLog(0));
        findViewById(R.id.btnCopyErr).setOnClickListener(v -> copyLog(1));
        findViewById(R.id.btnShare).setOnClickListener(v -> shareText(plain(0)));
        buildChips();
        load(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        ui.removeCallbacks(poll);
        if (active && !firstLoad) ui.postDelayed(poll, POLL_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(poll);
    }

    private void buildChips() {
        setChipRow(new String[]{getString(R.string.log_all), getString(R.string.log_errors)}, mode, idx -> {
            mode = idx;
            buildChips();
            render(true);
        });
    }

    private void load(final boolean silent) {
        if (!silent) loading(true);
        io.execute(() -> {
            String newRaw = null;
            boolean cut = false;
            Exception err = null;
            try {
                final GitHubApi.TextResult r = api.readTail(api.jobLogsPath(owner, repo, jobId),
                        "application/vnd.github+json", MAX_BYTES);
                newRaw = r.text;
                cut = r.truncated;
            } catch (Exception e) {
                err = e;
            }
            boolean stillActive = active;
            String label = statusLabel;
            try {
                JSONObject job = api.getJob(owner, repo, jobId);
                stillActive = Status.isActive(job.optString("status"));
                label = Status.label(job.optString("status"), job.optString("conclusion"));
            } catch (Exception ignored) {
            }
            final String fRaw = newRaw;
            final boolean fCut = cut;
            final Exception fErr = err;
            final boolean fActive = stillActive;
            final String fLabel = label;
            post(() -> {
                loading(false);
                active = fActive;
                statusLabel = fLabel;
                if (fErr != null && !silent && !fActive) {
                    fail(fErr);
                    return;
                }
                setSubtitle(active ? "● " + getString(R.string.lg_live) : statusLabel);
                if (fRaw != null && (firstLoad || !fRaw.equals(raw))) {
                    raw = fRaw;
                    truncated = fCut;
                    render(firstLoad);
                } else if (fRaw == null && raw.isEmpty()) {
                    text.setText(R.string.lg_waiting);
                }
                firstLoad = false;
                ui.removeCallbacks(poll);
                if (resumed && active) ui.postDelayed(poll, POLL_MS);
            });
        });
    }

    private void render(boolean forceEnd) {
        View child = scroll.getChildAt(0);
        boolean atBottom = forceEnd || child == null
                || child.getBottom() - (scroll.getHeight() + scroll.getScrollY()) <= Ui.dp(this, 120);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (truncated && mode == 0) sb.append(getString(R.string.log_truncated)).append("\n\n");
        SpannableStringBuilder body = LogFmt.format(this, raw, mode, 0);
        if (body.length() == 0) {
            sb.append(getString(raw.isEmpty() ? R.string.lg_waiting
                    : mode == 1 ? R.string.log_no_errors : R.string.log_empty));
        } else {
            sb.append(body);
        }
        text.setText(sb);
        if (atBottom) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private String plain(int m) {
        String s = LogFmt.format(this, raw, m, 0).toString().trim();
        return s.length() > MAX_CLIP_CHARS ? s.substring(s.length() - MAX_CLIP_CHARS) : s;
    }

    private void copyLog(int m) {
        String s = plain(m);
        if (s.isEmpty()) {
            toast(m == 1 ? R.string.lg_no_errors_copy : R.string.log_empty);
            return;
        }
        copy("log", s);
    }

    private void moreMenu() {
        String[] items = {getString(R.string.download), getString(R.string.share), getString(R.string.go_top),
                getString(R.string.go_bottom)};
        choose(getString(R.string.more), items, (d, which) -> {
            if (which == 0) {
                saveAs("job-" + jobId + "-log.txt",
                        () -> api.openDownload(api.jobLogsPath(owner, repo, jobId), "application/vnd.github+json"));
            } else if (which == 1) {
                shareText(plain(0));
            } else if (which == 2) {
                scroll.post(() -> scroll.fullScroll(View.FOCUS_UP));
            } else {
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }
}

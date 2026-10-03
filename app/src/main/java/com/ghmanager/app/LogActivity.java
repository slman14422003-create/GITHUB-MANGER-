package com.ghmanager.app;

import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Viewer for a job's log with error highlighting and an "errors only" filter. */
public class LogActivity extends BaseRepoActivity {
    private static final int MAX_BYTES = 300 * 1024;
    private static final int MAX_CLIP_CHARS = 150000;
    private static final Pattern TS = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z ?");
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-9;?]*[ -/]*[@-~]");

    private long jobId;
    private String raw = "";
    private boolean truncated = false;
    private int mode = 0;
    private TextView text;
    private ScrollView scroll;
    private CharSequence rendered = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);
        jobId = getIntent().getLongExtra("jobId", 0);
        String jobName = getIntent().getStringExtra("jobName");
        bindHeader(jobName == null ? getString(R.string.view_log) : jobName, getString(R.string.log_title));
        text = findViewById(R.id.text);
        scroll = findViewById(R.id.scroll);
        chipRow = findViewById(R.id.chipRow);
        filterScroll = findViewById(R.id.filterScroll);

        btnRefresh.setOnClickListener(v -> load());
        action(btnA1, R.drawable.ic_download, R.string.download, v -> saveAs("job-" + jobId + "-log.txt",
                () -> api.openDownload(api.jobLogsPath(owner, repo, jobId), "application/vnd.github+json")));
        action(btnA2, R.drawable.ic_more, R.string.more, v -> moreMenu());
        buildChips();
        load();
    }

    private void buildChips() {
        setChipRow(new String[]{getString(R.string.log_all), getString(R.string.log_errors)}, mode, idx -> {
            mode = idx;
            buildChips();
            render(idx == 0 ? true : false);
        });
    }

    private void load() {
        loading(true);
        io.execute(() -> {
            try {
                final GitHubApi.TextResult r = api.readTail(api.jobLogsPath(owner, repo, jobId),
                        "application/vnd.github+json", MAX_BYTES);
                post(() -> {
                    loading(false);
                    raw = r.text;
                    truncated = r.truncated;
                    render(true);
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private static boolean looksLikeError(String lower) {
        return lower.contains("error") || lower.contains("fatal") || lower.contains("failed")
                || lower.contains("exception") || lower.contains("traceback");
    }

    private void render(final boolean toEnd) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (truncated) sb.append(getString(R.string.log_truncated)).append("\n\n");
        final int bad = Ui.color(this, R.color.bad);
        final int warn = Ui.color(this, R.color.warn);
        final int info = Ui.color(this, R.color.info);
        String[] lines = raw.split("\n", -1);
        int shown = 0;
        for (String l : lines) {
            String line = l;
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            Matcher m = TS.matcher(line);
            if (m.find()) line = line.substring(m.end());
            line = ANSI.matcher(line).replaceAll("");

            int color = 0;
            boolean bold = false;
            boolean isError = false;
            if (line.startsWith("##[group]")) {
                line = "▶ " + line.substring(9);
                bold = true;
            } else if (line.startsWith("##[endgroup]")) {
                continue;
            } else if (line.startsWith("##[error]")) {
                line = "✖ " + line.substring(9);
                color = bad;
                bold = true;
                isError = true;
            } else if (line.startsWith("##[warning]")) {
                line = "⚠ " + line.substring(11);
                color = warn;
            } else if (line.startsWith("##[command]")) {
                line = "$ " + line.substring(11);
                color = info;
            }
            if (mode == 1 && !isError && !looksLikeError(line.toLowerCase(Locale.ROOT))) continue;

            int start = sb.length();
            sb.append(line).append('\n');
            int end = sb.length() - 1;
            if (color != 0) sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bold) sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            shown++;
        }
        if (shown == 0) sb.append(getString(mode == 1 ? R.string.log_no_errors : R.string.log_empty));
        rendered = sb;
        text.setText(sb);
        if (toEnd) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        else scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private String tail(CharSequence cs) {
        String s = cs.toString();
        return s.length() > MAX_CLIP_CHARS ? s.substring(s.length() - MAX_CLIP_CHARS) : s;
    }

    private void moreMenu() {
        String[] items = {getString(R.string.copy_log), getString(R.string.share), getString(R.string.go_top),
                getString(R.string.go_bottom)};
        choose(getString(R.string.more), items, (d, which) -> {
            if (which == 0) copy("log", tail(rendered));
            else if (which == 1) shareText(tail(rendered));
            else if (which == 2) scroll.post(() -> scroll.fullScroll(View.FOCUS_UP));
            else scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }
}

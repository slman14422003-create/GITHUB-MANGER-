package com.ghmanager.app;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Job viewer laid out like the GitHub website: every step is a row with its status, name and duration;
 * tapping a step opens its output with line numbers. A running job is followed live (the running step
 * opens by itself, its clock ticks and new lines appear as they are written).
 */
public class LogActivity extends BaseRepoActivity {
    private static final int MAX_BYTES = 300 * 1024;
    private static final int MAX_CLIP_CHARS = 150000;
    private static final long POLL_MS = 3000;
    private static final int LINES_PER_ROW = 10;

    private long jobId;
    private String jobName = "";
    private String raw = "";
    private boolean truncated = false;
    private int mode = 0;
    private boolean active = false;
    private boolean resumed = false;
    private boolean firstLoad = true;
    private String statusLabel = "";
    private TextView text;
    private ListView list;
    private List<StepLog.Step> steps = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();
    private final Map<Integer, Boolean> expanded = new HashMap<>();
    private int wantedStep = 0;

    private static final class Item {
        final int type;   // 0 step header, 1 block of lines, 2 note
        final StepLog.Step step;
        final CharSequence text;

        Item(int type, StepLog.Step step, CharSequence text) {
            this.type = type;
            this.step = step;
            this.text = text;
        }
    }

    /** Each row holds a handful of lines, so no single view is ever taller than the GPU can draw. */
    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        @Override
        public int getItemViewType(int i) {
            return items.get(i).type;
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            Item it = items.get(i);
            if (it.type == 0) {
                StepHeader h;
                if (v == null) {
                    h = new StepHeader(LogActivity.this);
                    v = h.root;
                } else {
                    h = (StepHeader) v.getTag();
                }
                h.bind(it.step, isOpen(it.step));
                return v;
            }
            TextView t;
            if (v == null) {
                FrameLayout w = new FrameLayout(LogActivity.this);
                w.setPadding(Ui.dp(LogActivity.this, 8), 0, Ui.dp(LogActivity.this, 8), 0);
                t = new TextView(LogActivity.this);
                t.setTypeface(Typeface.MONOSPACE);
                t.setLineSpacing(Ui.dp(LogActivity.this, 2), 1f);
                t.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                t.setTextDirection(View.TEXT_DIRECTION_LTR);
                if (it.type == 1) {
                    t.setTextSize(12f);
                    t.setTextColor(Ui.color(LogActivity.this, R.color.text_primary));
                    t.setBackgroundColor(Ui.color(LogActivity.this, R.color.field));
                    t.setPadding(Ui.dp(LogActivity.this, 8), Ui.dp(LogActivity.this, 4),
                            Ui.dp(LogActivity.this, 10), Ui.dp(LogActivity.this, 4));
                } else {
                    t.setTextSize(12.5f);
                    t.setTextColor(Ui.color(LogActivity.this, R.color.text_hint));
                    t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
                    t.setPadding(Ui.dp(LogActivity.this, 16), Ui.dp(LogActivity.this, 8),
                            Ui.dp(LogActivity.this, 16), Ui.dp(LogActivity.this, 8));
                }
                w.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                w.setTag(t);
                v = w;
            } else {
                t = (TextView) v.getTag();
            }
            t.setText(it.text);
            return v;
        }
    };

    private final Runnable poll = () -> {
        if (resumed && active) load(true);
    };

    /** Advances the clock of the running step once a second. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            if (list != null) {
                for (int i = 0; i < list.getChildCount(); i++) {
                    Object tag = list.getChildAt(i).getTag();
                    if (tag instanceof StepHeader) ((StepHeader) tag).tick();
                }
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);
        jobId = getIntent().getLongExtra("jobId", 0);
        active = getIntent().getBooleanExtra("active", false);
        wantedStep = getIntent().getIntExtra("step", 0);
        if (wantedStep > 0) expanded.put(wantedStep, true);
        String jn = getIntent().getStringExtra("jobName");
        jobName = jn == null ? "" : jn;
        bindHeader(jobName.isEmpty() ? getString(R.string.view_log) : jobName, getString(R.string.log_title));
        text = findViewById(R.id.text);
        list = findViewById(R.id.list);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= items.size() || mode == 1) return;
            Item it = items.get(pos);
            if (it.type != 0) return;
            expanded.put(it.step.number, !isOpen(it.step));
            rebuild(false);
        });
        // long press copies just that step (or just that block of lines)
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= items.size()) return true;
            Item it = items.get(pos);
            if (it.type == 0) {
                StringBuilder b = new StringBuilder();
                for (LogFmt.Line l : it.step.lines) b.append(l.plain).append('\n');
                String s = b.toString().trim();
                if (s.isEmpty()) toast(R.string.log_empty);
                else copy("log", s);
            } else if (it.type == 1) {
                copy("log", it.text.toString());
            }
            return true;
        });
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
        ui.removeCallbacks(ticker);
        if (active && !firstLoad) ui.postDelayed(poll, POLL_MS);
        ui.postDelayed(ticker, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(poll);
        ui.removeCallbacks(ticker);
    }

    private boolean isOpen(StepLog.Step s) {
        if (mode == 1) return true;
        Boolean b = expanded.get(s.number);
        if (b != null) return b;
        return s.code == 1 || s.code == 3;
    }

    private void buildChips() {
        setChipRow(new String[]{getString(R.string.log_all), getString(R.string.log_errors)}, mode, idx -> {
            mode = idx;
            buildChips();
            rebuild(true);
        });
    }

    private List<StepLog.Step> fallback(List<LogFmt.Line> lines) {
        List<StepLog.Step> out = new ArrayList<>();
        StepLog.Step s = new StepLog.Step();
        s.number = 1;
        s.name = jobName;
        s.code = active ? 1 : 2;
        for (LogFmt.Line l : lines) {
            s.lines.add(l);
            l.no = s.lines.size();
        }
        out.add(s);
        return out;
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
            JSONArray jobSteps = null;
            try {
                JSONObject job = api.getJob(owner, repo, jobId);
                stillActive = Status.isActive(job.optString("status"));
                label = Status.label(job.optString("status"), job.optString("conclusion"));
                jobSteps = job.optJSONArray("steps");
            } catch (Exception ignored) {
            }
            final String fRaw = newRaw;
            final boolean fCut = cut;
            final Exception fErr = err;
            final boolean fActive = stillActive;
            final String fLabel = label;
            final boolean changed = fRaw != null && (firstLoad || !fRaw.equals(raw));
            List<StepLog.Step> built = null;
            if (fRaw != null || jobSteps != null) {
                List<LogFmt.Line> lines = LogFmt.parse(this, fRaw != null ? fRaw : raw);
                built = jobSteps != null && jobSteps.length() > 0 ? StepLog.build(jobSteps, lines) : fallback(lines);
            }
            final List<StepLog.Step> fBuilt = built;
            post(() -> {
                loading(false);
                active = fActive;
                statusLabel = fLabel;
                if (fErr != null && !silent && !fActive && fBuilt == null) {
                    fail(fErr);
                    return;
                }
                setSubtitle(active ? "\u25CF " + getString(R.string.lg_live) : statusLabel);
                if (fRaw != null && changed) {
                    raw = fRaw;
                    truncated = fCut;
                }
                if (fBuilt != null && (changed || firstLoad || fActive)) {
                    steps = fBuilt;
                    rebuild(firstLoad);
                } else if (fBuilt == null && raw.isEmpty()) {
                    message(getString(R.string.lg_waiting));
                }
                firstLoad = false;
                ui.removeCallbacks(poll);
                if (resumed && active) ui.postDelayed(poll, POLL_MS);
            });
        });
    }

    /** Shows a short message in place of the list (waiting, nothing to show). */
    private void message(String m) {
        items.clear();
        adapter.notifyDataSetChanged();
        list.setVisibility(View.GONE);
        text.setVisibility(View.VISIBLE);
        text.setText(m);
    }

    private List<LogFmt.Line> visible(StepLog.Step s) {
        if (mode == 0) return s.lines;
        List<LogFmt.Line> out = new ArrayList<>();
        for (LogFmt.Line l : s.lines) if (l.err) out.add(l);
        return out;
    }

    private SpannableStringBuilder block(List<LogFmt.Line> vis, int from, int to, int gutter) {
        int hint = Ui.color(this, R.color.text_hint);
        int pad = Ui.dp(this, 8);
        SpannableStringBuilder b = new SpannableStringBuilder();
        for (int j = from; j < to; j++) {
            LogFmt.Line l = vis.get(j);
            int st = b.length();
            if (l.text.length() == 0) b.append(' ');
            else b.append(l.text);
            if (j < to - 1) b.append('\n');
            b.setSpan(new LineNumberSpan(l.no, gutter, hint, pad), st, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    private void rebuild(boolean forceEnd) {
        int count = adapter.getCount();
        boolean atBottom = forceEnd || count == 0 || list.getLastVisiblePosition() >= count - 2;
        int first = list.getFirstVisiblePosition();
        View top = list.getChildAt(0);
        int offset = top == null ? 0 : top.getTop();
        items.clear();
        if (steps.isEmpty()) {
            message(getString(raw.isEmpty() ? R.string.lg_waiting : R.string.log_empty));
            return;
        }
        if (truncated && mode == 0) items.add(new Item(2, null, getString(R.string.log_truncated)));
        Paint mp = new Paint();
        mp.setTypeface(Typeface.MONOSPACE);
        mp.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f,
                getResources().getDisplayMetrics()));
        float charW = mp.measureText("0");
        boolean any = false;
        int targetPos = -1;
        for (StepLog.Step s : steps) {
            List<LogFmt.Line> vis = visible(s);
            if (mode == 1 && vis.isEmpty()) continue;
            any = true;
            if (s.number == wantedStep && firstLoad) targetPos = items.size();
            items.add(new Item(0, s, null));
            if (!isOpen(s)) continue;
            if (vis.isEmpty()) {
                items.add(new Item(2, s, getString(s.code == 0 ? R.string.step_pending : R.string.step_no_output)));
                continue;
            }
            int digits = Math.max(2, String.valueOf(s.lines.size()).length());
            int gutter = (int) (charW * digits) + Ui.dp(this, 14);
            for (int i = 0; i < vis.size(); i += LINES_PER_ROW) {
                int end = Math.min(vis.size(), i + LINES_PER_ROW);
                items.add(new Item(1, s, block(vis, i, end, gutter)));
            }
        }
        if (!any) {
            message(getString(R.string.log_no_errors));
            return;
        }
        text.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        adapter.notifyDataSetChanged();
        final int fTarget = targetPos;
        if (fTarget >= 0) {
            wantedStep = 0;
            list.post(() -> list.setSelection(fTarget));
        } else if (atBottom && active) {
            list.post(() -> list.setSelection(adapter.getCount() - 1));
        } else if (forceEnd && !active) {
            list.post(() -> list.setSelection(0));
        } else {
            list.setSelectionFromTop(first, offset);
        }
    }

    private String plain(int m) {
        StringBuilder b = new StringBuilder();
        for (LogFmt.Line l : LogFmt.parse(this, raw)) {
            if (m == 1 && !l.err) continue;
            b.append(l.plain).append('\n');
        }
        String s = b.toString().trim();
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

    private void setAll(boolean open) {
        expanded.clear();
        for (StepLog.Step s : steps) expanded.put(s.number, open);
        rebuild(false);
    }

    private void moreMenu() {
        String[] items = {getString(R.string.download), getString(R.string.share),
                getString(R.string.log_expand_all), getString(R.string.log_collapse_all),
                getString(R.string.go_top), getString(R.string.go_bottom)};
        choose(getString(R.string.more), items, (d, which) -> {
            if (which == 0) {
                saveAs("job-" + jobId + "-log.txt",
                        () -> api.openDownload(api.jobLogsPath(owner, repo, jobId), "application/vnd.github+json"));
            } else if (which == 1) {
                shareText(plain(0));
            } else if (which == 2) {
                setAll(true);
            } else if (which == 3) {
                setAll(false);
            } else if (which == 4) {
                list.post(() -> list.setSelection(0));
            } else {
                list.post(() -> list.setSelection(Math.max(0, adapter.getCount() - 1)));
            }
        });
    }
}

package com.ghmanager.app;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Workflow runs of a repository, with filters, quick actions and bulk cleanup. */
public class ActionsActivity extends BaseRepoActivity {
    private static final int PER_PAGE = 30;
    private static final String[] STATUS = {null, "in_progress", "queued", "success", "failure", "cancelled"};

    private final List<JSONObject> runs = new ArrayList<>();
    private long workflowId = 0;
    private String workflowName = null;
    private String statusFilter = null;
    private String branchFilter = null;
    private int page = 1;
    private boolean hasMore = false;
    private boolean resumed = false;
    private boolean firstLoad = true;
    private int gen = 0;
    private JSONArray workflows = null;
    private final Map<Long, int[]> progress = new HashMap<>();
    private final Map<Long, String> stepNow = new HashMap<>();
    private LinearLayout dash;
    private RunAdapter runAdapter;

    private final Runnable poll = () -> {
        if (resumed && !busy) load(true, true);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        bindHeader(getString(R.string.actions), repo);
        initList();
        dash = new LinearLayout(this);
        dash.setOrientation(LinearLayout.VERTICAL);
        listView.addHeaderView(dash, null, false);
        runAdapter = new RunAdapter();
        listView.setAdapter(runAdapter);
        workflowId = getIntent().getLongExtra("workflowId", 0);
        workflowName = getIntent().getStringExtra("workflowName");

        btnRefresh.setOnClickListener(v -> load(true, false));
        action(btnA1, R.drawable.ic_run, R.string.run_workflow, v -> runWorkflowFlow());
        action(btnA2, R.drawable.ic_more, R.string.more, v -> moreMenu());

        listView.setOnItemClickListener((p, v, position, id) -> {
            int pos = position - listView.getHeaderViewsCount();
            if (pos < 0) return;
            if (pos == runs.size()) {
                page++;
                load(false, false);
                return;
            }
            if (pos < 0 || pos > runs.size()) return;
            android.content.Intent i = repoIntent(RunDetailActivity.class);
            i.putExtra("runId", runs.get(pos).optLong("id"));
            startActivity(i);
        });
        listView.setOnItemLongClickListener((p, v, position, id) -> {
            int pos = position - listView.getHeaderViewsCount();
            if (pos < 0 || pos >= runs.size()) return false;
            runMenu(runs.get(pos));
            return true;
        });
        buildChips();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        load(true, !firstLoad);
        firstLoad = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(poll);
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void buildChips() {
        chipRow.removeAllViews();
        String[] labels = {getString(R.string.filter_all), getString(R.string.st_running),
                getString(R.string.st_queued), getString(R.string.st_success),
                getString(R.string.st_failure), getString(R.string.st_cancelled)};
        for (int i = 0; i < labels.length; i++) {
            final String value = STATUS[i];
            android.widget.TextView c = Ui.chip(this, labels[i], same(statusFilter, value));
            c.setOnClickListener(v -> {
                statusFilter = value;
                buildChips();
                load(true, false);
            });
            chipRow.addView(c);
        }
        android.widget.TextView wf = Ui.chip(this, (workflowId > 0 && workflowName != null
                ? workflowName : getString(R.string.workflow_all)) + " ▾", workflowId > 0);
        wf.setOnClickListener(v -> pickWorkflowFilter());
        chipRow.addView(wf);
        android.widget.TextView br = Ui.chip(this, (branchFilter == null
                ? getString(R.string.all_branches) : branchFilter) + " ▾", branchFilter != null);
        br.setOnClickListener(v -> pickBranchFilter());
        chipRow.addView(br);
        filterScroll.setVisibility(android.view.View.VISIBLE);
    }

    // ------------------------------------------------------------------ loading

    private void load(final boolean reset, final boolean silent) {
        if (reset) page = 1;
        final int pg = page;
        final long wf = workflowId;
        final String st = statusFilter;
        final String br = branchFilter;
        final int myGen = ++gen;
        if (!silent) loading(true);
        io.execute(() -> {
            try {
                JSONArray arr = api.listRuns(owner, repo, wf, st, br, pg, PER_PAGE).optJSONArray("workflow_runs");
                final List<JSONObject> tmp = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) tmp.add(arr.getJSONObject(i));
                }
                final Map<Long, int[]> prog = new HashMap<>();
                final Map<Long, String> cur = new HashMap<>();
                int fetched = 0;
                for (JSONObject r : tmp) {
                    if (fetched >= 4) break;
                    if (!"in_progress".equals(r.optString("status"))) continue;
                    try {
                        JSONArray jobs = api.listJobs(owner, repo, r.optLong("id"));
                        int done = 0;
                        int total = 0;
                        String now = "";
                        for (int j = 0; jobs != null && j < jobs.length(); j++) {
                            JSONObject job = jobs.getJSONObject(j);
                            JSONArray steps = job.optJSONArray("steps");
                            for (int k = 0; steps != null && k < steps.length(); k++) {
                                JSONObject stepObj = steps.getJSONObject(k);
                                total++;
                                String ss = stepObj.optString("status");
                                if ("completed".equals(ss)) done++;
                                else if ("in_progress".equals(ss) && now.isEmpty()) {
                                    now = job.optString("name") + " › " + stepObj.optString("name");
                                }
                            }
                        }
                        if (total > 0) {
                            prog.put(r.optLong("id"), new int[]{done, total});
                            cur.put(r.optLong("id"), now);
                        }
                        fetched++;
                    } catch (Exception ignored) {
                    }
                }
                post(() -> {
                    if (myGen != gen) return;
                    loading(false);
                    showStatus(null);
                    progress.putAll(prog);
                    stepNow.putAll(cur);
                    if (pg == 1) runs.clear();
                    runs.addAll(tmp);
                    hasMore = tmp.size() >= PER_PAGE;
                    render();
                    schedulePoll();
                });
            } catch (Exception e) {
                if (myGen == gen) fail(e);
            }
        });
    }

    private void schedulePoll() {
        ui.removeCallbacks(poll);
        boolean active = false;
        for (JSONObject r : runs) {
            if (Status.isActive(r.optString("status"))) {
                active = true;
                break;
            }
        }
        if (resumed && active && page == 1) ui.postDelayed(poll, 8000);
    }

    private void render() {
        renderDash();
        runAdapter.notifyDataSetChanged();
        showEmpty(runs.isEmpty(), R.string.no_runs);
    }

    // ------------------------------------------------------------------ dashboard

    private static boolean isBad(String state) {
        return "failure".equals(state) || "timed_out".equals(state) || "startup_failure".equals(state);
    }

    private static boolean isWaiting(String state) {
        return "queued".equals(state) || "waiting".equals(state) || "pending".equals(state)
                || "requested".equals(state);
    }

    private static long runMillis(JSONObject run) {
        long a = Fmt.parse(Fmt.s(run, "run_started_at"));
        long b = Fmt.parse(Fmt.s(run, "updated_at"));
        return a > 0 && b >= a ? b - a : 0;
    }

    private TextView text(CharSequence t, int sp, int colorRes) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(Ui.color(this, colorRes));
        return v;
    }

    private View statTile(int count, int labelRes, int colorRes) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        int pv = Ui.dp(this, 12);
        t.setPadding(Ui.dp(this, 4), pv, Ui.dp(this, 4), pv);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 20));
        g.setColor(Ui.color(this, R.color.field));
        t.setBackground(g);
        TextView n = text(String.valueOf(count), 22, colorRes);
        n.setTypeface(Typeface.SERIF);
        n.setGravity(Gravity.CENTER);
        TextView l = text(getString(labelRes), 12, R.color.text_secondary);
        l.setGravity(Gravity.CENTER);
        l.setSingleLine(true);
        t.addView(n);
        t.addView(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMarginStart(Ui.dp(this, 3));
        lp.setMarginEnd(Ui.dp(this, 3));
        t.setLayoutParams(lp);
        return t;
    }

    private void renderDash() {
        dash.removeAllViews();
        dash.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 6));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = Ui.dp(this, 16);
        card.setPadding(pad, pad, pad, pad);
        dash.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!runs.isEmpty()) {
            int ok = 0;
            int bad = 0;
            int running = 0;
            int waiting = 0;
            long sum = 0;
            int timed = 0;
            for (JSONObject r : runs) {
                String st = r.optString("status");
                String state = Status.state(st, r.optString("conclusion"));
                if ("success".equals(state)) ok++;
                else if (isBad(state)) bad++;
                else if ("in_progress".equals(state)) running++;
                else if (isWaiting(state)) waiting++;
                if ("completed".equals(st)) {
                    long ms = runMillis(r);
                    if (ms > 0) {
                        sum += ms;
                        timed++;
                    }
                }
            }

            TextView title = text(getString(R.string.act_dash_title), 18, R.color.text_primary);
            title.setTypeface(Typeface.SERIF);
            card.addView(title);
            TextView sub = text(getString(R.string.act_dash_sub, runs.size()), 12, R.color.text_secondary);
            sub.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 12));
            card.addView(sub);

            LinearLayout tiles = new LinearLayout(this);
            tiles.setOrientation(LinearLayout.HORIZONTAL);
            tiles.addView(statTile(ok, R.string.st_success, R.color.ok));
            tiles.addView(statTile(bad, R.string.st_failure, R.color.bad));
            tiles.addView(statTile(running, R.string.st_running, R.color.info));
            tiles.addView(statTile(waiting, R.string.st_queued, R.color.warn));
            card.addView(tiles, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            int finished = ok + bad;
            if (finished > 0) {
                int rate = Math.round(ok * 100f / finished);
                LinearLayout line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setPadding(0, Ui.dp(this, 16), 0, 0);
                TextView l1 = text(getString(R.string.act_success_rate), 13, R.color.text_secondary);
                TextView v1 = text(rate + "%", 13, rate >= 80 ? R.color.ok : rate >= 50 ? R.color.warn : R.color.bad);
                v1.setTypeface(Typeface.DEFAULT_BOLD);
                line.addView(l1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                line.addView(v1);
                card.addView(line);
                card.addView(Ui.bar(this, rate, rate >= 80 ? R.color.ok : rate >= 50 ? R.color.warn : R.color.bad),
                        barParams());
            }
            if (timed > 0) {
                LinearLayout line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setPadding(0, Ui.dp(this, 6), 0, 0);
                TextView l1 = text(getString(R.string.act_avg_duration), 13, R.color.text_secondary);
                TextView v1 = text(Fmt.duration(sum / timed), 13, R.color.text_primary);
                v1.setTypeface(Typeface.DEFAULT_BOLD);
                line.addView(l1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                line.addView(v1);
                card.addView(line);
            }

            // history strip: oldest on the left, newest on the right, height = duration
            int n = Math.min(24, runs.size());
            long max = 1;
            for (int i = 0; i < n; i++) max = Math.max(max, runMillis(runs.get(i)));
            LinearLayout strip = new LinearLayout(this);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            strip.setGravity(Gravity.BOTTOM);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48));
            sp.topMargin = Ui.dp(this, 16);
            for (int i = n - 1; i >= 0; i--) {
                JSONObject r = runs.get(i);
                String state = Status.state(r.optString("status"), r.optString("conclusion"));
                long ms = runMillis(r);
                float f = Status.isActive(r.optString("status")) ? 0.55f : Math.max(0.25f, ms / (float) max);
                View bar = new View(this);
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(Ui.dp(this, 4));
                g.setColor(Status.color(this, r.optString("status"), r.optString("conclusion")));
                bar.setBackground(g);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                        0, Math.max(Ui.dp(this, 10), Math.round(Ui.dp(this, 48) * f)), 1);
                bp.setMarginStart(Ui.dp(this, 2));
                bp.setMarginEnd(Ui.dp(this, 2));
                strip.addView(bar, bp);
            }
            card.addView(strip, sp);
            TextView cap = text(getString(R.string.act_last_runs, n), 11, R.color.text_hint);
            cap.setPadding(0, Ui.dp(this, 6), 0, 0);
            card.addView(cap);
        }

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.Button run = Ui.button(this, R.string.run_workflow, true);
        run.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0);
        run.setCompoundDrawablePadding(Ui.dp(this, 8));
        run.setCompoundDrawableTintList(ColorStateList.valueOf(Ui.color(this, R.color.on_accent)));
        run.setOnClickListener(v -> runWorkflowFlow());
        android.widget.Button more = Ui.button(this, R.string.more, false);
        more.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_more, 0, 0, 0);
        more.setCompoundDrawablePadding(Ui.dp(this, 8));
        more.setCompoundDrawableTintList(ColorStateList.valueOf(Ui.color(this, R.color.text_primary)));
        more.setOnClickListener(v -> moreMenu());
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f);
        l1.setMarginEnd(Ui.dp(this, 6));
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        l2.setMarginStart(Ui.dp(this, 6));
        btns.addView(run, l1);
        btns.addView(more, l2);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dp(this, runs.isEmpty() ? 0 : 16);
        card.addView(btns, bl);
    }

    private LinearLayout.LayoutParams barParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        lp.topMargin = Ui.dp(this, 6);
        return lp;
    }

    // ------------------------------------------------------------------ run cards

    private void quickRerun(final JSONObject run, final boolean failedOnly) {
        final long id = run.optLong("id");
        bg(() -> {
            if (failedOnly) api.rerunFailed(owner, repo, id);
            else api.rerunRun(owner, repo, id);
            post(() -> {
                toast(R.string.done_ok);
                ui.postDelayed(() -> load(true, true), 1500);
            });
        });
    }

    private void quickCancel(final JSONObject run) {
        final long id = run.optLong("id");
        bg(() -> {
            api.cancelRun(owner, repo, id, false);
            post(() -> {
                toast(R.string.done_ok);
                ui.postDelayed(() -> load(true, true), 1500);
            });
        });
    }

    private void styleAction(TextView t, int textRes, int iconRes) {
        t.setVisibility(View.VISIBLE);
        t.setText(textRes);
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
        t.setCompoundDrawableTintList(ColorStateList.valueOf(Ui.color(this, R.color.text_primary)));
        int s = Ui.dp(this, 16);
        android.graphics.drawable.Drawable[] d = t.getCompoundDrawablesRelative();
        if (d[0] != null) d[0].setBounds(0, 0, s, s);
        t.setCompoundDrawablesRelative(d[0], null, null, null);
    }

    private final class RunAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return runs.size() + (hasMore ? 1 : 0);
        }

        @Override
        public Object getItem(int position) {
            return position < runs.size() ? runs.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return position < runs.size() ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (position >= runs.size()) {
                View v = convertView != null ? convertView
                        : LayoutInflater.from(ActionsActivity.this).inflate(R.layout.item_row, parent, false);
                RowAdapter.bind(ActionsActivity.this, v, new Row(R.drawable.ic_refresh, false,
                        getString(R.string.load_more), null, false, false));
                Ui.shapeRow(ActionsActivity.this, v, true, true);
                return v;
            }
            View v = convertView != null ? convertView
                    : LayoutInflater.from(ActionsActivity.this).inflate(R.layout.item_run, parent, false);
            bindRun(v, runs.get(position));
            return v;
        }
    }

    private void bindRun(View v, final JSONObject run) {
        final String s = run.optString("status");
        final String c = run.optString("conclusion");
        final String state = Status.state(s, c);
        final boolean active = Status.isActive(s);
        final int color = Status.color(this, s, c);

        // card with a status-tinted outline for failed / running runs
        View card = v.findViewById(R.id.card);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Ui.color(this, R.color.surface));
        fill.setCornerRadius(Ui.dp(this, 28));
        boolean hot = isBad(state) || "in_progress".equals(state);
        fill.setStroke(Ui.dp(this, 1), hot ? ((color & 0x00FFFFFF) | 0x80000000)
                : Ui.color(this, R.color.stroke_soft));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(Ui.dp(this, 28));
        card.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Ui.color(this, R.color.ripple)), fill, mask));

        ImageView icon = v.findViewById(R.id.icon);
        icon.setImageResource(Status.icon(s, c));
        icon.setImageTintList(ColorStateList.valueOf(color));

        String title = Fmt.s(run, "display_title");
        if (title.isEmpty()) title = Fmt.s(run, "name");
        ((TextView) v.findViewById(R.id.title)).setText(title);

        StringBuilder l1 = new StringBuilder("#").append(run.optInt("run_number"));
        String wfName = Fmt.s(run, "name");
        if (!wfName.isEmpty()) l1.append(" · ").append(wfName);
        int attempt = run.optInt("run_attempt", 1);
        if (attempt > 1) l1.append(" · ").append(getString(R.string.act_attempt, attempt));
        ((TextView) v.findViewById(R.id.line1)).setText(l1.toString());

        TextView badge = v.findViewById(R.id.badge);
        badge.setText(Status.label(s, c));
        badge.setTextColor(color);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 100));
        bg.setColor((color & 0x00FFFFFF) | 0x26000000);
        badge.setBackground(bg);

        StringBuilder meta = new StringBuilder();
        String hb = Fmt.s(run, "head_branch");
        if (!hb.isEmpty()) meta.append("⎇ ").append(hb);
        String sha = Fmt.s(run, "head_sha");
        if (sha.length() >= 7) meta.append(meta.length() > 0 ? "  ·  " : "").append(sha, 0, 7);
        JSONObject actor = run.optJSONObject("triggering_actor");
        if (actor == null) actor = run.optJSONObject("actor");
        if (actor != null && !actor.optString("login").isEmpty()) {
            meta.append(meta.length() > 0 ? "  ·  " : "").append("@").append(actor.optString("login"));
        }
        String ev = Fmt.s(run, "event");
        if (!ev.isEmpty()) meta.append(meta.length() > 0 ? "  ·  " : "").append(ev);
        TextView metaV = v.findViewById(R.id.meta);
        metaV.setText(meta.toString());
        metaV.setVisibility(meta.length() == 0 ? View.GONE : View.VISIBLE);

        StringBuilder time = new StringBuilder(Fmt.ago(Fmt.s(run, "created_at")));
        if (active) {
            long a = Fmt.parse(Fmt.s(run, "run_started_at"));
            if (a > 0) {
                time.append(time.length() > 0 ? "  ·  " : "")
                        .append(getString(R.string.act_elapsed, Fmt.duration(System.currentTimeMillis() - a)));
            }
        } else if ("completed".equals(s)) {
            long ms = runMillis(run);
            if (ms > 0) {
                time.append(time.length() > 0 ? "  ·  " : "")
                        .append(getString(R.string.act_took, Fmt.duration(ms)));
            }
        }
        ((TextView) v.findViewById(R.id.time)).setText(time.toString());

        // live step progress for running runs
        View progWrap = v.findViewById(R.id.progWrap);
        int[] pr = progress.get(run.optLong("id"));
        if ("in_progress".equals(s) && pr != null && pr[1] > 0) {
            progWrap.setVisibility(View.VISIBLE);
            ProgressBar pb = v.findViewById(R.id.prog);
            pb.setProgress(Math.max(4, pr[0] * 100 / pr[1]));
            String now = stepNow.get(run.optLong("id"));
            String line = getString(R.string.act_step_progress, pr[0], pr[1])
                    + (now == null || now.isEmpty() ? "" : "  ·  " + now);
            ((TextView) v.findViewById(R.id.progText)).setText(line);
        } else {
            progWrap.setVisibility(View.GONE);
        }

        // quick actions
        TextView a1 = v.findViewById(R.id.act1);
        TextView a2 = v.findViewById(R.id.act2);
        View a3 = v.findViewById(R.id.act3);
        a2.setVisibility(View.GONE);
        if (active) {
            styleAction(a1, R.string.act_cancel_short, R.drawable.ic_cancel);
            a1.setOnClickListener(x -> quickCancel(run));
        } else {
            styleAction(a1, R.string.act_rerun_short, R.drawable.ic_refresh);
            a1.setOnClickListener(x -> quickRerun(run, false));
            if (isBad(state)) {
                styleAction(a2, R.string.act_rerun_failed_short, R.drawable.ic_undo);
                a2.setOnClickListener(x -> quickRerun(run, true));
            }
        }
        a3.setOnClickListener(x -> runMenu(run));
    }

    // ------------------------------------------------------------------ workflows / branches pickers

    private void withWorkflows(final Runnable then) {
        if (workflows != null) {
            then.run();
            return;
        }
        loading(true);
        io.execute(() -> {
            try {
                final JSONArray w = api.listWorkflows(owner, repo);
                post(() -> {
                    loading(false);
                    workflows = w == null ? new JSONArray() : w;
                    then.run();
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void pickWorkflowFilter() {
        withWorkflows(() -> {
            final int n = workflows.length();
            String[] names = new String[n + 1];
            names[0] = getString(R.string.workflow_all);
            for (int i = 0; i < n; i++) names[i + 1] = workflows.optJSONObject(i).optString("name");
            choose(getString(R.string.workflow_label), names, (d, which) -> {
                if (which == 0) {
                    workflowId = 0;
                    workflowName = null;
                } else {
                    JSONObject w = workflows.optJSONObject(which - 1);
                    workflowId = w.optLong("id");
                    workflowName = w.optString("name");
                }
                buildChips();
                load(true, false);
            });
        });
    }

    private void pickBranchFilter() {
        loading(true);
        io.execute(() -> {
            try {
                JSONArray arr = api.listBranches(owner, repo);
                final String[] names = new String[arr.length() + 1];
                names[0] = getString(R.string.all_branches);
                for (int i = 0; i < arr.length(); i++) names[i + 1] = arr.getJSONObject(i).optString("name");
                post(() -> {
                    loading(false);
                    choose(getString(R.string.branch_label), names, (d, which) -> {
                        branchFilter = which == 0 ? null : names[which];
                        buildChips();
                        load(true, false);
                    });
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void runWorkflowFlow() {
        if (workflowId > 0) {
            dispatchDialog(workflowId, workflowName, () -> ui.postDelayed(() -> load(true, true), 3000));
            return;
        }
        withWorkflows(() -> {
            final int n = workflows.length();
            if (n == 0) {
                toast(R.string.no_workflows);
                return;
            }
            String[] names = new String[n];
            for (int i = 0; i < n; i++) names[i] = workflows.optJSONObject(i).optString("name");
            choose(getString(R.string.run_workflow), names, (d, which) -> {
                JSONObject w = workflows.optJSONObject(which);
                dispatchDialog(w.optLong("id"), w.optString("name"),
                        () -> ui.postDelayed(() -> load(true, true), 3000));
            });
        });
    }

    // ------------------------------------------------------------------ menus

    private void moreMenu() {
        String[] items = {
                getString(R.string.manage_workflows),
                getString(R.string.artifacts),
                getString(R.string.caches),
                getString(R.string.variables),
                getString(R.string.secrets),
                getString(R.string.delete_failed_runs),
                getString(R.string.delete_cancelled_runs),
                getString(R.string.delete_all_completed_runs)};
        choose(getString(R.string.more), items, (d, which) -> {
            switch (which) {
                case 0:
                    startActivity(repoIntent(WorkflowsActivity.class));
                    break;
                case 1:
                    openData("artifacts");
                    break;
                case 2:
                    openData("caches");
                    break;
                case 3:
                    openData("variables");
                    break;
                case 4:
                    openData("secrets");
                    break;
                case 5:
                    bulkDelete("failure");
                    break;
                case 6:
                    bulkDelete("cancelled");
                    break;
                default:
                    bulkDelete("completed");
                    break;
            }
        });
    }

    private void openData(String mode) {
        android.content.Intent i = repoIntent(ActionsDataActivity.class);
        i.putExtra("mode", mode);
        startActivity(i);
    }

    private void runMenu(final JSONObject run) {
        final long id = run.optLong("id");
        final boolean active = Status.isActive(run.optString("status"));
        String[] items = {
                getString(active ? R.string.cancel_run : R.string.rerun),
                getString(R.string.delete_run),
                getString(R.string.open_in_github)};
        choose("#" + run.optInt("run_number"), items, (d, which) -> {
            if (which == 0) {
                bg(() -> {
                    if (active) api.cancelRun(owner, repo, id, false);
                    else api.rerunRun(owner, repo, id);
                    post(() -> {
                        toast(R.string.done_ok);
                        ui.postDelayed(() -> load(true, true), 1500);
                    });
                });
            } else if (which == 1) {
                confirm(getString(R.string.delete_run), getString(R.string.delete_run_msg), R.string.delete, () ->
                        bg(() -> {
                            api.deleteRun(owner, repo, id);
                            post(() -> load(true, false));
                        }));
            } else {
                openUrl(Fmt.s(run, "html_url"));
            }
        });
    }

    private void bulkDelete(final String status) {
        confirm(getString(R.string.bulk_delete_title), getString(R.string.bulk_delete_msg), R.string.delete, () -> {
            if (busy) return;
            busy = true;
            showProgress(getString(R.string.working));
            final long wf = workflowId;
            final String br = branchFilter;
            bg(() -> {
                List<Long> ids = new ArrayList<>();
                for (int pg = 1; pg <= 5; pg++) {
                    JSONArray a = api.listRuns(owner, repo, wf, status, br, pg, 100).optJSONArray("workflow_runs");
                    if (a == null || a.length() == 0) break;
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject r = a.getJSONObject(i);
                        if (!Status.isActive(r.optString("status"))) ids.add(r.optLong("id"));
                    }
                    if (a.length() < 100) break;
                }
                final int total = ids.size();
                int ok = 0;
                for (int i = 0; i < total; i++) {
                    final int cur = i;
                    final String label = "#" + ids.get(i);
                    post(() -> updateProgress(cur, total, label));
                    try {
                        api.deleteRun(owner, repo, ids.get(i));
                        ok++;
                    } catch (GitHubApi.ApiException ignored) {
                    }
                }
                final int fOk = ok;
                post(() -> {
                    hideProgress();
                    toast(getString(R.string.deleted_count, fOk));
                    load(true, false);
                });
            });
        });
    }
}

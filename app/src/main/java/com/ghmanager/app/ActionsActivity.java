package com.ghmanager.app;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Workflow runs of a repository laid out like GitHub's Actions page: workflow picker, management
 * shortcuts, search, Event / Status / Branch / Actor filters, a run counter and one row per run
 * (status, title, workflow #number: commit by actor, branch, time, duration, quick actions).
 */
public class ActionsActivity extends BaseRepoActivity {
    private static final int PER_PAGE = 30;
    private static final String[] EVENTS = {"push", "pull_request", "workflow_dispatch", "schedule",
            "release", "workflow_run", "repository_dispatch", "issues", "issue_comment", "create", "delete"};
    private static final String[] STATUSES = {"queued", "in_progress", "success", "failure", "cancelled",
            "skipped", "timed_out", "action_required", "waiting"};

    private final List<JSONObject> runs = new ArrayList<>();
    private final List<JSONObject> shown = new ArrayList<>();
    private long workflowId = 0;
    private String workflowName = null;
    private String workflowPath = null;
    private String statusFilter = null;
    private String branchFilter = null;
    private String eventFilter = null;
    private String actorFilter = null;
    private String query = "";
    private int totalCount = 0;
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

    // header views
    private TextView wfTitle;
    private TextView wfSub;
    private TextView countLine;
    private TextView clearFilters;
    private LinearLayout filterChips;

    private final Runnable poll = () -> {
        if (resumed && !busy) load(true, true);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        bindHeader(getString(R.string.actions), repo);
        initList();
        workflowId = getIntent().getLongExtra("workflowId", 0);
        workflowName = getIntent().getStringExtra("workflowName");

        View head = LayoutInflater.from(this).inflate(R.layout.view_actions_head, listView, false);
        listView.addHeaderView(head, null, false);
        dash = new LinearLayout(this);
        dash.setOrientation(LinearLayout.VERTICAL);
        listView.addHeaderView(dash, null, false);
        runAdapter = new RunAdapter();
        listView.setAdapter(runAdapter);
        bindHead(head);

        btnRefresh.setOnClickListener(v -> load(true, false));
        action(btnA1, R.drawable.ic_run, R.string.run_workflow, v -> runWorkflowFlow());
        action(btnA2, R.drawable.ic_more, R.string.more, v -> moreMenu());

        listView.setOnItemClickListener((p, v, position, id) -> {
            int pos = position - listView.getHeaderViewsCount();
            if (pos < 0) return;
            if (pos == shown.size()) {
                page++;
                load(false, false);
                return;
            }
            if (pos > shown.size()) return;
            android.content.Intent i = repoIntent(RunDetailActivity.class);
            i.putExtra("runId", shown.get(pos).optLong("id"));
            startActivity(i);
        });
        listView.setOnItemLongClickListener((p, v, position, id) -> {
            int pos = position - listView.getHeaderViewsCount();
            if (pos < 0 || pos >= shown.size()) return false;
            runMenu(shown.get(pos));
            return true;
        });
        loadWorkflowsQuietly();
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

    // ------------------------------------------------------------------ header (GitHub-style page head)

    private void bindHead(View head) {
        wfTitle = head.findViewById(R.id.wfTitle);
        wfSub = head.findViewById(R.id.wfSub);
        countLine = head.findViewById(R.id.countLine);
        clearFilters = head.findViewById(R.id.clearFilters);
        filterChips = head.findViewById(R.id.filterChips);

        LinearLayout btnRow = head.findViewById(R.id.btnRow);
        Button run = Ui.button(this, R.string.run_workflow, true);
        run.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0);
        run.setCompoundDrawablePadding(Ui.dp(this, 8));
        run.setCompoundDrawableTintList(ColorStateList.valueOf(Ui.color(this, R.color.on_accent)));
        run.setOnClickListener(v -> runWorkflowFlow());
        Button wf = Ui.button(this, R.string.act_workflows, false);
        wf.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_list, 0, 0, 0);
        wf.setCompoundDrawablePadding(Ui.dp(this, 8));
        wf.setCompoundDrawableTintList(ColorStateList.valueOf(Ui.color(this, R.color.text_primary)));
        wf.setOnClickListener(v -> pickWorkflowFilter());
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f);
        l1.setMarginEnd(Ui.dp(this, 6));
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l2.setMarginStart(Ui.dp(this, 6));
        btnRow.addView(run, l1);
        btnRow.addView(wf, l2);

        LinearLayout mgmt = head.findViewById(R.id.mgmtRow);
        mgmt.addView(iconChip(R.string.manage_workflows, R.drawable.ic_code,
                v -> startActivity(repoIntent(WorkflowsActivity.class))));
        mgmt.addView(iconChip(R.string.caches, R.drawable.ic_drive, v -> openData("caches")));
        mgmt.addView(iconChip(R.string.artifacts, R.drawable.ic_package, v -> openData("artifacts")));
        mgmt.addView(iconChip(R.string.variables, R.drawable.ic_clipboard, v -> openData("variables")));
        mgmt.addView(iconChip(R.string.secrets, R.drawable.ic_lock, v -> openData("secrets")));
        mgmt.addView(iconChip(R.string.act_cleanup, R.drawable.ic_trash_sweep, v -> cleanupMenu()));
        mgmt.addView(iconChip(R.string.select_items, R.drawable.ic_check_circle, v -> selectRunsForDelete()));

        ((EditText) head.findViewById(R.id.runSearch)).addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                applySearch();
                runAdapter.notifyDataSetChanged();
                updateCount();
                showEmpty(shown.isEmpty() && !runs.isEmpty(), R.string.no_runs);
            }
        });
        clearFilters.setOnClickListener(v -> {
            statusFilter = null;
            branchFilter = null;
            eventFilter = null;
            actorFilter = null;
            buildFilters();
            load(true, false);
        });
        buildFilters();
        updateHead();
    }

    private TextView iconChip(int textRes, int iconRes, View.OnClickListener l) {
        TextView c = Ui.chip(this, getString(textRes), false);
        c.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
        c.setCompoundDrawablePadding(Ui.dp(this, 7));
        c.setCompoundDrawableTintList(ColorStateList.valueOf(Ui.color(this, R.color.text_secondary)));
        Drawable[] d = c.getCompoundDrawablesRelative();
        if (d[0] != null) {
            int s = Ui.dp(this, 16);
            d[0].setBounds(0, 0, s, s);
            c.setCompoundDrawablesRelative(d[0], null, null, null);
        }
        c.setOnClickListener(l);
        return c;
    }

    private TextView filterChip(int labelRes, String value, View.OnClickListener l) {
        String label = getString(labelRes);
        TextView c = Ui.chip(this, (value == null ? label : label + ": " + value) + " ▾", value != null);
        c.setOnClickListener(l);
        return c;
    }

    private void buildFilters() {
        filterChips.removeAllViews();
        filterChips.addView(filterChip(R.string.act_event, eventFilter, v -> pickEvent()));
        filterChips.addView(filterChip(R.string.act_status,
                statusFilter == null ? null : statusLabel(statusFilter), v -> pickStatus()));
        filterChips.addView(filterChip(R.string.act_branch, branchFilter, v -> pickBranchFilter()));
        filterChips.addView(filterChip(R.string.act_actor, actorFilter, v -> pickActor()));
        boolean any = eventFilter != null || statusFilter != null || branchFilter != null || actorFilter != null;
        clearFilters.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    private void updateHead() {
        wfTitle.setText(workflowId > 0 && workflowName != null ? workflowName : getString(R.string.workflow_all));
        if (workflowId > 0) {
            wfSub.setText(workflowPath != null && !workflowPath.isEmpty() ? workflowPath
                    : getString(R.string.act_showing_one));
        } else {
            wfSub.setText(R.string.act_showing_all);
        }
    }

    private void updateCount() {
        int n = totalCount > 0 ? totalCount : runs.size();
        if (!query.isEmpty()) countLine.setText(getString(R.string.act_count_shown, shown.size(), runs.size()));
        else countLine.setText(getString(R.string.act_count, n));
    }

    private void loadWorkflowsQuietly() {
        io.execute(() -> {
            try {
                final JSONArray w = api.listWorkflows(owner, repo);
                post(() -> {
                    workflows = w == null ? new JSONArray() : w;
                    syncWorkflowInfo();
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void syncWorkflowInfo() {
        if (workflows != null && workflowId > 0) {
            for (int i = 0; i < workflows.length(); i++) {
                JSONObject w = workflows.optJSONObject(i);
                if (w != null && w.optLong("id") == workflowId) {
                    workflowPath = w.optString("path");
                    if (workflowName == null || workflowName.isEmpty()) workflowName = w.optString("name");
                }
            }
        } else {
            workflowPath = null;
        }
        updateHead();
    }

    // ------------------------------------------------------------------ loading

    private void load(final boolean reset, final boolean silent) {
        if (reset) page = 1;
        final int pg = page;
        final long wf = workflowId;
        final String st = statusFilter;
        final String br = branchFilter;
        final String ev = eventFilter;
        final String ac = actorFilter;
        final int myGen = ++gen;
        if (!silent) loading(true);
        io.execute(() -> {
            try {
                JSONObject resp = api.listRuns(owner, repo, wf, st, br, ev, ac, pg, PER_PAGE);
                JSONArray arr = resp.optJSONArray("workflow_runs");
                final int total = resp.optInt("total_count", 0);
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
                        int totalSteps = 0;
                        String now = "";
                        for (int j = 0; jobs != null && j < jobs.length(); j++) {
                            JSONObject job = jobs.getJSONObject(j);
                            JSONArray steps = job.optJSONArray("steps");
                            for (int k = 0; steps != null && k < steps.length(); k++) {
                                JSONObject stepObj = steps.getJSONObject(k);
                                totalSteps++;
                                String ss = stepObj.optString("status");
                                if ("completed".equals(ss)) done++;
                                else if ("in_progress".equals(ss) && now.isEmpty()) {
                                    now = job.optString("name") + " › " + stepObj.optString("name");
                                }
                            }
                        }
                        if (totalSteps > 0) {
                            prog.put(r.optLong("id"), new int[]{done, totalSteps});
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
                    totalCount = total;
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

    private void applySearch() {
        shown.clear();
        for (JSONObject r : runs) {
            if (query.isEmpty() || matches(r)) shown.add(r);
        }
    }

    private boolean matches(JSONObject r) {
        JSONObject actor = r.optJSONObject("triggering_actor");
        if (actor == null) actor = r.optJSONObject("actor");
        String hay = (Fmt.s(r, "display_title") + " " + Fmt.s(r, "name") + " " + Fmt.s(r, "head_branch") + " "
                + Fmt.s(r, "head_sha") + " " + Fmt.s(r, "event") + " "
                + (actor == null ? "" : actor.optString("login")) + " #" + r.optInt("run_number"))
                .toLowerCase(Locale.ROOT);
        return hay.contains(query);
    }

    private void render() {
        applySearch();
        syncWorkflowInfo();
        buildFilters();
        renderDash();
        updateCount();
        runAdapter.notifyDataSetChanged();
        showEmpty(shown.isEmpty(), R.string.no_runs);
    }

    // ------------------------------------------------------------------ overview card

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
        if (runs.isEmpty()) return;
        dash.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 6));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = Ui.dp(this, 16);
        card.setPadding(pad, pad, pad, pad);
        dash.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

        TextView title = text(getString(R.string.act_dash_title), 17, R.color.text_primary);
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
            int col = rate >= 80 ? R.color.ok : rate >= 50 ? R.color.warn : R.color.bad;
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0, Ui.dp(this, 16), 0, 0);
            TextView l1 = text(getString(R.string.act_success_rate), 13, R.color.text_secondary);
            TextView v1 = text(rate + "%", 13, col);
            v1.setTypeface(Typeface.DEFAULT_BOLD);
            line.addView(l1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            line.addView(v1);
            card.addView(line);
            card.addView(Ui.bar(this, rate, col), barParams());
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

    private LinearLayout.LayoutParams barParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        lp.topMargin = Ui.dp(this, 6);
        return lp;
    }

    // ------------------------------------------------------------------ run rows

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
        int s = Ui.dp(this, 15);
        Drawable[] d = t.getCompoundDrawablesRelative();
        if (d[0] != null) d[0].setBounds(0, 0, s, s);
        t.setCompoundDrawablesRelative(d[0], null, null, null);
    }

    private void smallIcon(TextView t, int iconRes) {
        t.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
        t.setCompoundDrawableTintList(ColorStateList.valueOf(Ui.color(this, R.color.text_hint)));
        int s = Ui.dp(this, 14);
        Drawable[] d = t.getCompoundDrawablesRelative();
        if (d[0] != null) d[0].setBounds(0, 0, s, s);
        t.setCompoundDrawablesRelative(d[0], null, null, null);
    }

    private final class RunAdapter extends BaseAdapter {
        private int animatedUpTo = -1;

        @Override
        public int getCount() {
            return shown.size() + (hasMore ? 1 : 0);
        }

        @Override
        public Object getItem(int position) {
            return position < shown.size() ? shown.get(position) : null;
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
            return position < shown.size() ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (position >= shown.size()) {
                View v = convertView != null ? convertView
                        : LayoutInflater.from(ActionsActivity.this).inflate(R.layout.item_row, parent, false);
                RowAdapter.bind(ActionsActivity.this, v, new Row(R.drawable.ic_refresh, false,
                        getString(R.string.load_more), null, false, false));
                Ui.shapeRow(ActionsActivity.this, v, true, true);
                return v;
            }
            View v = convertView != null ? convertView
                    : LayoutInflater.from(ActionsActivity.this).inflate(R.layout.item_run, parent, false);
            bindRun(v, shown.get(position));
            if (position > animatedUpTo) {
                animatedUpTo = position;
                Ui.enter(v, position);
            } else {
                v.animate().cancel();
                v.setAlpha(1f);
                v.setTranslationY(0f);
            }
            return v;
        }
    }

    private String describe(JSONObject run, String actorLogin) {
        String wfName = Fmt.s(run, "name");
        int num = run.optInt("run_number");
        String sha = Fmt.shortSha(Fmt.s(run, "head_sha"));
        String ev = Fmt.s(run, "event");
        String who = actorLogin.isEmpty() ? "" : "@" + actorLogin;
        switch (ev) {
            case "push":
                return getString(R.string.act_line_push, wfName, num, sha, who);
            case "pull_request":
            case "pull_request_target":
                return getString(R.string.act_line_pr, wfName, num, sha, who);
            case "workflow_dispatch":
                return getString(R.string.act_line_manual, wfName, num, sha, who);
            case "schedule":
                return getString(R.string.act_line_schedule, wfName, num, sha);
            default:
                return getString(R.string.act_line_other, wfName, num, ev, sha, who);
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
        Ui.press(this, card);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Ui.color(this, R.color.surface));
        fill.setCornerRadius(Ui.dp(this, 24));
        boolean hot = isBad(state) || "in_progress".equals(state);
        fill.setStroke(Ui.dp(this, 1), hot ? ((color & 0x00FFFFFF) | 0x80000000)
                : Ui.color(this, R.color.stroke_soft));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(Ui.dp(this, 24));
        card.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Ui.color(this, R.color.ripple)), fill, mask));

        ImageView icon = v.findViewById(R.id.icon);
        icon.setImageResource(Status.icon(s, c));
        icon.setImageTintList(ColorStateList.valueOf(color));

        String title = Fmt.s(run, "display_title");
        if (title.isEmpty()) title = Fmt.s(run, "name");
        ((TextView) v.findViewById(R.id.title)).setText(title);

        JSONObject actor = run.optJSONObject("triggering_actor");
        if (actor == null) actor = run.optJSONObject("actor");
        String login = actor == null ? "" : actor.optString("login");
        StringBuilder l1 = new StringBuilder(describe(run, login));
        int attempt = run.optInt("run_attempt", 1);
        if (attempt > 1) l1.append(" · ").append(getString(R.string.act_attempt, attempt));
        ((TextView) v.findViewById(R.id.line1)).setText(l1.toString());

        TextView branchPill = v.findViewById(R.id.branchPill);
        String hb = Fmt.s(run, "head_branch");
        branchPill.setText(hb);
        branchPill.setVisibility(hb.isEmpty() ? View.GONE : View.VISIBLE);
        branchPill.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_branch, 0, 0, 0);
        branchPill.setCompoundDrawableTintList(ColorStateList.valueOf(Ui.color(this, R.color.accent_text)));
        Drawable[] bd = branchPill.getCompoundDrawablesRelative();
        if (bd[0] != null) {
            int sz = Ui.dp(this, 13);
            bd[0].setBounds(0, 0, sz, sz);
            branchPill.setCompoundDrawablesRelative(bd[0], null, null, null);
        }

        TextView badge = v.findViewById(R.id.badge);
        badge.setText(Status.label(s, c));
        badge.setTextColor(color);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 100));
        bg.setColor((color & 0x00FFFFFF) | 0x26000000);
        badge.setBackground(bg);

        TextView time = v.findViewById(R.id.time);
        time.setText(Fmt.ago(Fmt.s(run, "created_at")));
        smallIcon(time, R.drawable.ic_calendar);

        TextView dur = v.findViewById(R.id.dur);
        long a = Fmt.parse(Fmt.s(run, "run_started_at"));
        if (active && a > 0) {
            dur.setText(Fmt.duration(System.currentTimeMillis() - a));
            dur.setVisibility(View.VISIBLE);
        } else if ("completed".equals(s) && runMillis(run) > 0) {
            dur.setText(Fmt.duration(runMillis(run)));
            dur.setVisibility(View.VISIBLE);
        } else {
            dur.setVisibility(View.GONE);
        }
        smallIcon(dur, R.drawable.ic_timer);

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

        // quick actions: cancel while running, re-run (failed jobs) after a failure
        View actions = v.findViewById(R.id.actions);
        TextView a1 = v.findViewById(R.id.act1);
        TextView a2 = v.findViewById(R.id.act2);
        a2.setVisibility(View.GONE);
        if (active) {
            actions.setVisibility(View.VISIBLE);
            styleAction(a1, R.string.act_cancel_short, R.drawable.ic_cancel);
            a1.setOnClickListener(x -> quickCancel(run));
        } else if (isBad(state)) {
            actions.setVisibility(View.VISIBLE);
            styleAction(a1, R.string.act_rerun_short, R.drawable.ic_refresh);
            a1.setOnClickListener(x -> quickRerun(run, false));
            styleAction(a2, R.string.act_rerun_failed_short, R.drawable.ic_undo);
            a2.setOnClickListener(x -> quickRerun(run, true));
        } else {
            actions.setVisibility(View.GONE);
        }
        v.findViewById(R.id.act3).setOnClickListener(x -> runMenu(run));
    }

    // ------------------------------------------------------------------ pickers

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
                    workflowPath = null;
                } else {
                    JSONObject w = workflows.optJSONObject(which - 1);
                    workflowId = w.optLong("id");
                    workflowName = w.optString("name");
                    workflowPath = w.optString("path");
                }
                updateHead();
                load(true, false);
            });
        });
    }

    private void pickEvent() {
        String[] names = new String[EVENTS.length + 1];
        names[0] = getString(R.string.act_all_events);
        System.arraycopy(EVENTS, 0, names, 1, EVENTS.length);
        choose(getString(R.string.act_event), names, (d, which) -> {
            eventFilter = which == 0 ? null : EVENTS[which - 1];
            buildFilters();
            load(true, false);
        });
    }

    private void pickStatus() {
        String[] names = new String[STATUSES.length + 1];
        names[0] = getString(R.string.act_all_status);
        for (int i = 0; i < STATUSES.length; i++) names[i + 1] = statusLabel(STATUSES[i]);
        choose(getString(R.string.act_status), names, (d, which) -> {
            statusFilter = which == 0 ? null : STATUSES[which - 1];
            buildFilters();
            load(true, false);
        });
    }

    private static String statusLabel(String value) {
        return Status.label(isConclusion(value) ? "completed" : value, value);
    }

    private static boolean isConclusion(String s) {
        return !("queued".equals(s) || "in_progress".equals(s) || "waiting".equals(s));
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
                        buildFilters();
                        load(true, false);
                    });
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void pickActor() {
        Set<String> seen = new LinkedHashSet<>();
        for (JSONObject r : runs) {
            JSONObject a = r.optJSONObject("triggering_actor");
            if (a == null) a = r.optJSONObject("actor");
            if (a != null && !a.optString("login").isEmpty()) seen.add(a.optString("login"));
        }
        final List<String> names = new ArrayList<>(seen);
        String[] items = new String[names.size() + 2];
        items[0] = getString(R.string.act_all_actors);
        for (int i = 0; i < names.size(); i++) items[i + 1] = "@" + names.get(i);
        items[items.length - 1] = getString(R.string.act_other_user);
        choose(getString(R.string.act_actor), items, (d, which) -> {
            if (which == 0) {
                actorFilter = null;
                buildFilters();
                load(true, false);
            } else if (which == items.length - 1) {
                typeActor();
            } else {
                actorFilter = names.get(which - 1);
                buildFilters();
                load(true, false);
            }
        });
    }

    private void typeActor() {
        LinearLayout box = Ui.box(this);
        final EditText in = Ui.edit(this, getString(R.string.act_actor), actorFilter);
        box.addView(in);
        new Dlg(this)
                .setTitle(R.string.act_actor)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String v = in.getText().toString().trim();
                    if (v.startsWith("@")) v = v.substring(1);
                    actorFilter = v.isEmpty() ? null : v;
                    buildFilters();
                    load(true, false);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
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

    private void cleanupMenu() {
        String[] items = {
                getString(R.string.delete_failed_runs),
                getString(R.string.delete_cancelled_runs),
                getString(R.string.delete_all_completed_runs)};
        choose(getString(R.string.act_cleanup), items, (d, which) -> {
            if (which == 0) bulkDelete("failure");
            else if (which == 1) bulkDelete("cancelled");
            else bulkDelete("completed");
        });
    }

    private void selectRunsForDelete() {
        if (runs.isEmpty()) { toast(R.string.no_runs); return; }
        final List<JSONObject> snap = new ArrayList<>(runs);   // the list may reload while the dialog is open
        List<String> labels = new ArrayList<>();
        for (JSONObject r : snap) labels.add("#" + r.optInt("run_number") + " · " + Fmt.s(r, "name"));
        Dlg.multiSelect(this, getString(R.string.select_items), labels, R.string.delete_selected, true, idx -> {
            List<Long> ids = new ArrayList<>();
            for (int i : idx) ids.add(snap.get(i).optLong("id"));
            deleteSelectedRuns(ids);
        });
    }

    private void deleteSelectedRuns(final List<Long> ids) {
        if (ids.isEmpty()) { toast(R.string.nothing_selected); return; }
        confirm(getString(R.string.delete_selected), getString(R.string.delete_selected_msg, ids.size()), R.string.delete, () -> {
            if (busy) return; busy = true; showProgress(getString(R.string.working));
            bg(() -> { int ok=0; for (long id:ids) { try { api.deleteRun(owner,repo,id); ok++; } catch(Exception ignored){} }
                final int done=ok; post(() -> { busy=false; hideProgress(); toast(getString(R.string.deleted_count,done)); load(true,false); }); });
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
        final String branchName = Fmt.s(run, "head_branch");
        final String sha = Fmt.s(run, "head_sha");
        JSONObject actorObj = run.optJSONObject("triggering_actor");
        if (actorObj == null) actorObj = run.optJSONObject("actor");
        final String login = actorObj == null ? "" : actorObj.optString("login");
        final String[] items = {
                getString(R.string.act_rerun_short),
                getString(R.string.act_rerun_failed_short),
                getString(R.string.cancel_run),
                getString(R.string.delete_run),
                getString(R.string.open_in_github),
                getString(R.string.act_view_commit),
                getString(R.string.act_copy_link),
                getString(R.string.act_only_branch),
                getString(R.string.act_only_actor)};
        choose("#" + run.optInt("run_number") + " · " + Fmt.s(run, "name"), items, (d, which) -> {
            switch (which) {
                case 0:
                    if (active) {
                        toast(R.string.act_still_running);
                        return;
                    }
                    quickRerun(run, false);
                    break;
                case 1:
                    if (active) {
                        toast(R.string.act_still_running);
                        return;
                    }
                    quickRerun(run, true);
                    break;
                case 2:
                    if (!active) {
                        toast(R.string.act_not_running);
                        return;
                    }
                    quickCancel(run);
                    break;
                case 3:
                    confirm(getString(R.string.delete_run), getString(R.string.delete_run_msg), R.string.delete, () ->
                            bg(() -> {
                                api.deleteRun(owner, repo, id);
                                post(() -> load(true, false));
                            }));
                    break;
                case 4:
                    openUrl(Fmt.s(run, "html_url"));
                    break;
                case 5:
                    if (!sha.isEmpty()) openUrl(webUrl("/commit/" + sha));
                    break;
                case 6:
                    copy("run", Fmt.s(run, "html_url"));
                    break;
                case 7:
                    if (!branchName.isEmpty()) {
                        branchFilter = branchName;
                        buildFilters();
                        load(true, false);
                    }
                    break;
                default:
                    if (!login.isEmpty()) {
                        actorFilter = login;
                        buildFilters();
                        load(true, false);
                    }
                    break;
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

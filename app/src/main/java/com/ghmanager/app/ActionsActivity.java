package com.ghmanager.app;

import android.app.ActivityOptions;
import android.content.Intent;
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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    private final List<RunItem> items = new ArrayList<>();
    private final Set<Long> entered = new HashSet<>();
    private RecyclerView rv;
    private FooterAdapter footer;
    private String dashSig = "";
    private int tickCount = 0;
    private long workflowId = 0;
    private LinearLayout wfChips;
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
    /** Jobs (with all their steps) of the runs that are still going: shown live inside the list. */
    private final Map<Long, JSONArray> liveJobs = new HashMap<>();
    private LinearLayout dash;
    private RunAdapter runAdapter;
    private ShimmerView shimmer;

    // header views
    private TextView wfTitle;
    private TextView wfSub;
    private TextView countLine;
    private TextView clearFilters;
    private LinearLayout filterChips;

    private static final long POLL_MS = 4000;

    private final Runnable poll = () -> {
        if (resumed && !busy) load(true, true);
    };

    /** Keeps the running clocks of the live runs ticking every second without rebuilding the list. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!resumed || rv == null) return;
            long now = System.currentTimeMillis();
            boolean refreshAgo = ++tickCount % 30 == 0;
            for (int i = 0; i < rv.getChildCount(); i++) {
                View row = rv.getChildAt(i);
                View d = row.findViewById(R.id.dur);
                if (d instanceof TextView && d.getTag() instanceof Long) {
                    ((TextView) d).setText(Fmt.duration(now - (Long) d.getTag()));
                }
                if (refreshAgo) {
                    View t = row.findViewById(R.id.time);
                    if (t instanceof TextView && t.getTag(R.id.run_time_tag) instanceof String) {
                        ((TextView) t).setText(Fmt.ago((String) t.getTag(R.id.run_time_tag)));
                    }
                }
                View lw = row.findViewById(R.id.liveWrap);
                if (lw != null) LiveSteps.tick(lw);
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_actions);
        bindHeader(getString(R.string.actions), repo);
        initChrome();
        rv = findViewById(R.id.list);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setItemViewCacheSize(6);
        workflowId = getIntent().getLongExtra("workflowId", 0);
        workflowName = getIntent().getStringExtra("workflowName");

        View head = LayoutInflater.from(this).inflate(R.layout.view_actions_head, rv, false);
        dash = new LinearLayout(this);
        dash.setOrientation(LinearLayout.VERTICAL);
        customSkeleton = true;
        shimmer = new ShimmerView(this);
        shimmer.setVisibility(View.GONE);
        FrameLayout shimmerBox = new FrameLayout(this);
        shimmerBox.addView(shimmer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 600)));
        runAdapter = new RunAdapter();
        footer = new FooterAdapter();
        rv.setAdapter(new ConcatAdapter(new HostAdapter(head), new HostAdapter(dash),
                new HostAdapter(shimmerBox), runAdapter, footer));
        configureAnimator();
        bindHead(head);

        btnRefresh.setOnClickListener(v -> load(true, false, true));
        action(btnA1, R.drawable.ic_run, R.string.run_workflow, v -> runWorkflowFlow());
        action(btnA2, R.drawable.ic_more, R.string.more, v -> moreMenu());
        loadWorkflowsQuietly();
    }

    private void configureAnimator() {
        if (!Motion.enabled()) {
            rv.setItemAnimator(null);
            return;
        }
        DefaultItemAnimator a = new DefaultItemAnimator();
        a.setSupportsChangeAnimations(false);
        a.setAddDuration(0);
        a.setMoveDuration(Motion.base(this));
        a.setRemoveDuration(Motion.fast(this));
        rv.setItemAnimator(a);
    }

    @Override
    protected boolean hasData() {
        return !runs.isEmpty();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        configureAnimator();
        load(true, !firstLoad);
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

    // ------------------------------------------------------------------ header (GitHub-style page head)

    private void bindHead(View head) {
        wfTitle = head.findViewById(R.id.wfTitle);
        wfSub = head.findViewById(R.id.wfSub);
        countLine = head.findViewById(R.id.countLine);
        clearFilters = head.findViewById(R.id.clearFilters);
        clearFilters.setMinHeight(Ui.dp(this, 48));
        clearFilters.setMinWidth(Ui.dp(this, 48));
        clearFilters.setGravity(Gravity.CENTER);
        ViewCompat.setAccessibilityDelegate(clearFilters, A11y.button());
        filterChips = head.findViewById(R.id.filterChips);
        wfChips = head.findViewById(R.id.wfChips);

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
                submit();
                updateCount();
                showEmpty(items.isEmpty() && !runs.isEmpty(), R.string.no_runs);
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
        if (!query.isEmpty()) countLine.setText(getString(R.string.act_count_shown, items.size(), runs.size()));
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
        buildWfChips();
    }

    private void buildWfChips() {
        if (wfChips == null) return;
        wfChips.removeAllViews();
        if (workflows == null || workflows.length() < 2) {
            ((View) wfChips.getParent()).setVisibility(View.GONE);
            return;
        }
        ((View) wfChips.getParent()).setVisibility(View.VISIBLE);
        TextView all = Ui.chip(this, getString(R.string.workflow_all), workflowId == 0);
        all.setOnClickListener(v -> selectWorkflow(0, null, null));
        wfChips.addView(all);
        for (int i = 0; i < workflows.length(); i++) {
            final JSONObject w = workflows.optJSONObject(i);
            if (w == null || !"active".equals(w.optString("state", "active"))) continue;
            TextView c = Ui.chip(this, w.optString("name"), w.optLong("id") == workflowId);
            c.setOnClickListener(v -> selectWorkflow(w.optLong("id"), w.optString("name"), w.optString("path")));
            wfChips.addView(c);
        }
    }

    private void selectWorkflow(long id, String name, String path) {
        if (id == workflowId) return;
        workflowId = id;
        workflowName = name;
        workflowPath = path;
        updateHead();
        buildWfChips();
        load(true, false);
    }

    private void watchActive(List<JSONObject> list) {
        if (!Store.runNotify(this)) return;
        for (JSONObject r : list) {
            if (Status.isActive(r.optString("status"))) {
                Perms.ensureNotifications(this);
                RunWatcher.watch(this, owner, repo, r.optLong("id"));
            }
        }
    }

    // ------------------------------------------------------------------ loading

    @Override
    protected void loading(boolean on) {
        super.loading(on);
        if (shimmer == null) return;
        if (on && runs.isEmpty()) {
            showEmpty(false, R.string.no_runs);
            shimmer.setAlpha(1f);
            shimmer.setVisibility(View.VISIBLE);
        } else if (!on && shimmer.getVisibility() == View.VISIBLE) {
            shimmer.setVisibility(View.GONE);
            Fx.slideIn(dash);
        }
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private String cacheKey(long wf, String st, String br, String ev, String ac) {
        return "runs:" + owner + "/" + repo + ":" + wf + ":" + nz(st) + ":" + nz(br) + ":" + nz(ev) + ":" + nz(ac);
    }

    private boolean fillFromCache(String key) {
        ListCache.Hit hit = ListCache.get(this, key);
        if (hit == null) return false;
        try {
            List<JSONObject> tmp = new ArrayList<>();
            for (int i = 0; i < hit.data.length(); i++) tmp.add(hit.data.getJSONObject(i));
            runs.clear();
            runs.addAll(tmp);
            totalCount = 0;
            hasMore = tmp.size() >= PER_PAGE;
            dataAt = hit.at;
            render();
            return true;
        } catch (Exception e) {
            runs.clear();
            return false;
        }
    }

    private void load(final boolean reset, final boolean silent) {
        load(reset, silent, false);
    }

    private void load(final boolean reset, final boolean silent, final boolean keep) {
        if (reset) page = 1;
        final int pg = page;
        final long wf = workflowId;
        final String st = statusFilter;
        final String br = branchFilter;
        final String ev = eventFilter;
        final String ac = actorFilter;
        final String key = cacheKey(wf, st, br, ev, ac);
        final int myGen = ++gen;
        if (reset && !silent && !keep) {
            hasMore = false;
            runs.clear();
            items.clear();
            entered.clear();
            dashSig = "";
            dash.removeAllViews();
            submit();
        }
        if (pg == 1 && runs.isEmpty()) fillFromCache(key);
        if (!silent) loading(true);
        io.execute(() -> {
            try {
                JSONObject resp = api.listRuns(owner, repo, wf, st, br, ev, ac, pg, PER_PAGE);
                JSONArray arr = resp.optJSONArray("workflow_runs");
                if (pg == 1 && arr != null) ListCache.put(getApplicationContext(), key, arr);
                final int total = resp.optInt("total_count", 0);
                final List<JSONObject> tmp = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) tmp.add(arr.getJSONObject(i));
                }
                final Map<Long, int[]> prog = new HashMap<>();
                final Map<Long, String> cur = new HashMap<>();
                final Map<Long, JSONArray> live = new HashMap<>();
                int fetched = 0;
                for (JSONObject r : tmp) {
                    if (fetched >= 3) break;
                    // queued runs too: their jobs appear (and start) while the list is open
                    if (!Status.isActive(r.optString("status"))) continue;
                    try {
                        JSONArray jobs = api.listJobs(owner, repo, r.optLong("id"));
                        if (jobs != null) live.put(r.optLong("id"), jobs);
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
                    for (JSONObject r : tmp) {
                        if (!Status.isActive(r.optString("status"))) liveJobs.remove(r.optLong("id"));
                    }
                    liveJobs.putAll(live);
                    if (pg == 1) runs.clear();
                    runs.addAll(tmp);
                    totalCount = total;
                    hasMore = tmp.size() >= PER_PAGE;
                    dataAt = System.currentTimeMillis();
                    render();
                    watchActive(tmp);
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
        if (resumed && active && page == 1) ui.postDelayed(poll, POLL_MS);
    }

    private void applySearch() {
        items.clear();
        for (JSONObject r : runs) {
            if (query.isEmpty() || matches(r)) {
                long id = r.optLong("id");
                items.add(new RunItem(r, progress.get(id), stepNow.get(id), liveJobs.get(id)));
            }
        }
    }

    private void submit() {
        runAdapter.submitList(new ArrayList<>(items));
        footer.set(hasMore);
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
        submit();
        showEmpty(items.isEmpty(), R.string.no_runs);
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
        g.setCornerRadius(Ui.radius(this, R.dimen.radius_m));
        g.setColor(Ui.color(this, R.color.field));
        t.setBackground(g);
        TextView n = text(String.valueOf(count), 22, colorRes);
        n.setTypeface(Typeface.SERIF);
        n.setGravity(Gravity.CENTER);
        TextView l = text(getString(labelRes), 12, R.color.text_secondary);
        l.setGravity(Gravity.CENTER);
        l.setMaxLines(2);
        t.setFocusable(true);
        t.setContentDescription(getString(R.string.a11y_stat, count, getString(labelRes)));
        n.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        l.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        t.addView(n);
        t.addView(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMarginStart(Ui.dp(this, 3));
        lp.setMarginEnd(Ui.dp(this, 3));
        t.setLayoutParams(lp);
        return t;
    }

    private void renderDash() {
        StringBuilder sg = new StringBuilder();
        for (JSONObject r : runs) {
            sg.append(r.optLong("id")).append(r.optString("status")).append(r.optString("conclusion"))
                    .append(r.optString("updated_at")).append(';');
        }
        String sig = sg.toString();
        if (sig.equals(dashSig) && dash.getChildCount() > 0) return;
        dashSig = sig;
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
            g.setCornerRadius(Ui.radius(this, R.dimen.radius_xs));
            g.setColor(Status.color(this, r.optString("status"), r.optString("conclusion")));
            bar.setBackground(g);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    0, Math.max(Ui.dp(this, 10), Math.round(Ui.dp(this, 48) * f)), 1);
            bp.setMarginStart(Ui.dp(this, 2));
            bp.setMarginEnd(Ui.dp(this, 2));
            strip.addView(bar, bp);
        }
        strip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
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

    private static final class RunItem {
        final long id;
        final JSONObject run;
        final int[] prog;
        final String now;
        final JSONArray jobs;
        final String sig;

        RunItem(JSONObject run, int[] prog, String now, JSONArray jobs) {
            this.id = run.optLong("id");
            this.run = run;
            this.prog = prog;
            this.now = now;
            this.jobs = jobs;
            this.sig = run.toString() + "|" + (prog == null ? "" : prog[0] + "/" + prog[1]) + "|"
                    + (now == null ? "" : now) + "|" + (jobs == null ? "" : jobs.toString());
        }
    }

    private static final DiffUtil.ItemCallback<RunItem> DIFF = new DiffUtil.ItemCallback<RunItem>() {
        @Override
        public boolean areItemsTheSame(RunItem a, RunItem b) {
            return a.id == b.id;
        }

        @Override
        public boolean areContentsTheSame(RunItem a, RunItem b) {
            return a.sig.equals(b.sig);
        }
    };

    private static final class HostAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final View host;

        HostAdapter(View host) {
            this.host = host;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (host.getParent() instanceof ViewGroup) ((ViewGroup) host.getParent()).removeView(host);
            host.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            RecyclerView.ViewHolder h = new RecyclerView.ViewHolder(host) {
            };
            h.setIsRecyclable(false);
            return h;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        }

        @Override
        public int getItemCount() {
            return 1;
        }
    }

    private final class FooterAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private boolean on = false;

        void set(boolean visible) {
            if (visible == on) return;
            on = visible;
            if (visible) notifyItemInserted(0);
            else notifyItemRemoved(0);
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(ActionsActivity.this).inflate(R.layout.item_row, parent, false);
            v.setOnClickListener(x -> {
                page++;
                load(false, false);
            });
            return new RecyclerView.ViewHolder(v) {
            };
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            RowAdapter.bind(ActionsActivity.this, holder.itemView, new Row(R.drawable.ic_refresh, false,
                    getString(R.string.load_more), null, false, false));
            Ui.shapeRow(ActionsActivity.this, holder.itemView, true, true);
        }

        @Override
        public int getItemCount() {
            return on ? 1 : 0;
        }
    }

    private final class RunAdapter extends ListAdapter<RunItem, RunHolder> {
        RunAdapter() {
            super(DIFF);
        }

        RunItem at(int position) {
            return getItem(position);
        }

        @Override
        public RunHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new RunHolder(LayoutInflater.from(ActionsActivity.this).inflate(R.layout.item_run, parent, false));
        }

        @Override
        public void onBindViewHolder(RunHolder holder, int position) {
            RunItem item = getItem(position);
            bindRun(holder.itemView, item);
            if (entered.add(item.id)) {
                Ui.enter(holder.itemView, position);
            } else {
                holder.itemView.animate().cancel();
                holder.itemView.setAlpha(1f);
                holder.itemView.setTranslationY(0f);
            }
        }

        @Override
        public void onViewRecycled(RunHolder holder) {
            holder.itemView.animate().cancel();
            holder.itemView.setAlpha(1f);
            holder.itemView.setTranslationY(0f);
            Fx.pulse(holder.itemView.findViewById(R.id.icon), false);
        }
    }

    private final class RunHolder extends RecyclerView.ViewHolder {
        RunHolder(View v) {
            super(v);
            final View card = v.findViewById(R.id.card);
            card.setOnClickListener(x -> {
                RunItem it = current();
                if (it != null) openRun(it, card);
            });
            card.setOnLongClickListener(x -> {
                RunItem it = current();
                if (it == null) return false;
                runMenu(it.run);
                return true;
            });
            ViewCompat.replaceAccessibilityAction(card, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                    getString(R.string.more), null);
            int[] btns = {R.id.act1, R.id.act2, R.id.act4};
            for (int id : btns) ViewCompat.setAccessibilityDelegate(v.findViewById(id), A11y.button());
            int[] quiet = {R.id.title, R.id.line1, R.id.branchPill, R.id.badge, R.id.time, R.id.dur, R.id.progText};
            for (int id : quiet) {
                View q = v.findViewById(id);
                if (q != null) q.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
        }

        RunItem current() {
            int p = getBindingAdapterPosition();
            return p == RecyclerView.NO_POSITION ? null : runAdapter.at(p);
        }
    }

    private void openRun(RunItem it, View card) {
        Intent i = repoIntent(RunDetailActivity.class);
        i.putExtra("runId", it.id);
        if (Motion.enabled()) {
            String name = "run_card_" + it.id;
            card.setTransitionName(name);
            i.putExtra("transitionName", name);
            startActivity(i, ActivityOptions.makeSceneTransitionAnimation(this, card, name).toBundle());
        } else {
            startActivity(i);
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

    private void bindRun(View v, final RunItem item) {
        final JSONObject run = item.run;
        final String s = run.optString("status");
        final String c = run.optString("conclusion");
        final String state = Status.state(s, c);
        final boolean active = Status.isActive(s);
        final int color = Status.color(this, s, c);

        // card with a status-tinted outline for failed / running runs
        View card = v.findViewById(R.id.card);
        card.setTransitionName("run_card_" + item.id);
        Ui.press(this, card);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Ui.color(this, R.color.surface));
        fill.setCornerRadius(Ui.radius(this, R.dimen.radius_m));
        boolean hot = isBad(state) || "in_progress".equals(state);
        fill.setStroke(Ui.dp(this, 1), hot ? ((color & 0x00FFFFFF) | 0x80000000)
                : Ui.color(this, R.color.stroke_soft));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(Ui.radius(this, R.dimen.radius_m));
        card.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Ui.color(this, R.color.ripple)), fill, mask));

        ImageView icon = v.findViewById(R.id.icon);
        icon.setImageResource(Status.icon(s, c));
        icon.setImageTintList(ColorStateList.valueOf(color));
        GradientDrawable tile = new GradientDrawable();
        tile.setShape(GradientDrawable.OVAL);
        tile.setColor((color & 0x00FFFFFF) | 0x26000000);
        icon.setBackground(tile);
        Fx.pulse(icon, active);

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
        branchPill.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels * 0.45f));
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
        bg.setCornerRadius(Ui.radius(this, R.dimen.radius_pill));
        bg.setColor((color & 0x00FFFFFF) | 0x26000000);
        badge.setBackground(bg);

        TextView time = v.findViewById(R.id.time);
        time.setText(Fmt.ago(Fmt.s(run, "created_at")));
        time.setTag(R.id.run_time_tag, Fmt.s(run, "created_at"));
        smallIcon(time, R.drawable.ic_calendar);

        TextView dur = v.findViewById(R.id.dur);
        long a = Fmt.parse(Fmt.s(run, "run_started_at"));
        dur.setTag(null);
        if (active && a > 0) {
            dur.setText(Fmt.duration(System.currentTimeMillis() - a));
            dur.setTag(a);   // advanced every second by the ticker
            dur.setVisibility(View.VISIBLE);
        } else if ("completed".equals(s) && runMillis(run) > 0) {
            dur.setText(Fmt.duration(runMillis(run)));
            dur.setVisibility(View.VISIBLE);
        } else {
            dur.setVisibility(View.GONE);
        }
        smallIcon(dur, R.drawable.ic_timer);

        // live pipeline: a progress bar plus every job with all of its steps
        View progWrap = v.findViewById(R.id.progWrap);
        LinearLayout liveWrap = v.findViewById(R.id.liveWrap);
        int[] pr = item.prog;
        if (active && pr != null && pr[1] > 0) {
            progWrap.setVisibility(View.VISIBLE);
            ProgressBar pb = v.findViewById(R.id.prog);
            int target = Math.max(4, pr[0] * 1000 / pr[1]);
            pb.setMax(1000);
            Ui.progress(pb, target);
            String now = item.now;
            String line = getString(R.string.act_step_progress, pr[0], pr[1])
                    + (now == null || now.isEmpty() ? "" : "  ·  " + now);
            ((TextView) v.findViewById(R.id.progText)).setText(line);
        } else {
            progWrap.setVisibility(View.GONE);
        }
        if (active) LiveSteps.bind(this, liveWrap, item.jobs);
        else liveWrap.setVisibility(View.GONE);

        // quick actions: cancel while running, re-run (failed jobs) after a failure
        View actions = v.findViewById(R.id.actions);
        TextView a1 = v.findViewById(R.id.act1);
        TextView a2 = v.findViewById(R.id.act2);
        TextView a4 = v.findViewById(R.id.act4);
        a2.setVisibility(View.GONE);
        a4.setVisibility(View.GONE);
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
            styleAction(a4, R.string.ex_short, R.drawable.ic_sparkles);
            a4.setText("");
            a4.setCompoundDrawablePadding(0);
            a4.setContentDescription(getString(R.string.ex_btn));
            a4.setVisibility(View.VISIBLE);
            final long runIdForExplain = run.optLong("id");
            a4.setOnClickListener(x -> explainRun(runIdForExplain, () -> {
                Intent oi = repoIntent(RunDetailActivity.class);
                oi.putExtra("runId", runIdForExplain);
                startActivity(oi);
            }));
        } else {
            actions.setVisibility(View.GONE);
        }
        v.findViewById(R.id.act3).setOnClickListener(x -> runMenu(run));

        StringBuilder cd = new StringBuilder();
        cd.append(Status.label(s, c)).append(". ").append(title).append(". ").append(l1);
        if (!hb.isEmpty()) cd.append(". ").append(getString(R.string.a11y_branch, hb));
        String ago = Fmt.ago(Fmt.s(run, "created_at"));
        if (!ago.isEmpty()) cd.append(". ").append(ago);
        if (dur.getVisibility() == View.VISIBLE) {
            cd.append(". ").append(getString(R.string.a11y_duration, dur.getText()));
        }
        card.setContentDescription(cd);
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
                getString(R.string.act_only_actor),
                getString(R.string.rw_notify_me),
                getString(R.string.ex_btn)};
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
                case 8:
                    if (!login.isEmpty()) {
                        actorFilter = login;
                        buildFilters();
                        load(true, false);
                    }
                    break;
                case 9:
                    if (!active) {
                        toast(R.string.act_not_running);
                        return;
                    }
                    Perms.ensureNotifications(this);
                    RunWatcher.watch(this, owner, repo, id);
                    toast(R.string.rw_watching_toast);
                    break;
                default:
                    if (active || !isBad(Status.state(run.optString("status"), run.optString("conclusion")))) {
                        toast(R.string.ex_no_failed);
                        return;
                    }
                    explainRun(id, () -> {
                        Intent i = repoIntent(RunDetailActivity.class);
                        i.putExtra("runId", id);
                        startActivity(i);
                    });
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

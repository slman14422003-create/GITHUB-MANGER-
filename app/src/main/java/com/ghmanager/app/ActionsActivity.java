package com.ghmanager.app;

import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

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

        btnRefresh.setOnClickListener(v -> load(true, false));
        action(btnA1, R.drawable.ic_run, R.string.run_workflow, v -> runWorkflowFlow());
        action(btnA2, R.drawable.ic_more, R.string.more, v -> moreMenu());

        listView.setOnItemClickListener((p, v, pos, id) -> {
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
        listView.setOnItemLongClickListener((p, v, pos, id) -> {
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
                post(() -> {
                    if (myGen != gen) return;
                    loading(false);
                    showStatus(null);
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
        List<Row> rows = new ArrayList<>();
        for (JSONObject run : runs) {
            String s = run.optString("status");
            String c = run.optString("conclusion");
            String title = Fmt.s(run, "display_title");
            if (title.isEmpty()) title = Fmt.s(run, "name");
            StringBuilder sub = new StringBuilder("#").append(run.optInt("run_number"));
            String wfName = Fmt.s(run, "name");
            if (!wfName.isEmpty()) sub.append(" · ").append(wfName);
            sub.append(" · ").append(Fmt.s(run, "event"));
            String hb = Fmt.s(run, "head_branch");
            if (!hb.isEmpty()) sub.append(" · ").append(hb);
            String ago = Fmt.ago(Fmt.s(run, "created_at"));
            if (!ago.isEmpty()) sub.append(" · ").append(ago);
            if ("completed".equals(s)) {
                long a = Fmt.parse(Fmt.s(run, "run_started_at"));
                long b = Fmt.parse(Fmt.s(run, "updated_at"));
                if (a > 0 && b >= a) sub.append(" · ").append(Fmt.duration(b - a));
            }
            int color = Status.color(this, s, c);
            rows.add(new Row(Status.icon(s, c), false, title, sub.toString(), false, true)
                    .tint(color).badge(Status.label(s, c), color));
        }
        if (hasMore) rows.add(new Row(R.drawable.ic_refresh, false, getString(R.string.load_more), null, false, false));
        adapter.setRows(rows);
        showEmpty(runs.isEmpty(), R.string.no_runs);
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

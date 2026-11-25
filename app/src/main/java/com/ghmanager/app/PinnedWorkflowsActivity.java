package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class PinnedWorkflowsActivity extends BaseRepoActivity {
    private final List<PinnedWorkflows.Item> items = new ArrayList<>();
    private final List<JSONObject> lastRuns = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        bindHeader(getString(R.string.pw_title), null);
        initList();
        btnRefresh.setOnClickListener(v -> load());
        listView.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < items.size()) menu(items.get(pos), lastRuns.get(pos));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        final List<PinnedWorkflows.Item> pins = PinnedWorkflows.all(this);
        if (pins.isEmpty()) {
            items.clear();
            lastRuns.clear();
            adapter.setRows(new ArrayList<>());
            loading(false);
            showEmpty(true, R.string.pw_empty);
            return;
        }
        showEmpty(false, R.string.pw_empty);
        loading(true);
        io.execute(() -> {
            final List<JSONObject> runs = new ArrayList<>();
            Exception firstError = null;
            for (PinnedWorkflows.Item it : pins) {
                JSONObject run = null;
                try {
                    JSONArray a = api.listRuns(it.owner, it.repo, it.id, null, null, 1, 1).optJSONArray("workflow_runs");
                    if (a != null && a.length() > 0) run = a.optJSONObject(0);
                } catch (GitHubApi.ApiException e) {
                    if (e.code == 401) {
                        fail(e);
                        return;
                    }
                    if (firstError == null) firstError = e;
                } catch (Exception e) {
                    if (firstError == null) firstError = e;
                }
                runs.add(run);
            }
            final Exception err = firstError;
            post(() -> {
                loading(false);
                showStatus(err == null ? null : (err.getMessage() == null ? err.toString() : err.getMessage()));
                items.clear();
                items.addAll(pins);
                lastRuns.clear();
                lastRuns.addAll(runs);
                render();
            });
        });
    }

    private void render() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            PinnedWorkflows.Item it = items.get(i);
            JSONObject run = lastRuns.get(i);
            String sub;
            Row r;
            if (run == null) {
                r = new Row(R.drawable.ic_play, false, it.name, it.full() + " · " + getString(R.string.pw_no_runs),
                        false, true);
            } else {
                String s = run.optString("status");
                String c = run.isNull("conclusion") ? "" : run.optString("conclusion");
                sub = it.full() + " · " + getString(R.string.pw_last, "#" + run.optInt("run_number"),
                        Fmt.ago(Fmt.s(run, "created_at")));
                r = new Row(Status.icon(s, c), false, it.name, sub, false, true);
                r.tint(Status.color(this, s, c));
                r.badge(Status.label(s, c), Status.color(this, s, c));
            }
            rows.add(r);
        }
        adapter.setRows(rows);
        showEmpty(items.isEmpty(), R.string.pw_empty);
    }

    private Intent intentFor(Class<?> cls, PinnedWorkflows.Item it) {
        Intent i = new Intent(this, cls);
        i.putExtra("owner", it.owner);
        i.putExtra("repo", it.repo);
        i.putExtra("workflowId", it.id);
        i.putExtra("workflowName", it.name);
        return i;
    }

    private void menu(final PinnedWorkflows.Item it, final JSONObject run) {
        List<String> opts = new ArrayList<>();
        final boolean hasRun = run != null;
        if (hasRun) opts.add(getString(R.string.pw_open_last));
        opts.add(getString(R.string.view_runs));
        opts.add(getString(R.string.pw_stats));
        opts.add(getString(R.string.pw_unpin));
        final int off = hasRun ? 1 : 0;
        choose(it.name, opts.toArray(new String[0]), (d, which) -> {
            if (hasRun && which == 0) {
                Intent i = intentFor(RunDetailActivity.class, it);
                i.putExtra("runId", run.optLong("id"));
                String b = Fmt.s(run, "head_branch");
                if (!b.isEmpty()) i.putExtra("branch", b);
                startActivity(i);
            } else if (which == off) {
                startActivity(intentFor(ActionsActivity.class, it));
            } else if (which == off + 1) {
                startActivity(intentFor(BuildStatsActivity.class, it));
            } else {
                PinnedWorkflows.unpin(this, it.owner, it.repo, it.id);
                toast(R.string.pw_unpinned);
                load();
            }
        });
    }
}

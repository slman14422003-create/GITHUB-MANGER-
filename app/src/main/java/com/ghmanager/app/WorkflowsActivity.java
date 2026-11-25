package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class WorkflowsActivity extends BaseRepoActivity {
    private final List<JSONObject> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        bindHeader(getString(R.string.workflows), repo);
        initList();
        btnRefresh.setOnClickListener(v -> load());
        action(btnA1, R.drawable.ic_chart, R.string.pw_repo_stats, v -> openStats(0, null));
        listView.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < items.size()) menu(items.get(pos));
        });
        load();
    }

    private void load() {
        loading(true);
        io.execute(() -> {
            try {
                JSONArray arr = api.listWorkflows(owner, repo);
                final List<JSONObject> tmp = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) tmp.add(arr.getJSONObject(i));
                }
                post(() -> {
                    loading(false);
                    showStatus(null);
                    items.clear();
                    items.addAll(tmp);
                    render();
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private boolean pinned(JSONObject w) {
        return PinnedWorkflows.isPinned(this, owner, repo, w.optLong("id"));
    }

    private void render() {
        List<JSONObject> sorted = new ArrayList<>();
        for (JSONObject w : items) if (pinned(w)) sorted.add(w);
        for (JSONObject w : items) if (!pinned(w)) sorted.add(w);
        items.clear();
        items.addAll(sorted);
        List<Row> rows = new ArrayList<>();
        for (JSONObject w : items) {
            boolean active = "active".equals(w.optString("state"));
            Row r = new Row(R.drawable.ic_play, active, w.optString("name"), w.optString("path"), false, true);
            if (pinned(w)) r.badge(getString(R.string.pw_badge), Ui.color(this, R.color.accent));
            else if (!active) r.badge(getString(R.string.disabled), Ui.color(this, R.color.warn));
            rows.add(r);
        }
        adapter.setRows(rows);
        showEmpty(items.isEmpty(), R.string.no_workflows);
    }

    private void openStats(long id, String name) {
        Intent i = repoIntent(BuildStatsActivity.class);
        i.putExtra("workflowId", id);
        i.putExtra("workflowName", name);
        startActivity(i);
    }

    private void menu(final JSONObject w) {
        final long id = w.optLong("id");
        final String name = w.optString("name");
        final boolean active = "active".equals(w.optString("state"));
        final boolean pin = pinned(w);
        String[] opts = {
                getString(R.string.run_now),
                getString(R.string.view_runs),
                getString(R.string.edit_workflow_file),
                getString(active ? R.string.disable : R.string.enable),
                getString(pin ? R.string.pw_unpin : R.string.pw_pin),
                getString(R.string.pw_stats)};
        choose(name, opts, (d, which) -> {
            if (which == 0) {
                dispatchDialog(id, name, null);
            } else if (which == 1) {
                Intent i = repoIntent(ActionsActivity.class);
                i.putExtra("workflowId", id);
                i.putExtra("workflowName", name);
                startActivity(i);
            } else if (which == 2) {
                EditorActivity.launch(this, w.optString("path"), false);
            } else if (which == 3) {
                bg(() -> {
                    api.setWorkflowEnabled(owner, repo, id, !active);
                    post(() -> {
                        toast(R.string.done_ok);
                        load();
                    });
                });
            } else if (which == 4) {
                if (pin) {
                    PinnedWorkflows.unpin(this, owner, repo, id);
                    toast(R.string.pw_unpinned);
                } else if (PinnedWorkflows.pin(this, owner, repo, id, name)) {
                    toast(R.string.pw_pinned);
                } else {
                    toast(R.string.pw_limit);
                }
                render();
            } else {
                openStats(id, name);
            }
        });
    }
}

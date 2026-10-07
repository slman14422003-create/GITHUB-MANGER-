package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Lists the workflows of a repository: run, enable/disable, edit the file, view its runs. */
public class WorkflowsActivity extends BaseRepoActivity {
    private final List<JSONObject> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        bindHeader(getString(R.string.workflows), repo);
        initList();
        btnRefresh.setOnClickListener(v -> load());
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

    private void render() {
        List<Row> rows = new ArrayList<>();
        for (JSONObject w : items) {
            boolean active = "active".equals(w.optString("state"));
            Row r = new Row(R.drawable.ic_play, active, w.optString("name"), w.optString("path"), false, true);
            if (!active) r.badge(getString(R.string.disabled), Ui.color(this, R.color.warn));
            rows.add(r);
        }
        adapter.setRows(rows);
        showEmpty(items.isEmpty(), R.string.no_workflows);
    }

    private void menu(final JSONObject w) {
        final long id = w.optLong("id");
        final String name = w.optString("name");
        final boolean active = "active".equals(w.optString("state"));
        String[] opts = {
                getString(R.string.run_now),
                getString(R.string.view_runs),
                getString(R.string.edit_workflow_file),
                getString(active ? R.string.disable : R.string.enable)};
        choose(name, opts, (d, which) -> {
            if (which == 0) {
                dispatchDialog(id, name, null);
            } else if (which == 1) {
                Intent i = repoIntent(ActionsActivity.class);
                i.putExtra("workflowId", id);
                i.putExtra("workflowName", name);
                startActivity(i);
            } else if (which == 2) {
                Intent i = repoIntent(EditorActivity.class);
                i.putExtra("path", w.optString("path"));
                startActivity(i);
            } else {
                bg(() -> {
                    api.setWorkflowEnabled(owner, repo, id, !active);
                    post(() -> {
                        toast(R.string.done_ok);
                        load();
                    });
                });
            }
        });
    }
}

package com.ghmanager.app;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Repository-level Actions data, selected by the "mode" extra:
 * artifacts, caches, variables or secrets (names only).
 */
public class ActionsDataActivity extends BaseRepoActivity {
    private String mode = "artifacts";
    private final List<JSONObject> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        String m = getIntent().getStringExtra("mode");
        if (m != null) mode = m;
        int titleRes;
        switch (mode) {
            case "caches":
                titleRes = R.string.caches;
                break;
            case "variables":
                titleRes = R.string.variables;
                break;
            case "secrets":
                titleRes = R.string.secrets;
                break;
            default:
                titleRes = R.string.artifacts;
                break;
        }
        bindHeader(getString(titleRes), repo);
        initList();
        btnRefresh.setOnClickListener(v -> load());
        if ("variables".equals(mode)) {
            action(btnA1, R.drawable.ic_add, R.string.add, v -> variableDialog(null));
        } else if ("caches".equals(mode)) {
            action(btnA1, R.drawable.ic_delete, R.string.delete_all_caches, v -> deleteAllCaches());
        }
        if ("secrets".equals(mode)) showStatus(getString(R.string.secrets_note));
        listView.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < items.size()) itemMenu(items.get(pos));
        });
        load();
    }

    private void load() {
        loading(true);
        io.execute(() -> {
            try {
                JSONArray arr;
                String sub = null;
                switch (mode) {
                    case "caches":
                        arr = api.listCaches(owner, repo);
                        try {
                            JSONObject u = api.cacheUsage(owner, repo);
                            sub = Fmt.size(u.optLong("active_caches_size_in_bytes")) + " · "
                                    + u.optInt("active_caches_count");
                        } catch (Exception ignored) {
                        }
                        break;
                    case "variables":
                        arr = api.listVariables(owner, repo);
                        break;
                    case "secrets":
                        arr = api.listSecrets(owner, repo);
                        break;
                    default:
                        arr = api.listArtifacts(owner, repo);
                        break;
                }
                final List<JSONObject> tmp = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) tmp.add(arr.getJSONObject(i));
                }
                final String fSub = sub;
                post(() -> {
                    loading(false);
                    if (!"secrets".equals(mode)) showStatus(null);
                    if (fSub != null) setSubtitle(repo + " · " + fSub);
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
        for (JSONObject o : items) {
            switch (mode) {
                case "caches": {
                    String sub = Fmt.size(o.optLong("size_in_bytes")) + " · " + Fmt.s(o, "ref")
                            + " · " + Fmt.ago(Fmt.s(o, "last_accessed_at"));
                    rows.add(new Row(R.drawable.ic_package, false, o.optString("key"), sub, false, true));
                    break;
                }
                case "variables":
                    rows.add(new Row(R.drawable.ic_edit, false, o.optString("name"),
                            o.optString("value"), false, true));
                    break;
                case "secrets":
                    rows.add(new Row(R.drawable.ic_lock, false, o.optString("name"),
                            Fmt.ago(Fmt.s(o, "updated_at")), false, true));
                    break;
                default: {
                    boolean expired = o.optBoolean("expired");
                    String sub = Fmt.size(o.optLong("size_in_bytes")) + " · " + Fmt.ago(Fmt.s(o, "created_at"));
                    JSONObject wr = o.optJSONObject("workflow_run");
                    if (wr != null && !Fmt.s(wr, "head_branch").isEmpty()) sub += " · " + Fmt.s(wr, "head_branch");
                    Row r = new Row(R.drawable.ic_package, false, o.optString("name"), sub, false, true);
                    if (expired) r.badge(getString(R.string.expired), Ui.color(this, R.color.warn));
                    rows.add(r);
                    break;
                }
            }
        }
        adapter.setRows(rows);
        showEmpty(items.isEmpty(), R.string.nothing_here);
    }

    private void itemMenu(final JSONObject o) {
        switch (mode) {
            case "artifacts":
                artifactMenu(o, this::load);
                break;
            case "caches": {
                final long id = o.optLong("id");
                String key = o.optString("key");
                deleteWithConfirm(key, () -> api.deleteCache(owner, repo, id));
                break;
            }
            case "variables": {
                final String name = o.optString("name");
                String[] opts = {getString(R.string.edit), getString(R.string.delete)};
                choose(name, opts, (d, which) -> {
                    if (which == 0) variableDialog(o);
                    else deleteWithConfirm(name, () -> api.deleteVariable(owner, repo, name));
                });
                break;
            }
            default: {
                final String name = o.optString("name");
                deleteWithConfirm(name, () -> api.deleteSecret(owner, repo, name));
                break;
            }
        }
    }

    private void deleteWithConfirm(String name, final Job job) {
        confirm(getString(R.string.delete), getString(R.string.delete_msg, name), R.string.delete, () ->
                bg(() -> {
                    job.run();
                    post(this::load);
                }));
    }

    private void deleteAllCaches() {
        if (items.isEmpty()) return;
        confirm(getString(R.string.delete_all_caches), getString(R.string.delete_all_caches_msg), R.string.delete, () -> {
            if (busy) return;
            busy = true;
            showProgress(getString(R.string.working));
            final List<JSONObject> copy = new ArrayList<>(items);
            bg(() -> {
                final int total = copy.size();
                for (int i = 0; i < total; i++) {
                    final int cur = i;
                    final String key = copy.get(i).optString("key");
                    post(() -> updateProgress(cur, total, key));
                    try {
                        api.deleteCache(owner, repo, copy.get(i).optLong("id"));
                    } catch (GitHubApi.ApiException ignored) {
                    }
                }
                post(() -> {
                    hideProgress();
                    load();
                });
            });
        });
    }

    private void variableDialog(final JSONObject existing) {
        LinearLayout box = Ui.box(this);
        final EditText name = Ui.edit(this, getString(R.string.var_name), existing == null ? null : existing.optString("name"));
        final EditText value = Ui.editMulti(this, getString(R.string.var_value), existing == null ? null : existing.optString("value"), 2);
        if (existing != null) name.setEnabled(false);
        box.addView(name);
        box.addView(value);
        new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.add_variable : R.string.edit_variable)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    final String n = name.getText().toString().trim();
                    final String v = value.getText().toString();
                    if (n.isEmpty()) return;
                    bg(() -> {
                        if (existing == null) api.createVariable(owner, repo, n, v);
                        else api.updateVariable(owner, repo, n, v);
                        post(this::load);
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}

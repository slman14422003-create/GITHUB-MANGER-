package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Branch management: create, delete, merge, set default. */
public class BranchesActivity extends BaseRepoActivity {
    private final List<JSONObject> items = new ArrayList<>();
    private String defaultBranch = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        bindHeader(getString(R.string.branches), repo);
        initList();
        defaultBranch = branch;
        btnRefresh.setOnClickListener(v -> load());
        action(btnA1, R.drawable.ic_add, R.string.new_branch, v -> createDialog(defaultBranch));
        listView.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < items.size()) menu(items.get(pos));
        });
        load();
    }

    private void load() {
        loading(true);
        io.execute(() -> {
            try {
                final String def = api.getRepo(owner, repo).optString("default_branch", branch);
                JSONArray arr = api.listBranches(owner, repo);
                final List<JSONObject> tmp = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) tmp.add(arr.getJSONObject(i));
                post(() -> {
                    loading(false);
                    showStatus(null);
                    defaultBranch = def;
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
            String name = o.optString("name");
            JSONObject c = o.optJSONObject("commit");
            Row r = new Row(R.drawable.ic_branch, name.equals(defaultBranch), name,
                    c == null ? "" : Fmt.shortSha(c.optString("sha")), o.optBoolean("protected"), true);
            if (name.equals(defaultBranch)) r.badge(getString(R.string.default_label), Ui.color(this, R.color.accent_text));
            rows.add(r);
        }
        adapter.setRows(rows);
        showEmpty(items.isEmpty(), R.string.nothing_here);
    }

    private void menu(final JSONObject o) {
        final String name = o.optString("name");
        final boolean isDefault = name.equals(defaultBranch);
        String[] opts = {
                getString(R.string.browse_files),
                getString(R.string.commits),
                getString(R.string.new_branch_from),
                getString(R.string.merge_into),
                getString(R.string.set_default),
                getString(R.string.delete)};
        choose(name, opts, (d, which) -> {
            switch (which) {
                case 0: {
                    Intent i = repoIntent(BrowserActivity.class);
                    i.putExtra("branch", name);
                    startActivity(i);
                    break;
                }
                case 1: {
                    Intent i = repoIntent(CommitsActivity.class);
                    i.putExtra("branch", name);
                    startActivity(i);
                    break;
                }
                case 2:
                    createDialog(name);
                    break;
                case 3:
                    mergeInto(name);
                    break;
                case 4:
                    if (isDefault) {
                        toast(R.string.already_default);
                        break;
                    }
                    bg(() -> {
                        JSONObject b = new JSONObject();
                        b.put("default_branch", name);
                        api.updateRepo(owner, repo, b);
                        post(this::load);
                    });
                    break;
                default:
                    if (isDefault) {
                        toast(R.string.cannot_delete_default);
                        break;
                    }
                    confirm(getString(R.string.delete), getString(R.string.delete_msg, name), R.string.delete, () ->
                            bg(() -> {
                                api.deleteBranch(owner, repo, name);
                                post(this::load);
                            }));
                    break;
            }
        });
    }

    private void createDialog(String from) {
        LinearLayout box = Ui.box(this);
        final EditText name = Ui.edit(this, getString(R.string.new_branch_name), null);
        final List<String> names = new ArrayList<>();
        for (JSONObject o : items) names.add(o.optString("name"));
        int sel = Math.max(0, names.indexOf(from));
        final Spinner sp = Ui.spinner(this, names, sel);
        box.addView(name);
        box.addView(Ui.label(this, getString(R.string.create_from)));
        box.addView(sp);
        new Dlg(this)
                .setTitle(R.string.new_branch)
                .setView(box)
                .setPositiveButton(R.string.create, (d, w) -> {
                    final String n = name.getText().toString().trim();
                    if (n.isEmpty() || names.isEmpty()) return;
                    final String src = names.get(sp.getSelectedItemPosition());
                    bg(() -> {
                        String sha = api.getBranchSha(owner, repo, src);
                        api.createBranch(owner, repo, n, sha);
                        post(this::load);
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void mergeInto(final String head) {
        final List<String> targets = new ArrayList<>();
        for (JSONObject o : items) {
            if (!o.optString("name").equals(head)) targets.add(o.optString("name"));
        }
        if (targets.isEmpty()) {
            toast(R.string.nothing_here);
            return;
        }
        choose(getString(R.string.merge_into_title, head), targets.toArray(new String[0]), (d, which) -> {
            final String base = targets.get(which);
            confirm(getString(R.string.merge_into), getString(R.string.merge_confirm, head, base), R.string.merge, () ->
                    bg(() -> {
                        boolean created = api.mergeBranches(owner, repo, base, head, null);
                        post(() -> {
                            toast(created ? R.string.merged : R.string.nothing_to_merge);
                            load();
                        });
                    }));
        });
    }
}

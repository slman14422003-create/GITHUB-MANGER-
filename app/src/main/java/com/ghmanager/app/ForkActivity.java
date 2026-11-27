package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ForkActivity extends BaseRepoActivity {
    private final List<Runnable> actions = new ArrayList<>();
    private JSONObject data;
    private JSONObject parent;
    private String current = "";
    private int ahead = -1;
    private int behind = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        bindHeader(getString(R.string.fk_title), repo);
        initList();
        btnRefresh.setOnClickListener(v -> load());
        listView.setOnItemClickListener((p, v, pos, id) -> {
            if (!busy && pos >= 0 && pos < actions.size()) actions.get(pos).run();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onAccessChanged() {
        if (data != null) render();
    }

    private void load() {
        loading(true);
        final String picked = current;
        io.execute(() -> {
            try {
                final JSONObject r = api.getRepo(owner, repo);
                final JSONObject p = r.optJSONObject("parent");
                final String b = picked.isEmpty() ? r.optString("default_branch", branch) : picked;
                int a = -1;
                int h = -1;
                if (p != null) {
                    try {
                        JSONObject c = api.compare(owner, repo, upstreamRef(p), b);
                        a = c.optInt("ahead_by", -1);
                        h = c.optInt("behind_by", -1);
                    } catch (Exception ignored) {
                    }
                }
                final int fa = a;
                final int fh = h;
                post(() -> {
                    loading(false);
                    data = r;
                    parent = p;
                    current = b;
                    ahead = fa;
                    behind = fh;
                    adoptRepoJson(r);
                    render();
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private static String parentOwner(JSONObject p) {
        return p.optJSONObject("owner").optString("login");
    }

    private static String upstreamRef(JSONObject p) {
        return parentOwner(p) + ":" + p.optString("default_branch");
    }

    private void add(List<Row> rows, int icon, int title, String sub, Runnable action) {
        rows.add(new Row(icon, true, getString(title), sub, false, true));
        actions.add(action);
    }

    private void render() {
        List<Row> rows = new ArrayList<>();
        actions.clear();
        if (parent != null) {
            showStatus(getString(R.string.fk_from, parent.optString("full_name"))
                    + (ahead < 0 ? "" : "\n" + getString(R.string.fk_state, ahead, behind)));
        } else {
            showStatus(null);
        }
        add(rows, R.drawable.ic_add, R.string.fk_create, data.optString("full_name"), this::createDialog);
        add(rows, R.drawable.ic_branch, R.string.fk_list,
                String.valueOf(data.optInt("forks_count")), this::showForks);
        if (parent != null) {
            add(rows, R.drawable.ic_open, R.string.fk_open_parent, parent.optString("full_name"),
                    () -> openRepo(parent));
            add(rows, R.drawable.ic_branch, R.string.fk_branch, current, this::pickBranch);
            add(rows, R.drawable.ic_commit, R.string.fk_compare, upstreamRef(parent),
                    () -> startActivity(CommitDiffActivity.compare(this, upstreamRef(parent), current)));
            add(rows, R.drawable.ic_issue, R.string.fk_pr, upstreamRef(parent), this::pullDialog);
            if (canWrite) {
                add(rows, R.drawable.ic_download, R.string.fk_sync, current, this::sync);
                add(rows, R.drawable.ic_commit, R.string.fk_reset, current, this::reset);
                add(rows, R.drawable.ic_open, R.string.fk_delete, data.optString("full_name"), this::deleteFork);
            }
        }
        adapter.setRows(rows);
    }

    private void openRepo(JSONObject r) {
        JSONObject o = r.optJSONObject("owner");
        if (o == null) return;
        Intent i = new Intent(this, RepoHomeActivity.class);
        i.putExtra("owner", o.optString("login"));
        i.putExtra("repo", r.optString("name"));
        i.putExtra("branch", r.optString("default_branch"));
        startActivity(i);
    }

    private void createDialog() {
        LinearLayout box = Ui.box(this);
        final EditText name = Ui.edit(this, getString(R.string.repo_name), data.optString("name"));
        final EditText org = Ui.edit(this, getString(R.string.fk_org), null);
        final CheckBox only = Ui.check(this, R.string.fk_default_only, true);
        box.addView(name);
        box.addView(org);
        box.addView(only);
        new Dlg(this)
                .setTitle(R.string.fk_create)
                .setView(box)
                .setPositiveButton(R.string.fk_create, (d, w) -> create(
                        org.getText().toString().trim(), name.getText().toString().trim(), only.isChecked()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void create(final String org, final String name, final boolean only) {
        work(() -> {
            final JSONObject f = api.createFork(owner, repo, org, name, only);
            final String fo = f.getJSONObject("owner").getString("login");
            final String fn = f.getString("name");
            boolean ready = false;
            for (int i = 0; i < 15 && !ready; i++) {
                try {
                    api.arr("/repos/" + fo + "/" + fn + "/commits?per_page=1");
                    ready = true;
                } catch (GitHubApi.ApiException e) {
                    Thread.sleep(2000);
                }
            }
            final boolean ok = ready;
            post(() -> {
                if (ok) {
                    toast(R.string.done_ok);
                    openRepo(f);
                } else {
                    info(getString(R.string.fk_title), getString(R.string.fk_pending));
                }
            });
        });
    }

    private void work(final Job job) {
        if (busy) return;
        busy = true;
        showProgress(getString(R.string.working));
        bg(() -> {
            try {
                job.run();
            } finally {
                post(() -> {
                    busy = false;
                    hideProgress();
                });
            }
        });
    }

    private void showForks() {
        loading(true);
        io.execute(() -> {
            try {
                final JSONArray a = api.listForks(owner, repo);
                post(() -> {
                    loading(false);
                    if (a.length() == 0) {
                        toast(R.string.nothing_here);
                        return;
                    }
                    String[] names = new String[a.length()];
                    for (int i = 0; i < names.length; i++) names[i] = a.optJSONObject(i).optString("full_name");
                    choose(getString(R.string.fk_list), names, (d, which) -> openRepo(a.optJSONObject(which)));
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void pickBranch() {
        loading(true);
        io.execute(() -> {
            try {
                final JSONArray a = api.listBranches(owner, repo);
                post(() -> {
                    loading(false);
                    String[] names = new String[a.length()];
                    for (int i = 0; i < names.length; i++) names[i] = a.optJSONObject(i).optString("name");
                    choose(getString(R.string.fk_branch), names, (d, which) -> {
                        current = names[which];
                        load();
                    });
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void sync() {
        if (!requireWrite()) return;
        confirm(getString(R.string.fk_sync), getString(R.string.fk_sync_msg, current), R.string.confirm_ok, () -> {
            work(() -> {
                final JSONObject res = api.mergeUpstream(owner, repo, current);
                post(() -> {
                    info(getString(R.string.fk_sync), res.optString("message"));
                    load();
                });
            });
        });
    }

    private void reset() {
        if (!requireWrite()) return;
        confirm(getString(R.string.fk_reset), getString(R.string.fk_reset_msg, current, upstreamRef(parent)),
                R.string.confirm_ok, () -> {
                    work(() -> {
                        api.resetToUpstream(owner, repo, current, parentOwner(parent), parent.optString("name"),
                                parent.optString("default_branch"));
                        post(() -> {
                            toast(R.string.done_ok);
                            load();
                        });
                    });
                });
    }

    private void pullDialog() {
        LinearLayout box = Ui.box(this);
        final EditText title = Ui.edit(this, getString(R.string.fk_pr_title), current);
        final EditText body = Ui.editMulti(this, getString(R.string.description), null, 3);
        box.addView(title);
        box.addView(body);
        new Dlg(this)
                .setTitle(R.string.fk_pr)
                .setView(box)
                .setPositiveButton(R.string.confirm_ok, (d, w) -> {
                    final String t = title.getText().toString().trim();
                    final String b = body.getText().toString();
                    if (t.isEmpty()) {
                        Dlg.stay(d);
                        return;
                    }
                    work(() -> {
                        final JSONObject pr = api.createPull(parentOwner(parent), parent.optString("name"), t, b,
                                owner + ":" + current, parent.optString("default_branch"), false);
                        post(() -> {
                            toast(R.string.done_ok);
                            openUrl(pr.optString("html_url"));
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteFork() {
        if (!requireWrite()) return;
        final String full = data.optString("full_name");
        LinearLayout box = Ui.box(this);
        final EditText typed = Ui.edit(this, full, null);
        box.addView(Ui.label(this, getString(R.string.fk_delete_msg, full)));
        box.addView(typed);
        new Dlg(this)
                .setTitle(R.string.fk_delete)
                .setView(box)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    if (!full.equals(typed.getText().toString().trim())) {
                        Dlg.stay(d);
                        return;
                    }
                    work(() -> {
                        api.deleteRepo(owner, repo);
                        post(() -> {
                            toast(R.string.done_ok);
                            startActivity(new Intent(this, ReposActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                            finish();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}

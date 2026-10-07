package com.ghmanager.app;

import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Commit history of a branch, with details and a few quick actions. */
public class CommitsActivity extends BaseRepoActivity {
    private static final int PER_PAGE = 30;
    private final List<JSONObject> items = new ArrayList<>();
    private int page = 1;
    private boolean hasMore = false;
    private int gen = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        bindHeader(getString(R.string.commits), branch);
        initList();
        btnRefresh.setOnClickListener(v -> load(true));
        action(btnA1, R.drawable.ic_branch, R.string.branch_label, v -> pickBranch());
        action(btnA2, R.drawable.ic_undo, R.string.undo_last, v -> undoLast());
        listView.setOnItemClickListener((p, v, pos, id) -> {
            if (pos == items.size()) {
                page++;
                load(false);
                return;
            }
            if (pos >= 0 && pos < items.size()) menu(items.get(pos));
        });
        load(true);
    }

    private void load(final boolean reset) {
        if (reset) page = 1;
        final int pg = page;
        final String b = branch;
        final int myGen = ++gen;
        loading(true);
        io.execute(() -> {
            try {
                JSONArray arr = api.listCommits(owner, repo, b, pg, PER_PAGE);
                final List<JSONObject> tmp = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) tmp.add(arr.getJSONObject(i));
                post(() -> {
                    if (myGen != gen) return;
                    loading(false);
                    showStatus(null);
                    if (pg == 1) items.clear();
                    items.addAll(tmp);
                    hasMore = tmp.size() >= PER_PAGE;
                    render();
                });
            } catch (Exception e) {
                if (myGen == gen) fail(e);
            }
        });
    }

    private void render() {
        List<Row> rows = new ArrayList<>();
        for (int idx = 0; idx < items.size(); idx++) {
            JSONObject o = items.get(idx);
            JSONObject c = o.optJSONObject("commit");
            String msg = c == null ? "" : Fmt.firstLine(c.optString("message"));
            JSONObject au = c == null ? null : c.optJSONObject("author");
            StringBuilder sub = new StringBuilder(Fmt.shortSha(o.optString("sha")));
            if (au != null) {
                sub.append(" · ").append(au.optString("name"));
                String ago = Fmt.ago(Fmt.s(au, "date"));
                if (!ago.isEmpty()) sub.append(" · ").append(ago);
            }
            Row row = new Row(R.drawable.ic_commit, true, msg, sub.toString(), false, true);
            if (idx == 0) row.badge(getString(R.string.current_version), Ui.color(this, R.color.ok));
            rows.add(row);
        }
        if (hasMore) rows.add(new Row(R.drawable.ic_refresh, false, getString(R.string.load_more), null, false, false));
        adapter.setRows(rows);
        showEmpty(items.isEmpty(), R.string.no_commits);
    }

    private void pickBranch() {
        loading(true);
        io.execute(() -> {
            try {
                JSONArray arr = api.listBranches(owner, repo);
                final String[] names = new String[arr.length()];
                for (int i = 0; i < names.length; i++) names[i] = arr.getJSONObject(i).optString("name");
                post(() -> {
                    loading(false);
                    choose(getString(R.string.branch_label), names, (d, which) -> {
                        branch = names[which];
                        setSubtitle(branch);
                        load(true);
                    });
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void menu(final JSONObject o) {
        final String sha = o.optString("sha");
        JSONObject cm = o.optJSONObject("commit");
        final String msg = cm == null ? "" : Fmt.firstLine(cm.optString("message"));
        String[] opts = {getString(R.string.details), getString(R.string.download_version),
                getString(R.string.restore_here), getString(R.string.copy_sha),
                getString(R.string.create_branch_here), getString(R.string.open_in_github)};
        choose(Fmt.shortSha(sha), opts, (d, which) -> {
            if (which == 0) details(sha);
            else if (which == 1) downloadVersion(sha, Fmt.shortSha(sha));
            else if (which == 2) restoreVersion(sha, Fmt.shortSha(sha), msg, () -> load(true));
            else if (which == 3) copy("sha", sha);
            else if (which == 4) branchHere(sha);
            else openUrl(Fmt.s(o, "html_url"));
        });
    }

    /** One tap rollback: puts the branch back to the version just before the latest commit. */
    private void undoLast() {
        if (items.isEmpty()) return;
        JSONObject head = items.get(0);
        JSONArray parents = head.optJSONArray("parents");
        JSONObject first = parents == null ? null : parents.optJSONObject(0);
        if (first == null) {
            toast(R.string.no_previous_version);
            return;
        }
        JSONObject cm = head.optJSONObject("commit");
        String detail = getString(R.string.undo_detail,
                cm == null ? "" : Fmt.firstLine(cm.optString("message")));
        String parent = first.optString("sha");
        restoreVersion(parent, Fmt.shortSha(parent), detail, () -> load(true));
    }

    private void details(final String sha) {
        loading(true);
        io.execute(() -> {
            try {
                JSONObject c = api.getCommit(owner, repo, sha);
                JSONObject cm = c.optJSONObject("commit");
                StringBuilder sb = new StringBuilder();
                if (cm != null) sb.append(cm.optString("message")).append("\n\n");
                JSONObject au = cm == null ? null : cm.optJSONObject("author");
                if (au != null) sb.append(au.optString("name")).append(" · ").append(Fmt.date(Fmt.s(au, "date"))).append('\n');
                JSONObject st = c.optJSONObject("stats");
                if (st != null) {
                    sb.append("+").append(st.optInt("additions")).append("  −").append(st.optInt("deletions")).append('\n');
                }
                JSONArray files = c.optJSONArray("files");
                if (files != null) {
                    sb.append("\n").append(getString(R.string.changed_files, files.length())).append('\n');
                    for (int i = 0; i < Math.min(files.length(), 25); i++) {
                        JSONObject f = files.getJSONObject(i);
                        String s = f.optString("status");
                        String mark = "added".equals(s) ? "A" : "removed".equals(s) ? "D" : "renamed".equals(s) ? "R" : "M";
                        sb.append(mark).append("  ").append(f.optString("filename")).append('\n');
                    }
                    if (files.length() > 25) sb.append("…");
                }
                final String text = sb.toString();
                post(() -> {
                    loading(false);
                    info(Fmt.shortSha(sha), text);
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void branchHere(final String sha) {
        LinearLayout box = Ui.box(this);
        final EditText name = Ui.edit(this, getString(R.string.new_branch_name), null);
        box.addView(name);
        new Dlg(this)
                .setTitle(R.string.create_branch_here)
                .setView(box)
                .setPositiveButton(R.string.create, (d, w) -> {
                    final String n = name.getText().toString().trim();
                    if (n.isEmpty()) { Dlg.stay(d); return; }
                    bg(() -> {
                        api.createBranch(owner, repo, n, sha);
                        post(() -> toast(R.string.done_ok));
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}

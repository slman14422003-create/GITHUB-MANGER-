package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Issues and pull requests with state / type filters. */
public class IssuesActivity extends BaseRepoActivity {
    private static final int PER_PAGE = 30;
    private static final String[] STATES = {"open", "closed", "all"};
    private final List<JSONObject> all = new ArrayList<>();
    private final List<JSONObject> shown = new ArrayList<>();
    private int stateIdx = 0;
    private int typeIdx = 0; // 0 all, 1 issues, 2 PRs
    private int page = 1;
    private boolean hasMore = false;
    private boolean firstLoad = true;
    private int gen = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        bindHeader(getString(R.string.issues_prs), repo);
        initList();
        btnRefresh.setOnClickListener(v -> load(true));
        action(btnA1, R.drawable.ic_add, R.string.new_issue, v -> newIssueDialog());
        listView.setOnItemClickListener((p, v, pos, id) -> {
            if (pos == shown.size()) {
                page++;
                load(false);
                return;
            }
            if (pos < 0 || pos > shown.size()) return;
            Intent i = repoIntent(IssueDetailActivity.class);
            i.putExtra("number", shown.get(pos).optLong("number"));
            startActivity(i);
        });
        buildChips();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load(true);
        firstLoad = false;
    }

    private void buildChips() {
        chipRow.removeAllViews();
        String[] states = {getString(R.string.st_open), getString(R.string.st_closed), getString(R.string.filter_all)};
        for (int i = 0; i < states.length; i++) {
            final int idx = i;
            android.widget.TextView c = Ui.chip(this, states[i], i == stateIdx);
            c.setOnClickListener(v -> {
                stateIdx = idx;
                buildChips();
                load(true);
            });
            chipRow.addView(c);
        }
        String[] types = {getString(R.string.type_all), getString(R.string.type_issues), getString(R.string.type_prs)};
        for (int i = 0; i < types.length; i++) {
            final int idx = i;
            android.widget.TextView c = Ui.chip(this, types[i], i == typeIdx);
            c.setOnClickListener(v -> {
                typeIdx = idx;
                buildChips();
                render();
            });
            chipRow.addView(c);
        }
        filterScroll.setVisibility(android.view.View.VISIBLE);
    }

    private void load(final boolean reset) {
        if (reset) page = 1;
        final int pg = page;
        final String st = STATES[stateIdx];
        final int myGen = ++gen;
        loading(true);
        io.execute(() -> {
            try {
                JSONArray arr = api.listIssues(owner, repo, st, pg, PER_PAGE);
                final List<JSONObject> tmp = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) tmp.add(arr.getJSONObject(i));
                post(() -> {
                    if (myGen != gen) return;
                    loading(false);
                    showStatus(null);
                    if (pg == 1) all.clear();
                    all.addAll(tmp);
                    hasMore = tmp.size() >= PER_PAGE;
                    render();
                });
            } catch (Exception e) {
                if (myGen == gen) fail(e);
            }
        });
    }

    private void render() {
        shown.clear();
        List<Row> rows = new ArrayList<>();
        for (JSONObject o : all) {
            boolean pr = o.has("pull_request");
            if (typeIdx == 1 && pr) continue;
            if (typeIdx == 2 && !pr) continue;
            shown.add(o);
            boolean open = "open".equals(o.optString("state"));
            JSONObject u = o.optJSONObject("user");
            StringBuilder sub = new StringBuilder("#").append(o.optInt("number"));
            if (u != null) sub.append(" · ").append(u.optString("login"));
            int cm = o.optInt("comments");
            if (cm > 0) sub.append(" · ").append(getString(R.string.comments_count, cm));
            String ago = Fmt.ago(Fmt.s(o, "created_at"));
            if (!ago.isEmpty()) sub.append(" · ").append(ago);
            int color = Ui.color(this, open ? R.color.ok : R.color.text_secondary);
            Row r = new Row(pr ? R.drawable.ic_pr : R.drawable.ic_issue, false, o.optString("title"),
                    sub.toString(), false, true).tint(color);
            if (!open) r.badge(getString(R.string.st_closed), color);
            rows.add(r);
        }
        if (hasMore) rows.add(new Row(R.drawable.ic_refresh, false, getString(R.string.load_more), null, false, false));
        adapter.setRows(rows);
        showEmpty(shown.isEmpty(), R.string.nothing_here);
    }

    private void newIssueDialog() {
        LinearLayout box = Ui.box(this);
        final EditText title = Ui.edit(this, getString(R.string.issue_title), null);
        final EditText body = Ui.editMulti(this, getString(R.string.issue_body), null, 4);
        box.addView(title);
        box.addView(body);
        new Dlg(this)
                .setTitle(R.string.new_issue)
                .setView(box)
                .setPositiveButton(R.string.create, (d, w) -> {
                    final String t = title.getText().toString().trim();
                    if (t.isEmpty()) { Dlg.stay(d); return; }
                    final String b = body.getText().toString();
                    bg(() -> {
                        api.createIssue(owner, repo, t, b);
                        post(() -> load(true));
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}

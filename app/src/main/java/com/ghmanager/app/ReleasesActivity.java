package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Releases of a repository: list and create (with auto-generated notes). */
public class ReleasesActivity extends BaseRepoActivity {
    private static final int PER_PAGE = 30;
    private final List<JSONObject> items = new ArrayList<>();
    private int page = 1;
    private boolean hasMore = false;
    private boolean firstLoad = true;
    private int gen = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        bindHeader(getString(R.string.releases), repo);
        initList();
        btnRefresh.setOnClickListener(v -> load(true));
        action(btnA1, R.drawable.ic_add, R.string.new_release, v -> createDialog());
        listView.setOnItemClickListener((p, v, pos, id) -> {
            if (pos == items.size()) {
                page++;
                load(false);
                return;
            }
            if (pos < 0 || pos > items.size()) return;
            Intent i = repoIntent(ReleaseDetailActivity.class);
            i.putExtra("releaseId", items.get(pos).optLong("id"));
            startActivity(i);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load(true);
        firstLoad = false;
    }

    private void load(final boolean reset) {
        if (reset) page = 1;
        final int pg = page;
        final int myGen = ++gen;
        loading(firstLoad || !reset);
        io.execute(() -> {
            try {
                JSONArray arr = api.listReleases(owner, repo, pg, PER_PAGE);
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
        for (JSONObject o : items) {
            String name = Fmt.s(o, "name");
            String tag = Fmt.s(o, "tag_name");
            StringBuilder sub = new StringBuilder(tag);
            String target = Fmt.s(o, "target_commitish");
            if (!target.isEmpty() && !target.equals(tag)) sub.append(" · ").append(target);
            String when = Fmt.ago(Fmt.s(o, o.isNull("published_at") ? "created_at" : "published_at"));
            if (!when.isEmpty()) sub.append(" · ").append(when);
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null && assets.length() > 0) {
                sub.append(" · ").append(getString(R.string.files_count, assets.length()));
            }
            Row r = new Row(R.drawable.ic_tag, true, name.isEmpty() ? tag : name, sub.toString(), false, true);
            if (o.optBoolean("draft")) r.badge(getString(R.string.draft), Ui.color(this, R.color.warn));
            else if (o.optBoolean("prerelease")) r.badge(getString(R.string.prerelease), Ui.color(this, R.color.info));
            rows.add(r);
        }
        if (hasMore) rows.add(new Row(R.drawable.ic_refresh, false, getString(R.string.load_more), null, false, false));
        adapter.setRows(rows);
        showEmpty(items.isEmpty(), R.string.no_releases);
    }

    private void createDialog() {
        LinearLayout box = Ui.box(this);
        final EditText tag = Ui.edit(this, getString(R.string.tag_name), null);
        final EditText target = Ui.edit(this, getString(R.string.target_branch), branch);
        final EditText title = Ui.edit(this, getString(R.string.release_title), null);
        final EditText notes = Ui.editMulti(this, getString(R.string.release_notes), null, 4);
        final CheckBox draft = Ui.check(this, R.string.draft, false);
        final CheckBox pre = Ui.check(this, R.string.prerelease, false);
        Button gen = Ui.button(this, R.string.generate_notes, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 10);
        gen.setLayoutParams(lp);
        gen.setOnClickListener(v -> {
            final String t = tag.getText().toString().trim();
            if (t.isEmpty()) {
                toast(R.string.tag_required);
                return;
            }
            final String tg = target.getText().toString().trim();
            bg(() -> {
                final JSONObject g = api.generateNotes(owner, repo, t, tg);
                post(() -> {
                    notes.setText(g.optString("body"));
                    if (title.getText().toString().trim().isEmpty()) title.setText(g.optString("name"));
                });
            });
        });
        box.addView(tag);
        box.addView(target);
        box.addView(title);
        box.addView(notes);
        box.addView(gen);
        box.addView(draft);
        box.addView(pre);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle(R.string.new_release)
                .setView(sv)
                .setPositiveButton(R.string.create, (d, w) -> {
                    final String t = tag.getText().toString().trim();
                    if (t.isEmpty()) {
                        toast(R.string.tag_required);
                        return;
                    }
                    final String tg = target.getText().toString().trim();
                    final String ti = title.getText().toString().trim();
                    final String no = notes.getText().toString();
                    final boolean isDraft = draft.isChecked();
                    final boolean isPre = pre.isChecked();
                    bg(() -> {
                        JSONObject b = new JSONObject();
                        b.put("tag_name", t);
                        if (!tg.isEmpty()) b.put("target_commitish", tg);
                        if (!ti.isEmpty()) b.put("name", ti);
                        b.put("body", no);
                        b.put("draft", isDraft);
                        b.put("prerelease", isPre);
                        api.createRelease(owner, repo, b);
                        post(() -> {
                            toast(R.string.release_created);
                            load(true);
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}

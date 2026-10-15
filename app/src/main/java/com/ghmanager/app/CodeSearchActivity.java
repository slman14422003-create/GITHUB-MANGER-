package com.ghmanager.app;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Searches the code of one repository (default branch) and opens a hit in the editor. */
public class CodeSearchActivity extends BaseRepoActivity {
    private static final int PER_PAGE = 30;
    private final List<JSONObject> hits = new ArrayList<>();
    private EditText query;
    private String lastQuery = "";
    private int page = 1;
    private boolean hasMore = false;
    private int gen = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        bindHeader(getString(R.string.cs_title), repo);
        initList();
        btnRefresh.setOnClickListener(v -> search(true));

        query = Ui.edit(this, getString(R.string.cs_hint), null);
        query.setSingleLine(true);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        query.setCompoundDrawablePadding(Ui.dp(this, 12));
        query.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.text_hint)));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) query.getLayoutParams();
        lp.setMargins(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4));
        query.setOnEditorActionListener((v, id, ev) -> {
            search(true);
            return true;
        });
        ViewGroup root = (ViewGroup) ((View) listView.getParent()).getParent();
        root.addView(query, 1);

        listView.setOnItemClickListener((p, v, pos, id) -> {
            if (pos == hits.size()) {
                page++;
                search(false);
                return;
            }
            if (pos < 0 || pos > hits.size()) return;
            startActivity(repoIntent(EditorActivity.class).putExtra("path", hits.get(pos).optString("path")));
        });
        showEmpty(true, R.string.cs_empty);
    }

    private void search(final boolean reset) {
        String q = reset ? query.getText().toString().trim() : lastQuery;
        if (q.isEmpty()) return;
        if (reset) {
            page = 1;
            lastQuery = q;
        }
        final int pg = page;
        final String fq = q;
        final int myGen = ++gen;
        loading(true);
        io.execute(() -> {
            try {
                JSONObject res = api.searchCode(owner, repo, fq, pg, PER_PAGE);
                JSONArray items = res.optJSONArray("items");
                final List<JSONObject> tmp = new ArrayList<>();
                if (items != null) for (int i = 0; i < items.length(); i++) tmp.add(items.getJSONObject(i));
                final int total = res.optInt("total_count");
                post(() -> {
                    if (myGen != gen) return;
                    loading(false);
                    showStatus(null);
                    if (pg == 1) hits.clear();
                    hits.addAll(tmp);
                    hasMore = tmp.size() >= PER_PAGE && hits.size() < total;
                    render(total);
                });
            } catch (Exception e) {
                if (myGen == gen) fail(e);
            }
        });
    }

    private void render(int total) {
        List<Row> rows = new ArrayList<>();
        for (JSONObject o : hits) {
            String path = o.optString("path");
            rows.add(new Row(R.drawable.ic_code, false, o.optString("name"), path, false, true));
        }
        if (hasMore) rows.add(new Row(R.drawable.ic_refresh, false, getString(R.string.load_more), null, false, false));
        adapter.setRows(rows);
        if (hits.isEmpty()) {
            showEmpty(true, R.string.cs_none);
        } else {
            showEmpty(false, R.string.cs_none);
            showStatus(getString(R.string.cs_total, total));
        }
    }
}

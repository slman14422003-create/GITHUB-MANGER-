package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Lists old repositories by inactivity, lets the user pick and then archive or delete them as one job. */
public class CleanupActivity extends BaseRepoActivity {
    private static final long DAY = 24L * 3600 * 1000;

    private final List<JSONObject> all = new ArrayList<>();
    private final List<JSONObject> shown = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final Set<String> decided = new HashSet<>();
    private int ageIdx = 1;
    private int customDays = 200;
    private boolean onlyArchived;
    private boolean onlyEmpty;
    private boolean onlyForks;
    private Button btnDelete;
    private Button btnArchive;
    private boolean loadedOnce;

    private final Transfers.Listener finishListener = j -> {
        if ("cleanup".equals(j.tag)) {
            CleanupJobs.showPending(this);
            load();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        bindHeader(getString(R.string.cl_title), null);
        initList();
        btnRefresh.setOnClickListener(v -> load());
        action(btnA1, R.drawable.ic_check_circle, R.string.cl_select_toggle, v -> toggleAll());
        addBottomBar();
        listView.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= shown.size()) return;
            String key = key(shown.get(pos));
            if (!selected.remove(key)) selected.add(key);
            decided.add(key);
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            render();
        });
        listView.setOnItemLongClickListener((AdapterView<?> p, View v, int pos, long id) -> {
            if (pos < 0 || pos >= shown.size()) return false;
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            rowMenu(shown.get(pos));
            return true;
        });
        buildChips();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Transfers.addListener(finishListener);
        if (CleanupJobs.showPending(this)) load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Transfers.removeListener(finishListener);
    }

    private static String key(JSONObject o) {
        return o.optString("full_name", o.optString("name"));
    }

    private void addBottomBar() {
        FrameLayout content = findViewById(android.R.id.content);
        View root = content.getChildAt(0);
        if (!(root instanceof LinearLayout)) return;
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 12));
        btnArchive = Ui.button(this, R.string.cl_archive_btn, false);
        btnDelete = Ui.button(this, R.string.cl_delete_btn, true);
        btnDelete.setBackgroundResource(R.drawable.btn_danger);
        btnDelete.setTextColor(Ui.color(this, R.color.on_accent));
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        a.setMarginEnd(Ui.dp(this, 8));
        LinearLayout.LayoutParams d = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(btnArchive, a);
        bar.addView(btnDelete, d);
        ((LinearLayout) root).addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        btnArchive.setOnClickListener(v -> CleanupJobs.confirmArchive(this, targets(), this::afterStart));
        btnDelete.setOnClickListener(v -> CleanupJobs.confirmDelete(this, targets(), this::afterStart));
        refreshBar();
    }

    private void afterStart() {
        selected.clear();
        decided.clear();
        render();
    }

    private List<String[]> targets() {
        List<String[]> out = new ArrayList<>();
        for (JSONObject o : shown) {
            if (!selected.contains(key(o))) continue;
            JSONObject own = o.optJSONObject("owner");
            out.add(new String[]{own != null ? own.optString("login") : "", o.optString("name")});
        }
        return out;
    }

    private void refreshBar() {
        int n = 0;
        for (JSONObject o : shown) if (selected.contains(key(o))) n++;
        if (btnDelete != null) {
            btnDelete.setText(getString(R.string.cl_delete_btn, n));
            btnDelete.setEnabled(n > 0);
            btnDelete.setAlpha(n > 0 ? 1f : 0.45f);
        }
        if (btnArchive != null) {
            btnArchive.setEnabled(n > 0);
            btnArchive.setAlpha(n > 0 ? 1f : 0.45f);
        }
        setSubtitle(getString(R.string.cl_sub, shown.size(), n));
    }

    private void toggleAll() {
        boolean every = !shown.isEmpty();
        for (JSONObject o : shown) if (!selected.contains(key(o))) every = false;
        for (JSONObject o : shown) {
            if (every) selected.remove(key(o));
            else selected.add(key(o));
            decided.add(key(o));
        }
        render();
    }

    private void buildChips() {
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.cl_age_3m));
        labels.add(getString(R.string.cl_age_6m));
        labels.add(getString(R.string.cl_age_1y));
        labels.add(ageIdx == 3 ? getString(R.string.cl_age_custom_n, customDays) : getString(R.string.cl_age_custom));
        labels.add(getString(R.string.cl_f_archived));
        labels.add(getString(R.string.cl_f_empty));
        labels.add(getString(R.string.cl_f_forks));
        chipRow.removeAllViews();
        for (int i = 0; i < labels.size(); i++) {
            final int idx = i;
            boolean on = i < 4 ? i == ageIdx : i == 4 ? onlyArchived : i == 5 ? onlyEmpty : onlyForks;
            TextView c = Ui.chip(this, labels.get(i), on);
            c.setOnClickListener(v -> onChip(idx));
            chipRow.addView(c);
        }
        filterScroll.setVisibility(View.VISIBLE);
    }

    private void onChip(int idx) {
        if (idx == 3) {
            askCustomDays();
            return;
        }
        if (idx < 3) ageIdx = idx;
        else if (idx == 4) onlyArchived = !onlyArchived;
        else if (idx == 5) onlyEmpty = !onlyEmpty;
        else onlyForks = !onlyForks;
        decided.clear();
        buildChips();
        render();
    }

    private void askCustomDays() {
        LinearLayout box = Ui.box(this);
        final EditText days = Ui.edit(this, getString(R.string.cl_custom_hint), String.valueOf(customDays));
        days.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        box.addView(Ui.field(this, getString(R.string.cl_custom_title), days));
        new Dlg(this)
                .setTitle(R.string.cl_custom_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    int v;
                    try {
                        v = Integer.parseInt(days.getText().toString().trim());
                    } catch (Exception e) {
                        v = 0;
                    }
                    if (v <= 0) {
                        Dlg.stay(d);
                        return;
                    }
                    customDays = v;
                    ageIdx = 3;
                    decided.clear();
                    buildChips();
                    render();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private long thresholdDays() {
        switch (ageIdx) {
            case 0:
                return 90;
            case 1:
                return 180;
            case 2:
                return 365;
            default:
                return customDays;
        }
    }

    private static long lastActivity(JSONObject o) {
        long t = Fmt.parse(o.optString("pushed_at", ""));
        if (t == 0) t = Fmt.parse(o.optString("updated_at", ""));
        if (t == 0) t = Fmt.parse(o.optString("created_at", ""));
        return t;
    }

    private boolean canAdmin(JSONObject o) {
        JSONObject perm = o.optJSONObject("permissions");
        return perm == null || perm.optBoolean("admin", false);
    }

    private boolean isCandidate(JSONObject o) {
        if (!canAdmin(o)) return false;
        if (Store.isProtected(this, key(o))) return false;
        long last = lastActivity(o);
        long limit = System.currentTimeMillis() - thresholdDays() * DAY;
        if (last != 0 && last > limit) return false;
        if (onlyArchived && !o.optBoolean("archived")) return false;
        if (onlyEmpty && o.optLong("size") > 0) return false;
        if (onlyForks && !o.optBoolean("fork")) return false;
        return true;
    }

    private static boolean riskyToDelete(JSONObject o) {
        return o.optInt("stargazers_count") > 0 || o.optInt("forks_count") > 0 || o.optInt("open_issues_count") > 0;
    }

    private void rebuildShown() {
        shown.clear();
        for (JSONObject o : all) if (isCandidate(o)) shown.add(o);
        java.util.Collections.sort(shown, (x, y) -> Long.compare(lastActivity(x), lastActivity(y)));
        Set<String> present = new HashSet<>();
        for (JSONObject o : shown) {
            String k = key(o);
            present.add(k);
            if (!decided.contains(k) && !riskyToDelete(o)) selected.add(k);
        }
        selected.retainAll(present);
    }

    private void render() {
        rebuildShown();
        List<Row> rows = new ArrayList<>();
        for (JSONObject o : shown) {
            String k = key(o);
            long last = lastActivity(o);
            StringBuilder sub = new StringBuilder();
            sub.append(last == 0 ? getString(R.string.cl_never_active)
                    : getString(R.string.cl_last, Fmt.ago(o.optString("pushed_at", o.optString("updated_at")))));
            sub.append(" · ").append(Fmt.size(o.optLong("size") * 1024));
            int stars = o.optInt("stargazers_count");
            sub.append(" · ★").append(stars);
            int forks = o.optInt("forks_count");
            if (forks > 0) sub.append(" · ").append(getString(R.string.forks)).append(' ').append(forks);
            int issues = o.optInt("open_issues_count");
            if (issues > 0) sub.append(" · ").append(issues).append(" issues");
            Row row = new Row(R.drawable.ic_repo, true, o.optString("name"), sub.toString(), false, false);
            boolean priv = o.optBoolean("private");
            row.badge(getString(priv ? R.string.private_label : R.string.public_label),
                    Ui.color(this, priv ? R.color.warn : R.color.info));
            row.select(true, selected.contains(k));
            rows.add(row);
        }
        adapter.setRows(rows);
        showEmpty(loadedOnce && rows.isEmpty(), R.string.cl_none);
        refreshBar();
    }

    private void rowMenu(final JSONObject o) {
        final String k = key(o);
        final JSONObject own = o.optJSONObject("owner");
        final String[] items = {getString(R.string.cl_protect), getString(R.string.cl_open)};
        choose(o.optString("name"), items, (d, which) -> {
            if (which == 0) {
                Store.setProtected(this, k, true);
                selected.remove(k);
                toast(getString(R.string.cl_protected_done, o.optString("name")));
                render();
            } else {
                Intent i = new Intent(this, RepoHomeActivity.class);
                i.putExtra("owner", own != null ? own.optString("login") : "");
                i.putExtra("repo", o.optString("name"));
                i.putExtra("branch", o.optString("default_branch", "main"));
                startActivity(i);
            }
        });
    }

    private void fill(JSONArray arr) {
        all.clear();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) all.add(o);
        }
        loadedOnce = true;
        render();
    }

    private void load() {
        loading(true);
        showStatus(null);
        io.execute(() -> {
            if (all.isEmpty()) {
                final JSONArray cached = RepoCache.load(this);
                if (cached != null) post(() -> {
                    if (all.isEmpty()) fill(cached);
                });
            }
            try {
                final JSONArray arr = api.listRepos();
                RepoCache.save(this, arr);
                post(() -> {
                    loading(false);
                    fill(arr);
                });
            } catch (Exception e) {
                post(() -> loading(false));
                showError(e, this::load);
            }
        });
    }
}

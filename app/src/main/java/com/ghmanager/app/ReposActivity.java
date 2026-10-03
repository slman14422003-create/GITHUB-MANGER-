package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReposActivity extends AppCompatActivity {
    private GitHubApi api;
    private final List<JSONObject> allRepos = new ArrayList<>();
    private final List<JSONObject> shown = new ArrayList<>();
    private RowAdapter adapter;
    private TextView status;
    private TextView count;
    private LinearLayout chipRow;
    private int filter = 0;
    private String query = "";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_repos);
        api = new GitHubApi(Store.getToken(this));
        status = findViewById(R.id.status);
        count = findViewById(R.id.count);
        chipRow = findViewById(R.id.chipRow);
        ListView list = findViewById(R.id.list);
        adapter = new RowAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= shown.size()) return;
            JSONObject o = shown.get(pos);
            JSONObject own = o.optJSONObject("owner");
            Intent i = new Intent(ReposActivity.this, RepoHomeActivity.class);
            i.putExtra("owner", own != null ? own.optString("login") : "");
            i.putExtra("repo", o.optString("name"));
            i.putExtra("branch", o.optString("default_branch", "main"));
            startActivity(i);
        });

        findViewById(R.id.btnNew).setOnClickListener(v -> newRepoDialog());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> load());
        findViewById(R.id.btnLogout).setOnClickListener(v -> logout());
        findViewById(R.id.btnFiles).setOnClickListener(v ->
                startActivity(new Intent(ReposActivity.this, FileManagerActivity.class)));
        findViewById(R.id.btnPerms).setOnClickListener(v ->
                startActivity(new Intent(ReposActivity.this, PermissionsActivity.class)));

        ((EditText) findViewById(R.id.search)).addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                render();
            }
        });
        buildChips();
        load();
    }

    private void buildChips() {
        final String[] labels = {
                getString(R.string.filter_all), getString(R.string.filter_private),
                getString(R.string.filter_public), getString(R.string.filter_forks),
                getString(R.string.filter_archived)};
        chipRow.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView c = Ui.chip(this, labels[i], i == filter);
            c.setOnClickListener(v -> {
                filter = idx;
                buildChips();
                render();
            });
            chipRow.addView(c);
        }
    }

    private void logout() {
        Store.clear(this);
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    private boolean matches(JSONObject o) {
        switch (filter) {
            case 1:
                if (!o.optBoolean("private")) return false;
                break;
            case 2:
                if (o.optBoolean("private")) return false;
                break;
            case 3:
                if (!o.optBoolean("fork")) return false;
                break;
            case 4:
                if (!o.optBoolean("archived")) return false;
                break;
            default:
                break;
        }
        if (!query.isEmpty()) {
            String name = o.optString("full_name", o.optString("name")).toLowerCase(Locale.ROOT);
            String desc = o.optString("description", "").toLowerCase(Locale.ROOT);
            if (!name.contains(query) && !desc.contains(query)) return false;
        }
        return true;
    }

    private void render() {
        shown.clear();
        List<Row> rows = new ArrayList<>();
        for (JSONObject o : allRepos) {
            if (!matches(o)) continue;
            shown.add(o);
            JSONObject own = o.optJSONObject("owner");
            StringBuilder sub = new StringBuilder(own != null ? own.optString("login") : "");
            String lang = o.optString("language", "");
            if (!lang.isEmpty() && !"null".equals(lang)) sub.append(" · ").append(lang);
            int stars = o.optInt("stargazers_count");
            if (stars > 0) sub.append(" · ★").append(stars);
            String ago = Fmt.ago(o.optString("pushed_at", o.optString("updated_at")));
            if (!ago.isEmpty()) sub.append(" · ").append(ago);
            Row row = new Row(R.drawable.ic_repo, true, o.optString("name"), sub.toString(),
                    o.optBoolean("private"), true);
            if (o.optBoolean("archived")) row.badge(getString(R.string.archived), Ui.color(this, R.color.warn));
            else if (o.optBoolean("fork")) row.badge("Fork", Ui.color(this, R.color.info));
            rows.add(row);
        }
        adapter.setRows(rows);
        count.setText(getString(R.string.repos_count, rows.size()));
    }

    private void load() {
        status.setText(R.string.working);
        status.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                JSONArray arr = api.listRepos();
                final List<JSONObject> tmp = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) tmp.add(arr.getJSONObject(i));
                ui.post(() -> {
                    allRepos.clear();
                    allRepos.addAll(tmp);
                    render();
                    status.setVisibility(View.GONE);
                });
            } catch (GitHubApi.ApiException e) {
                if (e.code == 401) {
                    ui.post(this::logout);
                } else {
                    ui.post(() -> status.setText(e.getMessage()));
                }
            } catch (Exception e) {
                ui.post(() -> status.setText(String.valueOf(e.getMessage())));
            }
        });
    }

    private void newRepoDialog() {
        LinearLayout box = Ui.box(this);
        final EditText name = Ui.edit(this, getString(R.string.repo_name), null);
        final CheckBox priv = Ui.check(this, R.string.private_repo, false);
        box.addView(name);
        box.addView(priv);
        new AlertDialog.Builder(this)
                .setTitle(R.string.new_repo)
                .setView(box)
                .setPositiveButton(R.string.create, (d, w) -> {
                    final String n = name.getText().toString().trim();
                    if (n.isEmpty()) return;
                    final boolean isPriv = priv.isChecked();
                    status.setText(R.string.working);
                    status.setVisibility(View.VISIBLE);
                    io.execute(() -> {
                        try {
                            api.createRepo(n, isPriv);
                            ui.post(this::load);
                        } catch (Exception e) {
                            ui.post(() -> status.setText(String.valueOf(e.getMessage())));
                        }
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}

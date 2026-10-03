package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReposActivity extends AppCompatActivity {
    private GitHubApi api;
    private final List<JSONObject> repos = new ArrayList<>();
    private RowAdapter adapter;
    private TextView status;
    private TextView count;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_repos);
        api = new GitHubApi(Store.getToken(this));
        status = findViewById(R.id.status);
        count = findViewById(R.id.count);
        ListView list = findViewById(R.id.list);
        adapter = new RowAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            JSONObject o = repos.get(pos);
            JSONObject own = o.optJSONObject("owner");
            Intent i = new Intent(ReposActivity.this, BrowserActivity.class);
            i.putExtra("owner", own != null ? own.optString("login") : "");
            i.putExtra("repo", o.optString("name"));
            i.putExtra("branch", o.optString("default_branch", "main"));
            startActivity(i);
        });

        findViewById(R.id.btnNew).setOnClickListener(v -> newRepoDialog());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> load());
        findViewById(R.id.btnLogout).setOnClickListener(v -> logout());
        load();
    }

    private void logout() {
        Store.clear(this);
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    private void load() {
        status.setText(R.string.working);
        status.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                JSONArray arr = api.listRepos();
                final List<JSONObject> tmp = new ArrayList<>();
                final List<Row> rows = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    tmp.add(o);
                    JSONObject own = o.optJSONObject("owner");
                    rows.add(new Row(R.drawable.ic_repo, true, o.optString("name"),
                            own != null ? own.optString("login") : "",
                            o.optBoolean("private"), true));
                }
                ui.post(() -> {
                    repos.clear();
                    repos.addAll(tmp);
                    adapter.setRows(rows);
                    count.setText(getString(R.string.repos_count, rows.size()));
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

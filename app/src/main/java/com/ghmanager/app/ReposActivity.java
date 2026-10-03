package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
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
    private ArrayAdapter<String> adapter;
    private TextView status;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_repos);
        setTitle(R.string.repos);
        api = new GitHubApi(Store.getToken(this));
        status = findViewById(R.id.status);
        ListView list = findViewById(R.id.list);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            JSONObject o = repos.get(pos);
            Intent i = new Intent(ReposActivity.this, BrowserActivity.class);
            i.putExtra("owner", o.optJSONObject("owner") != null ? o.optJSONObject("owner").optString("login") : "");
            i.putExtra("repo", o.optString("name"));
            i.putExtra("branch", o.optString("default_branch", "main"));
            startActivity(i);
        });
        load();
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
                    repos.clear();
                    repos.addAll(tmp);
                    adapter.clear();
                    for (JSONObject o : tmp) {
                        adapter.add((o.optBoolean("private") ? "🔒 " : "") + o.optString("full_name"));
                    }
                    status.setVisibility(View.GONE);
                });
            } catch (Exception e) {
                ui.post(() -> status.setText(e.getMessage()));
            }
        });
    }

    private void newRepoDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText name = new EditText(this);
        name.setHint(R.string.repo_name);
        final CheckBox priv = new CheckBox(this);
        priv.setText(R.string.private_repo);
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
                            ui.post(() -> status.setText(e.getMessage()));
                        }
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.new_repo).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, 2, 1, R.string.refresh).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(0, 3, 2, R.string.logout).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                newRepoDialog();
                return true;
            case 2:
                load();
                return true;
            case 3:
                Store.clear(this);
                startActivity(new Intent(this, LoginActivity.class));
                finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}

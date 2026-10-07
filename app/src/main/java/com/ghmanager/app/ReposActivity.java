package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
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
    // ---- one shared guard: stops a fast double tap from pushing the same screen twice
    @Override
    public void startActivity(android.content.Intent intent) {
        if (Tap.ok("nav")) super.startActivity(intent);
    }

    @Override
    public void startActivity(android.content.Intent intent, android.os.Bundle options) {
        if (Tap.ok("nav")) super.startActivity(intent, options);
    }

    @Override
    public void startActivityForResult(android.content.Intent intent, int requestCode) {
        if (Tap.ok("nav")) super.startActivityForResult(intent, requestCode);
    }

    private GitHubApi api;
    private final List<JSONObject> allRepos = new ArrayList<>();
    private final List<JSONObject> shown = new ArrayList<>();
    private RowAdapter adapter;
    private TextView status;
    private TextView count;
    private LinearLayout chipRow;
    private int filter = 0;
    private String query = "";
    private ImageView accountBtn;
    private ListView listView;
    private View skeleton;
    private View refreshBtn;
    private GhStatusCard statusCard;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_repos);
        BottomNav.attach(this, BottomNav.REPOS);
        api = new GitHubApi(Store.getToken(this));
        status = findViewById(R.id.status);
        count = findViewById(R.id.count);
        chipRow = findViewById(R.id.chipRow);
        ListView list = findViewById(R.id.list);
        listView = list;
        refreshBtn = findViewById(R.id.btnRefresh);
        // GitHub server status: a button in the top bar (its dot shows the state), details open on tap
        statusCard = new GhStatusCard(this, findViewById(R.id.btnStatus), findViewById(R.id.statusDot));
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

        for (int id : new int[]{R.id.btnNew, R.id.btnRefresh, R.id.btnAccount, R.id.btnStatus}) {
            Ui.press(this, findViewById(id));
        }
        findViewById(R.id.btnNew).setOnClickListener(v -> newRepoDialog());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> {
            load();
            if (statusCard != null) statusCard.refresh();
        });
        accountBtn = findViewById(R.id.btnAccount);
        accountBtn.setOnClickListener(v -> AccountSheet.show(this));
        refreshAccountIcon();
        fillProfileIfMissing();
        ((EditText) findViewById(R.id.search)).setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                v.clearFocus();
                return true;
            }
            return false;
        });
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
        Updater.autoCheck(this, io, ui);
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

    @Override
    protected void onPause() {
        super.onPause();
        if (statusCard != null) statusCard.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusCard != null) statusCard.start();
        if (accountBtn != null) refreshAccountIcon();
    }

    private void refreshAccountIcon() {
        Accounts.Acc a = Accounts.active(this);
        Avatar.load(accountBtn, a == null ? "" : a.avatar, 10);
    }

    /** Accounts migrated from an older version have no profile yet: fetch it once for the icon. */
    private void fillProfileIfMissing() {
        final Accounts.Acc a = Accounts.active(this);
        if (a == null || !a.avatar.isEmpty()) return;
        final String id = a.id;
        io.execute(() -> {
            try {
                final JSONObject u = api.getUser();
                ui.post(() -> {
                    if (isFinishing()) return;
                    Accounts.updateProfile(this, id, u);
                    refreshAccountIcon();
                });
            } catch (Exception ignored) {
            }
        });
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

    private void showSkeleton(boolean on) {
        ViewGroup parent = (ViewGroup) listView.getParent();
        if (on) {
            if (skeleton == null) {
                skeleton = Skeleton.build(this, 6);
                parent.addView(skeleton, parent.indexOfChild(listView));
            }
            skeleton.setVisibility(View.VISIBLE);
            Skeleton.pulse(skeleton, true);
            listView.setVisibility(View.GONE);
        } else if (skeleton != null) {
            Skeleton.pulse(skeleton, false);
            skeleton.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
        }
    }

    private void fill(JSONArray arr) throws Exception {
        final List<JSONObject> tmp = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) tmp.add(arr.getJSONObject(i));
        allRepos.clear();
        allRepos.addAll(tmp);
        render();
    }

    private void load() {
        final boolean hadData = !allRepos.isEmpty();
        status.setVisibility(View.GONE);
        Ui.spin(refreshBtn, true);
        if (!hadData) showSkeleton(true);
        io.execute(() -> {
            // 1) show the saved list at once (first visit of this session only)
            if (!hadData) {
                final JSONArray cached = RepoCache.load(this);
                if (cached != null) {
                    ui.post(() -> {
                        if (isFinishing() || !allRepos.isEmpty()) return;
                        try {
                            fill(cached);
                            showSkeleton(false);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
            // 2) bring the fresh list; unchanged answers come back instantly (ETag)
            try {
                final JSONArray arr = api.listRepos();
                RepoCache.save(this, arr);
                ui.post(() -> {
                    if (isFinishing()) return;
                    try {
                        fill(arr);
                    } catch (Exception ignored) {
                    }
                    showSkeleton(false);
                    Ui.spin(refreshBtn, false);
                    status.setVisibility(View.GONE);
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    if (isFinishing()) return;
                    Ui.spin(refreshBtn, false);
                    showSkeleton(false);
                    boolean rejected = e instanceof GitHubApi.ApiException && ((GitHubApi.ApiException) e).code == 401;
                    String msg = rejected ? getString(R.string.token_rejected_msg) : String.valueOf(e.getMessage());
                    if (allRepos.isEmpty()) {
                        // keep the session on 401: the user decides whether to sign in again
                        status.setVisibility(View.VISIBLE);
                        status.setText(msg);
                    } else {
                        // the saved list stays usable; only say that the refresh failed
                        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void newRepoDialog() {
        LinearLayout box = Ui.box(this);
        final EditText name = Ui.edit(this, getString(R.string.repo_name), null);
        final CheckBox priv = Ui.check(this, R.string.private_repo, false);
        box.addView(name);
        box.addView(priv);
        new Dlg(this)
                .setTitle(R.string.new_repo)
                .setView(box)
                .setPositiveButton(R.string.create, (d, w) -> {
                    final String n = name.getText().toString().trim();
                    if (n.isEmpty()) { Dlg.stay(d); return; }
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
        if (statusCard != null) statusCard.destroy();
        io.shutdown();
    }
}

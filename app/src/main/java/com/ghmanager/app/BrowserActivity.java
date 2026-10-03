package com.ghmanager.app;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BrowserActivity extends AppCompatActivity {

    private interface Job {
        void run() throws Exception;
    }

    private static final int MAX_FILE_BYTES = 25 * 1024 * 1024;

    private GitHubApi api;
    private String owner;
    private String repo;
    private String branch;
    private String path = "";

    private final List<JSONObject> items = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView pathView;
    private Spinner spinner;
    private List<String> branches = new ArrayList<>();

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private AlertDialog progressDialog;
    private ProgressBar progressBar;
    private TextView progressText;
    private boolean busy = false;

    private ActivityResultLauncher<Uri> treeLauncher;
    private ActivityResultLauncher<String[]> filesLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);

        owner = getIntent().getStringExtra("owner");
        repo = getIntent().getStringExtra("repo");
        branch = getIntent().getStringExtra("branch");
        if (branch == null || branch.isEmpty()) branch = "main";
        setTitle(repo);
        api = new GitHubApi(Store.getToken(this));

        spinner = findViewById(R.id.branchSpinner);
        pathView = findViewById(R.id.pathView);
        ListView list = findViewById(R.id.list);
        Button btnFolder = findViewById(R.id.btnFolder);
        Button btnFiles = findViewById(R.id.btnFiles);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        list.setAdapter(adapter);

        list.setOnItemClickListener((p, v, pos, id) -> {
            JSONObject o = items.get(pos);
            if ("dir".equals(o.optString("type"))) {
                path = o.optString("path");
                load();
            } else {
                Toast.makeText(this, o.optString("name") + " (" + humanSize(o.optLong("size")) + ")",
                        Toast.LENGTH_SHORT).show();
            }
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            confirmDelete(items.get(pos));
            return true;
        });
        pathView.setOnClickListener(v -> goUp());

        treeLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
            if (uri != null) askUploadOptions(uri, null);
        });
        filesLauncher = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
            if (uris != null && !uris.isEmpty()) askUploadOptions(null, uris);
        });
        btnFolder.setOnClickListener(v -> treeLauncher.launch(null));
        btnFiles.setOnClickListener(v -> filesLauncher.launch(new String[]{"*/*"}));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!path.isEmpty()) {
                    goUp();
                } else {
                    setEnabled(false);
                    BrowserActivity.this.getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        loadBranches();
        load();
    }

    // ---------- helpers ----------

    private void bg(Job job) {
        io.execute(() -> {
            try {
                job.run();
            } catch (Exception e) {
                showError(e);
            }
        });
    }

    private void showError(Exception e) {
        final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        ui.post(() -> {
            hideProgress();
            new AlertDialog.Builder(BrowserActivity.this)
                    .setTitle(R.string.error)
                    .setMessage(msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
    }

    private static String humanSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
    }

    private static String normalize(String p) {
        String s = p.trim().replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void goUp() {
        if (path.isEmpty()) return;
        int i = path.lastIndexOf('/');
        path = i < 0 ? "" : path.substring(0, i);
        load();
    }

    // ---------- browsing ----------

    private void loadBranches() {
        io.execute(() -> {
            try {
                JSONArray arr = api.listBranches(owner, repo);
                final List<String> names = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) names.add(arr.getJSONObject(i).optString("name"));
                if (names.isEmpty()) names.add(branch);
                ui.post(() -> setupSpinner(names));
            } catch (Exception e) {
                final List<String> names = new ArrayList<>();
                names.add(branch);
                ui.post(() -> setupSpinner(names));
            }
        });
    }

    private void setupSpinner(List<String> names) {
        branches = names;
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(a);
        int idx = names.indexOf(branch);
        if (idx >= 0) spinner.setSelection(idx);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String b = branches.get(position);
                if (!b.equals(branch)) {
                    branch = b;
                    path = "";
                    load();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void load() {
        pathView.setText("/" + path);
        final String p = path;
        final String b = branch;
        io.execute(() -> {
            try {
                JSONArray arr = api.listContents(owner, repo, p, b);
                final List<JSONObject> tmp = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) tmp.add(arr.getJSONObject(i));
                Collections.sort(tmp, new Comparator<JSONObject>() {
                    @Override
                    public int compare(JSONObject x, JSONObject y) {
                        boolean dx = "dir".equals(x.optString("type"));
                        boolean dy = "dir".equals(y.optString("type"));
                        if (dx != dy) return dx ? -1 : 1;
                        return x.optString("name").compareToIgnoreCase(y.optString("name"));
                    }
                });
                ui.post(() -> show(tmp));
            } catch (GitHubApi.ApiException e) {
                if (e.code == 404) {
                    ui.post(() -> show(new ArrayList<JSONObject>()));
                } else {
                    showError(e);
                }
            } catch (Exception e) {
                showError(e);
            }
        });
    }

    private void show(List<JSONObject> list) {
        items.clear();
        items.addAll(list);
        adapter.clear();
        for (JSONObject o : list) {
            boolean dir = "dir".equals(o.optString("type"));
            adapter.add((dir ? "📁 " : "📄 ") + o.optString("name")
                    + (dir ? "" : "  (" + humanSize(o.optLong("size")) + ")"));
        }
        if (list.isEmpty()) adapter.add(getString(R.string.empty_folder));
    }

    // ---------- upload ----------

    private void askUploadOptions(final Uri tree, final List<Uri> files) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        final EditText target = new EditText(this);
        target.setHint(R.string.target_folder);
        target.setText(path);
        final EditText msg = new EditText(this);
        msg.setHint(R.string.commit_message);
        msg.setText(R.string.default_commit);
        final CheckBox includeRoot = new CheckBox(this);
        includeRoot.setText(R.string.include_root_folder);
        includeRoot.setChecked(true);

        box.addView(target);
        box.addView(msg);
        if (tree != null) box.addView(includeRoot);

        new AlertDialog.Builder(this)
                .setTitle(R.string.upload)
                .setView(box)
                .setPositiveButton(R.string.upload, (d, w) -> startUpload(tree, files,
                        normalize(target.getText().toString()),
                        msg.getText().toString().trim().isEmpty()
                                ? getString(R.string.default_commit) : msg.getText().toString().trim(),
                        includeRoot.isChecked()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static byte[] readBytes(ContentResolver cr, Uri u) throws IOException {
        InputStream is = cr.openInputStream(u);
        if (is == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_FILE_BYTES) return null;
            }
            return bos.toByteArray();
        } finally {
            is.close();
        }
    }

    private void startUpload(final Uri tree, final List<Uri> files, final String base,
                             final String message, final boolean includeRoot) {
        if (busy) return;
        busy = true;
        showProgress(getString(R.string.preparing));
        final String b = branch;

        bg(() -> {
            ContentResolver cr = getContentResolver();
            List<FileScanner.Item> all = new ArrayList<>();
            if (tree != null) {
                FileScanner.scanTree(cr, tree, includeRoot, all);
            } else {
                for (Uri u : files) {
                    String n = FileScanner.displayName(cr, u);
                    if (n == null) n = "file_" + System.currentTimeMillis();
                    all.add(new FileScanner.Item(n, u));
                }
            }
            if (all.isEmpty()) {
                ui.post(() -> {
                    hideProgress();
                    Toast.makeText(BrowserActivity.this, R.string.no_files, Toast.LENGTH_LONG).show();
                });
                return;
            }

            boolean empty = false;
            try {
                api.getBranchSha(owner, repo, b);
            } catch (GitHubApi.ApiException e) {
                if (e.code == 404 || e.code == 409) empty = true;
                else throw e;
            }

            final int total = all.size();
            List<GitHubApi.TreeEntry> entries = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            int idx = 0;
            for (FileScanner.Item it : all) {
                final int cur = idx;
                final String name = it.path;
                ui.post(() -> updateProgress(cur, total, name));
                idx++;
                byte[] data = readBytes(cr, it.uri);
                if (data == null) {
                    skipped.add(it.path);
                    continue;
                }
                String full = base.isEmpty() ? it.path : base + "/" + it.path;
                if (empty) {
                    api.putFile(owner, repo, full, data, message);
                    empty = false;
                    continue;
                }
                String sha = api.createBlob(owner, repo, data);
                entries.add(new GitHubApi.TreeEntry(full, sha));
            }

            if (!entries.isEmpty()) {
                ui.post(() -> {
                    if (progressText != null) progressText.setText(R.string.committing);
                });
                api.commitEntries(owner, repo, b, entries, message);
            }

            final int uploaded = total - skipped.size();
            final StringBuilder sb = new StringBuilder(getString(R.string.upload_summary, uploaded));
            if (!skipped.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (int i = 0; i < Math.min(skipped.size(), 10); i++) names.append(skipped.get(i)).append('\n');
                sb.append("\n\n").append(getString(R.string.skipped_summary, skipped.size(), names.toString()));
            }
            ui.post(() -> {
                hideProgress();
                new AlertDialog.Builder(BrowserActivity.this)
                        .setTitle(R.string.done)
                        .setMessage(sb.toString())
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                load();
            });
        });
    }

    // ---------- delete ----------

    private void confirmDelete(final JSONObject o) {
        final String name = o.optString("name");
        final String p = o.optString("path");
        final boolean dir = "dir".equals(o.optString("type"));
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_msg, name))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    if (busy) return;
                    busy = true;
                    showProgress(getString(R.string.working));
                    final String b = branch;
                    bg(() -> {
                        List<String> paths = new ArrayList<>();
                        if (dir) paths.addAll(api.listFilesUnder(owner, repo, b, p));
                        else paths.add(p);
                        if (!paths.isEmpty()) {
                            List<GitHubApi.TreeEntry> entries = new ArrayList<>();
                            for (String s : paths) entries.add(new GitHubApi.TreeEntry(s, null));
                            api.commitEntries(owner, repo, b, entries, "Delete " + name);
                        }
                        ui.post(() -> {
                            hideProgress();
                            load();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------- progress ----------

    private void showProgress(String text) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(8));
        progressText = new TextView(this);
        progressText.setText(text);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        box.addView(progressText);
        box.addView(progressBar);
        progressDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.working)
                .setView(box)
                .setCancelable(false)
                .create();
        progressDialog.show();
    }

    private void updateProgress(int cur, int total, String text) {
        if (progressBar == null) return;
        progressBar.setIndeterminate(false);
        progressBar.setMax(total);
        progressBar.setProgress(cur);
        progressText.setText((cur + 1) + "/" + total + "\n" + text);
    }

    private void hideProgress() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Exception ignored) {
            }
            progressDialog = null;
        }
        progressBar = null;
        progressText = null;
        busy = false;
    }

    // ---------- menu ----------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.refresh).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            load();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}

package com.ghmanager.app;

import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class BrowserActivity extends BaseRepoActivity {

    private static final int MAX_FILE_BYTES = 25 * 1024 * 1024;

    private String path = "";

    private final List<JSONObject> items = new ArrayList<>();
    private TextView pathView;
    private Spinner spinner;
    private List<String> branches = new ArrayList<>();
    private boolean needsReload = false;

    private ActivityResultLauncher<Intent> pickLauncher;
    private List<java.io.File> pickedRoots;
    private boolean pickedFolder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);

        ((TextView) findViewById(R.id.title)).setText(repo);

        spinner = findViewById(R.id.branchSpinner);
        pathView = findViewById(R.id.pathView);
        ListView list = findViewById(R.id.list);
        Button btnFolder = findViewById(R.id.btnFolder);
        Button btnFiles = findViewById(R.id.btnFiles);

        emptyView = findViewById(R.id.empty);
        adapter = new RowAdapter(this);
        list.setAdapter(adapter);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> load());
        findViewById(R.id.btnNew).setOnClickListener(v -> newMenu());
        findViewById(R.id.branchBox).setOnClickListener(v -> spinner.performClick());

        list.setOnItemClickListener((p, v, pos, id) -> {
            JSONObject o = items.get(pos);
            if ("dir".equals(o.optString("type"))) {
                path = o.optString("path");
                load();
            } else {
                openFile(o.optString("path"));
            }
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            itemMenu(items.get(pos));
            return true;
        });

        pickLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
            Intent d = res.getData();
            if (res.getResultCode() != RESULT_OK || d == null) return;
            List<String> paths = d.getStringArrayListExtra("paths");
            if (paths == null || paths.isEmpty()) return;
            List<java.io.File> roots = new ArrayList<>();
            for (String s : paths) roots.add(new java.io.File(s));
            pickedRoots = roots;
            pickedFolder = d.getBooleanExtra("folder", false);
            askUploadOptions(null, null);
        });
        // Both buttons open the app's own file manager in "pick" mode.
        btnFolder.setOnClickListener(v -> pickLauncher.launch(
                new Intent(this, FileManagerActivity.class).putExtra("pick", "folder")));
        btnFiles.setOnClickListener(v -> pickLauncher.launch(
                new Intent(this, FileManagerActivity.class).putExtra("pick", "files")));

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

    @Override
    protected void onResume() {
        super.onResume();
        if (needsReload) {
            needsReload = false;
            load();
        }
    }

    // ---------- helpers ----------

    private static String normalize(String p) {
        String s = p.trim().replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private String join(String base, String name) {
        String n = normalize(name);
        return base.isEmpty() ? n : base + "/" + n;
    }

    private void goUp() {
        if (path.isEmpty()) return;
        int i = path.lastIndexOf('/');
        path = i < 0 ? "" : path.substring(0, i);
        load();
    }

    private void openFile(String filePath) {
        Intent i = repoIntent(EditorActivity.class);
        i.putExtra("path", filePath);
        needsReload = true;
        startActivity(i);
    }

    // ---------- browsing ----------

    private void loadBranches() {
        io.execute(() -> {
            try {
                JSONArray arr = api.listBranches(owner, repo);
                final List<String> names = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) names.add(arr.getJSONObject(i).optString("name"));
                if (names.isEmpty()) names.add(branch);
                post(() -> setupSpinner(names));
            } catch (Exception e) {
                final List<String> names = new ArrayList<>();
                names.add(branch);
                post(() -> setupSpinner(names));
            }
        });
    }

    private void setupSpinner(List<String> names) {
        branches = names;
        ArrayAdapter<String> a = new ArrayAdapter<>(this, R.layout.spinner_item, names);
        a.setDropDownViewResource(R.layout.spinner_dropdown_item);
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
                post(() -> show(tmp));
            } catch (GitHubApi.ApiException e) {
                if (e.code == 404) {
                    post(() -> show(new ArrayList<JSONObject>()));
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
        List<Row> rows = new ArrayList<>();
        for (JSONObject o : list) {
            boolean dir = "dir".equals(o.optString("type"));
            rows.add(new Row(dir ? R.drawable.ic_folder : R.drawable.ic_file, dir,
                    o.optString("name"), dir ? null : Fmt.size(o.optLong("size")), false, dir));
        }
        adapter.setRows(rows);
        emptyView.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------- item actions ----------

    private void itemMenu(final JSONObject o) {
        final boolean dir = "dir".equals(o.optString("type"));
        final String name = o.optString("name");
        final String p = o.optString("path");
        List<String> labels = new ArrayList<>();
        if (!dir) labels.add(getString(R.string.view_edit));
        if (!dir) labels.add(getString(R.string.download));
        labels.add(getString(R.string.rename_move));
        labels.add(getString(R.string.copy_path));
        labels.add(getString(R.string.copy_link));
        labels.add(getString(R.string.delete));
        final String[] arr = labels.toArray(new String[0]);
        choose(name, arr, (d, which) -> {
            String chosen = arr[which];
            if (chosen.equals(getString(R.string.view_edit))) {
                openFile(p);
            } else if (chosen.equals(getString(R.string.download))) {
                saveAs(name, () -> api.openDownload(api.contentRawPath(owner, repo, p, branch),
                        "application/vnd.github.raw"));
            } else if (chosen.equals(getString(R.string.rename_move))) {
                renameDialog(o);
            } else if (chosen.equals(getString(R.string.copy_path))) {
                copy("path", p);
            } else if (chosen.equals(getString(R.string.copy_link))) {
                copy("link", webUrl((dir ? "/tree/" : "/blob/") + branch + "/" + p));
            } else {
                confirmDelete(o);
            }
        });
    }

    private void newMenu() {
        String[] opts = {getString(R.string.new_file), getString(R.string.new_folder)};
        choose(getString(R.string.new_item), opts, (d, which) -> {
            LinearLayout box = Ui.box(this);
            final EditText name = Ui.edit(this, getString(which == 0 ? R.string.file_name : R.string.folder_name), null);
            box.addView(name);
            new Dlg(this)
                    .setTitle(which == 0 ? R.string.new_file : R.string.new_folder)
                    .setView(box)
                    .setPositiveButton(R.string.create, (dd, w) -> {
                        final String n = normalize(name.getText().toString());
                        if (n.isEmpty()) return;
                        final String full = join(path, n);
                        if (which == 0) {
                            Intent i = repoIntent(EditorActivity.class);
                            i.putExtra("path", full);
                            i.putExtra("new", true);
                            needsReload = true;
                            startActivity(i);
                        } else {
                            showProgress(getString(R.string.working));
                            bg(() -> {
                                api.putFileContent(owner, repo, full + "/.gitkeep", new byte[0],
                                        "Create folder " + n, branch, null);
                                post(() -> {
                                    hideProgress();
                                    load();
                                });
                            });
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
    }

    private void renameDialog(final JSONObject o) {
        final String oldPath = o.optString("path");
        final boolean dir = "dir".equals(o.optString("type"));
        final String blobSha = o.optString("sha");
        LinearLayout box = Ui.box(this);
        final EditText target = Ui.edit(this, getString(R.string.new_path), oldPath);
        box.addView(target);
        new Dlg(this)
                .setTitle(R.string.rename_move)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    final String newPath = normalize(target.getText().toString());
                    if (newPath.isEmpty() || newPath.equals(oldPath)) return;
                    if (busy) return;
                    busy = true;
                    showProgress(getString(R.string.working));
                    final String b = branch;
                    bg(() -> {
                        List<GitHubApi.TreeEntry> entries = new ArrayList<>();
                        if (dir) {
                            for (GitHubApi.TreeEntry t : api.listBlobsUnder(owner, repo, b, oldPath)) {
                                String rel = t.path.substring(oldPath.length());
                                entries.add(new GitHubApi.TreeEntry(newPath + rel, t.sha, t.mode));
                                entries.add(new GitHubApi.TreeEntry(t.path, null));
                            }
                        } else {
                            entries.add(new GitHubApi.TreeEntry(newPath, blobSha));
                            entries.add(new GitHubApi.TreeEntry(oldPath, null));
                        }
                        if (!entries.isEmpty()) {
                            api.commitEntries(owner, repo, b, entries, "Move " + oldPath + " to " + newPath);
                        }
                        post(() -> {
                            hideProgress();
                            load();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------- upload ----------

    private void askUploadOptions(final Uri tree, final List<Uri> files) {
        LinearLayout box = Ui.box(this);
        final EditText target = Ui.edit(this, getString(R.string.target_folder), path);
        final EditText msg = Ui.edit(this, getString(R.string.commit_message), getString(R.string.default_commit));
        final CheckBox includeRoot = Ui.check(this, R.string.include_root_folder, true);

        box.addView(target);
        box.addView(msg);
        if (tree != null || (pickedRoots != null && pickedFolder)) box.addView(includeRoot);

        new Dlg(this)
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
        InputStream is;
        try {
            is = cr.openInputStream(u);
        } catch (java.io.FileNotFoundException e) {
            return null;
        }
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
        final List<java.io.File> roots = tree == null && files == null ? pickedRoots : null;
        final boolean folderPick = pickedFolder;
        pickedRoots = null;

        bg(() -> {
            ContentResolver cr = getContentResolver();
            List<FileScanner.Item> all = new ArrayList<>();
            if (tree != null) {
                FileScanner.scanTree(cr, tree, includeRoot, all);
            } else if (roots != null) {
                FileScanner.scanFiles(roots, folderPick ? includeRoot : true, all);
            } else {
                for (Uri u : files) {
                    String n = FileScanner.displayName(cr, u);
                    if (n == null) n = "file_" + System.currentTimeMillis();
                    all.add(new FileScanner.Item(n, u));
                }
            }
            if (all.isEmpty()) {
                post(() -> {
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
                post(() -> updateProgress(cur, total, name));
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
                post(() -> {
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
            post(() -> {
                hideProgress();
                Dlg.result(BrowserActivity.this, true, getString(R.string.done), sb.toString());
                load();
            });
        });
    }

    // ---------- delete ----------

    private void confirmDelete(final JSONObject o) {
        final String name = o.optString("name");
        final String p = o.optString("path");
        final boolean dir = "dir".equals(o.optString("type"));
        new Dlg(this)
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
                        post(() -> {
                            hideProgress();
                            load();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}

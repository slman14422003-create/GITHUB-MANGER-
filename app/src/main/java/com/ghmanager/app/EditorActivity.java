package com.ghmanager.app;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** View / edit / create a text file and commit it straight to the branch. */
public class EditorActivity extends BaseRepoActivity {
    private static final int MAX_BYTES = 1536 * 1024;

    private static final class Tab {
        String path;
        boolean isNew;
        String sha;
        boolean dirty;
        boolean editable = true;
        boolean loaded;
        int lockMsg;
        String text = "";
        int sel;
        int scroll;
        int gen;

        String name() {
            return path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        }
    }

    private final List<Tab> tabs = new ArrayList<>();
    private Tab cur;
    private boolean suppress = false;
    private EditText editor;
    private android.widget.ScrollView editorScroll;
    private CodeTools tools;
    private LinearLayout tabStrip;
    private HorizontalScrollView tabScroll;
    private View treePane;
    private TextView treePath;
    private RowAdapter treeAdapter;
    private RepoTree.Snapshot treeSnap;
    private String treeDir = "";
    private List<JSONObject> treeItems = new ArrayList<>();

    /** One editor screen per repository: opening another file adds a tab instead of stacking a new screen. */
    static void launch(BaseRepoActivity from, String path, boolean isNew) {
        Intent i = from.repoIntent(EditorActivity.class);
        i.putExtra("path", path);
        i.putExtra("new", isNew);
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        from.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);
        bindHeader("", "");
        wireRootNav();
        statusView = findViewById(R.id.status);
        editor = findViewById(R.id.editor);
        editorScroll = findViewById(R.id.editorScroll);
        tabStrip = findViewById(R.id.tabStrip);
        tabScroll = findViewById(R.id.tabScroll);
        tools = new CodeTools(this);
        setupTree();

        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!suppress && cur != null && !cur.dirty) {
                    cur.dirty = true;
                    rebuildTabs();
                }
            }
        });

        btnRefresh.setOnClickListener(v -> {
            if (cur == null || cur.isNew) return;
            if (cur.dirty) confirm(getString(R.string.discard_title), getString(R.string.discard_msg),
                    R.string.discard, () -> load(cur));
            else load(cur);
        });
        action(btnA1, R.drawable.ic_check, R.string.save, v -> saveDialog());
        action(btnA2, R.drawable.ic_more, R.string.more, v -> moreMenu());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (tools.findVisible()) {
                    tools.hideFind();
                    return;
                }
                confirmLeave(() -> finish());
            }
        });

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        final String o = intent.getStringExtra("owner");
        final String r = intent.getStringExtra("repo");
        String b0 = intent.getStringExtra("branch");
        final String b = b0 == null || b0.isEmpty() ? "main" : b0;
        boolean same = o != null && o.equals(owner) && r != null && r.equals(repo) && b.equals(branch);
        if (same) {
            handleIntent(intent);
        } else if (dirtyCount() > 0) {
            confirm(getString(R.string.unsaved_files_title),
                    getString(R.string.unsaved_files_msg, dirtyCount()), R.string.discard, () -> {
                        switchRepo(o, r, b);
                        handleIntent(intent);
                    });
        } else {
            switchRepo(o, r, b);
            handleIntent(intent);
        }
    }

    private void switchRepo(String o, String r, String b) {
        owner = o;
        repo = r;
        branch = b;
        tabs.clear();
        cur = null;
        treeSnap = null;
        treeDir = "";
        if (treePane.getVisibility() == View.VISIBLE) loadTree();
    }

    private boolean widePane() {
        return getResources().getConfiguration().screenWidthDp >= 600;
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        applyPane();
    }

    private void setupTree() {
        treePane = findViewById(R.id.treePane);
        treePath = findViewById(R.id.treePath);
        android.widget.ListView list = findViewById(R.id.treeList);
        treeAdapter = new RowAdapter(this);
        list.setAdapter(treeAdapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            boolean up = !treeDir.isEmpty();
            if (up && pos == 0) {
                int cut = treeDir.lastIndexOf('/');
                treeDir = cut < 0 ? "" : treeDir.substring(0, cut);
                renderTree();
                return;
            }
            int idx = up ? pos - 1 : pos;
            if (idx < 0 || idx >= treeItems.size()) return;
            JSONObject o = treeItems.get(idx);
            String path = o.optString("path");
            if ("dir".equals(o.optString("type"))) {
                treeDir = path;
                renderTree();
            } else {
                openTab(path, false);
            }
        });
        treeDir = Store.lastFolder(this, owner, repo);
        if (treeDir == null) treeDir = "";
        applyPane();
    }

    private void applyPane() {
        boolean wide = widePane();
        boolean was = treePane.getVisibility() == View.VISIBLE;
        treePane.setVisibility(wide ? View.VISIBLE : View.GONE);
        if (wide && !was) loadTree();
    }

    private void loadTree() {
        final android.content.Context app = getApplicationContext();
        final String o = owner;
        final String r = repo;
        final String b = branch;
        io.execute(() -> {
            try {
                RepoTree.Snapshot have = RepoTree.cached(app, o, r, b);
                if (have != null) {
                    final RepoTree.Snapshot first = have;
                    post(() -> {
                        treeSnap = first;
                        renderTree();
                    });
                }
                final RepoTree.Snapshot fresh = RepoTree.fetch(app, api, o, r, b, have);
                post(() -> {
                    if (!o.equals(owner) || !r.equals(repo) || !b.equals(branch)) return;
                    treeSnap = fresh;
                    renderTree();
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void renderTree() {
        if (treeSnap == null) return;
        if (treeSnap.list(treeDir).isEmpty() && !treeDir.isEmpty()) treeDir = "";
        treeItems = treeSnap.list(treeDir);
        treePath.setText("/" + treeDir);
        List<Row> rows = new ArrayList<>();
        if (!treeDir.isEmpty()) rows.add(new Row(R.drawable.ic_folder_up, true, "..", null, false, false));
        for (JSONObject o : treeItems) {
            boolean dir = "dir".equals(o.optString("type"));
            rows.add(new Row(dir ? R.drawable.ic_folder : R.drawable.ic_file, true, o.optString("name"), null,
                    false, dir));
        }
        treeAdapter.setRows(rows);
    }

    private void handleIntent(Intent i) {
        String p = i.getStringExtra("path");
        if (p == null) p = "";
        openTab(p, i.getBooleanExtra("new", false));
    }

    // ------------------------------------------------------------------ tabs

    private int dirtyCount() {
        saveCurrentState();
        int n = 0;
        for (Tab t : tabs) if (t.dirty) n++;
        return n;
    }

    @Override
    protected boolean confirmLeave(final Runnable proceed) {
        int n = dirtyCount();
        if (n == 0) {
            proceed.run();
        } else {
            confirm(getString(R.string.unsaved_files_title), getString(R.string.unsaved_files_msg, n),
                    R.string.discard, proceed);
        }
        return true;
    }

    private void saveCurrentState() {
        if (cur == null || !cur.loaded) return;
        cur.text = editor.getText().toString();
        cur.sel = editor.getSelectionStart();
        cur.scroll = editorScroll.getScrollY();
    }

    private void openTab(String path, boolean isNew) {
        for (Tab t : tabs) {
            if (t.path.equals(path)) {
                select(t);
                return;
            }
        }
        Tab t = new Tab();
        t.path = path;
        t.isNew = isNew;
        tabs.add(t);
        if (isNew) {
            t.loaded = true;
            t.sha = null;
        }
        select(t);
    }

    private void select(Tab t) {
        saveCurrentState();
        cur = t;
        String name = t.name();
        titleView.setText(name);
        setSubtitle(t.path);
        rebuildTabs();
        if (!t.loaded) {
            suppress = true;
            editor.setText("");
            suppress = false;
            editor.setEnabled(false);
            load(t);
            return;
        }
        applyCurrent();
    }

    private void applyCurrent() {
        final Tab t = cur;
        if (t == null) return;
        suppress = true;
        editor.setText(t.text);
        suppress = false;
        editor.setEnabled(t.editable);
        tools.setEditable(t.editable);
        btnA1.setVisibility(t.editable ? View.VISIBLE : View.GONE);
        showStatus(t.lockMsg != 0 ? getString(t.lockMsg) : null);
        tools.onTextLoaded();
        editor.post(() -> {
            int n = editor.getText().length();
            editor.setSelection(Math.max(0, Math.min(t.sel, n)));
            editorScroll.scrollTo(0, t.scroll);
            if (t.isNew && t.text.isEmpty()) editor.requestFocus();
        });
    }

    private void rebuildTabs() {
        tabStrip.removeAllViews();
        for (final Tab t : tabs) tabStrip.addView(tabView(t));
        tabScroll.setVisibility(View.VISIBLE);
        tabScroll.post(() -> {
            int idx = tabs.indexOf(cur);
            if (idx >= 0 && idx < tabStrip.getChildCount()) {
                View v = tabStrip.getChildAt(idx);
                tabScroll.smoothScrollTo(Math.max(0, v.getLeft() - Ui.dp(this, 24)), 0);
            }
        });
    }

    private View tabView(final Tab t) {
        final boolean sel = t == cur;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 8));
        box.setLayoutParams(lp);

        TextView name = Ui.chip(this, (t.dirty ? "\u25CF " : "") + t.name(), sel);
        name.setMaxWidth(Ui.dp(this, 180));
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        name.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        if (t.dirty) name.setTextColor(Ui.color(this, R.color.warn));
        name.setOnClickListener(v -> {
            if (t != cur) select(t);
        });
        name.setOnLongClickListener(v -> {
            tabMenu(t);
            return true;
        });

        TextView x = Ui.chip(this, "\u00D7", sel);
        x.setTextSize(16);
        x.setContentDescription(getString(R.string.close_file));
        x.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        x.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        x.setOnClickListener(v -> closeTab(t, false));

        box.addView(name);
        box.addView(x);
        return box;
    }

    private void tabMenu(final Tab t) {
        String[] items = {getString(R.string.close_file), getString(R.string.close_all_files)};
        choose(t.name(), items, (d, which) -> {
            if (which == 0) closeTab(t, false);
            else closeAll();
        });
    }

    private void closeTab(final Tab t, boolean force) {
        if (t == cur) saveCurrentState();
        if (t.dirty && !force) {
            confirm(getString(R.string.discard_title), getString(R.string.discard_msg),
                    R.string.discard, () -> closeTab(t, true));
            return;
        }
        int idx = tabs.indexOf(t);
        if (idx < 0) return;
        tabs.remove(t);
        t.gen++;
        if (tabs.isEmpty()) {
            cur = null;
            finish();
            return;
        }
        if (t == cur) {
            cur = null;
            select(tabs.get(Math.min(idx, tabs.size() - 1)));
        } else {
            rebuildTabs();
        }
    }

    private void closeAll() {
        confirmLeave(() -> {
            tabs.clear();
            cur = null;
            finish();
        });
    }

    private void pickFile() {
        final android.content.Context app = getApplicationContext();
        showProgress(getString(R.string.working));
        bg(() -> {
            RepoTree.Snapshot snap = RepoTree.cached(app, owner, repo, branch);
            if (snap == null) snap = RepoTree.fetch(app, api, owner, repo, branch, null);
            final List<String> all = new ArrayList<>();
            JSONArray raw = snap.raw;
            for (int i = 0; i < raw.length(); i++) {
                JSONArray e = raw.getJSONArray(i);
                if (!"tree".equals(e.getString(1))) all.add(e.getString(0));
            }
            post(() -> {
                hideProgress();
                filePickerDialog(all);
            });
        });
    }

    private void filePickerDialog(final List<String> all) {
        LinearLayout box = Ui.box(this);
        final EditText q = Ui.edit(this, getString(R.string.pick_file_title), "");
        box.addView(q);
        new Dlg(this)
                .setTitle(R.string.open_another_file)
                .setView(box)
                .setPositiveButton(R.string.ed_find, (d, w) -> {
                    String s = q.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
                    final List<String> hits = new ArrayList<>();
                    for (String p : all) {
                        if (s.isEmpty() || p.toLowerCase(java.util.Locale.ROOT).contains(s)) hits.add(p);
                        if (hits.size() >= 60) break;
                    }
                    if (hits.isEmpty()) {
                        toast(R.string.pick_file_empty);
                        return;
                    }
                    choose(getString(R.string.pick_file_title), hits.toArray(new String[0]),
                            (dd, which) -> openTab(hits.get(which), false));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static boolean isText(byte[] b) {
        for (int i = 0; i < Math.min(b.length, 8000); i++) {
            if (b[i] == 0) return false;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private void load(final Tab t) {
        if (t.isNew) return;
        final int gen = ++t.gen;
        if (t == cur) loading(true);
        final String p = t.path;
        final String b = branch;
        io.execute(() -> {
            try {
                JSONObject meta = api.getContent(owner, repo, p, b);
                final String newSha = meta.optString("sha");
                long size = meta.optLong("size");
                byte[] bytes = null;
                if (size <= MAX_BYTES) {
                    String enc = meta.optString("encoding");
                    String c = meta.optString("content");
                    if ("base64".equals(enc) && !c.isEmpty()) bytes = Base64.decode(c, Base64.DEFAULT);
                    else bytes = api.getFileBytes(owner, repo, p, b, MAX_BYTES);
                }
                final byte[] fb = bytes;
                post(() -> {
                    if (gen != t.gen || !tabs.contains(t)) return;
                    t.sha = newSha;
                    t.dirty = false;
                    t.loaded = true;
                    t.editable = true;
                    t.lockMsg = 0;
                    t.text = "";
                    if (fb == null) {
                        t.editable = false;
                        t.lockMsg = R.string.file_too_large;
                    } else if (!isText(fb)) {
                        t.editable = false;
                        t.lockMsg = R.string.file_binary;
                    } else {
                        t.text = new String(fb, StandardCharsets.UTF_8);
                    }
                    if (t == cur) {
                        loading(false);
                        applyCurrent();
                    }
                    rebuildTabs();
                });
            } catch (Exception e) {
                post(() -> {
                    if (t == cur) loading(false);
                });
                fail(e);
            }
        });
    }

    private void saveDialog() {
        final Tab t = cur;
        if (t == null || !t.editable) return;
        final String name = t.name();
        LinearLayout box = Ui.box(this);
        final EditText msg = Ui.edit(this, getString(R.string.commit_message),
                getString(t.isNew ? R.string.create_file_commit : R.string.update_file_commit, name));
        box.addView(msg);
        new Dlg(this)
                .setTitle(R.string.commit_changes)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String m = msg.getText().toString().trim();
                    final String message = m.isEmpty() ? getString(R.string.default_commit) : m;
                    final byte[] data = editor.getText().toString().getBytes(StandardCharsets.UTF_8);
                    final String currentSha = t.sha;
                    final String savedText = editor.getText().toString();
                    showProgress(getString(R.string.committing));
                    bg(() -> {
                        JSONObject res = api.putFileContent(owner, repo, t.path, data, message, branch, currentSha);
                        JSONObject content = res.optJSONObject("content");
                        final String newSha = content == null ? currentSha : content.optString("sha");
                        post(() -> {
                            hideProgress();
                            t.sha = newSha;
                            t.isNew = false;
                            t.dirty = false;
                            t.text = savedText;
                            rebuildTabs();
                            toast(R.string.saved);
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void moreMenu() {
        final Tab t = cur;
        if (t == null) return;
        String[] items = {getString(R.string.ed_find), getString(R.string.ed_goto),
                getString(R.string.copy_content), getString(R.string.download),
                getString(R.string.open_in_github), getString(R.string.open_another_file),
                getString(R.string.close_file), getString(R.string.close_all_files)};
        choose(getString(R.string.more), items, (d, which) -> {
            if (which == 0) {
                tools.toggleFind();
            } else if (which == 1) {
                tools.goToDialog();
            } else if (which == 2) {
                copy("file", editor.getText().toString());
            } else if (which == 3) {
                if (t.isNew) {
                    toast(R.string.save_first);
                    return;
                }
                saveAs(t.name(), () -> api.openDownload(api.contentRawPath(owner, repo, t.path, branch),
                        "application/vnd.github.raw"));
            } else if (which == 4) {
                openUrl(webUrl("/blob/" + branch + "/" + t.path));
            } else if (which == 5) {
                pickFile();
            } else if (which == 6) {
                closeTab(t, false);
            } else {
                closeAll();
            }
        });
    }
}

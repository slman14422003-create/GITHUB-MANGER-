package com.ghmanager.app;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

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
        String base = "";
        boolean isImage;
        Bitmap bitmap;
        long imageBytes;
        boolean previewMd;
        CodeEditText.History history = new CodeEditText.History();

        String name() {
            return path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        }
    }

    private final List<Tab> tabs = new ArrayList<>();
    private final Handler draftHandler = new Handler(Looper.getMainLooper());
    private final Runnable draftTick = new Runnable() {
        @Override
        public void run() {
            saveDraftAsync();
        }
    };
    private ActivityResultLauncher<Intent> conflictLauncher;
    private Tab cur;
    private boolean suppress = false;
    private CodeEditText editor;
    private SyntaxHighlighter highlighter;
    private ScrollView mdScroll;
    private TextView mdText;
    private View imageHolder;
    private ZoomImageView imageView;
    private TextView imageInfo;
    private View editorInfoView;
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
        highlighter = new SyntaxHighlighter(this, editor);
        highlighter.setEnabled(Store.syntaxOn(this));
        mdScroll = findViewById(R.id.mdScroll);
        mdText = findViewById(R.id.mdText);
        mdText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        imageHolder = findViewById(R.id.imageHolder);
        imageView = findViewById(R.id.imageView);
        imageInfo = findViewById(R.id.imageInfo);
        editorInfoView = findViewById(R.id.editorInfo);
        setupTree();

        conflictLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
            if (res.getResultCode() != android.app.Activity.RESULT_OK || cur == null) return;
            cur.dirty = false;
            cur.isNew = false;
            load(cur);
        });

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
                if (!suppress && highlighter != null) highlighter.schedule(380);
                if (!suppress && cur != null && cur.dirty) scheduleDraft();
            }
        });

        btnRefresh.setOnClickListener(v -> {
            if (cur == null || cur.isNew) return;
            if (cur.dirty) confirm(getString(R.string.discard_title), getString(R.string.discard_msg),
                    R.string.discard, () -> {
                        dropDraft(cur);
                        load(cur);
                    });
            else load(cur);
        });
        action(btnA1, R.drawable.ic_check, R.string.save, v -> saveDialog());
        action(btnA2, R.drawable.ic_more, R.string.more, v -> moreMenu());
        action(btnA3, R.drawable.ic_eye, R.string.ed_preview, v -> togglePreview());
        btnA3.setVisibility(View.GONE);

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
        dropAllDrafts();
        owner = o;
        repo = r;
        branch = b;
        retargetAccess(getIntent());
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
        boolean isNew = i.getBooleanExtra("new", false);
        if (isNew && !requireWrite()) {
            if (tabs.isEmpty()) finish();
            return;
        }
        openTab(p, isNew);
    }

    private boolean canEdit(Tab t) {
        return t != null && t.editable && canWrite;
    }

    @Override
    protected void onAccessChanged() {
        updateAccessBadge();
        if (cur != null && cur.loaded) applyCurrent();
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
                    R.string.discard, () -> {
                        dropAllDrafts();
                        proceed.run();
                    });
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
            t.base = "";
        }
        select(t);
        if (isNew) offerDraft(t);
    }

    @Override
    protected void onPause() {
        super.onPause();
        draftHandler.removeCallbacks(draftTick);
        saveCurrentState();
        for (Tab t : tabs) {
            if (t.loaded && t.dirty && canEdit(t) && !t.isImage) Drafts.save(this, draftOf(t, t.text));
        }
    }

    private Drafts.Draft draftOf(Tab t, String text) {
        Drafts.Draft d = new Drafts.Draft();
        d.acc = Accounts.activeId(this);
        d.owner = owner;
        d.repo = repo;
        d.branch = branch;
        d.path = t.path;
        d.text = text;
        d.baseSha = t.sha;
        d.baseText = t.base == null ? "" : t.base;
        return d;
    }

    private void scheduleDraft() {
        draftHandler.removeCallbacks(draftTick);
        draftHandler.postDelayed(draftTick, 1500);
    }

    private void saveDraftAsync() {
        final Tab t = cur;
        if (t == null || !t.loaded || !t.dirty || !canEdit(t) || t.isImage) return;
        final Drafts.Draft d = draftOf(t, editor.getText().toString());
        final android.content.Context app = getApplicationContext();
        try {
            io.execute(() -> Drafts.save(app, d));
        } catch (Exception ignored) {
        }
    }

    private void dropDraft(Tab t) {
        if (t == null) return;
        draftHandler.removeCallbacks(draftTick);
        Drafts.delete(this, owner, repo, branch, t.path);
    }

    private void dropAllDrafts() {
        draftHandler.removeCallbacks(draftTick);
        for (Tab t : tabs) if (t.dirty) Drafts.delete(this, owner, repo, branch, t.path);
    }

    private void offerDraft(final Tab t) {
        if (!t.loaded || !canEdit(t) || t.isImage) return;
        final Drafts.Draft d = Drafts.load(this, owner, repo, branch, t.path);
        if (d == null) return;
        if (d.text.equals(t.text)) {
            Drafts.delete(this, owner, repo, branch, t.path);
            return;
        }
        if (d.auto) {
            applyDraft(t, d);
            return;
        }
        boolean moved = !CommitQueue.same(d.baseSha, t.sha);
        new Dlg(this)
                .setTitle(R.string.dr_restore_title)
                .setMessage(getString(moved ? R.string.dr_restore_changed : R.string.dr_restore_msg, Fmt.agoMs(d.at)))
                .setPositiveButton(R.string.dr_restore, (x, w) -> applyDraft(t, d))
                .setNegativeButton(R.string.dr_discard, (x, w) -> Drafts.delete(this, owner, repo, branch, t.path))
                .show();
    }

    private void applyDraft(Tab t, Drafts.Draft d) {
        if (!tabs.contains(t)) return;
        t.text = d.text;
        t.sha = d.baseSha;
        t.base = d.baseText == null ? t.base : d.baseText;
        t.dirty = true;
        t.history = new CodeEditText.History();
        if (d.auto) {
            d.auto = false;
            Drafts.save(this, d);
        }
        if (t == cur) applyCurrent();
        rebuildTabs();
    }

    private void select(Tab t) {
        saveCurrentState();
        cur = t;
        String name = t.name();
        titleView.setText(name);
        setSubtitle(t.path);
        rebuildTabs();
        FileMarks.touch(this, owner, repo, t.path);
        if (!t.loaded) {
            suppress = true;
            editor.setTracking(false);
            editor.setText("");
            editor.setTracking(true);
            suppress = false;
            editor.setEnabled(false);
            showMode(false, false);
            btnA3.setVisibility(View.GONE);
            load(t);
            return;
        }
        applyCurrent();
    }

    private void showMode(boolean md, boolean image) {
        editorScroll.setVisibility(md || image ? View.GONE : View.VISIBLE);
        mdScroll.setVisibility(md ? View.VISIBLE : View.GONE);
        imageHolder.setVisibility(image ? View.VISIBLE : View.GONE);
        editorInfoView.setVisibility(md || image ? View.GONE : View.VISIBLE);
    }

    private static boolean isMarkdown(String path) {
        String l = path.toLowerCase(java.util.Locale.ROOT);
        return l.endsWith(".md") || l.endsWith(".markdown");
    }

    private void applyCurrent() {
        final Tab t = cur;
        if (t == null) return;
        if (t.isImage && t.bitmap != null) {
            showMode(false, true);
            imageView.setImageBitmap(t.bitmap);
            imageInfo.setText(t.bitmap.getWidth() + " \u00D7 " + t.bitmap.getHeight() + "  \u00B7  " + Fmt.size(t.imageBytes));
            btnA1.setVisibility(View.GONE);
            btnA3.setVisibility(View.GONE);
            tools.setEditable(false);
            showStatus(null);
            return;
        }
        suppress = true;
        editor.setTracking(false);
        editor.setText(t.text);
        editor.setTracking(true);
        suppress = false;
        editor.useHistory(t.history);
        editor.setEnabled(canEdit(t));
        tools.setEditable(canEdit(t));
        tools.refreshHistory();
        btnA1.setVisibility(canEdit(t) ? View.VISIBLE : View.GONE);
        boolean md = t.editable && isMarkdown(t.path);
        btnA3.setVisibility(md ? View.VISIBLE : View.GONE);
        if (md) {
            btnA3.setImageResource(t.previewMd ? R.drawable.ic_edit : R.drawable.ic_eye);
            btnA3.setContentDescription(getString(t.previewMd ? R.string.ed_edit_mode : R.string.ed_preview));
        }
        if (md && t.previewMd) {
            renderMarkdown(t);
            showMode(true, false);
        } else {
            showMode(false, false);
        }
        showStatus(t.lockMsg != 0 ? getString(t.lockMsg) : !canWrite ? getString(R.string.read_only_blocked) : null);
        highlighter.setPath(t.path);
        highlighter.schedule(0);
        tools.onTextLoaded();
        editor.post(() -> {
            int n = editor.getText().length();
            editor.setSelection(Math.max(0, Math.min(t.sel, n)));
            editorScroll.scrollTo(0, t.scroll);
            if (t.isNew && t.text.isEmpty()) editor.requestFocus();
        });
    }

    private void renderMarkdown(final Tab t) {
        mdText.setText(MarkdownRender.render(this, editor.getText().toString(), url -> {
            String u = url.trim();
            if (u.startsWith("#")) return;
            if (u.regionMatches(true, 0, "https://", 0, 8)) {
                openUrl(u);
            } else if (u.startsWith("//") || u.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
                toast(R.string.no_browser);
            } else {
                openTab(resolveRelative(t.path, u), false);
            }
        }));
    }

    private static String resolveRelative(String from, String rel) {
        int q = rel.indexOf('#');
        if (q >= 0) rel = rel.substring(0, q);
        List<String> parts = new ArrayList<>();
        if (!rel.startsWith("/")) {
            int slash = from.lastIndexOf('/');
            if (slash > 0) for (String s : from.substring(0, slash).split("/")) parts.add(s);
        }
        for (String s : rel.split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(s);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append('/');
            sb.append(s);
        }
        return sb.toString();
    }

    private void togglePreview() {
        final Tab t = cur;
        if (t == null || !t.editable) return;
        saveCurrentState();
        t.previewMd = !t.previewMd;
        if (t.previewMd) {
            renderMarkdown(t);
            Fx.crossfade(editorScroll, mdScroll);
            editorInfoView.setVisibility(View.GONE);
        } else {
            Fx.crossfade(mdScroll, editorScroll);
            editorInfoView.setVisibility(View.VISIBLE);
        }
        btnA3.setImageResource(t.previewMd ? R.drawable.ic_edit : R.drawable.ic_eye);
        btnA3.setContentDescription(getString(t.previewMd ? R.string.ed_edit_mode : R.string.ed_preview));
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
        name.setPaddingRelative(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
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
        x.setPaddingRelative(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
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
        if (t.dirty) dropDraft(t);
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

    private static Bitmap decodeBitmap(byte[] data) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int side = Math.max(o.outWidth, o.outHeight);
            int sample = 1;
            while (side / sample > 2400) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, d);
        } catch (Throwable e) {
            return null;
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
                final boolean image = BrowserActivity.isImage(p);
                final int limit = image ? MAX_IMAGE_BYTES : MAX_BYTES;
                if (size <= limit) {
                    String enc = meta.optString("encoding");
                    String c = meta.optString("content");
                    if ("base64".equals(enc) && !c.isEmpty()) bytes = Base64.decode(c, Base64.DEFAULT);
                    else bytes = api.getFileBytes(owner, repo, p, b, limit);
                }
                final byte[] fb = bytes;
                final Bitmap bmp = image && fb != null ? decodeBitmap(fb) : null;
                post(() -> {
                    if (gen != t.gen || !tabs.contains(t)) return;
                    t.sha = newSha;
                    t.dirty = false;
                    t.loaded = true;
                    t.editable = true;
                    t.lockMsg = 0;
                    t.text = "";
                    t.isImage = false;
                    t.bitmap = null;
                    if (fb == null) {
                        t.editable = false;
                        t.lockMsg = R.string.file_too_large;
                    } else if (bmp != null) {
                        t.isImage = true;
                        t.bitmap = bmp;
                        t.imageBytes = fb.length;
                        t.editable = false;
                    } else if (!isText(fb)) {
                        t.editable = false;
                        t.lockMsg = R.string.file_binary;
                    } else {
                        t.text = new String(fb, StandardCharsets.UTF_8);
                    }
                    t.base = t.text;
                    t.history = new CodeEditText.History();
                    if (t == cur) {
                        loading(false);
                        applyCurrent();
                    }
                    rebuildTabs();
                    offerDraft(t);
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
        if (!requireWrite()) return;
        final String name = t.name();
        final String current = editor.getText().toString();
        final LineDiff.Result diff = current.length() + t.base.length() > 600000 ? null
                : LineDiff.compute(t.base, current);
        if (diff != null && !diff.changed() && !t.isNew) {
            toast(R.string.ed_no_changes);
            return;
        }
        LinearLayout box = Ui.box(this);
        final EditText msg = Ui.edit(this, getString(R.string.commit_message),
                getString(t.isNew ? R.string.create_file_commit : R.string.update_file_commit, name));
        box.addView(msg);
        if (diff != null) {
            TextView sum = Ui.noteCard(this, getString(R.string.ed_changes_summary, diff.added, diff.removed),
                    R.color.text_secondary);
            sum.setOnClickListener(v -> showDiff(name, diff));
            Ui.press(this, sum);
            box.addView(sum);
            android.widget.Button view = Ui.button(this, R.string.ed_view_changes, false);
            view.setOnClickListener(v -> showDiff(name, diff));
            box.addView(view);
        }
        final CheckBox nb = Ui.check(this, R.string.ed_new_branch, false);
        final EditText nbName = Ui.edit(this, getString(R.string.ed_new_branch_name), suggestBranch(name));
        nbName.setVisibility(View.GONE);
        nbName.setTextDirection(View.TEXT_DIRECTION_LTR);
        nb.setOnCheckedChangeListener((b, on) -> {
            if (on) Fx.slideIn(nbName);
            else nbName.setVisibility(View.GONE);
        });
        box.addView(nb);
        box.addView(nbName);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new Dlg(this)
                .setTitle(R.string.commit_changes)
                .setView(sv)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String m = msg.getText().toString().trim();
                    final String message = m.isEmpty() ? getString(R.string.default_commit) : m;
                    final boolean toNew = nb.isChecked();
                    final String newName = nbName.getText().toString().trim().replace(' ', '-');
                    if (toNew && newName.isEmpty()) {
                        Dlg.stay(d);
                        return;
                    }
                    final byte[] data = current.getBytes(StandardCharsets.UTF_8);
                    final String currentSha = t.sha;
                    final String savedText = current;
                    final String fromBranch = branch;
                    showProgress(getString(R.string.committing));
                    final android.content.Context app = getApplicationContext();
                    final String filePath = t.path;
                    bg(() -> {
                        String remoteSha;
                        try {
                            remoteSha = CommitQueue.remoteSha(api, owner, repo, filePath, fromBranch);
                        } catch (GitHubApi.ApiException e) {
                            throw e;
                        } catch (Exception e) {
                            if (!toNew && isOffline(e)) {
                                post(() -> offerQueue(t, savedText, message, fromBranch, currentSha));
                                return;
                            }
                            throw e;
                        }
                        if (!toNew && remoteSha != null && remoteSha.equals(GitSha.blob(data))) {
                            final String same = remoteSha;
                            post(() -> saved(t, same, savedText, fromBranch, fromBranch, message, false));
                            return;
                        }
                        if (!CommitQueue.same(remoteSha, currentSha)) {
                            final CommitQueue.Item it = CommitQueue.add(app, owner, repo, fromBranch, filePath,
                                    message, currentSha, savedText, CommitQueue.CONFLICT);
                            post(() -> openConflict(it));
                            return;
                        }
                        String target = fromBranch;
                        if (toNew) {
                            String head = api.getBranchSha(owner, repo, fromBranch);
                            api.createBranch(owner, repo, newName, head);
                            target = newName;
                        }
                        JSONObject res;
                        try {
                            res = api.putFileContent(owner, repo, filePath, data, message, target, currentSha);
                        } catch (GitHubApi.ApiException e) {
                            if (e.code == 409 && !toNew) {
                                final CommitQueue.Item it = CommitQueue.add(app, owner, repo, fromBranch, filePath,
                                        message, currentSha, savedText, CommitQueue.CONFLICT);
                                post(() -> openConflict(it));
                                return;
                            }
                            throw e;
                        } catch (java.io.IOException e) {
                            if (!toNew) {
                                post(() -> offerQueue(t, savedText, message, fromBranch, currentSha));
                                return;
                            }
                            throw e;
                        }
                        JSONObject content = res.optJSONObject("content");
                        final String newSha = content == null ? currentSha : content.optString("sha");
                        final String landed = target;
                        post(() -> saved(t, newSha, savedText, fromBranch, landed, message, toNew));
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void saved(Tab t, String newSha, String savedText, String fromBranch, String landed, String message,
                       boolean toNew) {
        hideProgress();
        draftHandler.removeCallbacks(draftTick);
        Drafts.delete(this, owner, repo, fromBranch, t.path);
        CommitQueue.supersede(this, owner, repo, fromBranch, t.path);
        t.sha = newSha;
        t.isNew = false;
        t.dirty = false;
        t.text = savedText;
        t.base = savedText;
        RepoTree.invalidate(getApplicationContext(), owner, repo, fromBranch);
        FileDates.invalidate();
        rebuildTabs();
        if (toNew) {
            branch = landed;
            treeSnap = null;
            if (treePane.getVisibility() == View.VISIBLE) loadTree();
            confirm(getString(R.string.ed_branch_saved_title),
                    getString(R.string.ed_branch_saved_msg, landed), R.string.df_create_pr,
                    () -> prDialog(fromBranch, landed, message));
        } else {
            toast(R.string.saved);
        }
    }

    private void openConflict(CommitQueue.Item it) {
        hideProgress();
        draftHandler.removeCallbacks(draftTick);
        if (it == null) {
            toast(R.string.error);
            return;
        }
        Intent i = new Intent(this, ConflictActivity.class);
        i.putExtra("itemId", it.id);
        i.putExtra("fromEditor", true);
        conflictLauncher.launch(i);
    }

    private void offerQueue(final Tab t, final String text, final String message, final String fromBranch,
                            final String baseSha) {
        hideProgress();
        new Dlg(this)
                .setTitle(R.string.cq_offline_title)
                .setMessage(R.string.cq_offline_msg)
                .setPositiveButton(R.string.cq_queue_it, (d, w) -> {
                    CommitQueue.Item it = CommitQueue.add(this, owner, repo, fromBranch, t.path, message, baseSha,
                            text, CommitQueue.PENDING);
                    if (it == null) {
                        toast(R.string.error);
                        return;
                    }
                    draftHandler.removeCallbacks(draftTick);
                    Drafts.delete(this, owner, repo, fromBranch, t.path);
                    t.dirty = false;
                    t.text = text;
                    t.base = text;
                    rebuildTabs();
                    CommitQueueJob.schedule(getApplicationContext());
                    toast(R.string.cq_queued);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String suggestBranch(String fileName) {
        String base = fileName.replaceAll("[^A-Za-z0-9._-]", "-");
        return "edit/" + base + "-" + (System.currentTimeMillis() / 1000 % 100000);
    }

    private void showDiff(String name, LineDiff.Result diff) {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 8));
        List<CharSequence> chunks = LineDiff.chunks(this, diff, 3);
        if (chunks.isEmpty()) {
            TextView none = Ui.body(this, getString(R.string.ed_no_changes), 14, R.color.text_hint);
            inner.addView(none);
        }
        for (CharSequence cs : chunks) {
            TextView tv = new TextView(this);
            tv.setText(cs);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextSize(12);
            tv.setLineSpacing(Ui.dp(this, 2), 1f);
            tv.setTextColor(Ui.color(this, R.color.text_primary));
            tv.setTextDirection(View.TEXT_DIRECTION_LTR);
            tv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            tv.setHorizontallyScrolling(true);
            inner.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(inner);
        ScrollView sv = new ScrollView(this);
        sv.addView(hs);
        new Dlg(this).sheet()
                .setTitle(name + "  +" + diff.added + " \u2212" + diff.removed)
                .setView(sv)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void prDialog(final String baseBranch, final String head, String suggested) {
        LinearLayout box = Ui.box(this);
        final EditText title = Ui.edit(this, getString(R.string.issue_title), suggested);
        final EditText body = Ui.editMulti(this, getString(R.string.issue_body), null, 3);
        box.addView(title);
        box.addView(body);
        new Dlg(this)
                .setTitle(R.string.df_create_pr)
                .setView(box)
                .setPositiveButton(R.string.create, (d, w) -> {
                    final String ti = title.getText().toString().trim();
                    if (ti.isEmpty()) {
                        Dlg.stay(d);
                        return;
                    }
                    final String bo = body.getText().toString();
                    bg(() -> {
                        final JSONObject pr = api.createPull(owner, repo, ti, bo, head, baseBranch, false);
                        post(() -> {
                            toast(R.string.df_pr_created);
                            Intent i = repoIntent(IssueDetailActivity.class);
                            i.putExtra("number", pr.optLong("number"));
                            startActivity(i);
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void moreMenu() {
        final Tab t = cur;
        if (t == null) return;
        final boolean fav = FileMarks.isFavorite(this, owner, repo, t.path);
        String[] items = {getString(R.string.ed_find), getString(R.string.ed_goto),
                getString(R.string.copy_content), getString(R.string.download),
                getString(R.string.open_in_github), getString(R.string.open_another_file),
                getString(R.string.close_file), getString(R.string.close_all_files),
                getString(fav ? R.string.bs_fav_remove : R.string.bs_fav_add), getString(R.string.df_history),
                getString(R.string.ed_recent_files), getString(highlighter.isEnabled() ? R.string.syn_off : R.string.syn_on)};
        choose(getString(R.string.more), items, (d, which) -> {
            if (which == 8) {
                FileMarks.setFavorite(this, owner, repo, t.path, !fav);
                toast(fav ? R.string.bs_fav_removed : R.string.bs_fav_added);
                return;
            }
            if (which == 9) {
                if (t.isNew) {
                    toast(R.string.save_first);
                    return;
                }
                Intent hi = repoIntent(CommitsActivity.class);
                hi.putExtra("path", t.path);
                startActivity(hi);
                return;
            }
            if (which == 10) {
                recentSheet();
                return;
            }
            if (which == 11) {
                boolean on = !highlighter.isEnabled();
                Store.setSyntaxOn(this, on);
                highlighter.setEnabled(on);
                if (on) {
                    highlighter.setPath(t.path);
                    highlighter.schedule(0);
                }
                return;
            }
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

    private void recentSheet() {
        final List<String> favs = FileMarks.favorites(this, owner, repo);
        final List<String> recs = FileMarks.recents(this, owner, repo);
        final List<String> all = new ArrayList<>();
        for (String f : favs) all.add(f);
        for (String r : recs) if (!all.contains(r)) all.add(r);
        if (all.isEmpty()) {
            toast(R.string.bs_no_recent);
            return;
        }
        String[] labels = new String[all.size()];
        for (int i = 0; i < labels.length; i++) {
            String p = all.get(i);
            int slash = p.lastIndexOf('/');
            String nm = slash < 0 ? p : p.substring(slash + 1);
            labels[i] = (favs.contains(p) ? "\u2605  " : "") + nm + (slash < 0 ? "" : "   \u00B7   " + p.substring(0, slash));
        }
        choose(getString(R.string.ed_recent_files), labels, (d, which) -> openTab(all.get(which), false));
    }
}

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
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.BaseAdapter;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.text.Editable;
import android.text.TextWatcher;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class BrowserActivity extends BaseRepoActivity {

    private static final int MAX_FILE_BYTES = 25 * 1024 * 1024;

    private String path = "";

    private final List<JSONObject> items = new ArrayList<>();
    private final List<JSONObject> allItems = new ArrayList<>();
    private String query = "";
    private int sortMode = 0;
    private boolean gridMode = false;
    private ListView list;
    private GridView grid;
    private EditText searchBox;
    private TextView chipSort;
    private TextView chipView;
    private TextView chipSearch;
    private final AtomicInteger dateGen = new AtomicInteger();
    private TextView pathView;
    private android.widget.HorizontalScrollView crumbScroll;
    private Spinner spinner;
    private List<String> branches = new ArrayList<>();
    private boolean needsReload = false;

    // ---- select mode (delete one / several / all repository files in a single commit)
    private boolean selecting = false;
    private final Set<String> selected = new LinkedHashSet<>();
    private View hintRow, selBar, uploadBar, deleteBar;
    private TextView selCount, selAll, chipSelect;
    private Button btnDeleteSel;
    private Button btnDownloadSel;
    private android.widget.ImageButton btnNew;

    private ActivityResultLauncher<Intent> pickLauncher;
    private List<java.io.File> pickedRoots;
    private boolean pickedFolder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);

        ((TextView) findViewById(R.id.title)).setText(repo);

        spinner = findViewById(R.id.branchSpinner);
        pathView = findViewById(R.id.crumbBar);
        crumbScroll = findViewById(R.id.crumbScroll);
        View headerSub = findViewById(R.id.subtitle);
        if (headerSub != null) headerSub.setVisibility(View.GONE);
        pathView.setTextDirection(View.TEXT_DIRECTION_LTR);
        pathView.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        pathView.setHighlightColor(android.graphics.Color.TRANSPARENT);
        wireRootNav();
        path = Store.lastFolder(this, owner, repo);
        loadingBar = findViewById(R.id.loading);
        btnNew = findViewById(R.id.btnA1);
        btnNew.setImageResource(R.drawable.ic_add);
        btnNew.setContentDescription(getString(R.string.new_item));
        btnNew.setVisibility(View.VISIBLE);
        Ui.press(this, btnNew);
        Ui.press(this, findViewById(R.id.btnBack));
        Ui.press(this, findViewById(R.id.btnRefresh));
        list = findViewById(R.id.list);
        grid = findViewById(R.id.grid);
        searchBox = findViewById(R.id.searchBox);
        sortMode = Store.fileSort(this);
        gridMode = Store.fileGrid(this);
        Button btnFolder = findViewById(R.id.btnFolder);
        Button btnFiles = findViewById(R.id.btnFiles);

        emptyView = findViewById(R.id.empty);
        adapter = new RowAdapter(this);
        list.setAdapter(adapter);
        grid.setAdapter(gridAdapter);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> reloadFresh());
        btnNew.setOnClickListener(v -> newMenu());
        findViewById(R.id.branchBox).setOnClickListener(v -> spinner.performClick());

        hintRow = findViewById(R.id.hintRow);
        selBar = findViewById(R.id.selBar);
        uploadBar = findViewById(R.id.uploadBar);
        deleteBar = findViewById(R.id.deleteBar);
        selCount = findViewById(R.id.selCount);
        selAll = findViewById(R.id.selAll);
        btnDeleteSel = findViewById(R.id.btnDeleteSel);
        btnDownloadSel = findViewById(R.id.btnDownloadSel);
        Ui.press(this, btnDownloadSel);
        btnDownloadSel.setOnClickListener(v -> downloadSelected());
        chipSelect = Ui.chip(this, getString(R.string.sel_mode), false);
        ((LinearLayout.LayoutParams) chipSelect.getLayoutParams()).setMarginEnd(0);
        ((LinearLayout) hintRow).addView(chipSelect);
        Ui.press(this, selAll);
        Ui.press(this, btnDeleteSel);
        chipSelect.setOnClickListener(v -> {
            if (!items.isEmpty()) enterSelect(null);
        });
        findViewById(R.id.selClose).setOnClickListener(v -> exitSelect());
        selAll.setOnClickListener(v -> toggleAll());
        btnDeleteSel.setOnClickListener(v -> confirmDeleteSelected());

        list.setOnItemClickListener((p, v, pos, id) -> onItemTap(pos));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            onItemLong(pos);
            return true;
        });
        grid.setOnItemClickListener((p, v, pos, id) -> onItemTap(pos));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            onItemLong(pos);
            return true;
        });
        buildTools();
        applyViewMode(false);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                query = e.toString();
                buildItems();
                rebuildRows();
                updateEmpty();
            }
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
                if (selecting) {
                    exitSelect();
                } else if (searchBox.getVisibility() == View.VISIBLE) {
                    closeSearch();
                } else if (!path.isEmpty()) {
                    goUp();
                } else {
                    setEnabled(false);
                    BrowserActivity.this.getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        applyWriteUi();
        loadBranches();
        load();
    }

    @Override
    protected void onAccessChanged() {
        applyWriteUi();
    }

    private void applyWriteUi() {
        if (uploadBar == null || btnNew == null || btnDeleteSel == null) return;
        uploadBar.setVisibility(canWrite && !selecting ? View.VISIBLE : View.GONE);
        btnNew.setVisibility(!canWrite ? View.GONE : selecting ? View.INVISIBLE : View.VISIBLE);
        btnDeleteSel.setVisibility(canWrite ? View.VISIBLE : View.GONE);
        updateAccessBadge();
    }

    private int seenFinished = -1;
    private final Transfers.Listener txListener = job -> {
        if (job.tag.startsWith("upload:" + owner + "/" + repo)) {
            seenFinished = Transfers.finishedCount();
            load();
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        Transfers.addListener(txListener);
        if (needsReload) {
            needsReload = false;
            reloadFresh();
        } else if (seenFinished >= 0 && seenFinished != Transfers.finishedCount()) {
            // an upload may have finished while this screen was in the background
            load();
        }
        seenFinished = Transfers.finishedCount();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Transfers.removeListener(txListener);
        seenFinished = Transfers.finishedCount();
    }

    /** After a change made by this screen: drop the cached tree so the next listing is the real one. */
    private void reloadFresh() {
        RepoTree.invalidate(getApplicationContext(), owner, repo, branch);
        load(true);
    }

    private TextView toolChip(int icon, String text, View.OnClickListener l) {
        TextView c = Ui.chip(this, text, false);
        c.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        c.setCompoundDrawablePadding(Ui.dp(this, 7));
        c.setCompoundDrawableTintList(ColorStateList.valueOf(Ui.color(this, R.color.text_secondary)));
        android.graphics.drawable.Drawable[] d = c.getCompoundDrawablesRelative();
        if (d[0] != null) {
            int sz = Ui.dp(this, 16);
            d[0].setBounds(0, 0, sz, sz);
            c.setCompoundDrawablesRelative(d[0], null, null, null);
        }
        c.setOnClickListener(l);
        return c;
    }

    private String sortName() {
        return getString(sortMode == 1 ? R.string.bs_sort_date : sortMode == 2 ? R.string.bs_sort_size
                : R.string.bs_sort_name);
    }

    private void buildTools() {
        LinearLayout row = findViewById(R.id.toolsRow);
        row.removeAllViews();
        chipSearch = toolChip(R.drawable.ic_search, getString(R.string.bs_search), v -> toggleSearch());
        chipSort = toolChip(R.drawable.ic_sort, getString(R.string.bs_sort, sortName()), v -> pickSort());
        chipView = toolChip(gridMode ? R.drawable.ic_list : R.drawable.ic_grid,
                getString(gridMode ? R.string.bs_view_list : R.string.bs_view_grid), v -> {
                    gridMode = !gridMode;
                    Store.setFileGrid(this, gridMode);
                    chipView.setText(gridMode ? R.string.bs_view_list : R.string.bs_view_grid);
                    chipView.setCompoundDrawablesRelativeWithIntrinsicBounds(gridMode ? R.drawable.ic_list
                            : R.drawable.ic_grid, 0, 0, 0);
                    chipView.setCompoundDrawableTintList(ColorStateList.valueOf(Ui.color(this, R.color.text_secondary)));
                    applyViewMode(true);
                });
        row.addView(chipSearch);
        row.addView(chipSort);
        row.addView(chipView);
        row.addView(toolChip(R.drawable.ic_star_fill, getString(R.string.bs_favorites), v -> marksSheet(true)));
        row.addView(toolChip(R.drawable.ic_history, getString(R.string.bs_recent), v -> marksSheet(false)));
    }

    private void applyViewMode(boolean animate) {
        View show = gridMode ? grid : list;
        View hide = gridMode ? list : grid;
        if (animate) Fx.crossfade(hide, show);
        else {
            hide.setVisibility(View.GONE);
            show.setVisibility(View.VISIBLE);
        }
    }

    private void toggleSearch() {
        boolean show = searchBox.getVisibility() != View.VISIBLE;
        if (show) {
            Fx.slideIn(searchBox);
            searchBox.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(searchBox, 0);
        } else {
            closeSearch();
        }
    }

    private void closeSearch() {
        searchBox.setText("");
        query = "";
        Fx.slideOut(searchBox, View.GONE);
        buildItems();
        rebuildRows();
        updateEmpty();
    }

    private void pickSort() {
        String[] names = {getString(R.string.bs_sort_name), getString(R.string.bs_sort_date),
                getString(R.string.bs_sort_size)};
        choose(getString(R.string.bs_sort_title), names, (d, which) -> {
            sortMode = which;
            Store.setFileSort(this, which);
            chipSort.setText(getString(R.string.bs_sort, sortName()));
            buildItems();
            rebuildRows();
            ensureDates();
        });
    }

    private boolean isDir(JSONObject o) {
        return "dir".equals(o.optString("type"));
    }

    private void buildItems() {
        items.clear();
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            items.addAll(allItems);
        } else {
            Set<String> seen = new HashSet<>();
            for (JSONObject o : allItems) {
                if (o.optString("name").toLowerCase(Locale.ROOT).contains(q)) {
                    items.add(o);
                    seen.add(o.optString("path"));
                }
            }
            RepoTree.Snapshot snap = RepoTree.memory(getApplicationContext(), owner, repo, branch);
            if (snap != null) {
                for (JSONObject o : snap.search(path, q, 120)) {
                    if (seen.add(o.optString("path"))) items.add(o);
                }
            }
        }
        Collections.sort(items, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject x, JSONObject y) {
                boolean dx = isDir(x);
                boolean dy = isDir(y);
                if (dx != dy) return dx ? -1 : 1;
                if (sortMode == 1) {
                    long a = FileDates.cached(owner, repo, branch, x.optString("path"));
                    long b = FileDates.cached(owner, repo, branch, y.optString("path"));
                    if (a != b) return a > b ? -1 : 1;
                } else if (sortMode == 2 && !dx) {
                    long a = x.optLong("size");
                    long b = y.optLong("size");
                    if (a != b) return a > b ? -1 : 1;
                }
                return x.optString("name").compareToIgnoreCase(y.optString("name"));
            }
        });
    }

    private void ensureDates() {
        if (sortMode != 1) return;
        final List<String> need = new ArrayList<>();
        for (JSONObject o : items) {
            String p = o.optString("path");
            if (need.size() < 60 && FileDates.cached(owner, repo, branch, p) == 0) need.add(p);
        }
        if (need.isEmpty()) return;
        final int gen = dateGen.incrementAndGet();
        loading(true);
        FileDates.load(api, owner, repo, branch, need, new FileDates.Listener() {
            @Override
            public void onDate(String p, long millis) {
            }

            @Override
            public void onDone() {
                post(() -> {
                    if (gen != dateGen.get()) return;
                    loading(false);
                    buildItems();
                    rebuildRows();
                });
            }

            @Override
            public boolean cancelled() {
                return gen != dateGen.get() || sortMode != 1;
            }
        });
    }

    private void updateEmpty() {
        boolean none = items.isEmpty();
        emptyView.setText(query.trim().isEmpty() ? R.string.empty_folder : R.string.bs_no_results);
        Fx.emptyState(emptyView, query.trim().isEmpty() ? R.drawable.ic_folder : R.drawable.ic_search);
        if (none && emptyView.getVisibility() != View.VISIBLE) Fx.reveal(emptyView);
        else emptyView.setVisibility(none ? View.VISIBLE : View.GONE);
    }

    private void marksSheet(final boolean favorites) {
        final List<String> paths = favorites ? FileMarks.favorites(this, owner, repo)
                : FileMarks.recents(this, owner, repo);
        if (paths.isEmpty()) {
            toast(favorites ? R.string.bs_no_favorites : R.string.bs_no_recent);
            return;
        }
        String[] labels = new String[paths.size()];
        for (int i = 0; i < labels.length; i++) {
            String p = paths.get(i);
            int slash = p.lastIndexOf('/');
            labels[i] = slash < 0 ? p : p.substring(slash + 1) + "   \u00B7   " + p.substring(0, slash);
        }
        choose(getString(favorites ? R.string.bs_favorites : R.string.bs_recent), labels, (d, which) ->
                openMark(paths.get(which)));
    }

    private void openMark(String p) {
        RepoTree.Snapshot snap = RepoTree.memory(getApplicationContext(), owner, repo, branch);
        if (snap != null && snap.isDir(p)) {
            exitSelect();
            path = p;
            load();
        } else {
            openFile(p);
        }
    }

    private void onItemTap(int pos) {
        if (pos < 0 || pos >= items.size()) return;
        JSONObject o = items.get(pos);
        if (selecting) {
            toggle(o.optString("path"));
            return;
        }
        if (isDir(o)) {
            if (searchBox.getVisibility() == View.VISIBLE) closeSearch();
            path = o.optString("path");
            load();
        } else {
            openFile(o.optString("path"));
        }
    }

    private void onItemLong(int pos) {
        if (pos < 0 || pos >= items.size()) return;
        JSONObject o = items.get(pos);
        if (selecting) toggle(o.optString("path"));
        else itemMenu(o);
    }

    private final BaseAdapter gridAdapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(BrowserActivity.this).inflate(R.layout.item_file_grid, parent, false);
            final JSONObject o = items.get(pos);
            boolean dir = isDir(o);
            String name = o.optString("name");
            String p = o.optString("path");
            int color = Ui.color(BrowserActivity.this, dir ? R.color.accent_text : fileTint(name));
            ImageView icon = v.findViewById(R.id.icon);
            icon.setImageResource(dir ? R.drawable.ic_folder : isImage(name) ? R.drawable.ic_image : R.drawable.ic_file);
            icon.setImageTintList(ColorStateList.valueOf(color));
            GradientDrawable tile = new GradientDrawable();
            tile.setCornerRadius(Ui.radius(BrowserActivity.this, R.dimen.radius_s));
            tile.setColor((color & 0x00FFFFFF) | 0x22000000);
            icon.setBackground(tile);
            ((TextView) v.findViewById(R.id.title)).setText(name);
            TextView sub = v.findViewById(R.id.sub);
            String sb = dir ? "" : Fmt.size(o.optLong("size"));
            long when = sortMode == 1 ? FileDates.cached(owner, repo, branch, p) : 0;
            if (when > 0) sb = (sb.isEmpty() ? "" : sb + " \u00B7 ") + Fmt.agoMs(when);
            sub.setText(sb);
            v.findViewById(R.id.badge).setVisibility(View.GONE);
            v.findViewById(R.id.fav).setVisibility(FileMarks.isFavorite(BrowserActivity.this, owner, repo, p)
                    ? View.VISIBLE : View.GONE);
            v.findViewById(R.id.check).setVisibility(selecting && selected.contains(p) ? View.VISIBLE : View.GONE);
            View more = v.findViewById(R.id.more);
            more.setVisibility(selecting ? View.GONE : View.VISIBLE);
            more.setOnClickListener(x -> itemMenu(o));
            View card = v.findViewById(R.id.card);
            card.setAlpha(selecting && !selected.contains(p) ? 0.7f : 1f);
            if (pos < 18) Ui.enter(v, pos);
            return v;
        }
    };

    static boolean isImage(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif")
                || n.endsWith(".webp") || n.endsWith(".bmp");
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
        FileMarks.touch(this, owner, repo, filePath);
        needsReload = true;
        EditorActivity.launch(this, filePath, false);
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
                    exitSelect();
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

    private int loadGen = 0;

    private void load() {
        load(false);
    }

    /**
     * Shows the folder instantly from the cached tree, then checks quietly (one tiny conditional
     * request) whether the repository changed. {@code force} skips the cache.
     */
    private void load(final boolean force) {
        final int gen = ++loadGen;
        loading(true);
        pathView.setText(crumbs(path, target -> {
            if (target.equals(path)) return;
            exitSelect();
            path = target;
            load();
        }));
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
        Store.setLastFolder(this, owner, repo, path);
        final String p = path;
        final String b = branch;
        final android.content.Context app = getApplicationContext();
        io.execute(() -> {
            RepoTree.Snapshot have = null;
            try {
                have = force ? null : RepoTree.cached(app, owner, repo, b);
                if (have != null) {
                    final List<JSONObject> l = have.list(p);
                    post(() -> {
                        if (gen == loadGen) show(l);
                    });
                    try {
                        RepoTree.Snapshot fresh = RepoTree.fetch(app, api, owner, repo, b, have);
                        if (fresh != have && !fresh.truncated) {
                            final List<JSONObject> l2 = fresh.list(p);
                            if (!RepoTree.same(l, l2)) post(() -> {
                                if (gen == loadGen) show(l2);
                            });
                        }
                    } catch (GitHubApi.ApiException e) {
                        if (e.code == 401) showError(e);
                    } catch (Exception ignored) {
                        // offline or flaky: the cached listing stays on screen
                    }
                    return;
                }
                RepoTree.Snapshot s = RepoTree.fetch(app, api, owner, repo, b, null);
                if (!s.truncated) {
                    final List<JSONObject> l = s.list(p);
                    post(() -> {
                        if (gen == loadGen) show(l);
                    });
                    return;
                }
                loadViaContents(p, b, gen);
            } catch (GitHubApi.ApiException e) {
                if (e.code == 404 || e.code == 409) {
                    post(() -> {
                        if (gen == loadGen) show(new ArrayList<JSONObject>());
                    });
                } else if (e.code == 401) {
                    showError(e);
                } else {
                    loadViaContents(p, b, gen);
                }
            } catch (Exception e) {
                loadViaContents(p, b, gen);
            }
        });
    }

    /** Fallback for huge repositories (the single tree answer is cut off) or if the tree call fails. */
    private void loadViaContents(final String p, final String b, final int gen) {
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
            post(() -> {
                if (gen == loadGen) show(tmp);
            });
        } catch (GitHubApi.ApiException e) {
            if (e.code == 404) {
                post(() -> {
                    if (gen == loadGen) show(new ArrayList<JSONObject>());
                });
            } else {
                showError(e);
            }
        } catch (Exception e) {
            showError(e);
        }
    }

    private void show(List<JSONObject> list) {
        allItems.clear();
        allItems.addAll(list);
        buildItems();
        // drop selections that no longer exist (renamed, deleted, other folder)
        Set<String> alive = new HashSet<>();
        for (JSONObject o : list) alive.add(o.optString("path"));
        selected.retainAll(alive);
        if (list.isEmpty()) selecting = false;
        loading(false);
        rebuildRows();
        applySelectUi();
        chipSelect.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        updateEmpty();
        ensureDates();
    }

    private void rebuildRows() {
        List<Row> rows = new ArrayList<>();
        for (JSONObject o : items) {
            boolean dir = isDir(o);
            String name = o.optString("name");
            String p = o.optString("path");
            boolean on = selected.contains(p);
            int slash = p.lastIndexOf('/');
            String parent = slash < 0 ? "" : p.substring(0, slash);
            boolean deep = !parent.equals(path);
            StringBuilder sub = new StringBuilder();
            if (deep) sub.append(parent.isEmpty() ? "/" : parent);
            if (!dir) {
                if (sub.length() > 0) sub.append(" \u00B7 ");
                sub.append(Fmt.size(o.optLong("size")));
            }
            long when = sortMode == 1 ? FileDates.cached(owner, repo, branch, p) : 0;
            if (when > 0) {
                if (sub.length() > 0) sub.append(" \u00B7 ");
                sub.append(Fmt.agoMs(when));
            }
            Row r = new Row(dir ? R.drawable.ic_folder : isImage(name) ? R.drawable.ic_image : R.drawable.ic_file, dir,
                    name, sub.length() == 0 ? null : sub.toString(), false, dir && !selecting);
            r.tint(Ui.color(this, dir ? R.color.accent_text : fileTint(name)));
            r.select(selecting, on);
            if (FileMarks.isFavorite(this, owner, repo, p)) r.badge("\u2605", Ui.color(this, R.color.warn));
            rows.add(r);
        }
        adapter.setRows(rows);
        gridAdapter.notifyDataSetChanged();
    }

    /** Soft colour per file kind so a long listing is easy to scan. */
    private static int fileTint(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String e = dot < 0 ? "" : n.substring(dot + 1);
        switch (e) {
            case "java": case "kt": case "kts": case "js": case "ts": case "py": case "c": case "cpp":
            case "h": case "go": case "rs": case "swift": case "php": case "html": case "css": case "sh":
                return R.color.info;
            case "yml": case "yaml": case "json": case "xml": case "gradle": case "toml":
            case "properties": case "pro":
                return R.color.warn;
            case "png": case "jpg": case "jpeg": case "gif": case "webp": case "svg": case "mp4":
            case "mp3": case "zip": case "apk":
                return R.color.ok;
            default:
                return R.color.text_secondary;
        }
    }

    // ---------- select mode ----------

    private void enterSelect(String firstPath) {
        selecting = true;
        selected.clear();
        if (firstPath != null) selected.add(firstPath);
        rebuildRows();
        applySelectUi();
    }

    private void exitSelect() {
        if (!selecting && selected.isEmpty()) return;
        selecting = false;
        selected.clear();
        rebuildRows();
        applySelectUi();
    }

    private void toggle(String p) {
        if (!selected.remove(p)) selected.add(p);
        rebuildRows();
        applySelectUi();
    }

    private void toggleAll() {
        boolean all = !items.isEmpty() && selected.size() == items.size();
        selected.clear();
        if (!all) for (JSONObject o : items) selected.add(o.optString("path"));
        rebuildRows();
        applySelectUi();
    }

    private void applySelectUi() {
        hintRow.setVisibility(selecting ? View.GONE : View.VISIBLE);
        Fx.show(selBar, selecting);
        Fx.show(deleteBar, selecting);
        applyWriteUi();
        if (!selecting) return;
        int n = selected.size();
        selCount.setText(n == 0 ? getString(R.string.sel_hint)
                : getString(R.string.sel_count, n, items.size()));
        selAll.setText(n > 0 && n == items.size() ? R.string.deselect_all : R.string.select_all);
        btnDownloadSel.setText(getString(R.string.dl_sel_btn, n));
        btnDownloadSel.setEnabled(n > 0);
        btnDownloadSel.setAlpha(n > 0 ? 1f : 0.45f);
        btnDeleteSel.setText(getString(R.string.sel_delete_btn, n));
        btnDeleteSel.setEnabled(n > 0);
        btnDeleteSel.setAlpha(n > 0 ? 1f : 0.45f);
    }

    // ---------- item actions ----------

    private void itemMenu(final JSONObject o) {
        final boolean dir = "dir".equals(o.optString("type"));
        final String name = o.optString("name");
        final String p = o.optString("path");
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.sel_mode));
        if (!dir) labels.add(getString(canWrite ? R.string.view_edit : R.string.view_only));
        final boolean fav = FileMarks.isFavorite(this, owner, repo, p);
        labels.add(getString(fav ? R.string.bs_fav_remove : R.string.bs_fav_add));
        if (!dir) labels.add(getString(R.string.df_history));
        if (!dir) labels.add(getString(R.string.download));
        if (dir) labels.add(getString(R.string.dl_folder));
        if (canWrite) labels.add(getString(R.string.rename_move));
        labels.add(getString(R.string.copy_path));
        labels.add(getString(R.string.copy_link));
        if (canWrite) labels.add(getString(R.string.delete));
        final String[] arr = labels.toArray(new String[0]);
        choose(name, arr, (d, which) -> {
            String chosen = arr[which];
            if (chosen.equals(getString(R.string.sel_mode))) {
                enterSelect(p);
            } else if (chosen.equals(getString(R.string.view_edit)) || chosen.equals(getString(R.string.view_only))) {
                openFile(p);
            } else if (chosen.equals(getString(R.string.bs_fav_add)) || chosen.equals(getString(R.string.bs_fav_remove))) {
                FileMarks.setFavorite(this, owner, repo, p, !fav);
                rebuildRows();
            } else if (chosen.equals(getString(R.string.df_history))) {
                Intent hi = repoIntent(CommitsActivity.class);
                hi.putExtra("path", p);
                startActivity(hi);
            } else if (chosen.equals(getString(R.string.download))) {
                saveAs(name, () -> api.openDownload(api.contentRawPath(owner, repo, p, branch),
                        "application/vnd.github.raw"));
            } else if (chosen.equals(getString(R.string.dl_folder))) {
                downloadPaths(java.util.Collections.singletonList(p), name);
            } else if (chosen.equals(getString(R.string.rename_move))) {
                if (requireWrite()) renameDialog(o);
            } else if (chosen.equals(getString(R.string.copy_path))) {
                copy("path", p);
            } else if (chosen.equals(getString(R.string.copy_link))) {
                copy("link", webUrl((dir ? "/tree/" : "/blob/") + branch + "/" + p));
            } else {
                if (requireWrite()) confirmDelete(o);
            }
        });
    }

    private void newMenu() {
        if (!requireWrite()) return;
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
                        if (n.isEmpty()) { Dlg.stay(dd); return; }
                        final String full = join(path, n);
                        if (which == 0) {
                            needsReload = true;
                            EditorActivity.launch(BrowserActivity.this, full, true);
                        } else {
                            showProgress(getString(R.string.working));
                            bg(() -> {
                                api.putFileContent(owner, repo, full + "/.gitkeep", new byte[0],
                                        "Create folder " + n, branch, null);
                                post(() -> {
                                    hideProgress();
                                    reloadFresh();
                                });
                            });
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
    }

    private void renameDialog(final JSONObject o) {
        if (!requireWrite()) return;
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
                    if (newPath.isEmpty() || newPath.equals(oldPath)) { Dlg.stay(d); return; }
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
                            reloadFresh();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void downloadSelected() {
        final List<String> chosen = new ArrayList<>();
        for (JSONObject o : items) if (selected.contains(o.optString("path"))) chosen.add(o.optString("path"));
        if (chosen.isEmpty()) {
            toast(R.string.sel_nothing);
            return;
        }
        downloadPaths(chosen, chosen.size() == 1 ? chosen.get(0).substring(chosen.get(0).lastIndexOf('/') + 1) : repo);
        exitSelect();
    }

    private void downloadPaths(final List<String> paths, String label) {
        String safe = label.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (safe.isEmpty()) safe = repo;
        final java.io.File dest = new java.io.File(TransferTasks.saveDir(repo), safe);
        final String b = branch;
        Transfers.start(getApplicationContext(), getString(R.string.dl_job_title, safe),
                "download:" + owner + "/" + repo,
                TransferTasks.downloadRepoPaths(api, owner, repo, b, new ArrayList<>(paths), dest),
                (job, ok, msg) -> {
                    String first = msg == null ? "" : msg;
                    int nl = first.indexOf('\n');
                    if (nl > 0) first = first.substring(0, nl);
                    Toast.makeText(job.app, ok ? first : job.app.getString(R.string.tr_failed) + ": " + first,
                            Toast.LENGTH_LONG).show();
                });
        Toast.makeText(this, R.string.tr_started_bg, Toast.LENGTH_LONG).show();
    }

    // ---------- upload ----------

    private void askUploadOptions(final Uri tree, final List<Uri> files) {
        if (!requireWrite()) {
            pickedRoots = null;
            return;
        }
        LinearLayout box = Ui.box(this);
        final EditText target = Ui.edit(this, getString(R.string.dlg_path_hint), path);
        target.setTextDirection(View.TEXT_DIRECTION_LTR);
        final EditText msg = Ui.edit(this, getString(R.string.default_commit), getString(R.string.default_commit));
        final CheckBox includeRoot = Ui.check(this, R.string.include_root_folder, false);
        final CheckBox skipJunk = Ui.check(this, R.string.upload_skip_junk, false);

        box.addView(Ui.field(this, getString(R.string.target_folder), target));
        box.addView(Ui.field(this, getString(R.string.commit_message), msg));
        if (pickedRoots != null && pickedFolder) {
            box.addView(includeRoot);
            box.addView(skipJunk);
        }

        new Dlg(this)
                .setTitle(R.string.upload)
                .setView(box)
                .setPositiveButton(R.string.upload, (d, w) -> startUpload(
                        normalize(target.getText().toString()),
                        msg.getText().toString().trim().isEmpty()
                                ? getString(R.string.default_commit) : msg.getText().toString().trim(),
                        includeRoot.isChecked(), pickedFolder && skipJunk.isChecked()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Hands the upload to the background service: it keeps running (with a progress notification)
     * when the user leaves this screen or the whole app.
     */
    private void startUpload(final String base, final String message, final boolean includeRoot,
                             final boolean skipJunk) {
        if (!requireWrite()) {
            pickedRoots = null;
            return;
        }
        final List<java.io.File> roots = pickedRoots;
        final boolean folderPick = pickedFolder;
        pickedRoots = null;
        if (roots == null || roots.isEmpty()) return;
        final String b = branch;
        final boolean incRoot = folderPick ? includeRoot : true;
        runPreview(roots, base, message, b, incRoot, skipJunk);
    }

    private volatile boolean previewCancel;

    private void runPreview(final List<java.io.File> roots, final String base, final String message, final String b,
                            final boolean incRoot, final boolean skipJunk) {
        previewCancel = false;
        showProgress(getString(R.string.fm_preview_scanning));
        io.execute(() -> {
            try {
                final TransferTasks.Preview p = TransferTasks.preview(api, getContentResolver(), owner, repo, b, base,
                        roots, incRoot, skipJunk, () -> previewCancel);
                post(() -> {
                    hideProgress();
                    StringBuilder sb = new StringBuilder();
                    sb.append(getString(R.string.fm_pv_files, p.files, Fmt.size(p.bytes))).append('\n');
                    sb.append(getString(R.string.fm_pv_states, p.fresh, p.changed, p.same)).append('\n');
                    sb.append(getString(R.string.fm_pv_upload, p.fresh + p.changed, Fmt.size(p.uploadBytes)));
                    if (p.ignored > 0 || p.tooBig > 0) {
                        sb.append('\n').append(getString(R.string.fm_pv_skipped, p.ignored, p.tooBig));
                    }
                    Dlg d = new Dlg(BrowserActivity.this);
                    d.setTitle(R.string.fm_pv_title);
                    d.setMessage(sb.toString());
                    if (p.fresh + p.changed > 0) {
                        d.setPositiveButton(R.string.upload, (x, w) -> beginUpload(roots, base, message, b, incRoot,
                                skipJunk));
                        d.setNegativeButton(R.string.cancel, null);
                    } else {
                        d.setPositiveButton(R.string.close, null);
                    }
                    d.show();
                });
            } catch (final Exception e) {
                showError(e, () -> runPreview(roots, base, message, b, incRoot, skipJunk));
            }
        });
    }

    private void beginUpload(final List<java.io.File> roots, final String base, final String message, final String b,
                             final boolean incRoot, final boolean skipJunk) {
        if (!requireWrite()) return;
        Transfers.start(getApplicationContext(), getString(R.string.tr_upload_title, repo),
                "upload:" + owner + "/" + repo,
                TransferTasks.upload(api, owner, repo, b, base, message, roots, incRoot, skipJunk),
                (job, ok, msg) -> {
                    android.content.Context app = job.app;
                    String first = msg == null ? "" : msg;
                    int nl = first.indexOf('\n');
                    if (nl > 0) first = first.substring(0, nl);
                    Toast.makeText(app, ok ? first : app.getString(R.string.tr_failed) + ": " + first,
                            Toast.LENGTH_LONG).show();
                });
        Toast.makeText(this, R.string.tr_started_bg, Toast.LENGTH_LONG).show();
    }

    // ---------- delete ----------

    private void confirmDelete(final JSONObject o) {
        if (!requireWrite()) return;
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
                            reloadFresh();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
    // ---------- delete several / all (one commit) ----------

    private void confirmDeleteSelected() {
        if (!requireWrite()) return;
        final List<JSONObject> chosen = new ArrayList<>();
        for (JSONObject o : items) if (selected.contains(o.optString("path"))) chosen.add(o);
        if (chosen.isEmpty()) {
            toast(R.string.sel_nothing);
            return;
        }
        final int n = chosen.size();
        boolean anyDir = false;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < n; i++) {
            JSONObject o = chosen.get(i);
            boolean dir = "dir".equals(o.optString("type"));
            if (dir) anyDir = true;
            if (i < 6) {
                if (i > 0) names.append('\n');
                names.append("\u2022 ").append(o.optString("name")).append(dir ? "/" : "");
            }
        }
        if (n > 6) names.append('\n').append(getString(R.string.sel_delete_more, n - 6));
        String msg = getString(R.string.sel_delete_msg, branch) + "\n\n" + names;
        if (anyDir) msg += "\n\n" + getString(R.string.sel_delete_dirs_note);
        new Dlg(this)
                .danger()
                .setTitle(getString(R.string.sel_delete_title, n))
                .setMessage(msg)
                .setPositiveButton(R.string.delete, (d, w) -> deleteChosen(chosen))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteChosen(final List<JSONObject> chosen) {
        if (!requireWrite()) return;
        if (busy) return;
        final int n = chosen.size();
        showProgress(getString(R.string.sel_delete_progress, n));
        busy = true;   // showProgress() clears the flag, so it is raised afterwards
        final String b = branch;
        bg(() -> {
            boolean anyDir = false;
            for (JSONObject o : chosen) if ("dir".equals(o.optString("type"))) anyDir = true;
            // one tree read covers every selected folder
            List<GitHubApi.BlobInfo> all = anyDir ? api.listAllBlobs(owner, repo, b) : null;
            List<String> paths = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JSONObject o : chosen) {
                String p = o.optString("path");
                if ("dir".equals(o.optString("type"))) {
                    String prefix = p + "/";
                    if (all != null) {
                        for (GitHubApi.BlobInfo bi : all) {
                            if (bi.path.startsWith(prefix) && seen.add(bi.path)) paths.add(bi.path);
                        }
                    }
                } else if (seen.add(p)) {
                    paths.add(p);
                }
            }
            if (!paths.isEmpty()) {
                List<GitHubApi.TreeEntry> entries = new ArrayList<>();
                for (String s : paths) entries.add(new GitHubApi.TreeEntry(s, null));
                String message = n == 1 ? "Delete " + chosen.get(0).optString("name")
                        : getString(R.string.sel_delete_msg_commit, n);
                api.commitEntries(owner, repo, b, entries, message);
            }
            final int files = paths.size();
            post(() -> {
                hideProgress();
                exitSelect();
                reloadFresh();
                Dlg.result(BrowserActivity.this, true, getString(R.string.done),
                        getString(R.string.sel_delete_done, n, files));
            });
        });
    }
}

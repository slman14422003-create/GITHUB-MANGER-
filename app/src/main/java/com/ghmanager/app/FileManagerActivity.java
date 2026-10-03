package com.ghmanager.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * In-app file manager (independent from the system one): browse every folder, search, sort,
 * multi-select, copy / move / delete / rename, zip + unzip, share, favorites, storage usage,
 * "largest files", text editing, APK install and image / APK thumbnails.
 */
public class FileManagerActivity extends AppCompatActivity {

    private static final int SORT_NAME = 0, SORT_DATE = 1, SORT_SIZE = 2, SORT_TYPE = 3;
    private static final int M_DIR = 0, M_SEARCH = 1, M_FAV = 2, M_LARGEST = 3;
    private static final int T_DIR = 0, T_IMG = 1, T_VID = 2, T_AUD = 3, T_ARC = 4, T_APK = 5,
            T_TXT = 6, T_PDF = 7, T_OTHER = 8;
    private static final int MAX_SEARCH = 300;
    private static final int MAX_LARGEST = 60;

    /** One row of the list, with everything precomputed off the UI thread. */
    private static class Entry {
        final File f;
        final String name;
        final boolean dir;
        final long size;
        final long mod;
        final int kids;
        final String ext;

        Entry(File f) {
            this.f = f;
            this.name = f.getName();
            this.dir = f.isDirectory();
            this.mod = f.lastModified();
            if (dir) {
                String[] l = f.list();
                kids = l == null ? -1 : l.length;
                size = 0;
                ext = "";
            } else {
                kids = 0;
                size = f.length();
                ext = extOf(name);
            }
        }
    }

    private static class Cancel extends IOException {
        Cancel() {
            super("cancelled");
        }
    }

    private interface Work {
        String run() throws Exception;
    }

    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final ExecutorService thumbIo = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US);

    private SharedPreferences prefs;
    private int sortMode = SORT_NAME;
    private boolean sortDesc = false;
    private boolean showHidden = false;

    private File cur;
    private int mode = M_DIR;
    private boolean lastGranted;
    private boolean listDenied = false;
    private String query = "";
    private boolean suppressSearch = false;
    private volatile long gen = 0;
    private volatile boolean cancelled = false;

    private final List<Entry> shown = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final List<File> clip = new ArrayList<>();
    private boolean clipCut = false;

    private ListView listView;
    private FileAdapter adapter;
    private TextView titleView, subtitleView, statusView, emptyView, selCount, pasteText, storageText;
    private ImageButton btnA1, btnA2, btnRefresh;
    private View loadingBar, selBar, pasteBar, permBanner, storageCard, crumbScroll;
    private EditText searchView;
    private LinearLayout placesRow, crumbRow;
    private android.widget.FrameLayout storageBarHolder;

    private AlertDialog busy;
    private TextView busyText;

    private ActivityResultLauncher<String> importLauncher;
    private final Runnable searchRun = () -> {
        if (query.isEmpty()) return;
        mode = M_SEARCH;
        refresh();
    };

    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_files);
        prefs = getSharedPreferences("fm", MODE_PRIVATE);
        sortMode = prefs.getInt("sort", SORT_NAME);
        sortDesc = prefs.getBoolean("desc", false);
        showHidden = prefs.getBoolean("hidden", false);

        titleView = findViewById(R.id.title);
        subtitleView = findViewById(R.id.subtitle);
        statusView = findViewById(R.id.status);
        emptyView = findViewById(R.id.empty);
        selCount = findViewById(R.id.selCount);
        pasteText = findViewById(R.id.pasteText);
        storageText = findViewById(R.id.storageText);
        storageBarHolder = findViewById(R.id.storageBarHolder);
        btnA1 = findViewById(R.id.btnA1);
        btnA2 = findViewById(R.id.btnA2);
        btnRefresh = findViewById(R.id.btnRefresh);
        loadingBar = findViewById(R.id.loading);
        selBar = findViewById(R.id.selBar);
        pasteBar = findViewById(R.id.pasteBar);
        permBanner = findViewById(R.id.permBanner);
        storageCard = findViewById(R.id.storageCard);
        crumbScroll = findViewById(R.id.crumbScroll);
        searchView = findViewById(R.id.search);
        placesRow = findViewById(R.id.placesRow);
        crumbRow = findViewById(R.id.crumbRow);
        listView = findViewById(R.id.list);
        if (loadingBar instanceof ProgressBar) Ui.tint(this, (ProgressBar) loadingBar);

        adapter = new FileAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, pos, id) -> onItemClick(pos));
        listView.setOnItemLongClickListener((p, v, pos, id) -> {
            toggleSelect(pos);
            return true;
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        action(btnA1, R.drawable.ic_sort, R.string.fm_sort, v -> sortMenu());
        action(btnA2, R.drawable.ic_add, R.string.fm_new, v -> newMenu());
        btnRefresh.setOnClickListener(v -> refresh());

        findViewById(R.id.selClose).setOnClickListener(v -> clearSelection());
        findViewById(R.id.selAll).setOnClickListener(v -> selectAll());
        findViewById(R.id.selCopy).setOnClickListener(v -> toClipboard(false));
        findViewById(R.id.selCut).setOnClickListener(v -> toClipboard(true));
        findViewById(R.id.selDelete).setOnClickListener(v -> deleteSelected());
        findViewById(R.id.selMore).setOnClickListener(v -> moreMenu());
        findViewById(R.id.pasteGo).setOnClickListener(v -> paste());
        findViewById(R.id.pasteCancel).setOnClickListener(v -> {
            clip.clear();
            updatePasteBar();
        });
        findViewById(R.id.permGrant).setOnClickListener(v -> Perms.requestAllFiles(this));

        searchView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (suppressSearch) return;
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                ui.removeCallbacks(searchRun);
                if (query.isEmpty()) {
                    if (mode == M_SEARCH) {
                        mode = M_DIR;
                        refresh();
                    }
                } else {
                    ui.postDelayed(searchRun, 350);
                }
            }
        });

        importLauncher = registerForActivityResult(new ActivityResultContracts.GetMultipleContents(),
                uris -> {
                    if (uris != null && !uris.isEmpty()) importUris(uris);
                });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBack();
            }
        });

        lastGranted = Perms.hasAllFiles(this);
        File start = null;
        String fromIntent = getIntent().getStringExtra("path");
        if (fromIntent != null && new File(fromIntent).isDirectory()) start = new File(fromIntent);
        String last = prefs.getString("last", null);
        if (start == null && lastGranted && last != null && new File(last).isDirectory()) {
            start = new File(last);
        }
        if (start == null) start = defaultRoot();
        cur = start;
        buildPlaces();
        updatePasteBar();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean g = Perms.hasAllFiles(this);
        if (g != lastGranted) {
            lastGranted = g;
            File target = g ? Environment.getExternalStorageDirectory() : appDir();
            navigate(target);
        }
        updatePermBanner();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        thumbIo.shutdownNow();
    }

    private void action(ImageButton b, int icon, int desc, View.OnClickListener l) {
        b.setImageResource(icon);
        b.setContentDescription(getString(desc));
        b.setVisibility(View.VISIBLE);
        b.setOnClickListener(l);
    }

    private void post(Runnable r) {
        ui.post(() -> {
            if (!isFinishing() && !isDestroyed()) r.run();
        });
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void loading(boolean on) {
        loadingBar.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    // ------------------------------------------------------------------ locations

    private File appDir() {
        File d = getExternalFilesDir(null);
        if (d == null) d = getFilesDir();
        return d;
    }

    private File defaultRoot() {
        return Perms.hasAllFiles(this) ? Environment.getExternalStorageDirectory() : appDir();
    }

    private List<File> sdRoots() {
        List<File> out = new ArrayList<>();
        File[] dirs = getExternalFilesDirs(null);
        if (dirs == null) return out;
        for (int i = 1; i < dirs.length; i++) {
            if (dirs[i] == null) continue;
            String p = dirs[i].getAbsolutePath();
            int k = p.indexOf("/Android/data");
            if (k > 0) {
                File r = new File(p.substring(0, k));
                if (r.exists()) out.add(r);
            }
        }
        return out;
    }

    private boolean isVolumeRoot(File f) {
        if (f.equals(Environment.getExternalStorageDirectory()) || f.equals(appDir())) return true;
        for (File r : sdRoots()) if (r.equals(f)) return true;
        return f.getParentFile() == null;
    }

    private static boolean isInside(File child, File parent) {
        String c = child.getAbsolutePath();
        String p = parent.getAbsolutePath();
        return c.equals(p) || c.startsWith(p.endsWith("/") ? p : p + "/");
    }

    private void navigate(File dir) {
        clearSelectionQuiet();
        ui.removeCallbacks(searchRun);
        suppressSearch = true;
        searchView.setText("");
        suppressSearch = false;
        query = "";
        mode = M_DIR;
        cur = dir;
        prefs.edit().putString("last", dir.getAbsolutePath()).apply();
        refresh();
    }

    private void onBack() {
        if (!selected.isEmpty()) {
            clearSelection();
            return;
        }
        if (mode != M_DIR || !query.isEmpty()) {
            navigate(cur);
            return;
        }
        if (!isVolumeRoot(cur)) {
            File p = cur.getParentFile();
            if (p != null && p.canRead()) {
                navigate(p);
                return;
            }
        }
        finish();
    }

    // ------------------------------------------------------------------ loading

    private void refresh() {
        switch (mode) {
            case M_SEARCH:
                runSearch();
                break;
            case M_FAV:
                showFavorites();
                break;
            case M_LARGEST:
                showLargest();
                break;
            default:
                load();
                break;
        }
        buildPlaces();
        updateChrome();
    }

    private Comparator<Entry> comparator() {
        final int m = sortMode;
        final boolean d = sortDesc;
        return (a, b) -> {
            if (a.dir != b.dir) return a.dir ? -1 : 1;
            int r;
            switch (m) {
                case SORT_DATE:
                    r = Long.compare(a.mod, b.mod);
                    break;
                case SORT_SIZE:
                    r = Long.compare(a.size, b.size);
                    break;
                case SORT_TYPE:
                    r = a.ext.compareTo(b.ext);
                    if (r == 0) r = a.name.compareToIgnoreCase(b.name);
                    break;
                default:
                    r = a.name.compareToIgnoreCase(b.name);
                    break;
            }
            return d ? -r : r;
        };
    }

    private void load() {
        final long my = ++gen;
        final File dir = cur;
        loading(true);
        io.execute(() -> {
            File[] arr = dir.listFiles();
            final boolean denied = arr == null;
            final List<Entry> out = new ArrayList<>();
            if (arr != null) {
                for (File f : arr) {
                    if (!showHidden && f.getName().startsWith(".")) continue;
                    out.add(new Entry(f));
                }
            }
            Collections.sort(out, comparator());
            post(() -> {
                if (my != gen) return;
                listDenied = denied;
                setShown(out);
                loading(false);
            });
        });
    }

    private void runSearch() {
        final long my = ++gen;
        final File dir = cur;
        final String q = query;
        loading(true);
        io.execute(() -> {
            List<Entry> out = new ArrayList<>();
            walkSearch(dir, q, out, my, 0);
            Collections.sort(out, comparator());
            final List<Entry> res = out;
            post(() -> {
                if (my != gen) return;
                listDenied = false;
                setShown(res);
                loading(false);
            });
        });
    }

    private void walkSearch(File dir, String q, List<Entry> out, long my, int depth) {
        if (my != gen || out.size() >= MAX_SEARCH || depth > 14) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (my != gen || out.size() >= MAX_SEARCH) return;
            String n = f.getName();
            if (!showHidden && n.startsWith(".")) continue;
            if (n.toLowerCase(Locale.ROOT).contains(q)) out.add(new Entry(f));
            if (f.isDirectory() && !isLink(f)) walkSearch(f, q, out, my, depth + 1);
        }
    }

    private static boolean isLink(File f) {
        try {
            return !f.getCanonicalPath().equals(f.getAbsolutePath());
        } catch (IOException e) {
            return true;
        }
    }

    private void showFavorites() {
        final long my = ++gen;
        loading(true);
        final Set<String> favs = new LinkedHashSet<>(prefs.getStringSet("fav", new LinkedHashSet<String>()));
        io.execute(() -> {
            List<Entry> out = new ArrayList<>();
            for (String p : favs) {
                File f = new File(p);
                if (f.exists()) out.add(new Entry(f));
            }
            Collections.sort(out, comparator());
            final List<Entry> res = out;
            post(() -> {
                if (my != gen) return;
                listDenied = false;
                setShown(res);
                loading(false);
            });
        });
    }

    private void showLargest() {
        final long my = ++gen;
        final File dir = cur;
        loading(true);
        io.execute(() -> {
            PriorityQueue<Entry> heap = new PriorityQueue<>(MAX_LARGEST + 1, (a, b) -> Long.compare(a.size, b.size));
            int[] budget = {250000};
            walkLargest(dir, heap, budget, my, 0);
            final List<Entry> res = new ArrayList<>(heap);
            Collections.sort(res, (a, b) -> Long.compare(b.size, a.size));
            post(() -> {
                if (my != gen) return;
                listDenied = false;
                setShown(res);
                loading(false);
            });
        });
    }

    private void walkLargest(File dir, PriorityQueue<Entry> heap, int[] budget, long my, int depth) {
        if (my != gen || budget[0] <= 0 || depth > 20) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (my != gen || budget[0] <= 0) return;
            if (!showHidden && f.getName().startsWith(".")) continue;
            budget[0]--;
            if (f.isDirectory()) {
                if (!isLink(f)) walkLargest(f, heap, budget, my, depth + 1);
            } else {
                long len = f.length();
                if (heap.size() < MAX_LARGEST || len > heap.peek().size) {
                    heap.add(new Entry(f));
                    if (heap.size() > MAX_LARGEST) heap.poll();
                }
            }
        }
    }

    private void setShown(List<Entry> list) {
        shown.clear();
        shown.addAll(list);
        // drop selections that no longer exist
        Set<String> alive = new LinkedHashSet<>();
        for (Entry e : shown) alive.add(e.f.getAbsolutePath());
        selected.retainAll(alive);
        adapter.notifyDataSetChanged();
        updateChrome();
        boolean empty = shown.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            int msg;
            if (mode == M_SEARCH) msg = R.string.fm_no_results;
            else if (mode == M_FAV) msg = R.string.fm_no_favs;
            else if (listDenied) msg = R.string.fm_denied;
            else msg = R.string.fm_empty;
            emptyView.setText(msg);
        }
    }

    // ------------------------------------------------------------------ header / chrome

    private String folderLabel(File f) {
        if (f.equals(Environment.getExternalStorageDirectory())) return getString(R.string.fm_internal);
        String n = f.getName();
        return n.isEmpty() ? "/" : n;
    }

    private void updateChrome() {
        switch (mode) {
            case M_SEARCH:
                titleView.setText(getString(R.string.fm_search_results, shown.size()));
                setSubtitle(cur.getAbsolutePath());
                break;
            case M_FAV:
                titleView.setText(R.string.fm_favorites);
                setSubtitle(null);
                break;
            case M_LARGEST:
                titleView.setText(R.string.fm_largest);
                setSubtitle(cur.getAbsolutePath());
                break;
            default:
                titleView.setText(folderLabel(cur));
                setSubtitle(cur.getAbsolutePath());
                break;
        }
        boolean dirMode = mode == M_DIR;
        crumbScroll.setVisibility(dirMode ? View.VISIBLE : View.GONE);
        storageCard.setVisibility(dirMode ? View.VISIBLE : View.GONE);
        if (dirMode) {
            buildCrumbs();
            updateStorage();
        }
        boolean sel = !selected.isEmpty();
        selBar.setVisibility(sel ? View.VISIBLE : View.GONE);
        searchView.setVisibility(sel ? View.GONE : View.VISIBLE);
        if (sel) selCount.setText(getString(R.string.fm_selected_n, selected.size()));
        updatePermBanner();
        updatePasteBar();
        adapter.notifyDataSetChanged();
    }

    private void setSubtitle(String s) {
        if (s == null || s.isEmpty()) {
            subtitleView.setVisibility(View.GONE);
        } else {
            subtitleView.setText(s);
            subtitleView.setVisibility(View.VISIBLE);
        }
    }

    private void updatePermBanner() {
        boolean need = !Perms.hasAllFiles(this) && !isInside(cur, appDir());
        permBanner.setVisibility(need ? View.VISIBLE : View.GONE);
    }

    private void updatePasteBar() {
        boolean show = !clip.isEmpty() && selected.isEmpty();
        pasteBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            pasteText.setText(getString(clipCut ? R.string.fm_clip_cut_n : R.string.fm_clip_copy_n, clip.size()));
            findViewById(R.id.pasteGo).setEnabled(mode == M_DIR);
        }
    }

    private void buildCrumbs() {
        crumbRow.removeAllViews();
        File internal = Environment.getExternalStorageDirectory();
        List<File> chain = new ArrayList<>();
        File f = cur;
        while (f != null) {
            chain.add(0, f);
            if (f.equals(internal)) break;
            f = f.getParentFile();
        }
        for (int i = 0; i < chain.size(); i++) {
            final File target = chain.get(i);
            if (i > 0) {
                TextView sep = new TextView(this);
                sep.setText("›");
                sep.setTextColor(Ui.color(this, R.color.text_hint));
                sep.setTextSize(16);
                sep.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
                crumbRow.addView(sep);
            }
            TextView c = new TextView(this);
            String label = target.equals(internal) ? getString(R.string.fm_internal)
                    : (target.getName().isEmpty() ? "/" : target.getName());
            c.setText(label);
            c.setSingleLine(true);
            c.setTextSize(13);
            boolean last = i == chain.size() - 1;
            c.setTextColor(Ui.color(this, last ? R.color.text_primary : R.color.accent_text));
            c.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
            if (!last) c.setOnClickListener(v -> navigate(target));
            crumbRow.addView(c);
        }
        crumbScroll.post(() -> ((HorizontalScrollView) crumbScroll).fullScroll(View.FOCUS_RIGHT));
    }

    private void updateStorage() {
        storageBarHolder.removeAllViews();
        try {
            File probe = cur.exists() ? cur : Environment.getExternalStorageDirectory();
            StatFs st = new StatFs(probe.getAbsolutePath());
            long total = st.getTotalBytes();
            long free = st.getAvailableBytes();
            long used = Math.max(0, total - free);
            if (total <= 0) throw new IllegalStateException();
            double pct = used * 100.0 / total;
            storageText.setText(getString(R.string.fm_storage_line, Fmt.size(used), Fmt.size(total), Fmt.size(free)));
            storageBarHolder.addView(Ui.bar(this, pct, pct > 90 ? R.color.bad : R.color.accent));
            storageCard.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            storageCard.setVisibility(View.GONE);
        }
    }

    private void buildPlaces() {
        placesRow.removeAllViews();
        addPlace(getString(R.string.fm_internal), Environment.getExternalStorageDirectory());
        addPlace(getString(R.string.fm_downloads), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        addPlace(getString(R.string.fm_dcim), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM));
        addPlace(getString(R.string.fm_pictures), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES));
        addPlace(getString(R.string.fm_documents), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS));
        addPlace(getString(R.string.fm_music), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC));
        addPlace(getString(R.string.fm_movies), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES));
        int n = 1;
        for (File sd : sdRoots()) {
            addPlace(getString(R.string.fm_sdcard) + (n > 1 ? " " + n : ""), sd);
            n++;
        }
        addPlace(getString(R.string.fm_app_folder), appDir());

        TextView fav = Ui.chip(this, getString(R.string.fm_favorites), mode == M_FAV);
        fav.setOnClickListener(v -> {
            clearSelectionQuiet();
            mode = M_FAV;
            refresh();
        });
        placesRow.addView(fav);

        TextView big = Ui.chip(this, getString(R.string.fm_largest), mode == M_LARGEST);
        big.setOnClickListener(v -> {
            clearSelectionQuiet();
            mode = M_LARGEST;
            refresh();
        });
        placesRow.addView(big);
    }

    private void addPlace(String label, final File dir) {
        if (dir == null || !dir.exists()) return;
        TextView c = Ui.chip(this, label, mode == M_DIR && cur.equals(dir));
        c.setOnClickListener(v -> navigate(dir));
        placesRow.addView(c);
    }

    // ------------------------------------------------------------------ types / icons

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 || i == name.length() - 1 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean in(String ext, String... list) {
        return Arrays.asList(list).contains(ext);
    }

    private static int typeOf(Entry e) {
        if (e.dir) return T_DIR;
        String x = e.ext;
        if (in(x, "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")) return T_IMG;
        if (in(x, "mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v")) return T_VID;
        if (in(x, "mp3", "wav", "ogg", "m4a", "aac", "flac", "opus", "amr")) return T_AUD;
        if (in(x, "zip", "rar", "7z", "tar", "gz", "tgz", "xz", "bz2", "jar")) return T_ARC;
        if (x.equals("apk")) return T_APK;
        if (x.equals("pdf")) return T_PDF;
        if (isTextExt(x)) return T_TXT;
        return T_OTHER;
    }

    private static boolean isTextExt(String x) {
        return in(x, "txt", "md", "json", "xml", "yml", "yaml", "java", "kt", "kts", "gradle",
                "properties", "js", "ts", "html", "htm", "css", "py", "sh", "c", "cpp", "h", "hpp", "cs",
                "go", "rs", "php", "rb", "sql", "csv", "log", "ini", "cfg", "conf", "toml", "gitignore",
                "pro", "bat", "svg", "tsx", "jsx", "dart", "swift", "lua", "env", "gitattributes");
    }

    private static int iconFor(int t) {
        switch (t) {
            case T_DIR:
                return R.drawable.ic_folder;
            case T_IMG:
                return R.drawable.ic_image;
            case T_VID:
                return R.drawable.ic_video;
            case T_AUD:
                return R.drawable.ic_music;
            case T_ARC:
                return R.drawable.ic_archive;
            case T_APK:
                return R.drawable.ic_package;
            case T_TXT:
                return R.drawable.ic_code;
            default:
                return R.drawable.ic_file;
        }
    }

    private static int colorFor(int t) {
        switch (t) {
            case T_DIR:
                return R.color.accent_text;
            case T_IMG:
                return R.color.ok;
            case T_VID:
                return R.color.bad;
            case T_AUD:
                return R.color.info;
            case T_ARC:
                return R.color.warn;
            case T_APK:
                return R.color.ok;
            case T_TXT:
                return R.color.accent_text;
            case T_PDF:
                return R.color.bad;
            default:
                return R.color.text_secondary;
        }
    }

    private static String mimeOf(File f) {
        String x = extOf(f.getName());
        if (isTextExt(x) && !x.equals("svg") && !x.equals("html") && !x.equals("htm") && !x.equals("json")
                && !x.equals("xml") && !x.equals("csv")) return "text/plain";
        String m = x.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(x);
        return m == null ? "*/*" : m;
    }

    private Set<String> favs() {
        return new LinkedHashSet<>(prefs.getStringSet("fav", new LinkedHashSet<String>()));
    }

    // ------------------------------------------------------------------ list adapter

    private class FileAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_file, parent, false);
            final Entry e = shown.get(pos);
            final String path = e.f.getAbsolutePath();
            boolean sel = selected.contains(path);
            int type = typeOf(e);
            int color = Ui.color(FileManagerActivity.this, colorFor(type));

            ImageView icon = v.findViewById(R.id.icon);
            ImageView thumb = v.findViewById(R.id.thumb);
            icon.setImageResource(iconFor(type));
            icon.setImageTintList(ColorStateList.valueOf(color));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Ui.dp(FileManagerActivity.this, 12));
            g.setColor((color & 0x00FFFFFF) | 0x26000000);
            icon.setBackground(g);
            thumb.setBackground(g);
            thumb.setClipToOutline(true);
            thumb.setTag(path);
            Bitmap cached = (type == T_IMG || type == T_APK) ? thumbs.get(path + "|" + e.mod) : null;
            if (cached != null) {
                thumb.setImageBitmap(cached);
                thumb.setVisibility(View.VISIBLE);
                icon.setVisibility(View.INVISIBLE);
            } else {
                thumb.setVisibility(View.GONE);
                icon.setVisibility(View.VISIBLE);
                if (type == T_IMG || type == T_APK) loadThumb(e, type, thumb, icon);
            }

            ((TextView) v.findViewById(R.id.title)).setText(e.name);
            StringBuilder sub = new StringBuilder();
            if (e.dir) {
                sub.append(e.kids < 0 ? getString(R.string.fm_unreadable) : getString(R.string.fm_items_n, e.kids));
            } else {
                sub.append(Fmt.size(e.size));
            }
            if (mode == M_DIR) {
                sub.append(" · ").append(dateFmt.format(new Date(e.mod)));
            } else {
                File p = e.f.getParentFile();
                if (p != null) sub.append(" · ").append(p.getAbsolutePath());
            }
            ((TextView) v.findViewById(R.id.sub)).setText(sub.toString());

            v.findViewById(R.id.fav).setVisibility(favs().contains(path) ? View.VISIBLE : View.GONE);
            v.findViewById(R.id.check).setVisibility(sel ? View.VISIBLE : View.GONE);
            v.findViewById(R.id.chevron).setVisibility(!sel && e.dir ? View.VISIBLE : View.GONE);
            Ui.shapeRow(FileManagerActivity.this, v, pos == 0, pos == shown.size() - 1,
                    sel ? R.color.accent_soft : R.color.surface);
            return v;
        }
    }

    private void loadThumb(final Entry e, final int type, final ImageView thumb, final ImageView icon) {
        final String path = e.f.getAbsolutePath();
        final String key = path + "|" + e.mod;
        thumbIo.execute(() -> {
            Bitmap b = null;
            try {
                if (type == T_IMG) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(path, o);
                    int s = 1;
                    int max = Math.max(o.outWidth, o.outHeight);
                    while (max / s > 192) s *= 2;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = Math.max(1, s);
                    b = BitmapFactory.decodeFile(path, o2);
                } else {
                    PackageManager pm = getPackageManager();
                    PackageInfo pi = pm.getPackageArchiveInfo(path, 0);
                    if (pi != null && pi.applicationInfo != null) {
                        ApplicationInfo ai = pi.applicationInfo;
                        ai.sourceDir = path;
                        ai.publicSourceDir = path;
                        Drawable d = ai.loadIcon(pm);
                        int w = Math.max(1, d.getIntrinsicWidth());
                        int h = Math.max(1, d.getIntrinsicHeight());
                        b = Bitmap.createBitmap(Math.min(w, 192), Math.min(h, 192), Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(b);
                        d.setBounds(0, 0, c.getWidth(), c.getHeight());
                        d.draw(c);
                    }
                }
            } catch (Throwable ignored) {
            }
            if (b == null) return;
            final Bitmap fb = b;
            thumbs.put(key, fb);
            post(() -> {
                if (path.equals(thumb.getTag())) {
                    thumb.setImageBitmap(fb);
                    thumb.setVisibility(View.VISIBLE);
                    icon.setVisibility(View.INVISIBLE);
                }
            });
        });
    }

    // ------------------------------------------------------------------ selection

    private void onItemClick(int pos) {
        if (pos < 0 || pos >= shown.size()) return;
        if (!selected.isEmpty()) {
            toggleSelect(pos);
            return;
        }
        Entry e = shown.get(pos);
        if (e.dir) {
            navigate(e.f);
        } else {
            openFile(e);
        }
    }

    private void toggleSelect(int pos) {
        if (pos < 0 || pos >= shown.size()) return;
        String p = shown.get(pos).f.getAbsolutePath();
        if (!selected.remove(p)) selected.add(p);
        updateChrome();
    }

    private void selectAll() {
        if (selected.size() == shown.size()) {
            selected.clear();
        } else {
            for (Entry e : shown) selected.add(e.f.getAbsolutePath());
        }
        updateChrome();
    }

    private void clearSelection() {
        selected.clear();
        updateChrome();
    }

    private void clearSelectionQuiet() {
        selected.clear();
    }

    private List<File> selectedFiles() {
        List<File> out = new ArrayList<>();
        for (Entry e : shown) if (selected.contains(e.f.getAbsolutePath())) out.add(e.f);
        return out;
    }

    private void toClipboard(boolean cut) {
        List<File> files = selectedFiles();
        if (files.isEmpty()) return;
        clip.clear();
        clip.addAll(files);
        clipCut = cut;
        selected.clear();
        updateChrome();
        toast(R.string.fm_clip_hint);
    }

    // ------------------------------------------------------------------ opening

    private void openFile(Entry e) {
        int t = typeOf(e);
        if (t == T_APK) {
            Perms.installApk(this, e.f);
            return;
        }
        if (e.ext.equals("zip") || e.ext.equals("jar")) {
            zipMenu(e.f);
            return;
        }
        if (t == T_TXT) {
            Intent i = new Intent(this, FileEditActivity.class);
            i.putExtra("path", e.f.getAbsolutePath());
            startActivity(i);
            return;
        }
        openExternal(e.f, false);
    }

    private void openExternal(File f, boolean chooser) {
        try {
            Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, mimeOf(f));
            i.setClipData(ClipData.newRawUri("", u));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(chooser ? Intent.createChooser(i, f.getName()) : i);
        } catch (Exception ex) {
            toast(R.string.fm_no_app);
        }
    }

    private void zipMenu(final File zip) {
        String[] items = {getString(R.string.fm_zip_view), getString(R.string.fm_extract_here),
                getString(R.string.fm_extract_folder), getString(R.string.fm_open_with)};
        new AlertDialog.Builder(this).setTitle(zip.getName()).setItems(items, (d, which) -> {
            if (which == 0) listZip(zip);
            else if (which == 1) extract(zip, false);
            else if (which == 2) extract(zip, true);
            else openExternal(zip, true);
        }).show();
    }

    private void listZip(final File zip) {
        loading(true);
        io.execute(() -> {
            final StringBuilder sb = new StringBuilder();
            String err = null;
            try (ZipFile zf = new ZipFile(zip)) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                int n = 0;
                int total = zf.size();
                while (en.hasMoreElements() && n < 300) {
                    ZipEntry ze = en.nextElement();
                    sb.append(ze.isDirectory() ? "▸ " : "• ").append(ze.getName());
                    if (!ze.isDirectory() && ze.getSize() >= 0) sb.append("  (").append(Fmt.size(ze.getSize())).append(")");
                    sb.append('\n');
                    n++;
                }
                if (total > n) sb.append("…  +").append(total - n);
            } catch (Exception ex) {
                err = String.valueOf(ex.getMessage());
            }
            final String e2 = err;
            post(() -> {
                loading(false);
                info(zip.getName(), e2 != null ? e2 : sb.toString());
            });
        });
    }

    private void info(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    // ------------------------------------------------------------------ menus

    private void sortMenu() {
        String[] names = {getString(R.string.fm_sort_name), getString(R.string.fm_sort_date),
                getString(R.string.fm_sort_size), getString(R.string.fm_sort_type)};
        List<String> items = new ArrayList<>();
        for (int i = 0; i < names.length; i++) items.add((i == sortMode ? "● " : "○ ") + names[i]);
        items.add(getString(sortDesc ? R.string.fm_order_desc : R.string.fm_order_asc));
        items.add(getString(showHidden ? R.string.fm_hide_hidden : R.string.fm_show_hidden));
        new AlertDialog.Builder(this).setTitle(R.string.fm_sort)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which < 4) {
                        sortMode = which;
                    } else if (which == 4) {
                        sortDesc = !sortDesc;
                    } else {
                        showHidden = !showHidden;
                    }
                    prefs.edit().putInt("sort", sortMode).putBoolean("desc", sortDesc)
                            .putBoolean("hidden", showHidden).apply();
                    refresh();
                }).show();
    }

    private void newMenu() {
        String[] items = {getString(R.string.fm_new_folder), getString(R.string.fm_new_file),
                getString(R.string.fm_import_files)};
        new AlertDialog.Builder(this).setTitle(R.string.fm_new).setItems(items, (d, which) -> {
            if (mode != M_DIR) {
                toast(R.string.fm_open_folder_first);
                return;
            }
            if (which == 0) newItemDialog(true);
            else if (which == 1) newItemDialog(false);
            else importLauncher.launch("*/*");
        }).show();
    }

    private void newItemDialog(final boolean folder) {
        LinearLayout box = Ui.box(this);
        final EditText name = Ui.edit(this, getString(R.string.fm_name_hint), null);
        box.addView(name);
        new AlertDialog.Builder(this)
                .setTitle(folder ? R.string.fm_new_folder : R.string.fm_new_file)
                .setView(box)
                .setPositiveButton(R.string.create, (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (!validName(n)) {
                        toast(R.string.fm_bad_name);
                        return;
                    }
                    File t = new File(cur, n);
                    if (t.exists()) {
                        toast(R.string.fm_exists);
                        return;
                    }
                    boolean ok;
                    try {
                        ok = folder ? t.mkdirs() : t.createNewFile();
                    } catch (IOException ex) {
                        ok = false;
                    }
                    if (!ok) toast(R.string.fm_create_failed);
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static boolean validName(String n) {
        return !n.isEmpty() && !n.contains("/") && !n.equals(".") && !n.equals("..") && n.length() <= 200;
    }

    private void moreMenu() {
        final List<File> files = selectedFiles();
        if (files.isEmpty()) return;
        final boolean single = files.size() == 1;
        final File one = files.get(0);
        final boolean isZip = single && !one.isDirectory() && (extOf(one.getName()).equals("zip"));
        final boolean anyFile = hasFile(files);
        final boolean allFav = favs().containsAll(paths(files));

        final List<String> labels = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        if (single && !one.isDirectory()) add(labels, ids, getString(R.string.fm_open_with), 0);
        if (single) add(labels, ids, getString(R.string.fm_rename), 1);
        add(labels, ids, getString(R.string.fm_details), 2);
        if (anyFile) add(labels, ids, getString(R.string.share), 3);
        add(labels, ids, getString(R.string.fm_compress), 4);
        if (isZip) {
            add(labels, ids, getString(R.string.fm_extract_here), 5);
            add(labels, ids, getString(R.string.fm_extract_folder), 6);
        }
        add(labels, ids, getString(allFav ? R.string.fm_unfavorite : R.string.fm_favorite), 7);
        if (single) add(labels, ids, getString(R.string.fm_copy_path), 8);

        new AlertDialog.Builder(this).setTitle(getString(R.string.fm_selected_n, files.size()))
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    switch (ids.get(which)) {
                        case 0:
                            openExternal(one, true);
                            break;
                        case 1:
                            renameDialog(one);
                            break;
                        case 2:
                            showDetails(files);
                            break;
                        case 3:
                            share(files);
                            break;
                        case 4:
                            compress(files);
                            break;
                        case 5:
                            extract(one, false);
                            break;
                        case 6:
                            extract(one, true);
                            break;
                        case 7:
                            toggleFav(files, allFav);
                            break;
                        default:
                            copyText(one.getAbsolutePath());
                            break;
                    }
                }).show();
    }

    private static void add(List<String> labels, List<Integer> ids, String l, int id) {
        labels.add(l);
        ids.add(id);
    }

    private static boolean hasFile(List<File> files) {
        for (File f : files) if (f.isFile()) return true;
        return false;
    }

    private static List<String> paths(List<File> files) {
        List<String> out = new ArrayList<>();
        for (File f : files) out.add(f.getAbsolutePath());
        return out;
    }

    private void toggleFav(List<File> files, boolean remove) {
        Set<String> s = favs();
        if (remove) s.removeAll(paths(files));
        else s.addAll(paths(files));
        prefs.edit().putStringSet("fav", s).apply();
        clearSelection();
        if (mode == M_FAV) refresh();
        toast(remove ? R.string.fm_unfavorited : R.string.fm_favorited);
    }

    private void copyText(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("path", text));
            toast(R.string.copied);
        }
    }

    // ------------------------------------------------------------------ operations

    private void error(Exception e) {
        if (e instanceof Cancel) {
            toast(R.string.fm_cancelled);
            return;
        }
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        info(getString(R.string.error), m);
    }

    private void showBusy(String text) {
        hideBusy();
        cancelled = false;
        LinearLayout box = Ui.box(this);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 14), Ui.dp(this, 22), Ui.dp(this, 8));
        busyText = new TextView(this);
        busyText.setText(text);
        busyText.setSingleLine(true);
        busyText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        busyText.setTextColor(Ui.color(this, R.color.text_secondary));
        busyText.setTextSize(14);
        busyText.setPadding(0, 0, 0, Ui.dp(this, 12));
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        Ui.tint(this, bar);
        box.addView(busyText);
        box.addView(bar);
        busy = new AlertDialog.Builder(this).setTitle(R.string.working).setView(box)
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, (d, w) -> cancelled = true)
                .create();
        busy.show();
    }

    private void setBusy(final String t) {
        post(() -> {
            if (busyText != null) busyText.setText(t);
        });
    }

    private void hideBusy() {
        if (busy != null) {
            try {
                busy.dismiss();
            } catch (Exception ignored) {
            }
            busy = null;
        }
        busyText = null;
    }

    private void runTask(String text, final Work w) {
        showBusy(text);
        io.execute(() -> {
            String msg = null;
            Exception err = null;
            try {
                msg = w.run();
            } catch (Exception e) {
                err = e;
            }
            final String m = msg;
            final Exception er = err;
            post(() -> {
                hideBusy();
                if (er != null) error(er);
                else if (m != null) toast(m);
                refresh();
            });
        });
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name;
        String ext = "";
        int i = name.lastIndexOf('.');
        if (i > 0) {
            base = name.substring(0, i);
            ext = name.substring(i);
        }
        int n = 1;
        while (true) {
            File c = new File(dir, base + " (" + n + ")" + ext);
            if (!c.exists()) return c;
            n++;
        }
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    private void copyTree(File src, File dst) throws IOException {
        if (cancelled) throw new Cancel();
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new IOException(getString(R.string.fm_err_mkdir, dst.getName()));
            }
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dst, k.getName()));
        } else {
            setBusy(src.getName());
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled) throw new Cancel();
                    out.write(buf, 0, n);
                }
            }
        }
    }

    private void deleteTree(File f) throws IOException {
        if (cancelled) throw new Cancel();
        if (f.isDirectory() && !isLink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        setBusy(f.getName());
        if (!f.delete() && f.exists()) throw new IOException(getString(R.string.fm_err_delete, f.getName()));
    }

    private void paste() {
        if (clip.isEmpty() || mode != M_DIR) return;
        final File dest = cur;
        final boolean cut = clipCut;
        final List<File> src = new ArrayList<>(clip);
        clip.clear();
        updatePasteBar();
        runTask(getString(cut ? R.string.fm_moving : R.string.fm_copying), () -> {
            int ok = 0;
            for (File f : src) {
                if (cancelled) throw new Cancel();
                if (!f.exists()) continue;
                if (f.isDirectory() && isInside(dest, f)) throw new IOException(getString(R.string.fm_err_into_self));
                if (cut && dest.equals(f.getParentFile())) continue;
                File target = unique(dest, f.getName());
                if (cut) {
                    if (!f.renameTo(target)) {
                        copyTree(f, target);
                        deleteTree(f);
                    }
                } else {
                    copyTree(f, target);
                }
                ok++;
            }
            return getString(R.string.fm_done_n, ok);
        });
    }

    private void deleteSelected() {
        final List<File> files = selectedFiles();
        if (files.isEmpty()) return;
        String msg = files.size() == 1
                ? getString(R.string.delete_msg_local, files.get(0).getName())
                : getString(R.string.delete_msg_local_n, files.size());
        new AlertDialog.Builder(this).setTitle(R.string.delete_title).setMessage(msg)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    selected.clear();
                    runTask(getString(R.string.fm_deleting), () -> {
                        for (File f : files) deleteTree(f);
                        Set<String> fv = favs();
                        if (fv.removeAll(paths(files))) prefs.edit().putStringSet("fav", fv).apply();
                        return getString(R.string.fm_done_n, files.size());
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void renameDialog(final File f) {
        LinearLayout box = Ui.box(this);
        final EditText name = Ui.edit(this, getString(R.string.fm_name_hint), f.getName());
        box.addView(name);
        new AlertDialog.Builder(this).setTitle(R.string.fm_rename).setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (!validName(n)) {
                        toast(R.string.fm_bad_name);
                        return;
                    }
                    if (n.equals(f.getName())) return;
                    File t = new File(f.getParentFile(), n);
                    if (t.exists()) {
                        toast(R.string.fm_exists);
                        return;
                    }
                    if (!f.renameTo(t)) toast(R.string.fm_rename_failed);
                    clearSelection();
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void compress(final List<File> files) {
        String def = files.size() == 1 ? stripExt(files.get(0).getName()) : getString(R.string.fm_archive_name);
        LinearLayout box = Ui.box(this);
        final EditText name = Ui.edit(this, getString(R.string.fm_name_hint), def + ".zip");
        box.addView(name);
        new AlertDialog.Builder(this).setTitle(R.string.fm_compress).setView(box)
                .setPositiveButton(R.string.create, (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (!validName(n)) {
                        toast(R.string.fm_bad_name);
                        return;
                    }
                    if (!n.toLowerCase(Locale.ROOT).endsWith(".zip")) n = n + ".zip";
                    File parent = files.get(0).getParentFile();
                    if (parent == null) parent = cur;
                    doCompress(files, unique(parent, n));
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void doCompress(final List<File> files, final File out) {
        selected.clear();
        runTask(getString(R.string.fm_compressing), () -> {
            boolean ok = false;
            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
                for (File f : files) addToZip(zos, f, f.getName(), out);
                ok = true;
            } finally {
                if (!ok) out.delete();
            }
            return getString(R.string.fm_created_name, out.getName());
        });
    }

    private void addToZip(ZipOutputStream zos, File f, String entry, File outFile) throws IOException {
        if (cancelled) throw new Cancel();
        if (f.equals(outFile)) return;
        if (f.isDirectory()) {
            zos.putNextEntry(new ZipEntry(entry + "/"));
            zos.closeEntry();
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) addToZip(zos, k, entry + "/" + k.getName(), outFile);
        } else {
            setBusy(f.getName());
            zos.putNextEntry(new ZipEntry(entry));
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled) throw new Cancel();
                    zos.write(buf, 0, n);
                }
            }
            zos.closeEntry();
        }
    }

    private void extract(final File zip, final boolean intoFolder) {
        selected.clear();
        File parent = zip.getParentFile();
        if (parent == null) parent = cur;
        final File dest = intoFolder ? unique(parent, stripExt(zip.getName())) : parent;
        runTask(getString(R.string.fm_extracting), () -> {
            if (!dest.exists() && !dest.mkdirs()) throw new IOException(getString(R.string.fm_err_mkdir, dest.getName()));
            String root = dest.getCanonicalPath() + File.separator;
            int count = 0;
            try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
                ZipEntry e;
                byte[] buf = new byte[64 * 1024];
                while ((e = zin.getNextEntry()) != null) {
                    if (cancelled) throw new Cancel();
                    File out = new File(dest, e.getName());
                    if (!out.getCanonicalPath().startsWith(root)) {
                        throw new IOException(getString(R.string.fm_err_zip_unsafe));
                    }
                    if (e.isDirectory()) {
                        out.mkdirs();
                        continue;
                    }
                    File p = out.getParentFile();
                    if (p != null) p.mkdirs();
                    setBusy(e.getName());
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        int r;
                        while ((r = zin.read(buf)) != -1) {
                            if (cancelled) throw new Cancel();
                            os.write(buf, 0, r);
                        }
                    }
                    count++;
                }
            }
            return getString(R.string.fm_extracted_n, count);
        });
    }

    private void importUris(final List<Uri> uris) {
        final File dest = cur;
        runTask(getString(R.string.fm_importing), () -> {
            int ok = 0;
            for (Uri u : uris) {
                if (cancelled) throw new Cancel();
                String n = FileScanner.displayName(getContentResolver(), u);
                if (n == null || !validName(n)) n = "file-" + System.currentTimeMillis();
                File t = unique(dest, n);
                setBusy(t.getName());
                try (InputStream in = getContentResolver().openInputStream(u);
                     OutputStream out = new FileOutputStream(t)) {
                    if (in == null) throw new IOException(n);
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        if (cancelled) {
                            out.close();
                            t.delete();
                            throw new Cancel();
                        }
                        out.write(buf, 0, r);
                    }
                }
                ok++;
            }
            return getString(R.string.fm_done_n, ok);
        });
    }

    private void share(List<File> files) {
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            for (File f : files) {
                if (f.isFile()) uris.add(FileProvider.getUriForFile(this, getPackageName() + ".files", f));
            }
            if (uris.isEmpty()) return;
            Intent i;
            if (uris.size() == 1) {
                i = new Intent(Intent.ACTION_SEND);
                i.setType(mimeOf(files.get(0)));
                i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                i.setClipData(ClipData.newRawUri("", uris.get(0)));
            } else {
                i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                i.setType("*/*");
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                ClipData cd = ClipData.newRawUri("", uris.get(0));
                for (int k = 1; k < uris.size(); k++) cd.addItem(new ClipData.Item(uris.get(k)));
                i.setClipData(cd);
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.share)));
            clearSelection();
        } catch (Exception e) {
            toast(R.string.fm_no_app);
        }
    }

    private void showDetails(final List<File> files) {
        showBusy(getString(R.string.fm_calculating));
        io.execute(() -> {
            long[] tot = new long[3]; // size, files, folders
            for (File f : files) sizeOf(f, tot);
            String hash = null;
            if (files.size() == 1 && files.get(0).isFile() && files.get(0).length() <= 200L * 1024 * 1024) {
                hash = sha256(files.get(0));
            }
            final StringBuilder sb = new StringBuilder();
            if (files.size() == 1) {
                File f = files.get(0);
                sb.append(getString(R.string.fm_d_path)).append(":\n").append(f.getAbsolutePath()).append("\n\n");
                sb.append(getString(R.string.fm_d_type)).append(": ")
                        .append(f.isDirectory() ? getString(R.string.fm_folder) : mimeOf(f)).append('\n');
                sb.append(getString(R.string.fm_d_modified)).append(": ")
                        .append(dateFmt.format(new Date(f.lastModified()))).append('\n');
                sb.append(getString(R.string.fm_d_access)).append(": ")
                        .append(f.canRead() ? "r" : "-").append(f.canWrite() ? "w" : "-")
                        .append(f.canExecute() ? "x" : "-").append('\n');
            } else {
                sb.append(getString(R.string.fm_selected_n, files.size())).append('\n');
            }
            sb.append(getString(R.string.fm_d_size)).append(": ").append(Fmt.size(tot[0]))
                    .append(" (").append(String.format(Locale.US, "%,d", tot[0])).append(" B)\n");
            if (files.size() > 1 || files.get(0).isDirectory()) {
                sb.append(getString(R.string.fm_d_contents)).append(": ")
                        .append(getString(R.string.fm_d_contents_v, tot[1], tot[2])).append('\n');
            }
            if (hash != null) sb.append("\nSHA-256:\n").append(hash);
            final String title = files.size() == 1 ? files.get(0).getName() : getString(R.string.fm_details);
            final String copy = files.size() == 1 ? files.get(0).getAbsolutePath() : null;
            post(() -> {
                hideBusy();
                AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(title)
                        .setMessage(sb.toString()).setPositiveButton(android.R.string.ok, null);
                if (copy != null) b.setNeutralButton(R.string.fm_copy_path, (d, w) -> copyText(copy));
                b.show();
            });
        });
    }

    private void sizeOf(File f, long[] tot) {
        if (cancelled) return;
        if (f.isDirectory()) {
            tot[2]++;
            if (isLink(f)) return;
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) sizeOf(k, tot);
        } else {
            tot[0] += f.length();
            tot[1]++;
        }
    }

    private String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}

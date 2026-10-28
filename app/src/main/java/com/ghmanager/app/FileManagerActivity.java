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
import android.content.res.Configuration;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import org.json.JSONArray;
import org.json.JSONObject;

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
import java.util.ArrayDeque;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final int M_DIR = 0, M_SEARCH = 1, M_FAV = 2, M_LARGEST = 3, M_RECENT = 4, M_TRASH = 5;
    private static final int T_DIR = 0, T_IMG = 1, T_VID = 2, T_AUD = 3, T_ARC = 4, T_APK = 5,
            T_TXT = 6, T_PDF = 7, T_OTHER = 8;
    private static final int PAGE = 300;
    private static final int MAX_SEARCH_ALL = 20000;
    private static final int MAX_LARGEST = 60;

    /** One row of the list, with everything precomputed off the UI thread. */
    private Set<String> favSnap;

    private void changed() {
        favSnap = null;
        if (adapter != null && !shown.isEmpty()) adapter.notifyItemRangeChanged(0, shown.size());
    }

    private static class Entry {
        final File f;
        final String name;
        final boolean dir;
        final long size;
        final long mod;
        final int kids;
        final String ext;
        final String trashId;
        final String origin;
        final int left;

        Entry(Trash.Item t) {
            this.f = t.stored;
            this.name = t.name;
            this.dir = t.dir;
            this.mod = t.deletedAt;
            this.kids = 0;
            this.size = t.size;
            this.ext = t.dir ? "" : extOf(t.name);
            this.trashId = t.id;
            this.origin = t.origin;
            this.left = t.daysLeft();
        }

        Entry(File f) {
            this.trashId = null;
            this.origin = null;
            this.left = 0;
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

    private RecyclerView listView;
    private GridLayoutManager gridLm;
    private boolean gridMode;
    private String listKey = "";
    private SearchSession session;
    private boolean pageLoading;
    private View undoBar;
    private TextView undoText;
    private final List<String> lastTrash = new ArrayList<>();
    private FileAdapter adapter;
    private TextView titleView, subtitleView, statusView, emptyView, selCount, pasteText, storageText;
    private ImageButton btnA1, btnA2, btnRefresh;
    private View loadingBar, selBar, pasteBar, permBanner, storageCard, crumbScroll;
    private EditText searchView;
    private LinearLayout placesRow, crumbRow;
    private android.widget.FrameLayout storageBarHolder;

    private AlertDialog busy;
    private TextView busyText;

    /** First visible row of folders we left, so going back lands where the user was. */
    private final java.util.Map<String, Integer> scrollPos = new java.util.HashMap<>();
    private boolean resetScroll = false;
    private View pickAll;

    /** "files" / "folder" when opened by the repo browser to choose what to upload, else null. */
    private String pickMode;
    private View pickBar;
    private Button pickGo;
    private TextView pickText;

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
        if (BottomNav.fromTab(this)) BottomNav.attach(this, BottomNav.FILES);
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

        gridMode = prefs.getBoolean("grid", false);
        adapter = new FileAdapter();
        gridLm = new GridLayoutManager(this, 1);
        listView.setLayoutManager(gridLm);
        listView.setAdapter(adapter);
        applyLayout();
        RecyclerView.ItemAnimator anim = listView.getItemAnimator();
        if (anim instanceof SimpleItemAnimator) ((SimpleItemAnimator) anim).setSupportsChangeAnimations(false);
        listView.addOnItemTouchListener(dragTouch);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy > 0) maybeLoadMore();
            }
        });
        undoBar = findViewById(R.id.undoBar);
        undoText = findViewById(R.id.undoText);
        findViewById(R.id.undoGo).setOnClickListener(v -> undoTrash());
        io.execute(() -> Trash.purgeOld(getApplicationContext()));
        Transfers.addListener(finishListener);
        loadBadgePrefs();

        pickMode = getIntent().getStringExtra("pick");
        pickBar = findViewById(R.id.pickBar);
        pickGo = findViewById(R.id.pickGo);
        pickText = findViewById(R.id.pickText);
        pickGo.setOnClickListener(v -> finishPick());
        pickAll = findViewById(R.id.pickAll);
        pickAll.setOnClickListener(v -> selectAll());
        pickAll.setOnLongClickListener(v -> {
            selected.clear();
            updateChrome();
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
        findViewById(R.id.selUpload).setOnClickListener(v -> {
            List<File> up = selectedFiles();
            if (!up.isEmpty()) uploadToGithub(up);
        });
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
        if (start == null && "save".equals(pickMode) && lastGranted) {
            File dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            if (dl != null && dl.isDirectory()) start = dl;
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
        Transfers.removeListener(finishListener);
        if (session != null) session.close();
        io.shutdownNow();
        thumbIo.shutdownNow();
        badgeIo.shutdownNow();
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
        if (mode == M_DIR && cur != null && listView != null && !cur.equals(dir)) {
            scrollPos.put(cur.getAbsolutePath(), gridLm.findFirstVisibleItemPosition());
        }
        resetScroll = !dir.equals(cur);
        // choosing files to upload keeps the ticks while moving between folders
        if (!"files".equals(pickMode)) clearSelectionQuiet();
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
        if (!selected.isEmpty() && pickMode == null) {
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
            case M_RECENT:
                showRecent();
                break;
            case M_TRASH:
                showTrash();
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

    private boolean useIndex() {
        return prefs.getBoolean("use_index", false);
    }

    private final class SearchSession {
        final String q;
        final boolean hidden;
        final String prefix;
        final ArrayDeque<File> stack = new ArrayDeque<>();
        final File root;
        java.io.BufferedReader idx;
        boolean started;
        volatile boolean done;
        int total;

        SearchSession(File root, String q, boolean hidden, boolean useIdx) {
            this.root = root;
            this.q = q;
            this.hidden = hidden;
            String rp = root.getAbsolutePath();
            this.prefix = rp.endsWith("/") ? rp : rp + "/";
            if (useIdx) {
                try {
                    idx = FmIndex.open(FileManagerActivity.this);
                } catch (IOException e) {
                    idx = null;
                }
            }
        }

        void close() {
            try {
                if (idx != null) idx.close();
            } catch (IOException ignored) {
            }
        }

        void fill(List<Entry> out, int want, long my) {
            int found = 0;
            if (idx != null) {
                try {
                    boolean eof = false;
                    while (found < want) {
                        String line = idx.readLine();
                        if (line == null) {
                            eof = true;
                            break;
                        }
                        if (my != gen) return;
                        if (!line.startsWith(prefix)) continue;
                        String rel = line.substring(prefix.length());
                        if (!hidden && (rel.startsWith(".") || rel.contains("/."))) continue;
                        int sl = line.lastIndexOf('/');
                        String name = sl >= 0 ? line.substring(sl + 1) : line;
                        if (!name.toLowerCase(Locale.ROOT).contains(q)) continue;
                        File f = new File(line);
                        if (!f.exists()) continue;
                        out.add(new Entry(f));
                        found++;
                    }
                    if (eof) done = true;
                } catch (IOException e) {
                    done = true;
                }
                total += found;
                return;
            }
            if (!started) {
                started = true;
                stack.push(root);
            }
            while (!stack.isEmpty() && found < want) {
                if (my != gen) return;
                File d = stack.pop();
                File[] arr = d.listFiles();
                if (arr == null) continue;
                for (File f : arr) {
                    String n = f.getName();
                    if (!hidden && n.startsWith(".")) continue;
                    if (n.toLowerCase(Locale.ROOT).contains(q)) {
                        out.add(new Entry(f));
                        found++;
                    }
                    if (f.isDirectory() && !isLink(f)) stack.push(f);
                }
            }
            if (stack.isEmpty()) done = true;
            total += found;
        }
    }

    private void runSearch() {
        final long my = ++gen;
        if (session != null) session.close();
        final SearchSession ss = new SearchSession(cur, query, showHidden, useIndex() && FmIndex.covers(this, cur));
        session = ss;
        pageLoading = false;
        loading(true);
        io.execute(() -> {
            List<Entry> out = new ArrayList<>();
            ss.fill(out, PAGE, my);
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

    private void maybeLoadMore() {
        final SearchSession ss = session;
        if (mode != M_SEARCH || ss == null || ss.done || pageLoading || ss.total >= MAX_SEARCH_ALL) return;
        if (gridLm.findLastVisibleItemPosition() < shown.size() - 15) return;
        pageLoading = true;
        final long my = gen;
        loading(true);
        io.execute(() -> {
            final List<Entry> more = new ArrayList<>();
            ss.fill(more, PAGE, my);
            Collections.sort(more, comparator());
            post(() -> {
                pageLoading = false;
                loading(false);
                if (my != gen || ss != session || mode != M_SEARCH) return;
                if (!more.isEmpty()) {
                    int start = shown.size();
                    shown.addAll(more);
                    adapter.notifyItemRangeInserted(start, more.size());
                }
                updateChrome();
            });
        });
    }

    private void showTrash() {
        final long my = ++gen;
        loading(true);
        io.execute(() -> {
            List<Trash.Item> items = Trash.list(getApplicationContext());
            final List<Entry> out = new ArrayList<>();
            for (Trash.Item t : items) out.add(new Entry(t));
            post(() -> {
                if (my != gen) return;
                listDenied = false;
                setShown(out);
                loading(false);
            });
        });
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

    private void showRecent() {
        final long my = ++gen;
        final File dir = cur;
        loading(true);
        io.execute(() -> {
            PriorityQueue<Entry> heap = new PriorityQueue<>(MAX_LARGEST + 1, (a, b) -> Long.compare(a.mod, b.mod));
            int[] budget = {250000};
            walkRecent(dir, heap, budget, my, 0);
            final List<Entry> res = new ArrayList<>(heap);
            Collections.sort(res, (a, b) -> Long.compare(b.mod, a.mod));
            post(() -> {
                if (my != gen) return;
                listDenied = false;
                setShown(res);
                loading(false);
            });
        });
    }

    private void walkRecent(File dir, PriorityQueue<Entry> heap, int[] budget, long my, int depth) {
        if (my != gen || budget[0] <= 0 || depth > 20) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (my != gen || budget[0] <= 0) return;
            if (!showHidden && f.getName().startsWith(".")) continue;
            budget[0]--;
            if (f.isDirectory()) {
                if (!isLink(f)) walkRecent(f, heap, budget, my, depth + 1);
            } else {
                long m = f.lastModified();
                if (heap.size() < MAX_LARGEST || m > heap.peek().mod) {
                    heap.add(new Entry(f));
                    if (heap.size() > MAX_LARGEST) heap.poll();
                }
            }
        }
    }

    private void scrollTo(int pos) {
        gridLm.scrollToPositionWithOffset(Math.max(0, pos), 0);
    }

    private void setShown(List<Entry> list) {
        final List<Entry> old = new ArrayList<>(shown);
        final List<Entry> now = new ArrayList<>(list);
        shown.clear();
        shown.addAll(now);
        if (!"files".equals(pickMode)) {
            Set<String> alive = new LinkedHashSet<>();
            for (Entry e : shown) alive.add(e.f.getAbsolutePath());
            selected.retainAll(alive);
        }
        String key = mode + "|" + (cur == null ? "" : cur.getAbsolutePath()) + "|" + query;
        boolean sameList = key.equals(listKey) && !resetScroll && !old.isEmpty() && !now.isEmpty();
        listKey = key;
        favSnap = null;
        if (sameList) {
            DiffUtil.DiffResult dr = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return old.size();
                }

                @Override
                public int getNewListSize() {
                    return now.size();
                }

                @Override
                public boolean areItemsTheSame(int o, int n) {
                    return old.get(o).f.getAbsolutePath().equals(now.get(n).f.getAbsolutePath());
                }

                @Override
                public boolean areContentsTheSame(int o, int n) {
                    Entry a = old.get(o);
                    Entry b = now.get(n);
                    return a.name.equals(b.name) && a.size == b.size && a.mod == b.mod && a.kids == b.kids
                            && a.dir == b.dir && a.left == b.left;
                }
            }, false);
            dr.dispatchUpdatesTo(adapter);
        } else {
            adapter.notifyDataSetChanged();
        }
        if (resetScroll) {
            scrollTo(0);
            resetScroll = false;
        }
        if (mode == M_DIR && cur != null) {
            Integer sp = scrollPos.remove(cur.getAbsolutePath());
            if (sp != null && !shown.isEmpty()) scrollTo(Math.min(sp, shown.size() - 1));
        }
        updateChrome();
        boolean empty = shown.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            int msg;
            if (mode == M_SEARCH) msg = R.string.fm_no_results;
            else if (mode == M_FAV) msg = R.string.fm_no_favs;
            else if (mode == M_TRASH) msg = R.string.fm_trash_empty;
            else if (listDenied) msg = R.string.fm_denied;
            else msg = R.string.fm_empty;
            emptyView.setText(msg);
        }
    }

    private void applyLayout() {
        int cols = 1;
        int pad = 0;
        if (gridMode) {
            int w = getResources().getDisplayMetrics().widthPixels;
            cols = Math.max(3, w / Ui.dp(this, 118));
            pad = Ui.dp(this, 10);
        }
        gridLm.setSpanCount(cols);
        listView.setPadding(pad, listView.getPaddingTop(), pad, listView.getPaddingBottom());
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (gridLm != null) applyLayout();
    }

    private void setGridMode(boolean on) {
        gridMode = on;
        prefs.edit().putBoolean("grid", on).apply();
        applyLayout();
        adapter.notifyDataSetChanged();
    }

    private boolean dragging;
    private int dragAnchor = -1;
    private float dragX, dragY;
    private int dragScroll;
    private final Set<String> dragAdded = new LinkedHashSet<>();

    private final Runnable dragRun = new Runnable() {
        @Override
        public void run() {
            if (!dragging || dragScroll == 0) return;
            listView.scrollBy(0, dragScroll);
            View v = listView.findChildViewUnder(dragX, dragY);
            if (v != null) {
                int pos = listView.getChildAdapterPosition(v);
                if (pos != RecyclerView.NO_POSITION) applyDragRange(pos);
            }
            ui.postDelayed(this, 16);
        }
    };

    private final RecyclerView.OnItemTouchListener dragTouch = new RecyclerView.OnItemTouchListener() {
        @Override
        public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                endDrag();
                return false;
            }
            if (!dragging) return false;
            if (a == MotionEvent.ACTION_MOVE) {
                dragMove(rv, e);
                return true;
            }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) endDrag();
            return false;
        }

        @Override
        public void onTouchEvent(RecyclerView rv, MotionEvent e) {
            if (!dragging) return;
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_MOVE) dragMove(rv, e);
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) endDrag();
        }

        @Override
        public void onRequestDisallowInterceptTouchEvent(boolean disallow) {
        }
    };

    private void startDrag(int pos) {
        if (pos < 0 || pos >= shown.size()) return;
        dragging = true;
        dragAnchor = pos;
        dragAdded.clear();
        dragAdded.add(shown.get(pos).f.getAbsolutePath());
    }

    private void endDrag() {
        dragging = false;
        dragScroll = 0;
        dragAnchor = -1;
        dragAdded.clear();
        ui.removeCallbacks(dragRun);
    }

    private void dragMove(RecyclerView rv, MotionEvent e) {
        dragX = e.getX();
        dragY = e.getY();
        View v = rv.findChildViewUnder(dragX, dragY);
        if (v != null) {
            int pos = rv.getChildAdapterPosition(v);
            if (pos != RecyclerView.NO_POSITION) applyDragRange(pos);
        }
        int edge = Ui.dp(this, 72);
        int step = Ui.dp(this, 14);
        int before = dragScroll;
        if (dragY < edge) dragScroll = -step;
        else if (dragY > rv.getHeight() - edge) dragScroll = step;
        else dragScroll = 0;
        if (dragScroll != 0 && before == 0) ui.post(dragRun);
    }

    private void applyDragRange(int pos) {
        if (dragAnchor < 0) return;
        int lo = Math.min(dragAnchor, pos);
        int hi = Math.min(Math.max(dragAnchor, pos), shown.size() - 1);
        boolean filesOnly = "files".equals(pickMode);
        Set<String> now = new LinkedHashSet<>();
        for (int i = lo; i <= hi; i++) {
            Entry e = shown.get(i);
            if (filesOnly && e.dir) continue;
            now.add(e.f.getAbsolutePath());
        }
        boolean changedSel = false;
        for (String p : dragAdded) {
            if (!now.contains(p) && selected.remove(p)) changedSel = true;
        }
        for (String p : now) {
            if (selected.add(p)) changedSel = true;
        }
        dragAdded.clear();
        dragAdded.addAll(now);
        if (changedSel) updateSelectionChrome();
    }

    private void updateSelectionChrome() {
        if (pickMode != null) updatePickBar();
        boolean sel = !selected.isEmpty() && pickMode == null;
        selBar.setVisibility(sel ? View.VISIBLE : View.GONE);
        searchView.setVisibility(sel ? View.GONE : View.VISIBLE);
        if (sel) {
            long total = 0;
            for (Entry e : shown) if (!e.dir && selected.contains(e.f.getAbsolutePath())) total += e.size;
            String t = getString(R.string.fm_selected_n, selected.size());
            selCount.setText(total > 0 ? t + " · " + Fmt.size(total) : t);
        }
        changed();
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
                titleView.setText(getString(R.string.fm_search_results, shown.size())
                        + (session != null && !session.done ? "+" : ""));
                setSubtitle(cur.getAbsolutePath());
                break;
            case M_TRASH:
                titleView.setText(R.string.fm_trash);
                setSubtitle(getString(R.string.fm_trash_hint));
                break;
            case M_FAV:
                titleView.setText(R.string.fm_favorites);
                setSubtitle(null);
                break;
            case M_LARGEST:
                titleView.setText(R.string.fm_largest);
                setSubtitle(cur.getAbsolutePath());
                break;
            case M_RECENT:
                titleView.setText(R.string.fm_recent);
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
        if (pickMode != null) {
            titleView.setText("files".equals(pickMode) ? getString(R.string.pk_title_files)
                    : "save".equals(pickMode) ? getString(R.string.pk_title_save)
                    : getString(R.string.pk_title_folder));
            updatePickBar();
        }
        boolean sel = !selected.isEmpty() && pickMode == null;
        selBar.setVisibility(sel ? View.VISIBLE : View.GONE);
        searchView.setVisibility(sel ? View.GONE : View.VISIBLE);
        if (sel) {
            long total = 0;
            for (Entry e : shown) if (!e.dir && selected.contains(e.f.getAbsolutePath())) total += e.size;
            String t = getString(R.string.fm_selected_n, selected.size());
            selCount.setText(total > 0 ? t + " · " + Fmt.size(total) : t);
        }
        updatePermBanner();
        updatePasteBar();
        changed();
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
        // first chip: the one folder where every download of the app lands (Download/GitHubManager/<repo>)
        File gh = TransferTasks.rootDir();
        if (!gh.exists() && Perms.hasAllFiles(this)) //noinspection ResultOfMethodCallIgnored
            gh.mkdirs();
        addPlace(getString(R.string.fm_github), gh);
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

        TextView recent = Ui.chip(this, getString(R.string.fm_recent), mode == M_RECENT);
        recent.setOnClickListener(v -> {
            clearSelectionQuiet();
            mode = M_RECENT;
            refresh();
        });
        placesRow.addView(recent);

        TextView trash = Ui.chip(this, getString(R.string.fm_trash), mode == M_TRASH);
        trash.setOnClickListener(v -> openTrash());
        placesRow.addView(trash);
    }

    private void openTrash() {
        clearSelectionQuiet();
        mode = M_TRASH;
        refresh();
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

    private class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            final ImageView icon, thumb, check, fav;
            final TextView title, sub, badge;
            final View more, chevron;

            VH(View v) {
                super(v);
                icon = v.findViewById(R.id.icon);
                thumb = v.findViewById(R.id.thumb);
                check = v.findViewById(R.id.check);
                fav = v.findViewById(R.id.fav);
                title = v.findViewById(R.id.title);
                sub = v.findViewById(R.id.sub);
                badge = v.findViewById(R.id.badge);
                more = v.findViewById(R.id.more);
                chevron = v.findViewById(R.id.chevron);
            }
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        @Override
        public int getItemViewType(int position) {
            return gridMode ? 1 : 0;
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(viewType == 1 ? R.layout.item_file_grid : R.layout.item_file,
                    parent, false);
            final VH h = new VH(v);
            v.setOnClickListener(x -> {
                int p = h.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) onItemClick(p);
            });
            v.setOnLongClickListener(x -> {
                int p = h.getBindingAdapterPosition();
                return p != RecyclerView.NO_POSITION && onItemLong(p);
            });
            if (h.more != null) {
                h.more.setOnClickListener(x -> {
                    int p = h.getBindingAdapterPosition();
                    if (p != RecyclerView.NO_POSITION && p < shown.size()) itemMenu(shown.get(p));
                });
            }
            return h;
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            if (pos < 0 || pos >= shown.size()) return;
            final Entry e = shown.get(pos);
            final String path = e.f.getAbsolutePath();
            boolean sel = selected.contains(path);
            int type = typeOf(e);
            int color = Ui.color(FileManagerActivity.this, colorFor(type));

            h.icon.setImageResource(iconFor(type));
            h.icon.setImageTintList(ColorStateList.valueOf(color));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Ui.radius(FileManagerActivity.this, R.dimen.radius_s));
            g.setColor((color & 0x00FFFFFF) | 0x26000000);
            h.icon.setBackground(g);
            h.thumb.setBackground(g);
            h.thumb.setClipToOutline(true);
            h.thumb.setTag(path);
            Bitmap cached = (type == T_IMG || type == T_APK) ? thumbs.get(path + "|" + e.mod) : null;
            if (cached != null) {
                h.thumb.setImageBitmap(cached);
                h.thumb.setVisibility(View.VISIBLE);
                h.icon.setVisibility(View.INVISIBLE);
            } else {
                h.thumb.setVisibility(View.GONE);
                h.icon.setVisibility(View.VISIBLE);
                if (type == T_IMG || type == T_APK) loadThumb(e, type, h.thumb, h.icon);
            }

            h.title.setText(e.name);
            StringBuilder sub = new StringBuilder();
            if (e.dir) {
                if (e.trashId != null) sub.append(Fmt.size(e.size));
                else sub.append(e.kids < 0 ? getString(R.string.fm_unreadable) : getString(R.string.fm_items_n, e.kids));
            } else {
                sub.append(Fmt.size(e.size));
            }
            if (e.trashId != null) {
                sub.append(" · ").append(getString(R.string.fm_trash_left, e.left));
            } else if (mode == M_DIR) {
                sub.append(" · ").append(dateFmt.format(new Date(e.mod)));
            } else {
                File p = e.f.getParentFile();
                if (p != null) sub.append(" · ").append(p.getAbsolutePath());
            }
            h.sub.setText(sub.toString());
            bindBadge(h, e);

            if (favSnap == null) favSnap = favs();
            h.fav.setVisibility(favSnap.contains(path) ? View.VISIBLE : View.GONE);
            boolean pickFiles = "files".equals(pickMode);
            boolean selecting = (!selected.isEmpty() && pickMode == null) || pickFiles;
            boolean showCheck = selecting && !(pickFiles && e.dir);
            h.check.setVisibility(showCheck ? View.VISIBLE : View.GONE);
            h.check.setImageResource(sel ? R.drawable.ic_check_circle : R.drawable.ic_circle_outline);
            h.check.setImageTintList(ColorStateList.valueOf(Ui.color(FileManagerActivity.this,
                    sel ? R.color.accent_text : R.color.text_hint)));
            if (h.chevron != null) h.chevron.setVisibility(!showCheck && e.dir ? View.VISIBLE : View.GONE);
            if (h.more != null) {
                h.more.setVisibility(pickMode == null && selected.isEmpty() ? View.VISIBLE : View.GONE);
            }
            Ui.shapeRow(FileManagerActivity.this, h.itemView, gridMode || pos == 0,
                    gridMode || pos == shown.size() - 1, sel ? R.color.accent_soft : R.color.surface);
        }
    }

    private String badgeRepo = "";
    private String badgeBranch = "";
    private String badgeRoot = "";
    private volatile java.util.Map<String, String> remoteShas;
    private volatile int badgeVer;
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> badgeCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> badgePending = Collections.synchronizedSet(new java.util.HashSet<String>());
    private final ExecutorService badgeIo = Executors.newSingleThreadExecutor();
    private final Runnable badgeRefresh = this::changed;
    private final Runnable undoHide = () -> {
        if (undoBar != null) undoBar.setVisibility(View.GONE);
    };
    private final Transfers.Listener finishListener = job -> {
        if (badgesOn() && job.tag != null && job.tag.equals("upload:" + badgeRepo)) reloadRemote();
    };

    private boolean badgesOn() {
        return !badgeRepo.isEmpty() && !badgeRoot.isEmpty();
    }

    private String relPath(File f) {
        String ap = f.getAbsolutePath();
        String root = badgeRoot;
        if (root.isEmpty() || !ap.startsWith(root + "/")) return null;
        return ap.substring(root.length() + 1);
    }

    private int badgeStatus(Entry e) {
        final java.util.Map<String, String> remote = remoteShas;
        if (remote == null) return -1;
        final String rel = relPath(e.f);
        if (rel == null) return -1;
        final String key = e.f.getAbsolutePath() + "|" + e.mod + "|" + e.size;
        Integer c = badgeCache.get(key);
        if (c != null) return c;
        if (e.size > 25L * 1024 * 1024) return -1;
        if (badgePending.add(key)) {
            final int ver = badgeVer;
            final File f = e.f;
            badgeIo.execute(() -> {
                String local = TransferTasks.gitSha(f);
                String r = remote.get(rel);
                int st = r == null ? 0 : (r.equals(local) ? 2 : 1);
                if (local != null && ver == badgeVer) badgeCache.put(key, st);
                badgePending.remove(key);
                ui.removeCallbacks(badgeRefresh);
                ui.postDelayed(badgeRefresh, 250);
            });
        }
        return -1;
    }

    private void bindBadge(FileAdapter.VH h, Entry e) {
        if (h.badge == null) return;
        if (!badgesOn() || e.dir || e.trashId != null) {
            h.badge.setVisibility(View.GONE);
            return;
        }
        int st = badgeStatus(e);
        if (st < 0) {
            h.badge.setVisibility(View.GONE);
            return;
        }
        int colorRes = st == 2 ? R.color.ok : st == 1 ? R.color.warn : R.color.info;
        int c = Ui.color(this, colorRes);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 10));
        g.setColor((c & 0x00FFFFFF) | 0x26000000);
        h.badge.setBackground(g);
        h.badge.setTextColor(c);
        h.badge.setText(st == 2 ? R.string.fm_b_up : st == 1 ? R.string.fm_b_changed : R.string.fm_b_new);
        h.badge.setVisibility(View.VISIBLE);
    }

    private void loadBadgePrefs() {
        badgeRepo = prefs.getString("bd_repo", "");
        badgeBranch = prefs.getString("bd_branch", "");
        badgeRoot = prefs.getString("bd_root", "");
        if (badgesOn()) reloadRemote();
    }

    private void reloadRemote() {
        final String repo = badgeRepo;
        final String br = badgeBranch;
        final int slash = repo.indexOf('/');
        if (slash <= 0 || Store.getToken(this) == null) return;
        badgeVer++;
        badgeCache.clear();
        remoteShas = null;
        io.execute(() -> {
            try {
                java.util.Map<String, String> m = new GitHubApi(this).blobShaMap(repo.substring(0, slash),
                        repo.substring(slash + 1), br);
                post(() -> {
                    if (!repo.equals(badgeRepo)) return;
                    remoteShas = m;
                    changed();
                });
            } catch (final Exception e) {
                if (e instanceof GitHubApi.ApiException
                        && (((GitHubApi.ApiException) e).code == 404 || ((GitHubApi.ApiException) e).code == 409)) {
                    post(() -> {
                        remoteShas = new java.util.HashMap<>();
                        changed();
                    });
                    return;
                }
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                post(() -> Dlg.error(FileManagerActivity.this, getString(R.string.error), msg, e, this::reloadRemote));
            }
        });
    }

    private void badgesMenu() {
        if (badgesOn()) {
            String[] items = {getString(R.string.fm_badges_refresh), getString(R.string.fm_badges_change),
                    getString(R.string.fm_badges_off)};
            new Dlg(this).setTitle(getString(R.string.fm_badges_title) + " · " + badgeRepo)
                    .setItems(items, (d, which) -> {
                        if (which == 0) {
                            reloadRemote();
                        } else if (which == 1) {
                            pickBadgeRepo();
                        } else {
                            prefs.edit().remove("bd_repo").remove("bd_branch").remove("bd_root").apply();
                            badgeRepo = "";
                            badgeBranch = "";
                            badgeRoot = "";
                            remoteShas = null;
                            badgeCache.clear();
                            changed();
                        }
                    }).show();
        } else {
            pickBadgeRepo();
        }
    }

    private void pickBadgeRepo() {
        JSONArray repos = RepoCache.load(this);
        if (repos == null || repos.length() == 0 || Store.getToken(this) == null) {
            toast(R.string.fm_gh_no_repos);
            return;
        }
        final List<JSONObject> list = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < repos.length(); i++) {
            JSONObject r = repos.optJSONObject(i);
            if (r == null || r.optString("full_name").indexOf('/') <= 0) continue;
            list.add(r);
            names.add(r.optString("full_name"));
        }
        if (list.isEmpty()) {
            toast(R.string.fm_gh_no_repos);
            return;
        }
        new Dlg(this).setTitle(R.string.fm_gh_pick_repo)
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    final JSONObject r = list.get(which);
                    final String full = r.optString("full_name");
                    String def = r.optString("default_branch", "main");
                    if (def.isEmpty() || "null".equals(def)) def = "main";
                    final String saved = prefs.getString("up_branch:" + full, "");
                    final String branch = saved.isEmpty() ? def : saved;
                    final File root = cur;
                    new Dlg(this).setTitle(R.string.fm_badges_title)
                            .setMessage(getString(R.string.fm_badges_confirm, root.getAbsolutePath(), full, branch))
                            .setPositiveButton(R.string.ok, (d2, w) -> {
                                prefs.edit().putString("bd_repo", full).putString("bd_branch", branch)
                                        .putString("bd_root", root.getAbsolutePath()).apply();
                                loadBadgePrefs();
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                }).show();
    }

    private boolean onItemLong(int pos) {
        if ("folder".equals(pickMode) || "save".equals(pickMode)) return true;
        if (pos < 0 || pos >= shown.size()) return true;
        if ("files".equals(pickMode) && shown.get(pos).dir) return true;
        String path = shown.get(pos).f.getAbsolutePath();
        if (!selected.contains(path)) {
            selected.add(path);
            updateChrome();
        }
        startDrag(pos);
        return true;
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
        if (e.trashId != null) {
            trashItemMenu(e);
            return;
        }
        if (e.dir) {
            navigate(e.f);
        } else if ("files".equals(pickMode)) {
            toggleSelect(pos);
        } else if (pickMode == null) {
            openFile(e);
        }
    }

    private void updatePickBar() {
        if (pickMode == null || pickBar == null) return;
        pickBar.setVisibility(View.VISIBLE);
        pickAll.setVisibility("files".equals(pickMode) ? View.VISIBLE : View.GONE);
        if ("files".equals(pickMode)) {
            int n = selected.size();
            pickGo.setEnabled(n > 0);
            pickGo.setAlpha(n > 0 ? 1f : 0.4f);
            pickGo.setText(R.string.pk_upload);
            pickText.setText(n > 0 ? getString(R.string.pk_selected_n, n) : getString(R.string.pk_hint_files));
        } else {
            boolean ok = mode == M_DIR;
            pickGo.setEnabled(ok);
            pickGo.setAlpha(ok ? 1f : 0.4f);
            if ("save".equals(pickMode)) {
                pickGo.setText(R.string.pk_save_here);
                pickText.setText(getString(R.string.pk_save_name,
                        getIntent().getStringExtra("fileName"), cur.getAbsolutePath()));
            } else {
                pickGo.setText(R.string.pk_upload_folder);
                pickText.setText(cur.getAbsolutePath());
            }
        }
    }

    private void finishPick() {
        ArrayList<String> paths = new ArrayList<>();
        if ("files".equals(pickMode)) {
            for (File f : selectedFiles()) paths.add(f.getAbsolutePath());
        } else {
            paths.add(cur.getAbsolutePath());
        }
        if (paths.isEmpty()) {
            toast(R.string.pk_nothing);
            return;
        }
        Intent r = new Intent();
        r.putStringArrayListExtra("paths", paths);
        r.putExtra("folder", !"files".equals(pickMode));
        setResult(RESULT_OK, r);
        finish();
    }

    private void toggleSelect(int pos) {
        if (pos < 0 || pos >= shown.size()) return;
        String p = shown.get(pos).f.getAbsolutePath();
        if (!selected.remove(p)) selected.add(p);
        updateChrome();
    }

    private void selectAll() {
        boolean filesOnly = "files".equals(pickMode);
        int eligible = 0;
        int chosen = 0;
        for (Entry e : shown) {
            if (filesOnly && e.dir) continue;
            eligible++;
            if (selected.contains(e.f.getAbsolutePath())) chosen++;
        }
        if (eligible > 0 && chosen == eligible) {
            for (Entry e : shown) selected.remove(e.f.getAbsolutePath());
        } else {
            for (Entry e : shown) {
                if (filesOnly && e.dir) continue;
                selected.add(e.f.getAbsolutePath());
            }
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
        if ("files".equals(pickMode)) {
            for (String p : selected) {
                File f = new File(p);
                if (f.isFile()) out.add(f);
            }
            return out;
        }
        for (Entry e : shown) if (selected.contains(e.f.getAbsolutePath())) out.add(e.f);
        return out;
    }

    private void toClipboard(boolean cut) {
        if (mode == M_TRASH) return;
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
        new Dlg(this).setTitle(zip.getName()).setItems(items, (d, which) -> {
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
        new Dlg(this).setTitle(title).setMessage(msg)
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
        items.add(getString(gridMode ? R.string.fm_view_list : R.string.fm_view_grid));
        items.add(getString(R.string.fm_trash));
        items.add(getString(badgesOn() ? R.string.fm_badges_on : R.string.fm_badges_menu));
        items.add(getString(useIndex() ? R.string.fm_index_off : R.string.fm_index_on));
        long it = FmIndex.time(this);
        items.add(getString(R.string.fm_index_build) + (it > 0 ? " · " + dateFmt.format(new Date(it)) : ""));
        new Dlg(this).setTitle(R.string.fm_sort)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 6) {
                        setGridMode(!gridMode);
                        return;
                    }
                    if (which == 7) {
                        openTrash();
                        return;
                    }
                    if (which == 8) {
                        badgesMenu();
                        return;
                    }
                    if (which == 9) {
                        if (useIndex()) {
                            prefs.edit().putBoolean("use_index", false).apply();
                            toast(R.string.fm_index_disabled);
                        } else if (FmIndex.root(this).isEmpty()) {
                            buildIndex();
                        } else {
                            prefs.edit().putBoolean("use_index", true).apply();
                            toast(R.string.fm_index_enabled);
                        }
                        return;
                    }
                    if (which == 10) {
                        buildIndex();
                        return;
                    }
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

    private void buildIndex() {
        final File root = lastGranted ? Environment.getExternalStorageDirectory() : cur;
        runTask(getString(R.string.fm_index_building), () -> {
            final AtomicBoolean stop = new AtomicBoolean(false);
            int n;
            try {
                n = FmIndex.build(getApplicationContext(), root, stop, c -> {
                    if (cancelled) stop.set(true);
                    setBusy(getString(R.string.fm_index_count, c));
                });
            } catch (IOException e) {
                if (cancelled) throw new Cancel();
                throw e;
            }
            prefs.edit().putBoolean("use_index", true).apply();
            return getString(R.string.fm_index_done, n);
        });
    }

    private void newMenu() {
        if (mode == M_TRASH) {
            emptyTrashDialog();
            return;
        }
        String[] items = {getString(R.string.fm_new_folder), getString(R.string.fm_new_file),
                getString(R.string.fm_import_files)};
        new Dlg(this).setTitle(R.string.fm_new).setItems(items, (d, which) -> {
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
        new Dlg(this)
                .setTitle(folder ? R.string.fm_new_folder : R.string.fm_new_file)
                .setView(box)
                .setPositiveButton(R.string.create, (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (!validName(n)) {
                        toast(R.string.fm_bad_name);
                        Dlg.stay(d);
                        return;
                    }
                    File t = new File(cur, n);
                    if (t.exists()) {
                        toast(R.string.fm_exists);
                        Dlg.stay(d);
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

    /** Actions for one row, opened from its "more" button (no need to know about long-press). */
    private void itemMenu(final Entry e) {
        if (pickMode != null) return;
        if (e.trashId != null) {
            trashItemMenu(e);
            return;
        }
        final File f = e.f;
        final List<File> one = new ArrayList<>();
        one.add(f);
        final boolean isZip = !e.dir && e.ext.equals("zip");
        final boolean fav = favs().contains(f.getAbsolutePath());
        final List<String> labels = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        if (!e.dir) add(labels, ids, getString(R.string.fm_open), 0);
        add(labels, ids, getString(R.string.fm_select), 1);
        add(labels, ids, getString(R.string.fm_copy), 2);
        add(labels, ids, getString(R.string.fm_move), 3);
        add(labels, ids, getString(R.string.fm_rename), 4);
        add(labels, ids, getString(R.string.fm_details), 5);
        if (!e.dir) add(labels, ids, getString(R.string.share), 6);
        add(labels, ids, getString(R.string.fm_compress), 7);
        if (isZip) {
            add(labels, ids, getString(R.string.fm_extract_here), 8);
            add(labels, ids, getString(R.string.fm_extract_folder), 9);
        }
        add(labels, ids, getString(fav ? R.string.fm_unfavorite : R.string.fm_favorite), 10);
        add(labels, ids, getString(R.string.fm_copy_path), 11);
        add(labels, ids, getString(R.string.fm_gh_upload), 13);
        add(labels, ids, getString(R.string.delete), 12);
        new Dlg(this).setTitle(e.name)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    switch (ids.get(which)) {
                        case 0:
                            openFile(e);
                            break;
                        case 1:
                            selected.add(f.getAbsolutePath());
                            updateChrome();
                            break;
                        case 2:
                        case 3:
                            selected.clear();
                            selected.add(f.getAbsolutePath());
                            toClipboard(ids.get(which) == 3);
                            break;
                        case 4:
                            renameDialog(f);
                            break;
                        case 5:
                            showDetails(one);
                            break;
                        case 6:
                            share(one);
                            break;
                        case 7:
                            compress(one);
                            break;
                        case 8:
                            extract(f, false);
                            break;
                        case 9:
                            extract(f, true);
                            break;
                        case 10:
                            toggleFav(one, fav);
                            break;
                        case 11:
                            copyText(f.getAbsolutePath());
                            break;
                        case 13:
                            uploadToGithub(one);
                            break;
                        default:
                            confirmDelete(one);
                            break;
                    }
                }).show();
    }

    private void moreMenu() {
        if (mode == M_TRASH) {
            trashSelMenu();
            return;
        }
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
        add(labels, ids, getString(R.string.fm_gh_upload), 13);

        new Dlg(this).setTitle(getString(R.string.fm_selected_n, files.size()))
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
                        case 13:
                            uploadToGithub(files);
                            break;
                        default:
                            copyText(one.getAbsolutePath());
                            break;
                    }
                }).show();
    }

    // ------------------------------------------------------------------ upload straight to a repository

    private void uploadToGithub(final List<File> files) {
        if (files.isEmpty() || mode == M_TRASH) return;
        JSONArray repos = RepoCache.load(this);
        if (repos == null || repos.length() == 0 || Store.getToken(this) == null) {
            toast(R.string.fm_gh_no_repos);
            return;
        }
        final List<JSONObject> list = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < repos.length(); i++) {
            JSONObject r = repos.optJSONObject(i);
            if (r == null || r.optString("full_name").indexOf('/') <= 0) continue;
            list.add(r);
            names.add(r.optString("full_name"));
        }
        if (list.isEmpty()) {
            toast(R.string.fm_gh_no_repos);
            return;
        }
        final String lastRepo = prefs.getString("up_repo", "");
        int lastAt = names.indexOf(lastRepo);
        if (lastAt > 0) {
            list.add(0, list.remove(lastAt));
            names.add(0, names.remove(lastAt));
        }
        new Dlg(this).setTitle(R.string.fm_gh_pick_repo)
                .setItems(names.toArray(new String[0]), (d, which) -> uploadDialog(files, list.get(which)))
                .show();
    }

    private void uploadDialog(final List<File> files, JSONObject r) {
        final String full = r.optString("full_name");
        final int slash = full.indexOf('/');
        final String owner = full.substring(0, slash);
        final String name = full.substring(slash + 1);
        String def = r.optString("default_branch", "main");
        if (def.isEmpty() || "null".equals(def)) def = "main";
        String savedBranch = prefs.getString("up_branch:" + full, "");
        if (!savedBranch.isEmpty()) def = savedBranch;
        String savedTarget = prefs.getString("up_target:" + full, "");

        boolean anyDir = false;
        for (File f : files) if (f.isDirectory()) anyDir = true;

        LinearLayout box = Ui.box(this);
        final EditText branch = Ui.edit(this, getString(R.string.branch_label), def);
        branch.setTextDirection(View.TEXT_DIRECTION_LTR);
        final EditText target = Ui.edit(this, getString(R.string.target_folder), savedTarget);
        target.setTextDirection(View.TEXT_DIRECTION_LTR);
        final EditText msg = Ui.edit(this, getString(R.string.default_commit), getString(R.string.default_commit));
        final CheckBox includeRoot = Ui.check(this, R.string.include_root_folder, true);
        final CheckBox skipJunk = Ui.check(this, R.string.upload_skip_junk, true);
        box.addView(Ui.field(this, getString(R.string.branch_label), branch));
        box.addView(Ui.field(this, getString(R.string.target_folder), target));
        box.addView(Ui.field(this, getString(R.string.commit_message), msg));
        if (anyDir) {
            box.addView(includeRoot);
            box.addView(skipJunk);
        }
        final boolean hasDir = anyDir;

        new Dlg(this)
                .setTitle(full)
                .setView(box)
                .setPositiveButton(R.string.upload, (d, w) -> {
                    String b = branch.getText().toString().trim();
                    if (b.isEmpty()) b = "main";
                    String base = target.getText().toString().trim().replaceAll("^/+|/+$", "");
                    String m = msg.getText().toString().trim();
                    if (m.isEmpty()) m = getString(R.string.default_commit);
                    prefs.edit().putString("up_repo", full).putString("up_branch:" + full, b)
                            .putString("up_target:" + full, base).apply();
                    previewThenUpload(new GitHubApi(this), owner, name, b, base, m, new ArrayList<>(files),
                            !hasDir || includeRoot.isChecked(), hasDir && skipJunk.isChecked());
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void previewThenUpload(final GitHubApi api, final String owner, final String name, final String b,
                                   final String base, final String m, final List<File> files,
                                   final boolean incRoot, final boolean skip) {
        showBusy(getString(R.string.fm_preview_scanning), false);
        io.execute(() -> {
            try {
                final TransferTasks.Preview p = TransferTasks.preview(api, getContentResolver(), owner, name, b, base,
                        files, incRoot, skip, () -> cancelled);
                post(() -> {
                    hideBusy();
                    showPreview(p, () -> {
                        Transfers.start(getApplicationContext(), getString(R.string.tr_upload_title, name),
                                "upload:" + owner + "/" + name,
                                TransferTasks.upload(api, owner, name, b, base, m, files, incRoot, skip),
                                (job, ok, text) -> {
                                    String first = text == null ? "" : text;
                                    int nl = first.indexOf('\n');
                                    if (nl > 0) first = first.substring(0, nl);
                                    Toast.makeText(job.app, ok ? first
                                            : job.app.getString(R.string.tr_failed) + ": " + first,
                                            Toast.LENGTH_LONG).show();
                                });
                        Toast.makeText(this, R.string.tr_started_bg, Toast.LENGTH_LONG).show();
                        clearSelection();
                    });
                });
            } catch (final Exception e) {
                post(() -> {
                    hideBusy();
                    if (e instanceof Transfers.Cancelled) {
                        toast(R.string.fm_cancelled);
                        return;
                    }
                    String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    Dlg.error(FileManagerActivity.this, getString(R.string.error), msg, e,
                            () -> previewThenUpload(api, owner, name, b, base, m, files, incRoot, skip));
                });
            }
        });
    }

    private void showPreview(TransferTasks.Preview p, final Runnable go) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.fm_pv_files, p.files, Fmt.size(p.bytes))).append('\n');
        sb.append(getString(R.string.fm_pv_states, p.fresh, p.changed, p.same)).append('\n');
        sb.append(getString(R.string.fm_pv_upload, p.fresh + p.changed, Fmt.size(p.uploadBytes)));
        if (p.ignored > 0 || p.tooBig > 0) {
            sb.append('\n').append(getString(R.string.fm_pv_skipped, p.ignored, p.tooBig));
        }
        Dlg d = new Dlg(this);
        d.setTitle(R.string.fm_pv_title);
        d.setMessage(sb.toString());
        if (p.fresh + p.changed > 0) {
            d.setPositiveButton(R.string.upload, (x, w) -> go.run());
            d.setNegativeButton(R.string.cancel, null);
        } else {
            d.setPositiveButton(R.string.close, null);
        }
        d.show();
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
        error(e, null);
    }

    private void error(Exception e, Runnable retry) {
        if (e instanceof Cancel) {
            toast(R.string.fm_cancelled);
            return;
        }
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        Dlg.error(this, getString(R.string.error), m, e, retry);
    }

    private int opCount;

    private static final class Op {
        volatile long total;
        volatile long done;
        long lastUi;
        long lastT;
        long lastD;
        float speed;
    }

    private Op op;
    private ProgressBar opBar;
    private TextView opDetail;

    private void showBusy(String text) {
        showBusy(text, false);
    }

    private void showBusy(String text, boolean progress) {
        hideBusy();
        cancelled = false;
        opCount = 0;
        busyText = new TextView(this);
        busyText.setText(text);
        busyText.setSingleLine(true);
        busyText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout box;
        if (progress) {
            op = new Op();
            op.lastT = SystemClock.elapsedRealtime();
            box = Ui.box(this);
            busyText.setTextColor(Ui.color(this, R.color.text_primary));
            busyText.setTextSize(14);
            opBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            opBar.setMax(1000);
            opBar.setIndeterminate(true);
            Ui.tint(this, opBar);
            opDetail = new TextView(this);
            opDetail.setTextColor(Ui.color(this, R.color.text_secondary));
            opDetail.setTextSize(12);
            opDetail.setText(R.string.fm_op_preparing);
            box.addView(busyText);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Ui.dp(this, 8));
            lp.topMargin = Ui.dp(this, 14);
            box.addView(opBar, lp);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            dl.topMargin = Ui.dp(this, 10);
            box.addView(opDetail, dl);
        } else {
            op = null;
            opBar = null;
            opDetail = null;
            IosSpinner wheel = new IosSpinner(this);
            box = Ui.busyBox(this, wheel, busyText);
        }
        busy = new Dlg(this).centered().setTitle(R.string.working).setView(box)
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

    private void setOpTotal(long t) {
        Op o = op;
        if (o != null) o.total = t;
    }

    private static String clock(long sec) {
        if (sec < 3600) return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
        return String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);
    }

    private void tick(long n, String name) {
        final Op o = op;
        if (o == null) return;
        o.done += n;
        long now = SystemClock.elapsedRealtime();
        if (now - o.lastUi < 200) return;
        o.lastUi = now;
        if (now - o.lastT >= 500) {
            float inst = (o.done - o.lastD) * 1000f / (now - o.lastT);
            o.speed = o.speed <= 0 ? inst : o.speed * 0.6f + inst * 0.4f;
            o.lastT = now;
            o.lastD = o.done;
        }
        final long d = o.done;
        final long t = o.total;
        final float sp = o.speed;
        final String nm = name;
        post(() -> showOp(o, d, t, sp, nm));
    }

    private void showOp(Op o, long d, long t, float speed, String name) {
        if (op != o || opBar == null || opDetail == null) return;
        if (name != null && busyText != null) busyText.setText(name);
        StringBuilder sb = new StringBuilder();
        if (t > 0) {
            int pm = (int) Math.min(1000, d * 1000 / t);
            if (opBar.isIndeterminate()) opBar.setIndeterminate(false);
            Ui.progress(opBar, pm);
            sb.append(pm / 10).append('%').append("  ·  ").append(Fmt.size(d)).append(" / ").append(Fmt.size(t));
        } else {
            sb.append(Fmt.size(d));
        }
        if (speed > 0) {
            sb.append("  ·  ").append(Fmt.size((long) speed)).append("/s");
            if (t > d) sb.append("  ·  ").append(getString(R.string.fm_op_eta, clock((long) ((t - d) / speed))));
        }
        opDetail.setText(sb.toString());
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
        op = null;
        opBar = null;
        opDetail = null;
    }

    private void runTask(String text, final Work w) {
        runTask(text, false, w);
    }

    private void runTask(final String text, final boolean progress, final Work w) {
        showBusy(text, progress);
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
                if (er != null) error(er, () -> runTask(text, progress, w));
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
            if (op == null) setBusy(src.getName() + "  ·  " + (++opCount));
            else tick(0, src.getName());
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled) throw new Cancel();
                    out.write(buf, 0, n);
                    tick(n, src.getName());
                }
            }
            dst.setLastModified(src.lastModified());
        }
    }

    private void deleteTree(File f) throws IOException {
        if (cancelled) throw new Cancel();
        if (f.isDirectory() && !isLink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        setBusy(f.getName() + "  ·  " + (++opCount));
        if (!f.delete() && f.exists()) throw new IOException(getString(R.string.fm_err_delete, f.getName()));
    }

    private void paste() {
        if (clip.isEmpty() || mode != M_DIR) return;
        final File dest = cur;
        final boolean cut = clipCut;
        final List<File> src = new ArrayList<>(clip);
        final Set<File> doneSrc = new java.util.HashSet<>();
        clip.clear();
        updatePasteBar();
        runTask(getString(cut ? R.string.fm_moving : R.string.fm_copying), true, () -> {
            final long[] sizes = new long[src.size()];
            long total = 0;
            for (int i = 0; i < src.size(); i++) {
                long[] t = new long[3];
                if (src.get(i).exists()) sizeOf(src.get(i), t);
                sizes[i] = t[0];
                total += t[0];
            }
            setOpTotal(total);
            if (!cut) {
                long free = dest.getUsableSpace();
                if (free > 0 && free < total + 1024L * 1024) {
                    throw new IOException(getString(R.string.fm_err_no_space, Fmt.size(total)));
                }
            }
            int ok = 0;
            for (int i = 0; i < src.size(); i++) {
                File f = src.get(i);
                if (cancelled) throw new Cancel();
                if (doneSrc.contains(f)) continue;
                if (!f.exists()) continue;
                if (f.isDirectory() && isInside(dest, f)) throw new IOException(getString(R.string.fm_err_into_self));
                if (cut && dest.equals(f.getParentFile())) continue;
                File target = unique(dest, f.getName());
                if (cut) {
                    if (f.renameTo(target)) {
                        tick(sizes[i], f.getName());
                    } else {
                        long free = dest.getUsableSpace();
                        if (free > 0 && free < sizes[i] + 1024L * 1024) {
                            throw new IOException(getString(R.string.fm_err_no_space, Fmt.size(sizes[i])));
                        }
                        try {
                            copyTree(f, target);
                        } catch (IOException ex) {
                            Trash.deleteTree(target);
                            throw ex;
                        }
                        deleteTree(f);
                    }
                } else {
                    try {
                        copyTree(f, target);
                    } catch (IOException ex) {
                        Trash.deleteTree(target);
                        throw ex;
                    }
                }
                doneSrc.add(f);
                ok++;
            }
            return getString(R.string.fm_done_n, ok);
        });
    }

    private void deleteSelected() {
        if (mode == M_TRASH) {
            foreverEntries(selectedEntries());
            return;
        }
        confirmDelete(selectedFiles());
    }

    private void confirmDelete(final List<File> files) {
        if (files.isEmpty() || mode == M_TRASH) return;
        String msg = files.size() == 1
                ? getString(R.string.fm_trash_msg, files.get(0).getName())
                : getString(R.string.fm_trash_msg_n, files.size());
        LinearLayout box = Ui.box(this);
        box.addView(Ui.body(this, msg, 15, R.color.text_secondary));
        final CheckBox perm = Ui.check(this, R.string.fm_delete_forever, false);
        box.addView(perm);
        new Dlg(this).setTitle(R.string.delete_title).setView(box)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    final boolean forever = perm.isChecked();
                    selected.clear();
                    if (forever) {
                        runTask(getString(R.string.fm_deleting), () -> {
                            for (File f : files) deleteTree(f);
                            Set<String> fv = favs();
                            if (fv.removeAll(paths(files))) prefs.edit().putStringSet("fav", fv).apply();
                            return getString(R.string.fm_done_n, files.size());
                        });
                    } else {
                        trashFiles(files);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private Trash.Tick trashTick(final AtomicBoolean stop) {
        return (n, name) -> {
            tick(n, name);
            if (cancelled) stop.set(true);
        };
    }

    private void trashFiles(final List<File> files) {
        final List<String> ids = new ArrayList<>();
        runTask(getString(R.string.fm_trashing), true, () -> {
            long total = 0;
            for (File f : files) {
                long[] t = new long[3];
                if (f.exists()) sizeOf(f, t);
                total += t[0];
            }
            setOpTotal(total);
            final AtomicBoolean stop = new AtomicBoolean(false);
            Trash.Tick tk = trashTick(stop);
            for (File f : files) {
                if (cancelled) throw new Cancel();
                if (!f.exists()) continue;
                try {
                    ids.add(Trash.put(getApplicationContext(), f, tk, stop));
                } catch (IOException ex) {
                    if (cancelled) throw new Cancel();
                    throw ex;
                }
            }
            Set<String> fv = favs();
            if (fv.removeAll(paths(files))) prefs.edit().putStringSet("fav", fv).apply();
            final List<String> got = new ArrayList<>(ids);
            post(() -> showUndo(got));
            return null;
        });
    }

    private void showUndo(List<String> ids) {
        lastTrash.clear();
        lastTrash.addAll(ids);
        if (ids.isEmpty() || undoBar == null) return;
        undoText.setText(getString(R.string.fm_trashed_n, ids.size()));
        undoBar.setVisibility(View.VISIBLE);
        ui.removeCallbacks(undoHide);
        ui.postDelayed(undoHide, 10000);
    }

    private void undoTrash() {
        final List<String> ids = new ArrayList<>(lastTrash);
        lastTrash.clear();
        ui.removeCallbacks(undoHide);
        undoBar.setVisibility(View.GONE);
        if (ids.isEmpty()) return;
        runTask(getString(R.string.fm_restoring), true, () -> restoreIds(ids));
    }

    private String restoreIds(List<String> ids) throws IOException {
        long total = 0;
        List<Trash.Item> items = new ArrayList<>();
        for (String id : ids) {
            Trash.Item it = Trash.find(getApplicationContext(), id);
            if (it == null) continue;
            items.add(it);
            total += it.size;
        }
        setOpTotal(total);
        final AtomicBoolean stop = new AtomicBoolean(false);
        Trash.Tick tk = trashTick(stop);
        int n = 0;
        for (Trash.Item it : items) {
            if (cancelled) throw new Cancel();
            try {
                Trash.restore(getApplicationContext(), it, tk, stop);
            } catch (IOException ex) {
                if (cancelled) throw new Cancel();
                throw ex;
            }
            n++;
        }
        return getString(R.string.fm_restored_n, n);
    }

    private List<Entry> selectedEntries() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : shown) if (selected.contains(e.f.getAbsolutePath())) out.add(e);
        return out;
    }

    private void trashItemMenu(final Entry e) {
        String[] items = {getString(R.string.fm_restore), getString(R.string.fm_delete_forever),
                getString(R.string.fm_select)};
        new Dlg(this).setTitle(e.name).setItems(items, (d, which) -> {
            final List<Entry> one = new ArrayList<>();
            one.add(e);
            if (which == 0) {
                restoreEntries(one);
            } else if (which == 1) {
                foreverEntries(one);
            } else {
                selected.add(e.f.getAbsolutePath());
                updateChrome();
            }
        }).show();
    }

    private void trashSelMenu() {
        final List<Entry> es = selectedEntries();
        if (es.isEmpty()) return;
        String[] items = {getString(R.string.fm_restore), getString(R.string.fm_delete_forever)};
        new Dlg(this).setTitle(getString(R.string.fm_selected_n, es.size())).setItems(items, (d, which) -> {
            if (which == 0) restoreEntries(es);
            else foreverEntries(es);
        }).show();
    }

    private void restoreEntries(List<Entry> es) {
        final List<String> ids = new ArrayList<>();
        for (Entry e : es) if (e.trashId != null) ids.add(e.trashId);
        selected.clear();
        if (ids.isEmpty()) return;
        runTask(getString(R.string.fm_restoring), true, () -> restoreIds(ids));
    }

    private void foreverEntries(List<Entry> es) {
        final List<String> ids = new ArrayList<>();
        for (Entry e : es) if (e.trashId != null) ids.add(e.trashId);
        if (ids.isEmpty()) return;
        new Dlg(this).setTitle(R.string.delete_title)
                .setMessage(getString(R.string.fm_forever_msg, ids.size()))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    selected.clear();
                    runTask(getString(R.string.fm_deleting), () -> {
                        int n = 0;
                        for (String id : ids) {
                            Trash.Item it = Trash.find(getApplicationContext(), id);
                            if (it != null) {
                                Trash.remove(getApplicationContext(), it);
                                n++;
                            }
                        }
                        return getString(R.string.fm_done_n, n);
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void emptyTrashDialog() {
        new Dlg(this).setTitle(R.string.fm_trash)
                .setMessage(getString(R.string.fm_empty_trash_msg))
                .setPositiveButton(R.string.fm_empty_trash, (d, w) -> runTask(getString(R.string.fm_deleting), () -> {
                    int n = Trash.empty(getApplicationContext());
                    return getString(R.string.fm_done_n, n);
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void renameDialog(final File f) {
        LinearLayout box = Ui.box(this);
        final EditText name = Ui.edit(this, getString(R.string.fm_name_hint), f.getName());
        box.addView(name);
        new Dlg(this).setTitle(R.string.fm_rename).setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (!validName(n)) {
                        toast(R.string.fm_bad_name);
                        Dlg.stay(d);
                        return;
                    }
                    if (n.equals(f.getName())) return;
                    File t = new File(f.getParentFile(), n);
                    if (t.exists()) {
                        toast(R.string.fm_exists);
                        Dlg.stay(d);
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
        new Dlg(this).setTitle(R.string.fm_compress).setView(box)
                .setPositiveButton(R.string.create, (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (!validName(n)) {
                        toast(R.string.fm_bad_name);
                        Dlg.stay(d);
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
        runTask(getString(R.string.fm_compressing), true, () -> {
            long total = 0;
            for (File f : files) {
                long[] t = new long[3];
                sizeOf(f, t);
                total += t[0];
            }
            setOpTotal(total);
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
            if (op == null) setBusy(f.getName());
            else tick(0, f.getName());
            zos.putNextEntry(new ZipEntry(entry));
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled) throw new Cancel();
                    zos.write(buf, 0, n);
                    tick(n, f.getName());
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
        runTask(getString(R.string.fm_extracting), true, () -> {
            if (!dest.exists() && !dest.mkdirs()) throw new IOException(getString(R.string.fm_err_mkdir, dest.getName()));
            long unpacked = 0;
            try (ZipFile zf = new ZipFile(zip)) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    long sz = ze.getSize();
                    unpacked += sz >= 0 ? sz : Math.max(0, ze.getCompressedSize());
                }
            } catch (IOException ignored) {
                unpacked = 0;
            }
            setOpTotal(unpacked);
            long free = dest.getUsableSpace();
            if (unpacked > 0 && free > 0 && free < unpacked + 1024L * 1024) {
                throw new IOException(getString(R.string.fm_err_no_space, Fmt.size(unpacked)));
            }
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
                    if (op == null) setBusy(e.getName());
                    else tick(0, e.getName());
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        int r;
                        while ((r = zin.read(buf)) != -1) {
                            if (cancelled) throw new Cancel();
                            os.write(buf, 0, r);
                            tick(r, e.getName());
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
        final Set<Uri> doneUri = new java.util.HashSet<>();
        runTask(getString(R.string.fm_importing), true, () -> {
            long total = 0;
            for (Uri u : uris) {
                long sz = FileScanner.size(getContentResolver(), u);
                if (sz > 0) total += sz;
            }
            setOpTotal(total);
            int ok = 0;
            for (Uri u : uris) {
                if (cancelled) throw new Cancel();
                if (doneUri.contains(u)) continue;
                String n = FileScanner.displayName(getContentResolver(), u);
                if (n == null || !validName(n)) n = "file-" + System.currentTimeMillis();
                File t = unique(dest, n);
                if (op == null) setBusy(t.getName());
                else tick(0, t.getName());
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
                        tick(r, t.getName());
                    }
                }
                doneUri.add(u);
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
                AlertDialog.Builder b = new Dlg(this).setTitle(title)
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

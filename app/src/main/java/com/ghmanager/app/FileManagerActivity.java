package com.ghmanager.app;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.ghmanager.app.FmConst.*;
import static com.ghmanager.app.FmFiles.*;

/**
 * In-app file manager (independent from the system one): browse every folder, search, sort,
 * multi-select, copy / move / delete / rename, zip + unzip, share, favorites, storage usage,
 * "largest files", text editing, APK install and image / APK thumbnails.
 */
public class FileManagerActivity extends AppCompatActivity {

    final FmListing listing = new FmListing(this);
    final FmSelection selection = new FmSelection(this);
    final FmSearch search = new FmSearch(this);
    final FmOperations ops = new FmOperations(this);
    final FmDialogs dialogs = new FmDialogs(this);
    final FmBadges badges = new FmBadges(this);
    final FmChrome chrome = new FmChrome(this);

    /** One row of the list, with everything precomputed off the UI thread. */
    Set<String> favSnap;

    void changed() {
        favSnap = null;
        if (adapter != null && !shown.isEmpty()) adapter.notifyItemRangeChanged(0, shown.size());
    }

    final ExecutorService io = Executors.newFixedThreadPool(3);

    final Handler ui = new Handler(Looper.getMainLooper());

    final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US);

    SharedPreferences prefs;

    int sortMode = SORT_NAME;

    boolean sortDesc = false;

    boolean showHidden = false;

    File cur;

    int mode = M_DIR;

    boolean lastGranted;

    boolean listDenied = false;

    volatile long gen = 0;

    final List<FmEntry> shown = new ArrayList<>();

    RecyclerView listView;

    GridLayoutManager gridLm;

    boolean gridMode;

    View undoBar;

    TextView undoText;

    FmAdapter adapter;

    TextView titleView, subtitleView, statusView, emptyView, selCount, pasteText, storageText;

    ImageButton btnA1, btnA2, btnRefresh;

    View loadingBar, selBar, pasteBar, permBanner, storageCard, crumbScroll;

    EditText searchView;

    LinearLayout placesRow, crumbRow;

    android.widget.FrameLayout storageBarHolder;

    View pickAll;

    /** "files" / "folder" when opened by the repo browser to choose what to upload, else null. */
    String pickMode;

    View pickBar;

    Button pickGo;

    TextView pickText;

    ActivityResultLauncher<String> importLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Themes.apply(this);
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
        adapter = new FmAdapter(this);
        gridLm = new GridLayoutManager(this, 1);
        listView.setLayoutManager(gridLm);
        listView.setAdapter(adapter);
        listing.applyLayout();
        RecyclerView.ItemAnimator anim = listView.getItemAnimator();
        if (anim instanceof SimpleItemAnimator) ((SimpleItemAnimator) anim).setSupportsChangeAnimations(false);
        listView.addOnItemTouchListener(selection.dragTouch);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy > 0) search.maybeLoadMore();
            }
        });
        undoBar = findViewById(R.id.undoBar);
        undoText = findViewById(R.id.undoText);
        findViewById(R.id.undoGo).setOnClickListener(v -> ops.undoTrash());
        io.execute(() -> Trash.purgeOld(getApplicationContext()));
        Transfers.addListener(badges.finishListener);
        badges.loadBadgePrefs();

        pickMode = getIntent().getStringExtra("pick");
        pickBar = findViewById(R.id.pickBar);
        pickGo = findViewById(R.id.pickGo);
        pickText = findViewById(R.id.pickText);
        pickGo.setOnClickListener(v -> selection.finishPick());
        pickAll = findViewById(R.id.pickAll);
        pickAll.setOnClickListener(v -> selection.selectAll());
        pickAll.setOnLongClickListener(v -> {
            selection.selected.clear();
            chrome.updateChrome();
            return true;
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        action(btnA1, R.drawable.ic_sort, R.string.fm_sort, v -> dialogs.sortMenu());
        action(btnA2, R.drawable.ic_add, R.string.fm_new, v -> dialogs.newMenu());
        btnRefresh.setOnClickListener(v -> listing.refresh());

        findViewById(R.id.selClose).setOnClickListener(v -> selection.clearSelection());
        findViewById(R.id.selAll).setOnClickListener(v -> selection.selectAll());
        findViewById(R.id.selCopy).setOnClickListener(v -> selection.toClipboard(false));
        findViewById(R.id.selCut).setOnClickListener(v -> selection.toClipboard(true));
        findViewById(R.id.selDelete).setOnClickListener(v -> ops.deleteSelected());
        findViewById(R.id.selUpload).setOnClickListener(v -> {
            List<File> up = selection.selectedFiles();
            if (!up.isEmpty()) dialogs.uploadToGithub(up);
        });
        findViewById(R.id.selMore).setOnClickListener(v -> dialogs.moreMenu());
        findViewById(R.id.pasteGo).setOnClickListener(v -> ops.paste());
        findViewById(R.id.pasteCancel).setOnClickListener(v -> {
            selection.clip.clear();
            chrome.updatePasteBar();
        });
        findViewById(R.id.permGrant).setOnClickListener(v -> Perms.requestAllFiles(this));

        search.attach(searchView);

        importLauncher = registerForActivityResult(new ActivityResultContracts.GetMultipleContents(),
                uris -> {
                    if (uris != null && !uris.isEmpty()) ops.importUris(uris);
                });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                listing.onBack();
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
        if (start == null) start = listing.defaultRoot();
        cur = start;
        chrome.buildPlaces();
        chrome.updatePasteBar();
        listing.refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean g = Perms.hasAllFiles(this);
        if (g != lastGranted) {
            lastGranted = g;
            File target = g ? Environment.getExternalStorageDirectory() : listing.appDir();
            listing.navigate(target);
        }
        chrome.updatePermBanner();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        Transfers.removeListener(badges.finishListener);
        if (search.session != null) search.session.close();
        io.shutdownNow();
        if (adapter != null) adapter.thumbIo.shutdownNow();
        badges.badgeIo.shutdownNow();
    }

    void action(ImageButton b, int icon, int desc, View.OnClickListener l) {
        b.setImageResource(icon);
        b.setContentDescription(getString(desc));
        b.setVisibility(View.VISIBLE);
        b.setOnClickListener(l);
    }

    void post(Runnable r) {
        ui.post(() -> {
            if (!isFinishing() && !isDestroyed()) r.run();
        });
    }

    void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    void loading(boolean on) {
        loadingBar.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (gridLm != null) listing.applyLayout();
    }
}

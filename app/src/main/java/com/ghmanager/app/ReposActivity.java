package com.ghmanager.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReposActivity extends AppCompatActivity {
    private GitHubApi api;
    private final List<JSONObject> allRepos = new ArrayList<>();
    private final List<JSONObject> shown = new ArrayList<>();
    private RowAdapter adapter;
    private SwipeRows swipeRows;
    private View sharedTitle;
    private TextView status;
    private TextView tokenWarn;
    private TextView count;
    private LinearLayout chipRow;
    private int filter = 0;
    private String query = "";
    private ImageView accountBtn;
    private ListView listView;
    private View skeleton;
    private View refreshBtn;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe;
    private GhStatusCard statusCard;
    private StoreTab store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean resumed = false;
    private boolean syncing = false;
    private boolean selectMode = false;
    private boolean emptyShown = false;
    private final Set<String> picked = new HashSet<>();
    private LinearLayout selBar;
    private TextView selCount;
    private OnBackPressedCallback backCb;
    private final Transfers.Listener finishListener = j -> {
        if ("cleanup".equals(j.tag)) {
            CleanupJobs.showPending(this);
            load();
        }
    };
    private String signature = "";
    private static final long SYNC_MS = 15000;
    private static final long CLOCK_MS = 10000;

    /** Repositories change on GitHub all the time: bring the list quietly while the screen is open. */
    private final Runnable syncTask = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            load(true);
            ui.postDelayed(this, SYNC_MS);
        }
    };

    /** Keeps the "x minutes ago" labels of every project moving without waiting for a sync. */
    private final Runnable clockTask = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            if (!allRepos.isEmpty() && !swipeRows.hasOpen()) render();
            showTokenWarn();
            ui.postDelayed(this, CLOCK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Themes.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_repos);
        buildSelectBar();
        BottomNav.attach(this, BottomNav.REPOS);
        api = new GitHubApi(this);
        status = findViewById(R.id.status);
        tokenWarn = findViewById(R.id.tokenWarn);
        tokenWarn.setOnClickListener(v -> startActivity(new Intent(this, AccountActivity.class)));
        count = findViewById(R.id.count);
        chipRow = findViewById(R.id.chipRow);
        ListView list = findViewById(R.id.list);
        listView = list;
        refreshBtn = findViewById(R.id.btnRefresh);
        swipe = findViewById(R.id.swipe);
        if (swipe != null) {
            swipe.setColorSchemeColors(Ui.color(this, R.color.accent));
            swipe.setProgressBackgroundColorSchemeColor(Ui.color(this, R.color.surface_high));
            swipe.setOnRefreshListener(() -> {
                refreshBtn.performClick();
                swipe.postDelayed(() -> {
                    if (swipe != null) swipe.setRefreshing(false);
                }, 10000);
            });
        }
        // GitHub server status: a button in the top bar (its dot shows the state), details open on tap
        statusCard = new GhStatusCard(this, findViewById(R.id.btnStatus), findViewById(R.id.statusDot));
        adapter = new RowAdapter(this);
        list.setAdapter(adapter);
        swipeRows = new SwipeRows(list, Ui.dp(this, 216));
        list.setOnTouchListener(swipeRows);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL) swipeRows.closeOpen();
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
            }
        });
        adapter.setDecorator(this::decorateRow);
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= shown.size()) return false;
            swipeRows.closeOpen();
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (!selectMode) enterSelect();
            picked.add(fullName(shown.get(pos)));
            render();
            return true;
        });
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (swipeRows.closeOpen()) return;
            if (pos < 0 || pos >= shown.size()) return;
            JSONObject o = shown.get(pos);
            if (selectMode) {
                String fn = fullName(o);
                if (!picked.remove(fn)) picked.add(fn);
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                render();
                return;
            }
            JSONObject own = o.optJSONObject("owner");
            Intent i = new Intent(ReposActivity.this, RepoHomeActivity.class);
            i.putExtra("owner", own != null ? own.optString("login") : "");
            i.putExtra("repo", o.optString("name"));
            i.putExtra("branch", o.optString("default_branch", "main"));
            View titleView = v.findViewById(R.id.title);
            if (Motion.enabled() && titleView != null) {
                if (sharedTitle != null) androidx.core.view.ViewCompat.setTransitionName(sharedTitle, null);
                sharedTitle = titleView;
                androidx.core.view.ViewCompat.setTransitionName(titleView, "repo_title");
                startActivity(i, androidx.core.app.ActivityOptionsCompat
                        .makeSceneTransitionAnimation(this, titleView, "repo_title").toBundle());
            } else {
                startActivity(i);
            }
        });

        for (int id : new int[]{R.id.btnNew, R.id.btnRefresh, R.id.btnAccount, R.id.btnStatus, R.id.btnCleanup, R.id.btnSearch}) {
            Ui.press(this, findViewById(id));
        }
        findViewById(R.id.btnCleanup).setOnClickListener(v -> startActivity(new Intent(this, CleanupActivity.class)));
        findViewById(R.id.btnSearch).setOnClickListener(v -> startActivity(new Intent(this, SearchActivity.class)));
        status.setOnClickListener(v -> {
            if (!emptyShown) load();
        });
        backCb = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                exitSelect();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCb);
        findViewById(R.id.btnNew).setOnClickListener(v -> newRepoDialog());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> {
            if (store != null && store.isShown()) {
                store.refresh();
                return;
            }
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
        store = new StoreTab(this, api, ui, () -> {
            if (selectMode) exitSelect();
            if (store != null && !store.isShown() && !allRepos.isEmpty()) render();
        });
        load();
        Updater.autoCheck(this, io, ui);
    }

    private void buildChips() {
        final String[] labels = {
                getString(R.string.filter_all), getString(R.string.filter_private),
                getString(R.string.filter_public), getString(R.string.filter_forks),
                getString(R.string.filter_archived), getString(R.string.rs_f_pinned),
                getString(R.string.rs_sort_label, sortName())};
        chipRow.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView c = Ui.chip(this, labels[i], i == filter);
            c.setOnClickListener(v -> {
                if (idx == 6) {
                    pickSort();
                    return;
                }
                filter = idx;
                buildChips();
                render();
            });
            chipRow.addView(c);
        }
    }

    private String sortName() {
        int s = Store.repoSort(this);
        return getString(s == 1 ? R.string.rs_sort_name : s == 2 ? R.string.rs_sort_size : R.string.rs_sort_activity);
    }

    private void pickSort() {
        final String[] items = {getString(R.string.rs_sort_activity), getString(R.string.rs_sort_name),
                getString(R.string.rs_sort_size)};
        new Dlg(this).setTitle(R.string.rs_sort_title).setItems(items, (d, which) -> {
            Store.setRepoSort(this, which);
            buildChips();
            render();
        }).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Transfers.removeListener(finishListener);
        resumed = false;
        ui.removeCallbacks(syncTask);
        ui.removeCallbacks(clockTask);
        if (statusCard != null) statusCard.stop();
    }

    private void showTokenWarn() {
        if (tokenWarn == null) return;
        String m = TokenExpiry.banner(this);
        if (m == null) {
            tokenWarn.setVisibility(View.GONE);
        } else {
            tokenWarn.setText("\u26A0  " + m);
            tokenWarn.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        showTokenWarn();
        Transfers.addListener(finishListener);
        if (CleanupJobs.showPending(this)) load();
        if (statusCard != null) statusCard.start();
        if (accountBtn != null) refreshAccountIcon();
        resumed = true;
        ui.removeCallbacks(syncTask);
        ui.removeCallbacks(clockTask);
        if (!allRepos.isEmpty()) {
            render();
            ui.post(syncTask);
        } else {
            ui.postDelayed(syncTask, SYNC_MS);
        }
        ui.postDelayed(clockTask, CLOCK_MS);
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
            case 5:
                if (!Store.isPinned(this, fullName(o))) return false;
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

    private static String fullName(JSONObject o) {
        return o.optString("full_name", o.optString("name"));
    }

    private static int blend(int a, int b, float f) {
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * f);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * f);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f);
        return Color.rgb(r, g, bl);
    }

    /** Green for a repository pushed recently, fading to gray over a year without activity. */
    private int ageColor(JSONObject o) {
        long t = Fmt.parse(o.optString("pushed_at", o.optString("updated_at")));
        if (t == 0) return Ui.color(this, R.color.text_hint);
        float f = (System.currentTimeMillis() - t) / (365f * 24 * 3600 * 1000);
        f = Math.max(0f, Math.min(1f, f));
        return blend(Ui.color(this, R.color.ok), Ui.color(this, R.color.text_hint), f);
    }

    static int langColor(String lang) {
        switch (lang) {
            case "Java":
                return 0xFFB07219;
            case "Kotlin":
                return 0xFFA97BFF;
            case "Python":
                return 0xFF3572A5;
            case "JavaScript":
                return 0xFFF1E05A;
            case "TypeScript":
                return 0xFF3178C6;
            case "HTML":
                return 0xFFE34C26;
            case "CSS":
                return 0xFF563D7C;
            case "C":
                return 0xFF555555;
            case "C++":
                return 0xFFF34B7D;
            case "C#":
                return 0xFF178600;
            case "Go":
                return 0xFF00ADD8;
            case "Rust":
                return 0xFFDEA584;
            case "Swift":
                return 0xFFF05138;
            case "Dart":
                return 0xFF00B4AB;
            case "Shell":
                return 0xFF89E051;
            case "PHP":
                return 0xFF4F5D95;
            case "Ruby":
                return 0xFF701516;
            default:
                return 0xFF8A8A96;
        }
    }

    private void render() {
        shown.clear();
        for (JSONObject o : allRepos) if (matches(o)) shown.add(o);
        final Set<String> pins = Store.pinned(this);
        final int sort = Store.repoSort(this);
        Collections.sort(shown, (x, y) -> {
            boolean px = pins.contains(fullName(x));
            boolean py = pins.contains(fullName(y));
            if (px != py) return px ? -1 : 1;
            if (sort == 1) return x.optString("name").compareToIgnoreCase(y.optString("name"));
            if (sort == 2) return Long.compare(y.optLong("size"), x.optLong("size"));
            return 0;
        });
        List<Row> rows = new ArrayList<>();
        for (JSONObject o : shown) {
            JSONObject own = o.optJSONObject("owner");
            StringBuilder sub = new StringBuilder();
            if (pins.contains(fullName(o))) sub.append("\uD83D\uDCCC ");
            sub.append(own != null ? own.optString("login") : "");
            String lang = o.optString("language", "");
            boolean hasLang = !lang.isEmpty() && !"null".equals(lang);
            if (hasLang) sub.append(" \u00B7 ").append(lang);
            int stars = o.optInt("stargazers_count");
            if (stars > 0) sub.append(" \u00B7 \u2605").append(stars);
            String ago = Fmt.ago(o.optString("pushed_at", o.optString("updated_at")));
            if (!ago.isEmpty()) sub.append(" \u00B7 ").append(ago);
            Row row = new Row(R.drawable.ic_repo, true, o.optString("name"), sub.toString(),
                    o.optBoolean("private"), true);
            row.tint(ageColor(o));
            if (hasLang) row.dot = langColor(lang);
            if (selectMode) row.select(true, picked.contains(fullName(o)));
            if (o.optBoolean("archived")) row.badge(getString(R.string.archived), Ui.color(this, R.color.warn));
            else if (o.optBoolean("fork")) row.badge("Fork", Ui.color(this, R.color.info));
            rows.add(row);
        }
        adapter.setRows(rows);
        if (store == null || !store.isShown()) count.setText(getString(R.string.repos_count, rows.size()));
        if (rows.isEmpty() && !allRepos.isEmpty()) {
            emptyShown = true;
            status.setText(R.string.rs_empty_filter);
            status.setVisibility(View.VISIBLE);
        } else if (emptyShown) {
            emptyShown = false;
            status.setVisibility(View.GONE);
        }
        if (selCount != null) selCount.setText(getString(R.string.rs_selected, picked.size()));
    }


    private JSONObject repoByName(String fn) {
        for (JSONObject o : shown) if (fn.equals(fullName(o))) return o;
        return null;
    }

    private View trayButton(int icon, LinearLayout tray) {
        android.widget.ImageView b = new android.widget.ImageView(this);
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 2), Ui.dp(this, 3), Ui.dp(this, 2));
        b.setImageResource(icon);
        Ui.press(this, b);
        tray.addView(b, lp);
        return b;
    }

    private void paintTray(View b, int fillRes, int tintRes) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.radius(this, R.dimen.radius_m));
        g.setColor(Ui.color(this, fillRes));
        b.setBackground(g);
        ((android.widget.ImageView) b).setImageTintList(
                android.content.res.ColorStateList.valueOf(Ui.color(this, tintRes)));
    }

    private LinearLayout buildTray(ViewGroup row) {
        LinearLayout t = new LinearLayout(this);
        t.setId(R.id.swipe_tray);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setVisibility(View.INVISIBLE);
        row.addView(t, 0, new FrameLayout.LayoutParams(Ui.dp(this, 216), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END));
        trayButton(R.drawable.ic_folder_star, t);
        trayButton(R.drawable.ic_copy, t);
        trayButton(R.drawable.ic_delete, t);
        return t;
    }

    private void updateTray(LinearLayout tray, final String fn) {
        final boolean pinned = Store.isPinned(this, fn);
        View pin = tray.getChildAt(0);
        View copy = tray.getChildAt(1);
        View del = tray.getChildAt(2);
        paintTray(pin, pinned ? R.color.accent : R.color.accent_soft, pinned ? R.color.on_accent : R.color.accent_text);
        paintTray(copy, R.color.surface_high, R.color.text_primary);
        paintTray(del, R.color.bad, R.color.on_accent);
        pin.setContentDescription(getString(pinned ? R.string.rs_unpin : R.string.rs_pin));
        copy.setContentDescription(getString(R.string.sw_copy_url));
        del.setContentDescription(getString(R.string.rs_delete));
        pin.setOnClickListener(v -> {
            Store.setPinned(this, fn, !Store.isPinned(this, fn));
            swipeRows.closeOpen();
            render();
        });
        copy.setOnClickListener(v -> {
            JSONObject o = repoByName(fn);
            swipeRows.closeOpen();
            if (o == null) return;
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("url", o.optString("html_url", "https://github.com/" + fn)));
            }
            android.widget.Toast.makeText(this, R.string.sw_url_copied, android.widget.Toast.LENGTH_SHORT).show();
        });
        del.setOnClickListener(v -> {
            JSONObject o = repoByName(fn);
            swipeRows.closeOpen();
            if (o == null) return;
            if (Store.isProtected(this, fn)) {
                android.widget.Toast.makeText(this, R.string.sw_protected, android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            List<String[]> one = pickedTargets(Collections.singletonList(o));
            CleanupJobs.confirmDelete(this, one, null);
        });
    }

    private LinearLayout buildStats(LinearLayout card) {
        LinearLayout s = new LinearLayout(this);
        s.setId(R.id.row_spark);
        s.setOrientation(LinearLayout.HORIZONTAL);
        s.setGravity(Gravity.CENTER_VERTICAL);
        s.setVisibility(View.GONE);
        s.addView(new SparklineView(this), new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 22)));
        View dot = new View(this);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10));
        dp.setMarginStart(Ui.dp(this, 6));
        s.addView(dot, dp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 8));
        card.addView(s, card.indexOfChild(card.findViewById(R.id.badge)), lp);
        return s;
    }

    private void bindStats(LinearLayout stats, RepoStats.Info info) {
        SparklineView spark = (SparklineView) stats.getChildAt(0);
        View dot = stats.getChildAt(1);
        boolean hasSpark = info != null && info.weeks != null;
        int runColor = 0;
        int runDesc = 0;
        if (info != null && info.run != null) {
            if ("success".equals(info.run)) {
                runColor = Ui.color(this, R.color.ok);
                runDesc = R.string.rs_run_ok;
            } else if ("failure".equals(info.run)) {
                runColor = Ui.color(this, R.color.bad);
                runDesc = R.string.rs_run_fail;
            } else if ("active".equals(info.run)) {
                runColor = Ui.color(this, R.color.warn);
                runDesc = R.string.rs_run_active;
            }
        }
        if (hasSpark) {
            spark.setData(info.weeks, Ui.color(this, R.color.accent_text));
            spark.setVisibility(View.VISIBLE);
        } else {
            spark.setVisibility(View.GONE);
        }
        if (runColor != 0) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(runColor);
            dot.setBackground(g);
            dot.setContentDescription(getString(runDesc));
            dot.setVisibility(View.VISIBLE);
        } else {
            dot.setVisibility(View.GONE);
        }
        stats.setVisibility(hasSpark || runColor != 0 ? View.VISIBLE : View.GONE);
    }

    private void decorateRow(final View row, int pos) {
        View card = row.findViewById(R.id.card);
        if (card == null || !(card instanceof LinearLayout)) return;
        card.animate().cancel();
        card.setTranslationX(0f);
        if (pos < 0 || pos >= shown.size()) return;
        JSONObject o = shown.get(pos);
        final String fn = fullName(o);
        LinearLayout tray = row.findViewById(R.id.swipe_tray);
        if (tray == null) tray = buildTray((ViewGroup) row);
        tray.setVisibility(View.INVISIBLE);
        updateTray(tray, fn);

        LinearLayout stats = card.findViewById(R.id.row_spark);
        if (stats == null) stats = buildStats((LinearLayout) card);
        row.setTag(R.id.row_repo, fn);
        JSONObject own = o.optJSONObject("owner");
        final String owner = own != null ? own.optString("login") : "";
        final String name = o.optString("name");
        RepoStats.Info info = RepoStats.cached(this, fn);
        bindStats(stats, info);
        if (RepoStats.stale(info)) {
            ui.postDelayed(() -> {
                if (isFinishing() || !fn.equals(row.getTag(R.id.row_repo)) || !row.isAttachedToWindow()) return;
                RepoStats.load(this, owner, name, ui, i -> {
                    if (isFinishing() || !fn.equals(row.getTag(R.id.row_repo))) return;
                    LinearLayout s = row.findViewById(R.id.row_spark);
                    if (s != null) bindStats(s, i);
                });
            }, 350);
        }
    }

    private void buildSelectBar() {
        android.widget.FrameLayout content = findViewById(android.R.id.content);
        View root = content.getChildAt(0);
        if (!(root instanceof LinearLayout)) return;
        selBar = new LinearLayout(this);
        selBar.setOrientation(LinearLayout.HORIZONTAL);
        selBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        selBar.setPaddingRelative(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        selBar.setBackgroundResource(R.drawable.bg_card);
        selBar.setVisibility(View.GONE);
        TextView close = Ui.chip(this, getString(R.string.cancel), false);
        close.setOnClickListener(v -> exitSelect());
        selCount = new TextView(this);
        selCount.setTextColor(Ui.color(this, R.color.text_primary));
        selCount.setTextSize(15);
        selCount.setPaddingRelative(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        TextView all = Ui.chip(this, getString(R.string.rs_select_all), false);
        all.setOnClickListener(v -> {
            for (JSONObject o : shown) picked.add(fullName(o));
            render();
        });
        TextView act = Ui.chip(this, getString(R.string.rs_actions), true);
        act.setOnClickListener(v -> showActions());
        selBar.addView(close);
        selBar.addView(selCount, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selBar.addView(all);
        selBar.addView(act);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4));
        ((LinearLayout) root).addView(selBar, lp);
    }

    private void enterSelect() {
        selectMode = true;
        swipeRows.setEnabled(false);
        picked.clear();
        if (selBar != null) selBar.setVisibility(View.VISIBLE);
        if (backCb != null) backCb.setEnabled(true);
    }

    private void exitSelect() {
        selectMode = false;
        swipeRows.setEnabled(true);
        picked.clear();
        if (selBar != null) selBar.setVisibility(View.GONE);
        if (backCb != null) backCb.setEnabled(false);
        render();
    }

    private List<JSONObject> pickedRepos() {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : shown) if (picked.contains(fullName(o))) out.add(o);
        return out;
    }

    private List<String[]> pickedTargets(List<JSONObject> sel) {
        List<String[]> out = new ArrayList<>();
        for (JSONObject o : sel) {
            JSONObject own = o.optJSONObject("owner");
            out.add(new String[]{own != null ? own.optString("login") : "", o.optString("name")});
        }
        return out;
    }

    private void showActions() {
        final List<JSONObject> sel = pickedRepos();
        if (sel.isEmpty()) return;
        boolean allPinned = true;
        boolean allProtected = true;
        for (JSONObject o : sel) {
            if (!Store.isPinned(this, fullName(o))) allPinned = false;
            if (!Store.isProtected(this, fullName(o))) allProtected = false;
        }
        final boolean unpin = allPinned;
        final boolean unprotect = allProtected;
        final String[] items = {getString(R.string.rs_archive), getString(R.string.rs_delete),
                getString(R.string.rs_star), getString(R.string.rs_copy_urls),
                getString(unpin ? R.string.rs_unpin : R.string.rs_pin),
                getString(unprotect ? R.string.rs_unprotect : R.string.rs_protect)};
        new Dlg(this).setTitle(getString(R.string.rs_selected, sel.size())).setItems(items, (d, which) -> {
            switch (which) {
                case 0:
                    CleanupJobs.confirmArchive(this, pickedTargets(sel), this::exitSelect);
                    break;
                case 1:
                    CleanupJobs.confirmDelete(this, pickedTargets(sel), this::exitSelect);
                    break;
                case 2:
                    starAll(sel);
                    break;
                case 3:
                    copyUrls(sel);
                    break;
                case 4:
                    for (JSONObject o : sel) Store.setPinned(this, fullName(o), !unpin);
                    android.widget.Toast.makeText(this, getString(R.string.rs_done_n, sel.size()),
                            android.widget.Toast.LENGTH_SHORT).show();
                    exitSelect();
                    break;
                default:
                    for (JSONObject o : sel) Store.setProtected(this, fullName(o), !unprotect);
                    android.widget.Toast.makeText(this, getString(R.string.rs_done_n, sel.size()),
                            android.widget.Toast.LENGTH_SHORT).show();
                    exitSelect();
                    break;
            }
        }).show();
    }

    private void starAll(final List<JSONObject> sel) {
        final List<String[]> targets = pickedTargets(sel);
        exitSelect();
        io.execute(() -> {
            int ok = 0;
            for (String[] t : targets) {
                try {
                    api.star(t[0], t[1], true);
                    ok++;
                } catch (Exception ignored) {
                }
            }
            final int done = ok;
            ui.post(() -> {
                if (isFinishing()) return;
                android.widget.Toast.makeText(this, getString(R.string.rs_starred_n, done),
                        android.widget.Toast.LENGTH_SHORT).show();
                load(true);
            });
        });
    }

    private void copyUrls(List<JSONObject> sel) {
        StringBuilder sb = new StringBuilder();
        for (JSONObject o : sel) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(o.optString("html_url", "https://github.com/" + fullName(o)));
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("urls", sb.toString()));
        android.widget.Toast.makeText(this, getString(R.string.rs_urls_copied, sel.size()),
                android.widget.Toast.LENGTH_SHORT).show();
        exitSelect();
    }

    private void showSkeleton(boolean on) {
        ViewGroup parent = (ViewGroup) listView.getParent();
        if (on) {
            if (skeleton == null) {
                skeleton = new ShimmerView(this, ShimmerView.REPOS);
                ViewGroup.LayoutParams lp = parent instanceof LinearLayout
                        ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                        : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT);
                parent.addView(skeleton, parent.indexOfChild(listView), lp);
            }
            skeleton.setVisibility(View.VISIBLE);
            skeleton.invalidate();
            listView.setVisibility(View.GONE);
        } else if (skeleton != null) {
            skeleton.setVisibility(View.GONE);
            listView.setVisibility(View.VISIBLE);
        }
    }

    private static String sign(JSONArray arr) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            b.append(o.optLong("id")).append('|').append(o.optString("pushed_at")).append('|')
                    .append(o.optString("updated_at")).append('|').append(o.optInt("stargazers_count")).append('|')
                    .append(o.optBoolean("private")).append(o.optBoolean("archived")).append(';');
        }
        return b.toString();
    }

    private void fill(JSONArray arr) throws Exception {
        signature = sign(arr);
        final List<JSONObject> tmp = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) tmp.add(arr.getJSONObject(i));
        allRepos.clear();
        allRepos.addAll(tmp);
        render();
    }

    private void load() {
        load(false);
    }

    private void load(final boolean quiet) {
        if (quiet && (syncing || allRepos.isEmpty())) return;
        final boolean hadData = !allRepos.isEmpty();
        if (quiet) {
            syncing = true;
            io.execute(() -> {
                try {
                    final JSONArray arr = api.listRepos();
                    RepoCache.save(this, arr);
                    final String sig = sign(arr);
                    ui.post(() -> {
                        syncing = false;
                        if (isFinishing() || !resumed || sig.equals(signature)) return;
                        try {
                            fill(arr);
                        } catch (Exception ignored) {
                        }
                    });
                } catch (Exception e) {
                    ui.post(() -> syncing = false);
                }
            });
            return;
        }
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
                    if (swipe != null) swipe.setRefreshing(false);
                    status.setVisibility(View.GONE);
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    if (isFinishing()) return;
                    Ui.spin(refreshBtn, false);
                    if (swipe != null) swipe.setRefreshing(false);
                    showSkeleton(false);
                    boolean rejected = e instanceof GitHubApi.ApiException && ((GitHubApi.ApiException) e).code == 401;
                    String msg = rejected ? getString(R.string.token_rejected_msg) : String.valueOf(e.getMessage());
                    if (allRepos.isEmpty()) {
                        // keep the session on 401: the user decides whether to sign in again
                        status.setVisibility(View.VISIBLE);
                        status.setText(msg + "\n" + getString(R.string.rs_retry));
                        status.setMinHeight(Ui.dp(this, 48));
                        status.setOnClickListener(v -> load());
                    } else if (!(e instanceof GitHubApi.ApiException)) {
                        status.setVisibility(View.VISIBLE);
                        status.setText(getString(R.string.cache_offline, Fmt.agoMs(RepoCache.savedAt(this))));
                        status.setMinHeight(Ui.dp(this, 48));
                        status.setOnClickListener(v -> load());
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
        if (store != null) store.destroy();
        io.shutdown();
    }
}

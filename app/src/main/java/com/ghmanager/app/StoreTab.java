package com.ghmanager.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.text.method.LinkMovementMethod;
import android.text.style.StyleSpan;
import android.util.Base64;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StoreTab {
    private static final int PER_PAGE = 25;
    private static final int MAX_RESULTS = 1000;
    private static final long DEBOUNCE_MS = 500;
    private static final String PREFS = "gh";
    private static final String PREF_TAB = "store_tab";
    private static final String PREF_SORT = "store_f_sort";
    private static final String PREF_DESC = "store_f_desc";
    private static final String PREF_LANG = "store_f_lang";
    private static final String PREF_MIN = "store_f_min";
    private static final String PREF_PERIOD = "store_f_period";
    private static final String PREF_ACTIVE = "store_f_active";
    private static final String STAR_KEY = "store|starred";
    private static final long STAR_FRESH_MS = 6L * 3600L * 1000L;
    private static final long RATE_DEFAULT_MS = 60000L;
    private static final int TOP_AFTER = 15;
    private static final int RECENT_MAX = 6;
    private static final int README_MAX = 2500;
    private static final String[] SLIM = {"id", "name", "full_name", "description", "html_url", "clone_url",
            "default_branch", "language", "stargazers_count", "forks_count", "pushed_at", "updated_at",
            "created_at", "archived", "fork"};
    private static final String[] LANGS = {"Java", "Kotlin", "Python", "JavaScript", "TypeScript", "C", "C++", "C#",
            "Go", "Rust", "Swift", "Dart", "PHP", "Ruby", "Shell", "HTML", "CSS", "Lua", "Scala", "Jupyter Notebook"};
    private static final String[] SORT_KEYS = {"stars", "forks", "updated", ""};
    private static final long[] MIN_STARS = {0, 100, 1000, 10000};
    private static final int[] PERIOD_DAYS = {0, 7, 30, 365};
    private static final Pattern URL_REPO = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?github\\.com/([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(?:[/?#].*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_OWNER = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?github\\.com/([A-Za-z0-9-]+)/?(?:[?#].*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SLUG = Pattern.compile("^([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+)$");

    private final AppCompatActivity a;
    private final GitHubApi api;
    private final Handler ui;
    private final Runnable onTab;
    private final SharedPreferences prefs;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final List<JSONObject> items = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private final List<JSONObject> starred = new ArrayList<>();
    private final Set<String> starredNames = new HashSet<>();
    private final List<Row> rowList = new ArrayList<>();
    private final List<CharSequence> statList = new ArrayList<>();
    private final RowAdapter adapter;
    private final View paneMine;
    private final View paneStore;
    private final EditText input;
    private final LinearLayout chips;
    private final TextView statusView;
    private final TextView moreView;
    private final TextView retryBtn;
    private final TextView clearBtn;
    private final LinearLayout recentBox;
    private final SwipeRefreshLayout swipe;
    private final View host;
    private final ImageView topBtn;
    private final TextView headerCount;
    private final View btnNew;
    private final View btnCleanup;
    private final ListView list;
    private final ActivityResultLauncher<Intent> saveLauncher;
    private final boolean rtl;
    private ShimmerView skeleton;
    private String[] pendingZip;
    private Boolean retryOn;
    private TextView tabMine;
    private TextView tabStore;

    private int gen;
    private int page;
    private int total;
    private boolean loading;
    private boolean more;
    private boolean done;
    private boolean failedMore;
    private boolean started;
    private boolean muted;
    private boolean storeShown;
    private boolean starredLoaded;
    private boolean warming;
    private boolean cachedShown;
    private boolean skipCache;
    private boolean rowsDirty;
    private boolean rateStatus;
    private boolean topShown;
    private long rateUntil;
    private String statusMsg;

    private int sortIdx;
    private boolean desc = true;
    private String lang = "";
    private int minIdx;
    private int periodIdx;
    private boolean activeOnly;
    private boolean fav;

    private final Runnable go = this::restart;
    private final Runnable warm = this::warmStarred;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (a.isFinishing() || a.isDestroyed()) return;
            publish();
            if (rateUntil > System.currentTimeMillis()) ui.postDelayed(this, 1000);
        }
    };

    StoreTab(AppCompatActivity activity, GitHubApi api, Handler ui, Runnable onTab) {
        this.a = activity;
        this.api = api;
        this.ui = ui;
        this.onTab = onTab;
        prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        paneMine = a.findViewById(R.id.paneMine);
        paneStore = a.findViewById(R.id.paneStore);
        input = a.findViewById(R.id.storeSearch);
        chips = a.findViewById(R.id.storeChips);
        statusView = a.findViewById(R.id.storeStatus);
        moreView = a.findViewById(R.id.storeMore);
        retryBtn = a.findViewById(R.id.storeRetry);
        clearBtn = a.findViewById(R.id.storeClear);
        recentBox = a.findViewById(R.id.storeRecent);
        swipe = a.findViewById(R.id.storeSwipe);
        host = a.findViewById(R.id.storeHost);
        topBtn = a.findViewById(R.id.storeTop);
        headerCount = a.findViewById(R.id.count);
        btnNew = a.findViewById(R.id.btnNew);
        btnCleanup = a.findViewById(R.id.btnCleanup);
        list = a.findViewById(R.id.storeList);
        rtl = a.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        adapter = new RowAdapter(a) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (position >= PER_PAGE) {
                    v.animate().cancel();
                    v.setAlpha(1f);
                    v.setTranslationY(0f);
                }
                return v;
            }
        };
        list.setAdapter(adapter);
        adapter.setDecorator(this::decorate);
        loadFilters();
        saveLauncher = a.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
            String[] z = pendingZip;
            pendingZip = null;
            Intent d = res.getData();
            if (z == null || res.getResultCode() != Activity.RESULT_OK || d == null) return;
            ArrayList<String> paths = d.getStringArrayListExtra("paths");
            if (paths == null || paths.isEmpty()) return;
            startZip(z, new File(paths.get(0)));
        });
        buildTabs((LinearLayout) a.findViewById(R.id.tabBar));
        buildChips();
        bindList();
        bindInput();
        bindExtras();
        moreView.setOnClickListener(v -> {
            if (!failedMore || rateUntil > System.currentTimeMillis()) return;
            failedMore = false;
            more = true;
            loadPage(gen);
            publish();
        });
        select(prefs.getInt(PREF_TAB, 0) == 1);
    }

    boolean isShown() {
        return storeShown;
    }

    void refresh() {
        starredLoaded = false;
        skipCache = true;
        restart();
    }

    void destroy() {
        ui.removeCallbacks(go);
        ui.removeCallbacks(tick);
        ui.removeCallbacks(warm);
        pool.shutdownNow();
    }

    private void loadFilters() {
        int s = prefs.getInt(PREF_SORT, 0);
        sortIdx = s >= 0 && s < SORT_KEYS.length ? s : 0;
        desc = prefs.getBoolean(PREF_DESC, true);
        String l = prefs.getString(PREF_LANG, "");
        lang = "";
        for (String x : LANGS) if (x.equals(l)) lang = x;
        int m = prefs.getInt(PREF_MIN, 0);
        minIdx = m >= 0 && m < MIN_STARS.length ? m : 0;
        int p = prefs.getInt(PREF_PERIOD, 0);
        periodIdx = p >= 0 && p < PERIOD_DAYS.length ? p : 0;
        activeOnly = prefs.getBoolean(PREF_ACTIVE, false);
    }

    private void saveFilters() {
        prefs.edit().putInt(PREF_SORT, sortIdx).putBoolean(PREF_DESC, desc).putString(PREF_LANG, lang)
                .putInt(PREF_MIN, minIdx).putInt(PREF_PERIOD, periodIdx).putBoolean(PREF_ACTIVE, activeOnly).apply();
    }

    private boolean filtersActive() {
        return sortIdx != 0 || !desc || !lang.isEmpty() || minIdx != 0 || periodIdx != 0 || activeOnly || fav;
    }

    private void clearFilters() {
        sortIdx = 0;
        desc = true;
        lang = "";
        minIdx = 0;
        periodIdx = 0;
        activeOnly = false;
        fav = false;
        buildChips();
        restart();
    }

    private void bindExtras() {
        swipe.setColorSchemeColors(Ui.color(a, R.color.accent));
        swipe.setProgressBackgroundColorSchemeColor(Ui.color(a, R.color.surface_high));
        swipe.setOnRefreshListener(this::refresh);
        retryBtn.setOnClickListener(v -> {
            if (retryBtn.isEnabled()) restart();
        });
        Ui.press(a, retryBtn);
        clearBtn.setOnClickListener(v -> {
            muted = true;
            input.setText("");
            muted = false;
            updateInputState();
            restart();
        });
        GradientDrawable oval = new GradientDrawable();
        oval.setShape(GradientDrawable.OVAL);
        oval.setColor(Ui.color(a, R.color.surface_high));
        oval.setStroke(Ui.dp(a, 1), Ui.color(a, R.color.accent));
        topBtn.setBackground(oval);
        topBtn.setElevation(Ui.dp(a, 4));
        topBtn.setImageTintList(ColorStateList.valueOf(Ui.color(a, R.color.accent_text)));
        Ui.press(a, topBtn);
        topBtn.setOnClickListener(v -> {
            if (list.getFirstVisiblePosition() > 40) list.setSelection(12);
            list.smoothScrollToPosition(0);
        });
        input.setOnFocusChangeListener((v, f) -> showRecent());
        statusView.setOnClickListener(v -> {
            if (retryBtn.isEnabled()) restart();
        });
    }

    private void updateInputState() {
        clearBtn.setVisibility(input.getText().length() > 0 ? View.VISIBLE : View.GONE);
        showRecent();
    }

    private void showRecent() {
        recentBox.removeAllViews();
        if (!storeShown || !input.hasFocus() || input.getText().length() > 0) {
            recentBox.setVisibility(View.GONE);
            return;
        }
        List<String> all = RecentSearches.all(a);
        int n = Math.min(all.size(), RECENT_MAX);
        if (n == 0) {
            recentBox.setVisibility(View.GONE);
            return;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.radius(a, R.dimen.radius_s));
        bg.setColor(Ui.color(a, R.color.surface));
        bg.setStroke(Ui.dp(a, 1), Ui.color(a, R.color.stroke));
        recentBox.setBackground(bg);
        for (int i = 0; i < n; i++) {
            final String q = all.get(i);
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(a, 48));
            row.setClickable(true);
            row.setFocusable(true);
            TextView t = new TextView(a);
            t.setText(q);
            t.setTextSize(14);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            t.setTextColor(Ui.color(a, R.color.text_primary));
            t.setPaddingRelative(Ui.dp(a, 16), 0, Ui.dp(a, 8), 0);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView x = new TextView(a);
            x.setText("\u2715");
            x.setTextSize(14);
            x.setGravity(Gravity.CENTER);
            x.setTextColor(Ui.color(a, R.color.text_secondary));
            x.setContentDescription(a.getString(R.string.store_recent_remove));
            x.setOnClickListener(v -> {
                RecentSearches.remove(a, q);
                showRecent();
            });
            row.addView(x, new LinearLayout.LayoutParams(Ui.dp(a, 48), Ui.dp(a, 48)));
            row.setOnClickListener(v -> {
                muted = true;
                input.setText(q);
                input.setSelection(input.getText().length());
                muted = false;
                hideKeyboard();
                updateInputState();
                restart();
            });
            recentBox.addView(row);
        }
        recentBox.setVisibility(View.VISIBLE);
    }

    private void header() {
        btnNew.setVisibility(storeShown ? View.GONE : View.VISIBLE);
        btnCleanup.setVisibility(storeShown ? View.GONE : View.VISIBLE);
        headerCount.setText(storeShown ? countText() : "");
    }

    private String countText() {
        if (items.isEmpty() || (total <= 0 && !fav)) return "";
        int n = fav ? items.size() : total;
        String s = a.getString(R.string.store_count, String.format(Locale.US, "%,d", n));
        if (!fav && n > MAX_RESULTS) s += " \u00B7 " + a.getString(R.string.store_first_1000);
        return s;
    }

    private void buildTabs(LinearLayout bar) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.radius(a, R.dimen.radius_pill));
        bg.setColor(Ui.color(a, R.color.surface));
        bg.setStroke(Ui.dp(a, 1), Ui.color(a, R.color.stroke));
        bar.setBackground(bg);
        int pad = Ui.dp(a, 4);
        bar.setPadding(pad, pad, pad, pad);
        tabMine = tabText(R.string.store_tab_mine);
        tabStore = tabText(R.string.store_tab_store);
        tabMine.setOnClickListener(v -> select(false));
        tabStore.setOnClickListener(v -> select(true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 44), 1f);
        bar.addView(tabMine, lp);
        bar.addView(tabStore, new LinearLayout.LayoutParams(0, Ui.dp(a, 44), 1f));
    }

    private TextView tabText(int res) {
        TextView t = new TextView(a);
        t.setText(res);
        t.setTextSize(14);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        ViewCompat.setAccessibilityDelegate(t, A11y.button());
        Ui.press(a, t);
        return t;
    }

    private void paintTab(TextView t, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.radius(a, R.dimen.radius_pill));
        g.setColor(on ? Ui.color(a, R.color.accent_soft) : Color.TRANSPARENT);
        g.setStroke(on ? Ui.dp(a, 1) : 0, Ui.color(a, R.color.accent));
        t.setBackground(g);
        t.setTextColor(Ui.color(a, on ? R.color.accent_text : R.color.text_secondary));
        t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        ViewCompat.setStateDescription(t, on ? a.getString(R.string.a11y_selected) : null);
    }

    private void select(boolean store) {
        storeShown = store;
        paneMine.setVisibility(store ? View.GONE : View.VISIBLE);
        paneStore.setVisibility(store ? View.VISIBLE : View.GONE);
        paintTab(tabMine, !store);
        paintTab(tabStore, store);
        prefs.edit().putInt(PREF_TAB, store ? 1 : 0).apply();
        hideKeyboard();
        header();
        if (onTab != null) onTab.run();
        if (store && !started) {
            started = true;
            restart();
            ui.postDelayed(warm, 1500);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    private void bindInput() {
        input.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                RecentSearches.add(a, SearchQuery.normalize(input.getText().toString()));
                hideKeyboard();
                restart();
                return true;
            }
            return false;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateInputState();
                if (muted) return;
                gen++;
                loading = false;
                ui.removeCallbacks(go);
                ui.postDelayed(go, DEBOUNCE_MS);
            }
        });
    }

    private void bindList() {
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int totalCount) {
                if (totalCount > 0 && more && !loading && !fav && first + visible >= totalCount - 4) loadPage(gen);
                topVisible(first > TOP_AFTER);
            }
        });
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= items.size()) return;
            JSONObject o = items.get(pos);
            String typed = SearchQuery.normalize(input.getText().toString());
            if (!typed.isEmpty()) RecentSearches.add(a, typed);
            JSONObject own = o.optJSONObject("owner");
            Intent i = new Intent(a, RepoHomeActivity.class);
            i.putExtra("owner", own != null ? own.optString("login") : "");
            i.putExtra("repo", o.optString("name"));
            i.putExtra("branch", o.optString("default_branch", "main"));
            a.startActivity(i);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= items.size()) return false;
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            menu(items.get(pos));
            return true;
        });
    }

    private void topVisible(boolean on) {
        if (topShown == on) return;
        topShown = on;
        topBtn.animate().cancel();
        if (!Motion.enabled()) {
            topBtn.setAlpha(1f);
            topBtn.setVisibility(on ? View.VISIBLE : View.GONE);
            return;
        }
        if (on) {
            topBtn.setAlpha(0f);
            topBtn.setVisibility(View.VISIBLE);
            topBtn.animate().alpha(1f).setDuration(Motion.base(a)).start();
        } else {
            topBtn.animate().alpha(0f).setDuration(Motion.base(a)).withEndAction(() -> {
                if (!topShown) topBtn.setVisibility(View.GONE);
            }).start();
        }
    }

    private void buildChips() {
        chips.removeAllViews();
        lastClearChip = filtersActive();
        if (filtersActive()) addChip(a.getString(R.string.store_clear_filters), false, v -> clearFilters());
        String[] sortLabels = sortLabels();
        addChip(a.getString(R.string.store_sort_chip, sortLabels[sortIdx]), sortIdx != 0, v -> pickSort(sortLabels));
        addChip((desc ? "\u2193 " : "\u2191 ") + a.getString(desc ? R.string.store_desc : R.string.store_asc), false, v -> {
            desc = !desc;
            buildChips();
            restart();
        });
        addChip(lang.isEmpty() ? a.getString(R.string.store_lang) : lang, !lang.isEmpty(), v -> pickLang());
        addChip(minIdx == 0 ? a.getString(R.string.store_min_stars) : "\u2605 \u2265 " + compact(MIN_STARS[minIdx]),
                minIdx != 0, v -> pickMinStars());
        addChip(periodIdx == 0 ? a.getString(R.string.store_period)
                : a.getString(R.string.store_period_chip, periodLabels()[periodIdx]), periodIdx != 0, v -> pickPeriod());
        addChip(a.getString(R.string.store_active), activeOnly, v -> {
            activeOnly = !activeOnly;
            buildChips();
            restart();
        });
        addChip("\u2605 " + a.getString(R.string.store_fav), fav, v -> {
            fav = !fav;
            buildChips();
            restart();
        });
    }

    private void addChip(String text, boolean on, View.OnClickListener l) {
        TextView c = Ui.chip(a, text, on);
        c.setOnClickListener(l);
        chips.addView(c);
    }

    private String[] sortLabels() {
        return new String[]{a.getString(R.string.store_sort_stars), a.getString(R.string.store_sort_forks),
                a.getString(R.string.store_sort_updated), a.getString(R.string.store_sort_match)};
    }

    private String[] periodLabels() {
        return new String[]{a.getString(R.string.store_any), a.getString(R.string.store_period_week),
                a.getString(R.string.store_period_month), a.getString(R.string.store_period_year)};
    }

    private void pickSort(String[] labels) {
        new Dlg(a).setTitle(a.getString(R.string.store_sort_chip, labels[sortIdx])).setItems(labels, (d, which) -> {
            sortIdx = which;
            buildChips();
            restart();
        }).show();
    }

    private void pickLang() {
        final String[] labels = new String[LANGS.length + 1];
        labels[0] = a.getString(R.string.store_any);
        System.arraycopy(LANGS, 0, labels, 1, LANGS.length);
        new Dlg(a).setTitle(R.string.store_lang).setItems(labels, (d, which) -> {
            lang = which == 0 ? "" : LANGS[which - 1];
            buildChips();
            restart();
        }).show();
    }

    private void pickMinStars() {
        final String[] labels = new String[MIN_STARS.length];
        labels[0] = a.getString(R.string.store_any);
        for (int i = 1; i < MIN_STARS.length; i++) labels[i] = "\u2605 \u2265 " + compact(MIN_STARS[i]);
        new Dlg(a).setTitle(R.string.store_min_stars).setItems(labels, (d, which) -> {
            minIdx = which;
            buildChips();
            restart();
        }).show();
    }

    private void pickPeriod() {
        new Dlg(a).setTitle(R.string.store_period).setItems(periodLabels(), (d, which) -> {
            periodIdx = which;
            buildChips();
            restart();
        }).show();
    }

    private void restart() {
        ui.removeCallbacks(go);
        ui.removeCallbacks(tick);
        saveFilters();
        gen++;
        items.clear();
        seen.clear();
        resetRows();
        page = 0;
        total = 0;
        loading = false;
        more = true;
        done = false;
        failedMore = false;
        statusMsg = null;
        rateStatus = false;
        rateUntil = 0;
        cachedShown = false;
        showCache();
        buildChipsIfNeeded();
        publish();
        if (fav) loadFav(gen);
        else loadPage(gen);
    }

    private boolean lastClearChip;

    private void buildChipsIfNeeded() {
        boolean now = filtersActive();
        if (now != lastClearChip) {
            lastClearChip = now;
            buildChips();
        }
    }

    private void resetRows() {
        rowList.clear();
        statList.clear();
        rowsDirty = true;
    }

    private String cacheKey(String q, String sort, String order) {
        return "store|" + q + "|" + sort + "|" + order;
    }

    private static JSONObject slim(JSONObject o) {
        JSONObject r = new JSONObject();
        try {
            for (String k : SLIM) if (o.has(k) && !o.isNull(k)) r.put(k, o.get(k));
            JSONObject own = o.optJSONObject("owner");
            if (own != null) r.put("owner", new JSONObject().put("login", own.optString("login")));
        } catch (Exception ignored) {
        }
        return r;
    }

    private void cacheStore(String key, JSONObject res) {
        try {
            JSONArray src = res.optJSONArray("items");
            if (src == null || src.length() == 0) return;
            JSONArray out = new JSONArray();
            out.put(new JSONObject().put("_total", res.optInt("total_count")));
            for (int i = 0; i < src.length(); i++) {
                JSONObject o = src.optJSONObject(i);
                if (o != null) out.put(slim(o));
            }
            ListCache.put(a, key, out);
        } catch (Exception ignored) {
        }
    }

    private void showCache() {
        if (fav || skipCache) {
            skipCache = false;
            return;
        }
        String text = input.getText().toString().trim();
        ListCache.Hit h = ListCache.get(a, cacheKey(buildQuery(text), SORT_KEYS[sortIdx], desc ? "desc" : "asc"));
        if (h == null || h.data.length() < 2) return;
        JSONObject meta = h.data.optJSONObject(0);
        total = meta != null ? meta.optInt("_total") : 0;
        for (int i = 1; i < h.data.length(); i++) {
            JSONObject o = h.data.optJSONObject(i);
            if (o != null) addItem(o);
        }
        cachedShown = !items.isEmpty();
    }

    private static String[] parseTarget(String text) {
        Matcher m = URL_REPO.matcher(text);
        if (m.matches()) return new String[]{m.group(1), m.group(2)};
        m = SLUG.matcher(text);
        if (m.matches()) return new String[]{m.group(1), m.group(2)};
        return null;
    }

    private static String parseOwner(String text) {
        Matcher m = URL_OWNER.matcher(text);
        return m.matches() ? m.group(1) : null;
    }

    private static String dayStr(int daysAgo) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(System.currentTimeMillis() - daysAgo * 86400000L));
    }

    private String buildQuery(String text) {
        StringBuilder q = new StringBuilder();
        String[] target = parseTarget(text);
        String owner = parseOwner(text);
        if (target != null) q.append(target[1]).append(" in:name");
        else if (owner != null) q.append("user:").append(owner);
        else q.append(text);
        if (!lang.isEmpty()) {
            q.append(" language:");
            if (lang.indexOf(' ') >= 0) q.append('"').append(lang).append('"');
            else q.append(lang);
        }
        if (MIN_STARS[minIdx] > 0) q.append(" stars:>=").append(MIN_STARS[minIdx]);
        else if (text.isEmpty()) q.append(" stars:>=500");
        if (periodIdx > 0) q.append(" created:>=").append(dayStr(PERIOD_DAYS[periodIdx]));
        if (activeOnly) q.append(" pushed:>=").append(dayStr(90));
        return q.toString().trim();
    }

    private void loadPage(final int g) {
        if (loading || !more || g != gen) return;
        loading = true;
        final int pg = page + 1;
        final String text = input.getText().toString().trim();
        final String q = buildQuery(text);
        final String sort = SORT_KEYS[sortIdx];
        final String order = desc ? "desc" : "asc";
        final String key = cacheKey(q, sort, order);
        final String[] direct = pg == 1 ? parseTarget(text) : null;
        publish();
        pool.execute(() -> {
            JSONObject hit = null;
            if (direct != null) {
                try {
                    hit = api.getRepo(direct[0], direct[1]);
                } catch (Exception ignored) {
                }
            }
            final JSONObject fh = hit;
            try {
                final JSONObject res = api.searchRepos(q, sort, order, pg, PER_PAGE);
                if (pg == 1) cacheStore(key, res);
                ui.post(() -> onPage(g, pg, fh, res, null));
            } catch (final Exception e) {
                ui.post(() -> onPage(g, pg, fh, null, e));
            }
        });
    }

    private void onPage(int g, int pg, JSONObject hit, JSONObject res, Exception e) {
        if (g != gen || a.isFinishing() || a.isDestroyed()) return;
        loading = false;
        done = true;
        if (e != null) {
            if (hit != null) addItem(hit);
            cachedShown = false;
            more = false;
            boolean rate = isRate(e);
            if (rate) {
                rateUntil = System.currentTimeMillis() + rateWait(e);
                ui.removeCallbacks(tick);
                ui.postDelayed(tick, 1000);
            }
            if (pg == 1 && items.isEmpty()) {
                statusMsg = message(e);
                rateStatus = rate;
            } else if (pg == 1) Toast.makeText(a, message(e), Toast.LENGTH_LONG).show();
            else {
                failedMore = true;
                Toast.makeText(a, message(e), Toast.LENGTH_LONG).show();
            }
            publish();
            return;
        }
        if (pg == 1 && cachedShown) {
            items.clear();
            seen.clear();
            resetRows();
        }
        cachedShown = false;
        if (hit != null) addItem(hit);
        total = res.optInt("total_count");
        JSONArray arr = res.optJSONArray("items");
        int n = arr == null ? 0 : arr.length();
        for (int i = 0; i < n; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) addItem(o);
        }
        page = pg;
        more = n >= PER_PAGE && pg * PER_PAGE < MAX_RESULTS && pg * PER_PAGE < total;
        publish();
    }

    private void addItem(JSONObject o) {
        String fn = fullName(o).toLowerCase(Locale.ROOT);
        if (fn.isEmpty() || !seen.add(fn)) return;
        items.add(o);
    }

    private static boolean isRate(Exception e) {
        if (!(e instanceof GitHubApi.ApiException)) return false;
        int c = ((GitHubApi.ApiException) e).code;
        return c == 403 || c == 429;
    }

    private static long rateWait(Exception e) {
        long w = ((GitHubApi.ApiException) e).waitMs;
        return w > 0 ? w : RATE_DEFAULT_MS;
    }

    private String message(Exception e) {
        if (e instanceof GitHubApi.ApiException) {
            GitHubApi.ApiException x = (GitHubApi.ApiException) e;
            if (x.code == 403 || x.code == 429) {
                long s = x.waitMs > 0 ? (x.waitMs + 999) / 1000 : 0;
                return s > 0 ? a.getString(R.string.store_rate_wait, (int) s) : a.getString(R.string.store_rate);
            }
            if (x.code == 401) return a.getString(R.string.token_rejected_msg);
            if (x.code == 422) return a.getString(R.string.store_bad_query);
        }
        return String.valueOf(e.getMessage());
    }

    private List<JSONObject> fetchStarred() throws Exception {
        final List<JSONObject> all = new ArrayList<>();
        for (int p = 1; p <= 10; p++) {
            JSONArray arr = api.arr("/user/starred?per_page=100&page=" + p);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) all.add(o);
            }
            if (arr.length() < 100) break;
        }
        return all;
    }

    private Set<String> namesOf(List<JSONObject> list) {
        Set<String> out = new HashSet<>();
        for (JSONObject o : list) out.add(fullName(o).toLowerCase(Locale.ROOT));
        return out;
    }

    private void persistNames(Set<String> names) {
        JSONArray arr = new JSONArray();
        for (String n : names) arr.put(n);
        ListCache.put(a, STAR_KEY, arr);
    }

    private boolean applyNames(Set<String> names) {
        if (starredNames.equals(names)) return false;
        starredNames.clear();
        starredNames.addAll(names);
        resetRows();
        return true;
    }

    private void warmStarred() {
        if (a.isFinishing() || a.isDestroyed()) return;
        ListCache.Hit h = ListCache.get(a, STAR_KEY);
        if (h != null && starredNames.isEmpty()) {
            Set<String> names = new HashSet<>();
            for (int i = 0; i < h.data.length(); i++) {
                String n = h.data.optString(i, "");
                if (!n.isEmpty()) names.add(n);
            }
            if (applyNames(names)) publish();
        }
        boolean fresh = h != null && System.currentTimeMillis() - h.at < STAR_FRESH_MS;
        if (fresh || warming || starredLoaded) return;
        warming = true;
        pool.execute(() -> {
            try {
                final List<JSONObject> all = fetchStarred();
                final Set<String> names = namesOf(all);
                persistNames(names);
                ui.post(() -> {
                    warming = false;
                    if (a.isFinishing() || a.isDestroyed()) return;
                    starred.clear();
                    starred.addAll(all);
                    starredLoaded = true;
                    if (applyNames(names)) publish();
                });
            } catch (Exception e) {
                ui.post(() -> warming = false);
            }
        });
    }

    private void loadFav(final int g) {
        if (starredLoaded) {
            applyFav(g);
            return;
        }
        loading = true;
        publish();
        pool.execute(() -> {
            try {
                final List<JSONObject> all = fetchStarred();
                final Set<String> names = namesOf(all);
                persistNames(names);
                ui.post(() -> {
                    if (a.isFinishing() || a.isDestroyed()) return;
                    starred.clear();
                    starred.addAll(all);
                    starredLoaded = true;
                    applyNames(names);
                    if (g == gen) applyFav(g);
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    if (g != gen || a.isFinishing() || a.isDestroyed()) return;
                    loading = false;
                    done = true;
                    more = false;
                    statusMsg = message(e);
                    rateStatus = isRate(e);
                    if (rateStatus) {
                        rateUntil = System.currentTimeMillis() + rateWait(e);
                        ui.removeCallbacks(tick);
                        ui.postDelayed(tick, 1000);
                    }
                    publish();
                });
            }
        });
    }

    private void applyFav(int g) {
        if (g != gen) return;
        loading = false;
        done = true;
        more = false;
        items.clear();
        seen.clear();
        resetRows();
        final String text = input.getText().toString().trim().toLowerCase(Locale.ROOT);
        final long minStars = MIN_STARS[minIdx];
        final long created = periodIdx > 0 ? System.currentTimeMillis() - PERIOD_DAYS[periodIdx] * 86400000L : 0;
        final long pushed = activeOnly ? System.currentTimeMillis() - 90 * 86400000L : 0;
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject o : starred) {
            if (!text.isEmpty()) {
                String n = fullName(o).toLowerCase(Locale.ROOT);
                String d = Fmt.s(o, "description").toLowerCase(Locale.ROOT);
                if (!n.contains(text) && !d.contains(text)) continue;
            }
            if (!lang.isEmpty() && !lang.equalsIgnoreCase(Fmt.s(o, "language"))) continue;
            if (o.optLong("stargazers_count") < minStars) continue;
            if (created > 0 && Fmt.parse(Fmt.s(o, "created_at")) < created) continue;
            if (pushed > 0 && Fmt.parse(Fmt.s(o, "pushed_at")) < pushed) continue;
            out.add(o);
        }
        final int key = sortIdx;
        if (key != 3) {
            Collections.sort(out, (x, y) -> Long.compare(sortValue(y, key), sortValue(x, key)));
        }
        if (!desc) Collections.reverse(out);
        for (JSONObject o : out) addItem(o);
        total = items.size();
        publish();
    }

    private static long sortValue(JSONObject o, int key) {
        if (key == 0) return o.optLong("stargazers_count");
        if (key == 1) return o.optLong("forks_count");
        return Fmt.parse(Fmt.s(o, "updated_at"));
    }

    private Row buildRow(JSONObject o) {
        Row r = new Row(R.drawable.ic_repo, true, fullName(o), Fmt.s(o, "description"), false, true);
        String label = "";
        int color = 0;
        if (o.optBoolean("archived")) {
            label = a.getString(R.string.archived);
            color = Ui.color(a, R.color.warn);
        } else if (o.optBoolean("fork")) {
            label = "Fork";
            color = Ui.color(a, R.color.info);
        }
        if (starredNames.contains(fullName(o).toLowerCase(Locale.ROOT))) {
            String fb = a.getString(R.string.store_fav_badge);
            label = label.isEmpty() ? fb : fb + " \u00B7 " + label;
            color = Ui.color(a, R.color.warn);
        }
        if (!label.isEmpty()) r.badge(label, color);
        return r;
    }

    private void skeletonShow(boolean on) {
        if (on) {
            if (skeleton == null) {
                skeleton = new ShimmerView(a, ShimmerView.REPOS);
                ViewGroup parent = (ViewGroup) host.getParent();
                parent.addView(skeleton, parent.indexOfChild(host),
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            }
            if (skeleton.getVisibility() != View.VISIBLE) {
                skeleton.setVisibility(View.VISIBLE);
                skeleton.invalidate();
            }
            host.setVisibility(View.GONE);
        } else {
            if (skeleton != null && skeleton.getVisibility() != View.GONE) skeleton.setVisibility(View.GONE);
            host.setVisibility(View.VISIBLE);
        }
    }

    private void publish() {
        if (rowsDirty || rowList.size() != items.size()) {
            for (int i = rowList.size(); i < items.size(); i++) {
                JSONObject o = items.get(i);
                rowList.add(buildRow(o));
                statList.add(stats(o));
            }
            adapter.setRows(rowList);
            rowsDirty = false;
        }
        long now = System.currentTimeMillis();
        boolean counting = rateUntil > now;
        String text = statusMsg;
        if (rateStatus) {
            text = counting ? a.getString(R.string.store_rate_wait, (int) ((rateUntil - now + 999) / 1000))
                    : a.getString(R.string.store_rate_ready);
        }
        if (text != null) {
            statusView.setText(text);
            statusView.setVisibility(View.VISIBLE);
        } else if (items.isEmpty() && done && !loading) {
            statusView.setText(R.string.store_empty);
            statusView.setVisibility(View.VISIBLE);
        } else {
            statusView.setVisibility(View.GONE);
        }
        boolean on = !(rateStatus && counting);
        retryBtn.setVisibility(text != null ? View.VISIBLE : View.GONE);
        if (retryOn == null || retryOn != on) {
            retryOn = on;
            retryBtn.setEnabled(on);
            retryBtn.setAlpha(on ? 1f : 0.5f);
            Ui.setChip(a, retryBtn, on);
        }
        skeletonShow(loading && items.isEmpty() && page == 0 && text == null);
        swipe.setRefreshing(loading && page == 0 && !items.isEmpty());
        if (storeShown) headerCount.setText(countText());
        if (loading && page > 0) {
            moreView.setText(R.string.store_loading);
            moreView.setVisibility(View.VISIBLE);
        } else if (failedMore) {
            moreView.setText(counting ? a.getString(R.string.store_rate_wait, (int) ((rateUntil - now + 999) / 1000))
                    : a.getString(R.string.store_retry_more));
            moreView.setVisibility(View.VISIBLE);
        } else {
            moreView.setVisibility(View.GONE);
        }
    }

    private void decorate(View row, int pos) {
        TextView sub = row.findViewById(R.id.sub);
        if (sub == null || !(sub.getParent() instanceof LinearLayout)) return;
        LinearLayout col = (LinearLayout) sub.getParent();
        TextView stats = col.findViewById(R.id.store_stats);
        if (stats == null) {
            stats = new TextView(a);
            stats.setId(R.id.store_stats);
            stats.setTextSize(13);
            stats.setSingleLine(true);
            stats.setEllipsize(TextUtils.TruncateAt.END);
            stats.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            stats.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
            stats.setTextColor(Ui.color(a, R.color.text_secondary));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(a, 4);
            col.addView(stats, col.indexOfChild(sub) + 1, lp);
        }
        View card = row.findViewById(R.id.card);
        if (card != null) card.setMinimumHeight(Ui.dp(a, 104));
        if (sub.getVisibility() != View.VISIBLE) {
            sub.setText(" ");
            sub.setVisibility(View.INVISIBLE);
        }
        sub.setMinLines(2);
        sub.setMaxLines(2);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        if (pos < 0 || pos >= statList.size()) return;
        stats.setText(statList.get(pos));
    }

    private CharSequence stats(JSONObject o) {
        char m = rtl ? '\u200F' : '\u200E';
        String sep = m + " \u00B7 " + m;
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(m);
        sb.append("\u2605");
        sb.setSpan(new ForegroundColorSpan(Ui.color(a, R.color.warn)), 1, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(' ').append(compact(o.optLong("stargazers_count")));
        sb.append(sep).append(a.getString(R.string.store_forks_n, compact(o.optLong("forks_count"))));
        String language = Fmt.s(o, "language");
        if (!language.isEmpty()) {
            sb.append(sep);
            int s = sb.length();
            sb.append("\u25CF");
            sb.setSpan(new ForegroundColorSpan(ReposActivity.langColor(language)), s, sb.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(' ').append(language);
        }
        String when = Fmt.s(o, "pushed_at");
        if (when.isEmpty()) when = Fmt.s(o, "updated_at");
        String ago = Fmt.ago(when);
        if (!ago.isEmpty()) sb.append(sep).append(ago);
        return sb;
    }

    static String compact(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 999950) return trim(String.format(Locale.US, "%.1f", n / 1000.0)) + "k";
        return trim(String.format(Locale.US, "%.1f", n / 1000000.0)) + "M";
    }

    private static String trim(String s) {
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String fullName(JSONObject o) {
        return o.optString("full_name", o.optString("name"));
    }

    private void menu(final JSONObject o) {
        final String fn = fullName(o);
        JSONObject own = o.optJSONObject("owner");
        final String login = own != null ? own.optString("login") : "";
        final String name = o.optString("name");
        final String url = safeUrl(o, fn);
        final String[] labels = {a.getString(R.string.store_menu_preview), a.getString(R.string.store_menu_zip),
                a.getString(R.string.store_menu_star), a.getString(R.string.copy_clone_url),
                a.getString(R.string.open_in_github), a.getString(R.string.share),
                a.getString(R.string.store_menu_dev, login)};
        new Dlg(a).setTitle(fn).setItems(labels, (d, which) -> {
            switch (which) {
                case 0:
                    preview(o);
                    break;
                case 1:
                    zip(o);
                    break;
                case 2:
                    toggleStar(o, login, name);
                    break;
                case 3:
                    copy(o.optString("clone_url", "https://github.com/" + fn + ".git"));
                    break;
                case 4:
                    try {
                        a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (ActivityNotFoundException ignored) {
                    }
                    break;
                case 5:
                    Intent s = new Intent(Intent.ACTION_SEND);
                    s.setType("text/plain");
                    s.putExtra(Intent.EXTRA_TEXT, url);
                    a.startActivity(Intent.createChooser(s, a.getString(R.string.share)));
                    break;
                default:
                    if (login.isEmpty()) break;
                    muted = true;
                    input.setText("user:" + login);
                    input.setSelection(input.getText().length());
                    muted = false;
                    updateInputState();
                    restart();
                    break;
            }
        }).show();
    }

    private void zip(JSONObject o) {
        JSONObject own = o.optJSONObject("owner");
        String login = own != null ? own.optString("login") : "";
        String name = o.optString("name");
        if (login.isEmpty() || name.isEmpty()) return;
        String br = o.optString("default_branch", "main");
        String file = (name + "-" + br).replaceAll("[^A-Za-z0-9._-]", "-") + ".zip";
        Perms.ensureNotifications(a);
        String[] z = {login, name, br, file};
        if (!Store.askSaveLocation(a) && Perms.hasAllFiles(a)) {
            startZip(z, TransferTasks.saveDir(name));
            return;
        }
        pendingZip = z;
        try {
            saveLauncher.launch(new Intent(a, FileManagerActivity.class).putExtra("pick", "save")
                    .putExtra("fileName", file));
        } catch (Exception e) {
            pendingZip = null;
            Toast.makeText(a, R.string.cannot_save, Toast.LENGTH_SHORT).show();
        }
    }

    private static File uniqueFile(File dir, String name) {
        try {
            if (!dir.exists()) dir.mkdirs();
        } catch (Exception ignored) {
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 0; i < 1000; i++) {
            File f = new File(dir, i == 0 ? name : base + " (" + i + ")" + ext);
            try {
                if (f.createNewFile()) return f;
            } catch (Exception e) {
                return f;
            }
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private void startZip(final String[] z, File dir) {
        final File dest = uniqueFile(dir, z[3]);
        final GitHubApi g = new GitHubApi(a.getApplicationContext());
        Transfers.start(a.getApplicationContext(), a.getString(R.string.tr_download_title, dest.getName()),
                "download:" + dest.getName(), TransferTasks.download(() -> g.openZipball(z[0], z[1], z[2]), dest, false),
                (job, ok, msg) -> {
                    String first = msg == null ? "" : msg;
                    int nl = first.indexOf('\n');
                    if (nl > 0) first = first.substring(0, nl);
                    Toast.makeText(job.app, ok ? first : job.app.getString(R.string.tr_failed) + ": " + first,
                            Toast.LENGTH_LONG).show();
                });
        Toast.makeText(a, R.string.tr_started_bg, Toast.LENGTH_LONG).show();
    }

    private void preview(final JSONObject o) {
        final String fn = fullName(o);
        JSONObject own = o.optJSONObject("owner");
        final String login = own != null ? own.optString("login") : "";
        final String name = o.optString("name");
        final TextView body = new TextView(a);
        body.setTextSize(14);
        body.setLineSpacing(0f, 1.15f);
        body.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        body.setTextColor(Ui.color(a, R.color.text_primary));
        body.setMovementMethod(LinkMovementMethod.getInstance());
        body.setText(R.string.store_prev_loading);
        int pad = Ui.dp(a, 20);
        body.setPadding(pad, Ui.dp(a, 8), pad, Ui.dp(a, 8));
        ScrollView sv = new ScrollView(a);
        sv.addView(body);
        new Dlg(a).setTitle(fn).setView(sv).setPositiveButton(R.string.store_prev_open, (d, w) -> {
            Intent i = new Intent(a, RepoHomeActivity.class);
            i.putExtra("owner", login);
            i.putExtra("repo", name);
            i.putExtra("branch", o.optString("default_branch", "main"));
            a.startActivity(i);
        }).setNegativeButton(R.string.close, null).show();
        pool.execute(() -> {
            String md = "";
            try {
                JSONObject r = api.obj(GitHubApi.repo(login, name) + "/readme");
                String c = r.optString("content").replace("\n", "").replace("\r", "");
                md = new String(Base64.decode(c, Base64.DEFAULT), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
            String topics = "";
            try {
                JSONArray t = api.topics(login, name);
                StringBuilder tb = new StringBuilder();
                for (int i = 0; t != null && i < t.length(); i++) {
                    if (tb.length() > 0) tb.append("  ");
                    tb.append('#').append(t.optString(i));
                }
                topics = tb.toString();
            } catch (Exception ignored) {
            }
            String langs = "";
            try {
                langs = languagesText(api.languages(login, name));
            } catch (Exception ignored) {
            }
            final SpannableStringBuilder sb = new SpannableStringBuilder();
            String desc = Fmt.s(o, "description");
            if (!desc.isEmpty()) sb.append(desc).append("\n\n");
            section(sb, R.string.store_prev_topics, topics);
            section(sb, R.string.store_prev_langs, langs);
            int s = sb.length();
            sb.append(a.getString(R.string.store_prev_readme));
            sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append('\n');
            String clean = trimReadme(md);
            if (clean.isEmpty()) sb.append(a.getString(R.string.store_prev_no_readme));
            else sb.append(MarkdownRender.render(a, clean, this::openLink));
            ui.post(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                body.setText(sb);
            });
        });
    }

    private void section(SpannableStringBuilder sb, int label, String text) {
        if (text.isEmpty()) return;
        int s = sb.length();
        sb.append(a.getString(label));
        sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append('\n').append(text).append("\n\n");
    }

    private static String trimReadme(String md) {
        if (md == null || md.isEmpty()) return "";
        String t = md.replaceAll("(?m)^\\s*(\\[!\\[|!\\[).*$", "").replaceAll("<[^>]+>", "")
                .replaceAll("\\n{3,}", "\n\n").trim();
        if (t.length() > README_MAX) {
            t = t.substring(0, README_MAX);
            int cut = t.lastIndexOf('\n');
            if (cut > README_MAX / 2) t = t.substring(0, cut);
            t = t.trim() + "\n\u2026";
        }
        return t;
    }

    private static String languagesText(JSONObject l) {
        if (l == null) return "";
        List<String> names = new ArrayList<>();
        long sum = 0;
        Iterator<String> it = l.keys();
        while (it.hasNext()) {
            String k = it.next();
            names.add(k);
            sum += l.optLong(k);
        }
        if (sum <= 0) return "";
        final JSONObject src = l;
        Collections.sort(names, (x, y) -> Long.compare(src.optLong(y), src.optLong(x)));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size() && i < 5; i++) {
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(names.get(i)).append(' ').append(Math.round(100f * src.optLong(names.get(i)) / sum)).append('%');
        }
        return sb.toString();
    }

    private void openLink(String url) {
        if (!SafeUrl.https(url)) return;
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    private static String safeUrl(JSONObject o, String fn) {
        String url = o.optString("html_url", "");
        return SafeUrl.https(url) ? url : "https://github.com/" + fn;
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("url", text));
        Toast.makeText(a, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void toggleStar(final JSONObject o, final String login, final String name) {
        pool.execute(() -> {
            try {
                final boolean cur = api.isStarred(login, name);
                api.star(login, name, !cur);
                ui.post(() -> {
                    if (a.isFinishing() || a.isDestroyed()) return;
                    Toast.makeText(a, cur ? R.string.unstarred : R.string.starred, Toast.LENGTH_SHORT).show();
                    updateStarCache(o, !cur);
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    if (a.isFinishing() || a.isDestroyed()) return;
                    Toast.makeText(a, message(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void updateStarCache(JSONObject o, boolean on) {
        String fn = fullName(o);
        if (starredLoaded) {
            for (int i = starred.size() - 1; i >= 0; i--) {
                if (fullName(starred.get(i)).equalsIgnoreCase(fn)) starred.remove(i);
            }
            if (on) starred.add(0, o);
        }
        Set<String> names = new HashSet<>(starredNames);
        if (on) names.add(fn.toLowerCase(Locale.ROOT));
        else names.remove(fn.toLowerCase(Locale.ROOT));
        boolean changed = applyNames(names);
        if (changed) persistNames(names);
        if (fav && !on) {
            for (int i = items.size() - 1; i >= 0; i--) {
                if (fullName(items.get(i)).equalsIgnoreCase(fn)) items.remove(i);
            }
            seen.remove(fn.toLowerCase(Locale.ROOT));
            total = items.size();
            resetRows();
            publish();
        } else if (changed) {
            publish();
        }
    }
}

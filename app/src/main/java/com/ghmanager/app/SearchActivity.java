package com.ghmanager.app;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class SearchActivity extends BaseRepoActivity {
    private static final int PER_ALL = 6;
    private static final int PER_PAGE = 20;
    private static final int MAX_RESULTS = 1000;
    private static final long DEBOUNCE_MS = 450L;
    private static final SearchMemo<JSONObject> MEMO = new SearchMemo<>(48, 90000L);

    private static final class Section {
        final List<SearchHit> hits = new ArrayList<>();
        int total = -1;
        int page = 1;
        boolean more;
        boolean loading;
        boolean done;
        boolean waiting;
        String error;

        void reset() {
            hits.clear();
            total = -1;
            page = 1;
            more = false;
            loading = false;
            done = false;
            waiting = false;
            error = null;
        }
    }

    private final Section[] sec = {new Section(), new Section(), new Section(), new Section()};
    private final RateGate gate = new RateGate();
    private final List<Integer> chipFilters = new ArrayList<>();
    private final List<TextView> chips = new ArrayList<>();
    private LinearLayout content;
    private EditText query;
    private TextView rateBanner;
    private TextView everywhereChip;
    private String repoFull = "";
    private String login = "";
    private String lastQ = "";
    private int filter = -1;
    private boolean everywhere = false;
    private boolean resumePending = false;
    private int gen = 0;

    private final Runnable debounced = () -> run(false);

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (gate.blocked(now)) {
                rateBanner.setText(getString(R.string.us_rate, gate.seconds(now)));
                rateBanner.setVisibility(View.VISIBLE);
                ui.postDelayed(this, 1000L);
            } else {
                rateBanner.setVisibility(View.GONE);
                if (resumePending) {
                    resumePending = false;
                    SearchActivity.this.run(false);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        if (owner != null && repo != null && !owner.isEmpty() && !repo.isEmpty()) repoFull = owner + "/" + repo;
        Accounts.Acc acc = Accounts.active(this);
        login = acc == null || acc.login == null ? "" : acc.login;
        bindHeader(getString(R.string.us_title), repoFull.isEmpty() ? null : getString(R.string.us_in_repo, repoFull));
        content = findViewById(R.id.content);
        btnRefresh.setOnClickListener(v -> run(true));

        query = Ui.edit(this, getString(R.string.us_hint), null);
        query.setSingleLine(true);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        query.setCompoundDrawablePadding(Ui.dp(this, 12));
        query.setCompoundDrawableTintList(ColorStateList.valueOf(Ui.color(this, R.color.text_hint)));
        LinearLayout.LayoutParams qlp = (LinearLayout.LayoutParams) query.getLayoutParams();
        qlp.setMargins(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4));
        query.setOnEditorActionListener((v, id, ev) -> {
            String q = SearchQuery.normalize(query.getText().toString());
            if (!SearchQuery.valid(q)) {
                toast(R.string.us_min_len);
                return true;
            }
            RecentSearches.add(this, q);
            hideKeyboard();
            run(false);
            return true;
        });
        query.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                ui.removeCallbacks(debounced);
                if (SearchQuery.normalize(s.toString()).isEmpty()) run(false);
                else ui.postDelayed(debounced, DEBOUNCE_MS);
            }
        });

        rateBanner = Ui.noteCard(this, "", R.color.warn);
        rateBanner.setVisibility(View.GONE);

        ViewGroup root = (ViewGroup) ((View) content.getParent()).getParent();
        root.addView(query, 1);
        root.addView(buildChips(), 2);
        root.addView(rateBanner, 3);
        render();
    }

    private View buildChips() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setPaddingRelative(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 4));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addChip(row, R.string.us_all, -1);
        if (repoFull.isEmpty()) addChip(row, R.string.us_repos, SearchQuery.REPOS);
        addChip(row, R.string.us_code, SearchQuery.CODE);
        addChip(row, R.string.us_issues, SearchQuery.ISSUES);
        addChip(row, R.string.us_pulls, SearchQuery.PULLS);
        if (repoFull.isEmpty()) {
            everywhereChip = Ui.chip(this, getString(R.string.us_everywhere), false);
            everywhereChip.setOnClickListener(v -> {
                everywhere = !everywhere;
                Ui.setChip(this, everywhereChip, everywhere);
                if (!lastQ.isEmpty()) run(false);
            });
            row.addView(everywhereChip);
        }
        hs.addView(row);
        return hs;
    }

    private void addChip(LinearLayout row, int label, int kindFilter) {
        final TextView c = Ui.chip(this, getString(label), kindFilter == filter);
        chipFilters.add(kindFilter);
        chips.add(c);
        c.setOnClickListener(v -> setFilter(kindFilter));
        row.addView(c);
    }

    private void setFilter(int f) {
        if (filter == f) return;
        filter = f;
        for (int i = 0; i < chips.size(); i++) Ui.setChip(this, chips.get(i), chipFilters.get(i) == filter);
        if (lastQ.isEmpty()) {
            render();
            return;
        }
        run(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lastQ.isEmpty()) render();
        if (gate.blocked(System.currentTimeMillis())) {
            ui.removeCallbacks(tick);
            ui.post(tick);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(debounced);
        ui.removeCallbacks(tick);
        super.onDestroy();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(query.getWindowToken(), 0);
    }

    private boolean visible(int kind) {
        if (kind == SearchQuery.REPOS && !repoFull.isEmpty()) return false;
        return filter == -1 || filter == kind;
    }

    private List<Integer> plan() {
        List<Integer> out = new ArrayList<>();
        if (filter == -1) {
            if (repoFull.isEmpty()) out.add(SearchQuery.REPOS);
            out.add(SearchQuery.MIXED);
            out.add(SearchQuery.CODE);
        } else {
            out.add(filter);
        }
        return out;
    }

    private static int[] sectionsOf(int kind) {
        if (kind == SearchQuery.MIXED) return new int[]{SearchQuery.ISSUES, SearchQuery.PULLS};
        return new int[]{kind};
    }

    private void run(final boolean force) {
        ui.removeCallbacks(debounced);
        final String q = SearchQuery.normalize(query.getText().toString());
        final int myGen = ++gen;
        for (Section s : sec) s.reset();
        if (!SearchQuery.valid(q)) {
            lastQ = "";
            resumePending = false;
            loading(false);
            render();
            return;
        }
        lastQ = q;
        if (force) MEMO.clear();
        final List<Integer> plan = plan();
        for (int kind : plan) for (int s : sectionsOf(kind)) sec[s].loading = true;
        loading(true);
        render();
        io.execute(() -> execute(q, plan, myGen));
    }

    private void execute(final String q, final List<Integer> plan, final int myGen) {
        for (final int kind : plan) {
            if (myGen != gen) return;
            try {
                final JSONObject res = fetch(kind, q, 1, filter == -1 ? PER_ALL * 2 : PER_PAGE);
                post(() -> {
                    if (myGen != gen) return;
                    apply(kind, res, 1);
                    render();
                });
            } catch (GitHubApi.ApiException e) {
                if (e.code == 401) {
                    fail(e);
                    return;
                }
                if (RateGate.isRateLimited(e.code, e.waitMs, e.getMessage())) {
                    gate.block(RateGate.fallbackWaitMs(e.waitMs), System.currentTimeMillis());
                    post(() -> {
                        if (myGen != gen) return;
                        markWaiting(plan, kind);
                        startTick();
                        loading(false);
                        render();
                    });
                    return;
                }
                sectionError(kind, e, myGen);
            } catch (Exception e) {
                sectionError(kind, e, myGen);
            }
        }
        post(() -> {
            if (myGen == gen) loading(false);
        });
    }

    private void sectionError(final int kind, Exception e, final int myGen) {
        final String m = getString(R.string.us_error, e.getMessage() == null ? e.toString() : e.getMessage());
        post(() -> {
            if (myGen != gen) return;
            for (int s : sectionsOf(kind)) {
                sec[s].loading = false;
                sec[s].done = true;
                sec[s].error = m;
            }
            render();
        });
    }

    private void markWaiting(List<Integer> plan, int fromKind) {
        boolean on = false;
        for (int kind : plan) {
            if (kind == fromKind) on = true;
            if (!on) continue;
            for (int s : sectionsOf(kind)) {
                sec[s].loading = false;
                sec[s].waiting = true;
            }
        }
        resumePending = true;
    }

    private void startTick() {
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private JSONObject fetch(int kind, String q, int page, int per) throws Exception {
        String full = SearchQuery.build(q, repoFull, login, everywhere, kind);
        String key = SearchMemo.key(kind, full, "", page) + "|" + per;
        long now = System.currentTimeMillis();
        JSONObject cached = MEMO.get(key, now);
        if (cached != null) return cached;
        if (gate.blocked(now)) {
            GitHubApi.ApiException w = new GitHubApi.ApiException(429, "rate limit");
            w.waitMs = gate.remainingMs(now);
            throw w;
        }
        JSONObject res;
        if (kind == SearchQuery.REPOS) res = api.searchRepos(full, page, per);
        else if (kind == SearchQuery.CODE) res = api.searchCodeQuery(full, page, per);
        else res = api.searchIssues(full, page, per);
        MEMO.put(key, res, System.currentTimeMillis());
        return res;
    }

    private void apply(int kind, JSONObject res, int page) {
        JSONArray items = res.optJSONArray("items");
        int n = items == null ? 0 : items.length();
        int total = res.optInt("total_count");
        if (kind == SearchQuery.MIXED) {
            Section is = sec[SearchQuery.ISSUES];
            Section ps = sec[SearchQuery.PULLS];
            for (int i = 0; i < n; i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) continue;
                SearchHit h = SearchHit.issue(o);
                (h.pull ? ps : is).hits.add(h);
            }
            boolean more = total > n;
            for (Section s : new Section[]{is, ps}) {
                s.loading = false;
                s.done = true;
                s.waiting = false;
                s.total = -1;
                s.more = more;
            }
            return;
        }
        Section s = sec[kind];
        for (int i = 0; i < n; i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            if (kind == SearchQuery.REPOS) s.hits.add(SearchHit.repo(o));
            else if (kind == SearchQuery.CODE) s.hits.add(SearchHit.code(o));
            else s.hits.add(SearchHit.issue(o));
        }
        s.page = page;
        s.total = total;
        s.loading = false;
        s.done = true;
        s.waiting = false;
        s.more = n >= PER_PAGE && s.hits.size() < Math.min(total, MAX_RESULTS);
    }

    private void loadMore(final int kind) {
        final Section s = sec[kind];
        if (s.loading || !s.more) return;
        s.loading = true;
        final int myGen = gen;
        final String q = lastQ;
        final int next = s.page + 1;
        render();
        io.execute(() -> {
            try {
                final JSONObject res = fetch(kind, q, next, PER_PAGE);
                post(() -> {
                    if (myGen != gen) return;
                    apply(kind, res, next);
                    render();
                });
            } catch (GitHubApi.ApiException e) {
                if (e.code == 401) {
                    fail(e);
                    return;
                }
                if (RateGate.isRateLimited(e.code, e.waitMs, e.getMessage())) {
                    gate.block(RateGate.fallbackWaitMs(e.waitMs), System.currentTimeMillis());
                    post(() -> {
                        if (myGen != gen) return;
                        s.loading = false;
                        startTick();
                        render();
                    });
                    return;
                }
                sectionError(kind, e, myGen);
            } catch (Exception e) {
                sectionError(kind, e, myGen);
            }
        });
    }

    private void render() {
        content.removeAllViews();
        if (lastQ.isEmpty()) {
            renderHome();
            return;
        }
        int[] order = {SearchQuery.REPOS, SearchQuery.CODE, SearchQuery.ISSUES, SearchQuery.PULLS};
        for (int kind : order) {
            if (visible(kind)) renderSection(kind);
        }
    }

    private void renderHome() {
        List<String> recents = RecentSearches.all(this);
        if (!recents.isEmpty()) {
            content.addView(Ui.sectionTitle(this, getString(R.string.us_recent)));
            for (final String r : recents) {
                View v = Ui.rowView(this, content, new Row(R.drawable.ic_history, false, r, null, false, false), x -> {
                    query.setText(r);
                    query.setSelection(r.length());
                    RecentSearches.add(this, r);
                    hideKeyboard();
                    run(false);
                });
                v.setOnLongClickListener(x -> {
                    RecentSearches.remove(this, r);
                    render();
                    return true;
                });
                content.addView(v);
            }
            content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_trash_sweep, false,
                    getString(R.string.us_clear_history), null, false, false), x -> {
                RecentSearches.clear(this);
                toast(R.string.us_history_cleared);
                render();
            }));
        }
        content.addView(Ui.body(this, getString(R.string.us_tips), 12, R.color.text_hint));
    }

    private int titleOf(int kind) {
        if (kind == SearchQuery.REPOS) return R.string.us_repos;
        if (kind == SearchQuery.CODE) return R.string.us_code;
        if (kind == SearchQuery.ISSUES) return R.string.us_issues;
        return R.string.us_pulls;
    }

    private int iconOf(int kind) {
        if (kind == SearchQuery.REPOS) return R.drawable.ic_repo;
        if (kind == SearchQuery.CODE) return R.drawable.ic_code;
        if (kind == SearchQuery.ISSUES) return R.drawable.ic_issue;
        return R.drawable.ic_pr;
    }

    private void renderSection(final int kind) {
        final Section s = sec[kind];
        String title = getString(titleOf(kind));
        if (s.total >= 0) title = title + " (" + s.total + ")";
        content.addView(Ui.sectionTitle(this, title));
        if (kind == SearchQuery.CODE && everywhere && repoFull.isEmpty()) {
            content.addView(Ui.body(this, getString(R.string.us_code_scope), 12, R.color.text_hint));
        }
        if (s.error != null) content.addView(Ui.noteCard(this, s.error, R.color.bad));
        if (s.waiting) content.addView(Ui.body(this, getString(R.string.us_waiting), 13, R.color.text_hint));
        if (s.loading && s.hits.isEmpty()) {
            content.addView(Ui.body(this, getString(R.string.us_searching), 13, R.color.text_hint));
        }
        int limit = filter == -1 ? PER_ALL : s.hits.size();
        int shown = Math.min(limit, s.hits.size());
        for (int i = 0; i < shown; i++) {
            final SearchHit h = s.hits.get(i);
            content.addView(Ui.rowView(this, content, rowFor(kind, h), v -> open(h)));
        }
        if (s.done && s.hits.isEmpty() && s.error == null && !s.waiting) {
            content.addView(Ui.body(this, getString(R.string.us_none), 13, R.color.text_hint));
        }
        if (filter == -1 && (s.hits.size() > limit || (s.more && s.hits.size() >= limit))) {
            content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_list, false,
                    getString(R.string.us_see_all), null, false, true), v -> setFilter(kind)));
        } else if (filter != -1 && s.more) {
            content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_refresh, false,
                    getString(R.string.load_more), null, false, false), v -> loadMore(kind)));
        }
    }

    private Row rowFor(int kind, SearchHit h) {
        Row r = new Row(iconOf(kind), false, h.title, h.sub.isEmpty() ? null : h.sub, false, true);
        if (kind == SearchQuery.ISSUES || kind == SearchQuery.PULLS) {
            int color;
            int label;
            if (h.merged) {
                color = Ui.color(this, R.color.info);
                label = R.string.merged;
            } else if ("open".equals(h.state)) {
                color = Ui.color(this, R.color.ok);
                label = R.string.st_open;
            } else {
                color = Ui.color(this, R.color.bad);
                label = R.string.st_closed;
            }
            r.tint(color);
            r.badge(getString(label), color);
        }
        return r;
    }

    private void open(final SearchHit h) {
        RecentSearches.add(this, lastQ);
        if (h.kind == SearchQuery.REPOS) {
            Intent i = new Intent(this, RepoHomeActivity.class);
            i.putExtra("owner", h.owner);
            i.putExtra("repo", h.repo);
            i.putExtra("branch", h.branch);
            startActivity(i);
        } else if (h.kind == SearchQuery.CODE) {
            bg(() -> {
                String b = api.getRepo(h.owner, h.repo).optString("default_branch", "main");
                final Intent i = new Intent(this, EditorActivity.class);
                i.putExtra("owner", h.owner);
                i.putExtra("repo", h.repo);
                i.putExtra("branch", b);
                i.putExtra("path", h.path);
                i.putExtra("new", false);
                i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                post(() -> startActivity(i));
            });
        } else {
            Intent i = new Intent(this, IssueDetailActivity.class);
            i.putExtra("owner", h.owner);
            i.putExtra("repo", h.repo);
            i.putExtra("number", h.number);
            startActivity(i);
        }
    }
}

package com.ghmanager.app;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Hub screen of a repository: summary + entry points to every feature. */
public class RepoHomeActivity extends BaseRepoActivity {
    private LinearLayout content;
    private JSONObject repoJson;
    private JSONObject lastRun;
    private boolean starred = false;
    private LinearLayout stickyBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        bindHeader(repo, owner);
        androidx.core.view.ViewCompat.setTransitionName(titleView, "repo_title");
        content = findViewById(R.id.content);
        addStickyBar();
        btnRefresh.setOnClickListener(v -> load());
        action(btnA1, R.drawable.ic_star, R.string.star, v -> toggleStar());
        load();
    }

    @Override
    protected void onAccessChanged() {
        if (repoJson != null) render();
    }

    private void updateStar() {
        int c = Ui.color(this, starred ? R.color.warn : R.color.text_primary);
        btnA1.setImageTintList(ColorStateList.valueOf(c));
        refreshStickyBar();
    }

    private void toggleStar() {
        final boolean target = !starred;
        bg(() -> {
            api.star(owner, repo, target);
            post(() -> {
                starred = target;
                updateStar();
                toast(target ? R.string.starred : R.string.unstarred);
            });
        });
    }

    private void load() {
        loading(true);
        io.execute(() -> {
            try {
                final JSONObject r = api.getRepo(owner, repo);
                boolean st = false;
                try {
                    st = api.isStarred(owner, repo);
                } catch (Exception ignored) {
                }
                JSONObject run = null;
                try {
                    JSONArray a = api.listRuns(owner, repo, 0, null, null, 1, 1).optJSONArray("workflow_runs");
                    if (a != null && a.length() > 0) run = a.getJSONObject(0);
                } catch (Exception ignored) {
                }
                final boolean fSt = st;
                final JSONObject fRun = run;
                post(() -> {
                    loading(false);
                    repoJson = r;
                    adoptRepoJson(r);
                    starred = fSt;
                    lastRun = fRun;
                    updateStar();
                    render();
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void addRow(Row row, View.OnClickListener l) {
        content.addView(Ui.rowView(this, content, row, l));
    }

    private void addStickyBar() {
        FrameLayout host = findViewById(android.R.id.content);
        View root = host.getChildAt(0);
        if (!(root instanceof LinearLayout)) return;
        stickyBar = new LinearLayout(this);
        stickyBar.setOrientation(LinearLayout.HORIZONTAL);
        stickyBar.setGravity(Gravity.CENTER_VERTICAL);
        stickyBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 6));
        stickyBar.setVisibility(View.GONE);
        ((LinearLayout) root).addView(stickyBar, 1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void refreshStickyBar() {
        if (stickyBar == null || repoJson == null) return;
        stickyBar.removeAllViews();
        TextView br = Ui.chip(this, "\u2387 " + branch, false);
        br.setOnClickListener(v -> startActivity(repoIntent(BranchesActivity.class)));
        TextView st = Ui.chip(this, "\u2605 " + repoJson.optInt("stargazers_count"), starred);
        st.setOnClickListener(v -> toggleStar());
        stickyBar.addView(br);
        stickyBar.addView(st);
        stickyBar.setVisibility(View.VISIBLE);
    }

    private View bigCard(int icon, String title, String sub, String badge, int badgeColor, View.OnClickListener l) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_ripple);
        card.setMinimumHeight(Ui.dp(this, 112));
        card.setPaddingRelative(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.text_primary)));
        iv.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
        GradientDrawable tile = new GradientDrawable();
        tile.setCornerRadius(Ui.radius(this, R.dimen.radius_s));
        tile.setColor(Ui.color(this, R.color.neutral_soft));
        iv.setBackground(tile);
        top.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        View gap = new View(this);
        top.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
        if (badge != null && !badge.isEmpty()) {
            TextView b = new TextView(this);
            b.setText(badge);
            b.setTextSize(11);
            b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            b.setSingleLine(true);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.setMaxWidth(Ui.dp(this, 86));
            b.setTextColor(badgeColor);
            b.setPadding(Ui.dp(this, 9), Ui.dp(this, 3), Ui.dp(this, 9), Ui.dp(this, 3));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Ui.radius(this, R.dimen.radius_pill));
            g.setColor((badgeColor & 0x00FFFFFF) | 0x26000000);
            b.setBackground(g);
            top.addView(b);
        }
        card.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(17);
        t.setTextColor(Ui.color(this, R.color.text_primary));
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setSingleLine(true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(this, 12);
        card.addView(t, tl);

        if (sub != null && !sub.isEmpty()) {
            TextView sv = new TextView(this);
            sv.setText(sub);
            sv.setTextSize(12);
            sv.setMaxLines(2);
            sv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sv.setTextColor(Ui.color(this, R.color.text_secondary));
            sv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.topMargin = Ui.dp(this, 2);
            card.addView(sv, sl);
        }
        Ui.press(this, card);
        card.setOnClickListener(l);
        return card;
    }

    private void addCardRow(View left, View right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 5), Ui.dp(this, 14), Ui.dp(this, 5));
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        a.setMarginEnd(Ui.dp(this, 5));
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        b.setMarginStart(Ui.dp(this, 5));
        row.addView(left, a);
        row.addView(right, b);
        content.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void render() {
        content.removeAllViews();
        if (repoJson == null) return;

        String desc = repoJson.optString("description", "");
        if (!desc.isEmpty() && !"null".equals(desc)) {
            content.addView(Ui.body(this, desc, 15, R.color.text_primary));
        }

        StringBuilder st = new StringBuilder();
        String lang = repoJson.optString("language", "");
        if (!lang.isEmpty() && !"null".equals(lang)) st.append(lang).append(" · ");
        st.append("★ ").append(repoJson.optInt("stargazers_count"));
        st.append(" · ").append(getString(R.string.forks)).append(" ").append(repoJson.optInt("forks_count"));
        st.append(" · ").append(Fmt.size(repoJson.optLong("size") * 1024));
        st.append(" · ").append(getString(repoJson.optBoolean("private") ? R.string.private_label : R.string.public_label));
        content.addView(Ui.body(this, st.toString(), 13, R.color.text_secondary));

        String pushed = Fmt.ago(repoJson.optString("pushed_at"));
        content.addView(Ui.body(this, getString(R.string.default_branch_info, branch)
                + (pushed.isEmpty() ? "" : " · " + getString(R.string.last_push, pushed)), 13, R.color.text_secondary));

        content.addView(Ui.sectionTitle(this, getString(R.string.sections)));

        String runBadge = null;
        int runColor = 0;
        if (lastRun != null) {
            String rs = lastRun.optString("status");
            String rc = lastRun.optString("conclusion");
            runBadge = Status.label(rs, rc);
            runColor = Status.color(this, rs, rc);
        }
        int open = repoJson.optInt("open_issues_count");
        String lastPush = Fmt.ago(repoJson.optString("pushed_at"));

        View filesCard = bigCard(R.drawable.ic_folder, getString(R.string.files), getString(R.string.files_sub),
                null, 0, v -> startActivity(repoIntent(BrowserActivity.class)));
        View actionsCard = bigCard(R.drawable.ic_play, getString(R.string.actions), getString(R.string.actions_sub),
                runBadge, runColor, v -> startActivity(repoIntent(ActionsActivity.class)));
        View commitsCard = bigCard(R.drawable.ic_commit, getString(R.string.commits), getString(R.string.commits_sub),
                lastPush, Ui.color(this, R.color.info), v -> startActivity(repoIntent(CommitsActivity.class)));
        View issuesCard = bigCard(R.drawable.ic_issue, getString(R.string.issues_prs), getString(R.string.issues_sub),
                open > 0 ? String.valueOf(open) : null, Ui.color(this, R.color.ok),
                v -> startActivity(repoIntent(IssuesActivity.class)));
        addCardRow(filesCard, actionsCard);
        addCardRow(commitsCard, issuesCard);

        content.addView(Ui.sectionTitle(this, getString(R.string.rh_other)));

        addRow(new Row(R.drawable.ic_tag, true, getString(R.string.releases),
                getString(R.string.releases_sub), false, true),
                v -> startActivity(repoIntent(ReleasesActivity.class)));

        addRow(new Row(R.drawable.ic_package, true, getString(R.string.artifacts),
                getString(R.string.artifacts_sub), false, true), v -> {
            Intent ai = repoIntent(ActionsDataActivity.class);
            ai.putExtra("mode", "artifacts");
            startActivity(ai);
        });

        addRow(new Row(R.drawable.ic_search, true, getString(R.string.us_title),
                getString(R.string.us_repo_sub), false, true),
                v -> startActivity(repoIntent(SearchActivity.class)));

        addRow(new Row(R.drawable.ic_branch, true, getString(R.string.branches),
                getString(R.string.branches_sub), false, true),
                v -> startActivity(repoIntent(BranchesActivity.class)));

        addRow(new Row(R.drawable.ic_chart, true, getString(R.string.insights),
                getString(R.string.insights_sub), false, true),
                v -> startActivity(repoIntent(InsightsActivity.class)));

        content.addView(Ui.sectionTitle(this, getString(R.string.more)));

        addRow(new Row(R.drawable.ic_clipboard, true, getString(R.string.pr_title), getString(R.string.pr_sub),
                false, true), v -> startActivity(repoIntent(PromptsActivity.class)));

        addRow(new Row(R.drawable.ic_download, false, getString(R.string.download_zip),
                getString(R.string.download_zip_sub, branch), false, false),
                v -> saveAs(repo + "-" + branch.replace('/', '-') + ".zip",
                        () -> api.openZipball(owner, repo, branch)));

        final String html = repoJson.optString("html_url", webUrl(""));
        addRow(new Row(R.drawable.ic_open, false, getString(R.string.open_github),
                null, false, false), v -> openUrl(html));

        addRow(new Row(R.drawable.ic_copy, false, getString(R.string.copy_clone_url),
                repoJson.optString("clone_url"), false, false),
                v -> copy("clone", repoJson.optString("clone_url")));

        if (canWrite) {
            addRow(new Row(R.drawable.ic_settings, false, getString(R.string.repo_settings),
                    null, false, true),
                    v -> startActivity(repoIntent(RepoSettingsActivity.class)));
        }
    }
}

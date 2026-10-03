package com.ghmanager.app;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

/** Hub screen of a repository: summary + entry points to every feature. */
public class RepoHomeActivity extends BaseRepoActivity {
    private LinearLayout content;
    private JSONObject repoJson;
    private JSONObject lastRun;
    private boolean starred = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        bindHeader(repo, owner);
        content = findViewById(R.id.content);
        btnRefresh.setOnClickListener(v -> load());
        action(btnA1, R.drawable.ic_star, R.string.star, v -> toggleStar());
        load();
    }

    private void updateStar() {
        int c = Ui.color(this, starred ? R.color.warn : R.color.text_primary);
        btnA1.setImageTintList(ColorStateList.valueOf(c));
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

        addRow(new Row(R.drawable.ic_folder, true, getString(R.string.files),
                getString(R.string.files_sub), false, true),
                v -> startActivity(repoIntent(BrowserActivity.class)));

        Row actions = new Row(R.drawable.ic_play, true, getString(R.string.actions),
                getString(R.string.actions_sub), false, true);
        if (lastRun != null) {
            String s = lastRun.optString("status");
            String c = lastRun.optString("conclusion");
            actions.badge(Status.label(s, c), Status.color(this, s, c));
        }
        addRow(actions, v -> startActivity(repoIntent(ActionsActivity.class)));

        addRow(new Row(R.drawable.ic_tag, true, getString(R.string.releases),
                getString(R.string.releases_sub), false, true),
                v -> startActivity(repoIntent(ReleasesActivity.class)));

        addRow(new Row(R.drawable.ic_package, true, getString(R.string.artifacts),
                getString(R.string.artifacts_sub), false, true), v -> {
            Intent ai = repoIntent(ActionsDataActivity.class);
            ai.putExtra("mode", "artifacts");
            startActivity(ai);
        });

        addRow(new Row(R.drawable.ic_commit, true, getString(R.string.commits),
                getString(R.string.commits_sub), false, true),
                v -> startActivity(repoIntent(CommitsActivity.class)));

        Row issues = new Row(R.drawable.ic_issue, true, getString(R.string.issues_prs),
                getString(R.string.issues_sub), false, true);
        int open = repoJson.optInt("open_issues_count");
        if (open > 0) issues.badge(String.valueOf(open), Ui.color(this, R.color.ok));
        addRow(issues, v -> startActivity(repoIntent(IssuesActivity.class)));

        addRow(new Row(R.drawable.ic_branch, true, getString(R.string.branches),
                getString(R.string.branches_sub), false, true),
                v -> startActivity(repoIntent(BranchesActivity.class)));

        addRow(new Row(R.drawable.ic_chart, true, getString(R.string.insights),
                getString(R.string.insights_sub), false, true),
                v -> startActivity(repoIntent(InsightsActivity.class)));

        content.addView(Ui.sectionTitle(this, getString(R.string.more)));

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

        addRow(new Row(R.drawable.ic_settings, false, getString(R.string.repo_settings),
                null, false, true),
                v -> startActivity(repoIntent(RepoSettingsActivity.class)));
    }
}

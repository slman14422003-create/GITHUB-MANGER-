package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Edit repository details, visibility, features, default branch and topics; delete the repository. */
public class RepoSettingsActivity extends BaseRepoActivity {
    private LinearLayout content;
    private JSONObject data;
    private String topicsOriginal = "";
    private final List<String> branchNames = new ArrayList<>();

    private EditText fName;
    private EditText fDesc;
    private EditText fHome;
    private EditText fTopics;
    private CheckBox cPrivate;
    private CheckBox cArchived;
    private CheckBox cIssues;
    private CheckBox cWiki;
    private Spinner spBranch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        bindHeader(getString(R.string.repo_settings), repo);
        content = findViewById(R.id.content);
        btnRefresh.setOnClickListener(v -> load());
        action(btnA1, R.drawable.ic_check, R.string.save, v -> save());
        load();
    }

    private void load() {
        loading(true);
        io.execute(() -> {
            try {
                final JSONObject r = api.getRepo(owner, repo);
                String topics = "";
                try {
                    JSONArray t = api.topics(owner, repo);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; t != null && i < t.length(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(t.getString(i));
                    }
                    topics = sb.toString();
                } catch (Exception ignored) {
                }
                final List<String> names = new ArrayList<>();
                try {
                    JSONArray b = api.listBranches(owner, repo);
                    for (int i = 0; i < b.length(); i++) names.add(b.getJSONObject(i).optString("name"));
                } catch (Exception ignored) {
                }
                final String fTop = topics;
                post(() -> {
                    loading(false);
                    data = r;
                    topicsOriginal = fTop;
                    branchNames.clear();
                    branchNames.addAll(names);
                    render();
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void render() {
        content.removeAllViews();
        LinearLayout form = Ui.box(this);
        fName = Ui.edit(this, getString(R.string.repo_name), data.optString("name"));
        fDesc = Ui.editMulti(this, getString(R.string.description), Fmt.s(data, "description"), 2);
        fHome = Ui.edit(this, getString(R.string.homepage), Fmt.s(data, "homepage"));
        fTopics = Ui.edit(this, getString(R.string.topics_hint), topicsOriginal);
        cPrivate = Ui.check(this, R.string.private_repo, data.optBoolean("private"));
        cArchived = Ui.check(this, R.string.archived_repo, data.optBoolean("archived"));
        cIssues = Ui.check(this, R.string.enable_issues, data.optBoolean("has_issues", true));
        cWiki = Ui.check(this, R.string.enable_wiki, data.optBoolean("has_wiki", true));

        form.addView(Ui.label(this, getString(R.string.repo_name)));
        form.addView(fName);
        form.addView(Ui.label(this, getString(R.string.description)));
        form.addView(fDesc);
        form.addView(Ui.label(this, getString(R.string.homepage)));
        form.addView(fHome);
        form.addView(Ui.label(this, getString(R.string.topics)));
        form.addView(fTopics);
        if (!branchNames.isEmpty()) {
            form.addView(Ui.label(this, getString(R.string.default_branch)));
            int sel = Math.max(0, branchNames.indexOf(data.optString("default_branch")));
            spBranch = Ui.spinner(this, branchNames, sel);
            form.addView(spBranch);
        } else {
            spBranch = null;
        }
        form.addView(cPrivate);
        form.addView(cArchived);
        form.addView(cIssues);
        form.addView(cWiki);

        Button save = Ui.button(this, R.string.save, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 14);
        save.setLayoutParams(lp);
        save.setOnClickListener(v -> save());
        form.addView(save);
        content.addView(form);

        content.addView(Ui.sectionTitle(this, getString(R.string.danger_zone)));
        LinearLayout danger = Ui.box(this);
        Button del = Ui.button(this, R.string.delete_repo, false);
        del.setTextColor(Ui.color(this, R.color.bad));
        del.setOnClickListener(v -> deleteDialog());
        danger.addView(del);
        content.addView(danger);
    }

    private static List<String> parseTopics(String text) {
        List<String> out = new ArrayList<>();
        for (String p : text.split("[,\\s]+")) {
            String t = p.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private void save() {
        if (data == null) return;
        final String newName = fName.getText().toString().trim();
        if (newName.isEmpty()) return;
        final String desc = fDesc.getText().toString().trim();
        final String home = fHome.getText().toString().trim();
        final String topicsText = fTopics.getText().toString();
        final boolean priv = cPrivate.isChecked();
        final boolean arch = cArchived.isChecked();
        final boolean issues = cIssues.isChecked();
        final boolean wiki = cWiki.isChecked();
        final String defBranch = spBranch == null ? null : branchNames.get(spBranch.getSelectedItemPosition());
        final boolean renamed = !newName.equals(repo);
        bg(() -> {
            JSONObject b = new JSONObject();
            if (renamed) b.put("name", newName);
            b.put("description", desc);
            b.put("homepage", home);
            b.put("private", priv);
            b.put("archived", arch);
            b.put("has_issues", issues);
            b.put("has_wiki", wiki);
            if (defBranch != null && !defBranch.equals(data.optString("default_branch"))) {
                b.put("default_branch", defBranch);
            }
            api.updateRepo(owner, repo, b);
            String targetRepo = renamed ? newName : repo;
            List<String> wanted = parseTopics(topicsText);
            if (!wanted.equals(parseTopics(topicsOriginal))) {
                try {
                    api.setTopics(owner, targetRepo, wanted);
                } catch (GitHubApi.ApiException e) {
                    showError(e);
                }
            }
            post(() -> {
                toast(R.string.saved);
                if (renamed) {
                    Intent i = new Intent(this, ReposActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(i);
                    finish();
                } else {
                    load();
                }
            });
        });
    }

    private void deleteDialog() {
        LinearLayout box = Ui.box(this);
        box.addView(Ui.label(this, getString(R.string.delete_repo_msg, repo)));
        final EditText confirmText = Ui.edit(this, repo, null);
        box.addView(confirmText);
        new Dlg(this)
                .setTitle(R.string.delete_repo)
                .setView(box)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    if (!confirmText.getText().toString().trim().equals(repo)) {
                        toast(R.string.name_mismatch);
                        return;
                    }
                    bg(() -> {
                        api.deleteRepo(owner, repo);
                        post(() -> {
                            Intent i = new Intent(this, ReposActivity.class);
                            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            startActivity(i);
                            finish();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}

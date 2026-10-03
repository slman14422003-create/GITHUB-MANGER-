package com.ghmanager.app;

import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

/** An issue or pull request: body, comments, comment / close / reopen / merge. */
public class IssueDetailActivity extends BaseRepoActivity {
    private long number;
    private LinearLayout content;
    private JSONObject issue;
    private JSONObject pull;
    private JSONArray comments = new JSONArray();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        number = getIntent().getLongExtra("number", 0);
        bindHeader("#" + number, repo);
        content = findViewById(R.id.content);
        btnRefresh.setOnClickListener(v -> load());
        action(btnA1, R.drawable.ic_open, R.string.open_in_github, v -> {
            if (issue != null) openUrl(Fmt.s(issue, "html_url"));
        });
        load();
    }

    private void load() {
        loading(true);
        io.execute(() -> {
            try {
                final JSONObject i = api.getIssue(owner, repo, number);
                JSONObject pr = null;
                if (i.has("pull_request")) {
                    try {
                        pr = api.getPull(owner, repo, number);
                    } catch (Exception ignored) {
                    }
                }
                final JSONArray c = api.listComments(owner, repo, number);
                final JSONObject fpr = pr;
                post(() -> {
                    loading(false);
                    issue = i;
                    pull = fpr;
                    comments = c;
                    render();
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void render() {
        if (issue == null) return;
        content.removeAllViews();
        final boolean open = "open".equals(issue.optString("state"));
        final boolean isPr = issue.has("pull_request");
        setSubtitle(getString(isPr ? R.string.pull_request : R.string.issue));

        TextView t = Ui.body(this, issue.optString("title"), 18, R.color.text_primary);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(t);

        JSONObject u = issue.optJSONObject("user");
        StringBuilder meta = new StringBuilder();
        meta.append(getString(open ? R.string.st_open : R.string.st_closed));
        if (pull != null && pull.optBoolean("merged")) meta = new StringBuilder(getString(R.string.merged));
        if (u != null) meta.append(" · ").append(u.optString("login"));
        meta.append(" · ").append(Fmt.ago(Fmt.s(issue, "created_at")));
        JSONArray labels = issue.optJSONArray("labels");
        if (labels != null && labels.length() > 0) {
            meta.append("\n");
            for (int i = 0; i < labels.length(); i++) {
                JSONObject l = labels.optJSONObject(i);
                if (l != null) meta.append(i > 0 ? ", " : "").append(l.optString("name"));
            }
        }
        if (pull != null) {
            JSONObject head = pull.optJSONObject("head");
            JSONObject base = pull.optJSONObject("base");
            if (head != null && base != null) {
                meta.append("\n").append(head.optString("ref")).append(" → ").append(base.optString("ref"));
            }
            meta.append("\n+").append(pull.optInt("additions")).append("  −").append(pull.optInt("deletions"))
                    .append(" · ").append(getString(R.string.changed_files, pull.optInt("changed_files")));
        }
        content.addView(Ui.body(this, meta.toString(), 13, R.color.text_secondary));

        String body = Fmt.s(issue, "body");
        content.addView(Ui.body(this, body.isEmpty() ? getString(R.string.no_description) : body, 14,
                body.isEmpty() ? R.color.text_hint : R.color.text_primary));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        Button comment = Ui.button(this, R.string.add_comment, true);
        comment.setOnClickListener(v -> commentDialog());
        buttons.addView(comment, weighted());
        Button toggle = Ui.button(this, open ? R.string.close_item : R.string.reopen_item, false);
        toggle.setOnClickListener(v -> bg(() -> {
            api.setIssueState(owner, repo, number, !open);
            post(this::load);
        }));
        boolean merged = pull != null && pull.optBoolean("merged");
        if (!merged) buttons.addView(toggle, weighted());
        content.addView(buttons);

        if (isPr && open && pull != null) {
            boolean notMergeable = !pull.isNull("mergeable") && !pull.optBoolean("mergeable", true);
            if (notMergeable) {
                content.addView(Ui.body(this, getString(R.string.pr_not_mergeable), 13, R.color.bad));
            } else {
                LinearLayout mb = new LinearLayout(this);
                mb.setOrientation(LinearLayout.HORIZONTAL);
                mb.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
                Button merge = Ui.button(this, R.string.merge_pr, false);
                merge.setOnClickListener(v -> mergeDialog());
                mb.addView(merge, weighted());
                content.addView(mb);
            }
        }

        content.addView(Ui.sectionTitle(this, getString(R.string.comments_title, comments.length())));
        for (int i = 0; i < comments.length(); i++) {
            JSONObject c = comments.optJSONObject(i);
            if (c == null) continue;
            JSONObject cu = c.optJSONObject("user");
            String head = (cu == null ? "" : cu.optString("login")) + " · " + Fmt.ago(Fmt.s(c, "created_at"));
            TextView h = Ui.body(this, head, 12, R.color.accent_text);
            h.setTypeface(Typeface.DEFAULT_BOLD);
            content.addView(h);
            content.addView(Ui.body(this, Fmt.s(c, "body"), 14, R.color.text_primary));
        }
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        return lp;
    }

    private void commentDialog() {
        LinearLayout box = Ui.box(this);
        final EditText body = Ui.editMulti(this, getString(R.string.comment_hint), null, 4);
        box.addView(body);
        new Dlg(this)
                .setTitle(R.string.add_comment)
                .setView(box)
                .setPositiveButton(R.string.send, (d, w) -> {
                    final String b = body.getText().toString().trim();
                    if (b.isEmpty()) return;
                    bg(() -> {
                        api.addComment(owner, repo, number, b);
                        post(this::load);
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void mergeDialog() {
        final String[] methods = {"merge", "squash", "rebase"};
        String[] labels = {getString(R.string.merge_commit), getString(R.string.merge_squash), getString(R.string.merge_rebase)};
        choose(getString(R.string.merge_pr), labels, (d, which) ->
                bg(() -> {
                    api.mergePull(owner, repo, number, methods[which]);
                    post(() -> {
                        toast(R.string.done_ok);
                        load();
                    });
                }));
    }
}

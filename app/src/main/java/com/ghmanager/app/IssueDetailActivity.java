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
    private JSONArray reviews = new JSONArray();

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
                JSONArray rv = new JSONArray();
                if (pr != null) {
                    try {
                        rv = api.listReviews(owner, repo, number);
                    } catch (Exception ignored) {
                    }
                }
                final JSONArray frv = rv;
                final JSONObject fpr = pr;
                post(() -> {
                    loading(false);
                    issue = i;
                    pull = fpr;
                    comments = c;
                    reviews = frv;
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

        if (isPr && pull != null) prSection(open);

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
                    if (b.isEmpty()) { Dlg.stay(d); return; }
                    bg(() -> {
                        api.addComment(owner, repo, number, b);
                        post(this::load);
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void prSection(boolean open) {
        String ms = pull.optString("mergeable_state");
        boolean merged = pull.optBoolean("merged");
        if (open && !ms.isEmpty() && !"unknown".equals(ms)) {
            int res;
            int col;
            switch (ms) {
                case "clean":
                    res = R.string.pr_state_clean;
                    col = R.color.ok;
                    break;
                case "dirty":
                    res = R.string.pr_state_dirty;
                    col = R.color.bad;
                    break;
                case "blocked":
                    res = R.string.pr_state_blocked;
                    col = R.color.warn;
                    break;
                case "behind":
                    res = R.string.pr_state_behind;
                    col = R.color.warn;
                    break;
                case "unstable":
                    res = R.string.pr_state_unstable;
                    col = R.color.warn;
                    break;
                default:
                    res = R.string.pr_state_other;
                    col = R.color.text_secondary;
                    break;
            }
            TextView st = Ui.noteCard(this, getString(res), col);
            content.addView(st);
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        Button files = Ui.button(this, R.string.df_pr_files_btn, false);
        files.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_diff, 0, 0, 0);
        files.setCompoundDrawablePadding(Ui.dp(this, 8));
        files.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.text_primary)));
        files.setOnClickListener(v -> startActivity(CommitDiffActivity.pull(this, number)));
        row.addView(files, weighted());
        if (open) {
            Button review = Ui.button(this, R.string.pr_review, false);
            review.setOnClickListener(v -> reviewDialog());
            row.addView(review, weighted());
        }
        content.addView(row);
        if (open && !merged) {
            boolean notMergeable = !pull.isNull("mergeable") && !pull.optBoolean("mergeable", true);
            if (notMergeable) {
                content.addView(Ui.body(this, getString(R.string.pr_not_mergeable), 13, R.color.bad));
            } else {
                LinearLayout mb = new LinearLayout(this);
                mb.setOrientation(LinearLayout.HORIZONTAL);
                mb.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
                Button merge = Ui.button(this, R.string.merge_pr, true);
                merge.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_merge, 0, 0, 0);
                merge.setCompoundDrawablePadding(Ui.dp(this, 8));
                merge.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.on_accent)));
                merge.setOnClickListener(v -> mergeDialog());
                mb.addView(merge, weighted());
                content.addView(mb);
            }
        }
        if (reviews.length() > 0) {
            content.addView(Ui.sectionTitle(this, getString(R.string.pr_reviews_n, reviews.length())));
            for (int i = 0; i < reviews.length(); i++) {
                JSONObject r = reviews.optJSONObject(i);
                if (r == null) continue;
                String state = r.optString("state");
                JSONObject u = r.optJSONObject("user");
                int color;
                int label;
                if ("APPROVED".equals(state)) {
                    color = Ui.color(this, R.color.ok);
                    label = R.string.pr_rv_approved;
                } else if ("CHANGES_REQUESTED".equals(state)) {
                    color = Ui.color(this, R.color.bad);
                    label = R.string.pr_rv_changes;
                } else if ("DISMISSED".equals(state)) {
                    color = Ui.color(this, R.color.text_secondary);
                    label = R.string.pr_rv_dismissed;
                } else {
                    color = Ui.color(this, R.color.info);
                    label = R.string.pr_rv_commented;
                }
                String body = Fmt.s(r, "body");
                String sub = (u == null ? "" : u.optString("login")) + " · " + Fmt.ago(Fmt.s(r, "submitted_at"));
                Row row2 = new Row(R.drawable.ic_check_circle, false, body.isEmpty() ? getString(label) : body, sub,
                        false, false).tint(color);
                row2.badge(getString(label), color);
                content.addView(Ui.rowView(this, content, row2, null));
            }
        }
    }

    private void reviewDialog() {
        final String[] events = {"APPROVE", "REQUEST_CHANGES", "COMMENT"};
        String[] labels = {getString(R.string.pr_rv_approved), getString(R.string.pr_rv_changes),
                getString(R.string.pr_rv_commented)};
        LinearLayout box = Ui.box(this);
        final android.widget.Spinner sp = Ui.spinner(this, java.util.Arrays.asList(labels), 0);
        final EditText body = Ui.editMulti(this, getString(R.string.comment_hint), null, 4);
        box.addView(sp);
        box.addView(body);
        new Dlg(this)
                .setTitle(R.string.pr_review)
                .setView(box)
                .setPositiveButton(R.string.send, (d, w) -> {
                    final String ev = events[sp.getSelectedItemPosition()];
                    final String b = body.getText().toString().trim();
                    if (!"APPROVE".equals(ev) && b.isEmpty()) {
                        toast(R.string.pr_review_need_text);
                        Dlg.stay(d);
                        return;
                    }
                    bg(() -> {
                        api.createReview(owner, repo, number, ev, b);
                        post(() -> {
                            toast(R.string.done_ok);
                            load();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void mergeDialog() {
        final String[] methods = {"merge", "squash", "rebase"};
        String[] labels = {getString(R.string.merge_commit), getString(R.string.merge_squash), getString(R.string.merge_rebase)};
        final String prTitle = issue == null ? "" : issue.optString("title");
        LinearLayout box = Ui.box(this);
        final android.widget.Spinner sp = Ui.spinner(this, java.util.Arrays.asList(labels), 0);
        final EditText title = Ui.edit(this, getString(R.string.pr_merge_title), "Merge pull request #" + number);
        final EditText msg = Ui.editMulti(this, getString(R.string.pr_merge_msg), null, 3);
        JSONObject head = pull == null ? null : pull.optJSONObject("head");
        JSONObject base = pull == null ? null : pull.optJSONObject("base");
        final String headRef = head == null ? "" : head.optString("ref");
        boolean sameRepo = false;
        if (head != null && base != null) {
            JSONObject hr = head.optJSONObject("repo");
            JSONObject br = base.optJSONObject("repo");
            sameRepo = hr != null && br != null && hr.optString("full_name").equals(br.optString("full_name"));
        }
        final String baseRef = base == null ? "" : base.optString("ref");
        final android.widget.CheckBox del = Ui.check(this, R.string.pr_delete_branch, false);
        sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                title.setText(pos == 1 ? prTitle + " (#" + number + ")" : "Merge pull request #" + number);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });
        box.addView(Ui.label(this, getString(R.string.pr_merge_method)));
        box.addView(sp);
        box.addView(title);
        box.addView(msg);
        if (sameRepo && !headRef.isEmpty() && !headRef.equals(baseRef)) box.addView(del);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new Dlg(this)
                .setTitle(R.string.merge_pr)
                .setView(sv)
                .setPositiveButton(R.string.merge, (d, w) -> {
                    final String method = methods[sp.getSelectedItemPosition()];
                    final String t = title.getText().toString().trim();
                    final String m = msg.getText().toString().trim();
                    final boolean drop = del.getParent() != null && del.isChecked();
                    bg(() -> {
                        api.mergePull(owner, repo, number, method, "rebase".equals(method) ? null : t,
                                "rebase".equals(method) ? null : m);
                        if (drop) {
                            try {
                                api.deleteBranch(owner, repo, headRef);
                            } catch (Exception ignored) {
                            }
                        }
                        post(() -> {
                            toast(R.string.done_ok);
                            load();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}

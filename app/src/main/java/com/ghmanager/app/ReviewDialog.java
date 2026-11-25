package com.ghmanager.app;

import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

import org.json.JSONArray;
import org.json.JSONObject;

public final class ReviewDialog {
    private ReviewDialog() {
    }

    private static final String[] EVENTS = {"APPROVE", "REQUEST_CHANGES", "COMMENT"};

    static boolean valid(String event, String body, int comments) {
        if ("REQUEST_CHANGES".equals(event)) return !body.isEmpty();
        if ("COMMENT".equals(event)) return !body.isEmpty() || comments > 0;
        return "APPROVE".equals(event);
    }

    static void show(final BaseRepoActivity a, final long number, final String headSha, final Runnable onDone) {
        final PendingReviews.Review local = PendingReviews.load(a, a.owner, a.repo, number);
        String[] labels = {a.getString(R.string.pr_rv_approved), a.getString(R.string.pr_rv_changes),
                a.getString(R.string.pr_rv_commented)};
        LinearLayout box = Ui.box(a);
        final Spinner sp = Ui.spinner(a, java.util.Arrays.asList(labels), 2);
        final EditText body = Ui.editMulti(a, a.getString(R.string.comment_hint), local.body, 4);
        box.addView(sp);
        box.addView(body);
        if (!local.comments.isEmpty()) {
            box.addView(Ui.noteCard(a, a.getString(R.string.rv_lines_note, local.comments.size()),
                    R.color.text_secondary));
        }
        new Dlg(a)
                .setTitle(R.string.pr_review)
                .setView(box)
                .setPositiveButton(R.string.send, (d, w) -> {
                    final String ev = EVENTS[sp.getSelectedItemPosition()];
                    final String b = body.getText().toString().trim();
                    if (!valid(ev, b, local.comments.size())) {
                        a.toast("REQUEST_CHANGES".equals(ev) ? R.string.pr_review_need_text : R.string.rv_need_text);
                        Dlg.stay(d);
                        return;
                    }
                    local.body = b;
                    PendingReviews.save(a, a.owner, a.repo, number, local);
                    submit(a, number, headSha, ev, b, local, onDone);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void submit(final BaseRepoActivity a, final long number, final String headSha, final String ev,
                               final String body, final PendingReviews.Review local, final Runnable onDone) {
        a.showProgress(a.getString(R.string.working));
        a.bg(() -> {
            JSONArray reviews = a.api.listReviews(a.owner, a.repo, number);
            JSONObject pending = null;
            for (int i = 0; reviews != null && i < reviews.length(); i++) {
                JSONObject r = reviews.optJSONObject(i);
                if (r != null && "PENDING".equals(r.optString("state"))) pending = r;
            }
            final JSONObject fp = pending;
            if (fp != null) {
                a.post(() -> {
                    a.hideProgress();
                    askPending(a, number, headSha, ev, body, local, fp, onDone);
                });
                return;
            }
            create(a, number, headSha, ev, body, local, onDone);
        });
    }

    private static void askPending(final BaseRepoActivity a, final long number, final String headSha, final String ev,
                                   final String body, final PendingReviews.Review local, final JSONObject pending,
                                   final Runnable onDone) {
        final long id = pending.optLong("id");
        new Dlg(a)
                .setTitle(R.string.rv_pending_title)
                .setMessage(a.getString(R.string.rv_pending_msg))
                .setPositiveButton(R.string.rv_pending_submit, (d, w) -> {
                    a.showProgress(a.getString(R.string.working));
                    a.bg(() -> {
                        a.api.submitReview(a.owner, a.repo, number, id, ev, body);
                        a.post(() -> {
                            a.hideProgress();
                            a.toast(R.string.done_ok);
                            if (onDone != null) onDone.run();
                        });
                    });
                })
                .setNeutralButton(R.string.rv_pending_delete, (d, w) -> {
                    a.showProgress(a.getString(R.string.working));
                    a.bg(() -> {
                        a.api.deleteReview(a.owner, a.repo, number, id);
                        create(a, number, headSha, ev, body, local, onDone);
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void create(final BaseRepoActivity a, final long number, final String headSha, final String ev,
                               final String body, final PendingReviews.Review local, final Runnable onDone)
            throws Exception {
        String commit = local.commitId.isEmpty() ? headSha : local.commitId;
        a.api.createReview(a.owner, a.repo, number, commit, ev, body, local.apiComments());
        PendingReviews.clear(a, a.owner, a.repo, number);
        a.post(() -> {
            a.hideProgress();
            a.toast(R.string.done_ok);
            if (onDone != null) onDone.run();
        });
    }
}

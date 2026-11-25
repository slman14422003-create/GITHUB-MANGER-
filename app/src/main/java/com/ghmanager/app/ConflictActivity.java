package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class ConflictActivity extends BaseRepoActivity {
    private static final int MAX_BYTES = 1536 * 1024;
    private String itemId;
    private boolean fromEditor;
    private LinearLayout content;
    private String shownRemoteSha;
    private String shownRemoteText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        itemId = getIntent().getStringExtra("itemId");
        fromEditor = getIntent().getBooleanExtra("fromEditor", false);
        CommitQueue.Item it = itemId == null ? null : CommitQueue.get(this, itemId);
        if (it == null) {
            finish();
            return;
        }
        owner = it.owner;
        repo = it.repo;
        branch = it.branch;
        bindHeader(getString(R.string.cq_conflict_title), it.path);
        content = findViewById(R.id.content);
        btnRefresh.setOnClickListener(v -> load());
        load();
    }

    static String decode(GitHubApi api, JSONObject meta, String o, String r, String path, String b) throws Exception {
        long size = meta.optLong("size");
        if (size > MAX_BYTES) return "";
        byte[] bytes;
        String enc = meta.optString("encoding");
        String c = meta.optString("content");
        if ("base64".equals(enc) && !c.isEmpty()) bytes = Base64.decode(c, Base64.DEFAULT);
        else bytes = api.getFileBytes(o, r, path, b, MAX_BYTES);
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private void load() {
        final CommitQueue.Item it = CommitQueue.get(this, itemId);
        if (it == null) {
            finish();
            return;
        }
        loading(true);
        io.execute(() -> {
            try {
                String sha = null;
                String text = "";
                try {
                    JSONObject meta = api.getContent(it.owner, it.repo, it.path, it.branch);
                    sha = meta.optString("sha");
                    if (sha.isEmpty()) sha = null;
                    text = decode(api, meta, it.owner, it.repo, it.path, it.branch);
                } catch (GitHubApi.ApiException e) {
                    if (e.code != 404) throw e;
                }
                final String fs = sha;
                final String ft = text;
                post(() -> {
                    loading(false);
                    shownRemoteSha = fs;
                    shownRemoteText = ft;
                    render(it);
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void render(final CommitQueue.Item it) {
        content.removeAllViews();
        boolean missing = shownRemoteSha == null;
        content.addView(Ui.noteCard(this, getString(missing ? R.string.cq_remote_missing : R.string.cq_conflict_note),
                R.color.warn));
        content.addView(Ui.sectionTitle(this, getString(R.string.cq_diff_title)));
        final LineDiff.Result diff = it.content.length() + shownRemoteText.length() > 600000 ? null
                : LineDiff.compute(shownRemoteText, it.content);
        if (diff == null) {
            content.addView(Ui.body(this, getString(R.string.df_truncated), 14, R.color.text_secondary));
        } else if (!diff.changed()) {
            content.addView(Ui.body(this, getString(R.string.ed_no_changes), 14, R.color.text_secondary));
        } else {
            content.addView(Ui.body(this, getString(R.string.ed_changes_summary, diff.added, diff.removed), 13,
                    R.color.text_secondary));
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 8));
            List<CharSequence> chunks = LineDiff.chunks(this, diff, 3);
            for (CharSequence cs : chunks) {
                TextView tv = new TextView(this);
                tv.setText(cs);
                tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                tv.setTextSize(12);
                tv.setLineSpacing(Ui.dp(this, 2), 1f);
                tv.setTextColor(Ui.color(this, R.color.text_primary));
                tv.setTextDirection(View.TEXT_DIRECTION_LTR);
                tv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                tv.setHorizontallyScrolling(true);
                inner.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(inner);
            content.addView(hs);
        }
        content.addView(Ui.sectionTitle(this, getString(R.string.cq_choose)));
        Button mine = Ui.button(this, R.string.cq_keep_mine, true);
        mine.setOnClickListener(v -> confirm(getString(R.string.cq_mine_title), getString(R.string.cq_mine_msg),
                R.string.cq_keep_mine, () -> keepMine(it)));
        Button remote = Ui.button(this, R.string.cq_keep_remote, false);
        remote.setOnClickListener(v -> confirm(getString(R.string.cq_remote_title), getString(R.string.cq_remote_msg),
                R.string.cq_keep_remote, () -> keepRemote(it)));
        Button draft = Ui.button(this, R.string.cq_open_draft, false);
        draft.setOnClickListener(v -> openDraft(it));
        for (Button b : new Button[]{mine, remote, draft}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4));
            content.addView(b, lp);
        }
    }

    private void keepMine(final CommitQueue.Item it) {
        final String expected = shownRemoteSha;
        showProgress(getString(R.string.committing));
        bg(() -> {
            String fresh = CommitQueue.remoteSha(api, it.owner, it.repo, it.path, it.branch);
            if (!CommitQueue.same(fresh, expected)) {
                post(() -> {
                    hideProgress();
                    toast(R.string.cq_changed_again);
                    load();
                });
                return;
            }
            try {
                api.putFileContent(it.owner, it.repo, it.path, it.content.getBytes(StandardCharsets.UTF_8),
                        it.message, it.branch, fresh);
            } catch (GitHubApi.ApiException e) {
                if (e.code == 409) {
                    post(() -> {
                        hideProgress();
                        toast(R.string.cq_changed_again);
                        load();
                    });
                    return;
                }
                throw e;
            }
            CommitQueue.remove(getApplicationContext(), it.id);
            Drafts.delete(getApplicationContext(), it.owner, it.repo, it.branch, it.path);
            RepoTree.invalidate(getApplicationContext(), it.owner, it.repo, it.branch);
            FileDates.invalidate();
            post(() -> {
                hideProgress();
                toast(R.string.cq_done_mine);
                finishWith("mine");
            });
        });
    }

    private void keepRemote(CommitQueue.Item it) {
        CommitQueue.remove(this, it.id);
        Drafts.delete(this, it.owner, it.repo, it.branch, it.path);
        toast(R.string.cq_done_remote);
        finishWith("remote");
    }

    private void openDraft(CommitQueue.Item it) {
        Drafts.Draft d = new Drafts.Draft();
        d.acc = it.acc;
        d.owner = it.owner;
        d.repo = it.repo;
        d.branch = it.branch;
        d.path = it.path;
        d.text = it.content;
        d.baseSha = shownRemoteSha;
        d.baseText = shownRemoteText;
        d.auto = true;
        Drafts.save(this, d);
        CommitQueue.remove(this, it.id);
        if (fromEditor) {
            finishWith("draft");
        } else {
            EditorActivity.launch(this, it.path, false);
            finish();
        }
    }

    private void finishWith(String action) {
        Intent r = new Intent();
        r.putExtra("action", action);
        setResult(RESULT_OK, r);
        finish();
    }
}

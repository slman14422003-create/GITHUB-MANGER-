package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

public class QueueActivity extends BaseRepoActivity {
    private final List<CommitQueue.Item> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        bindHeader(getString(R.string.cq_title), null);
        initList();
        btnRefresh.setOnClickListener(v -> sendAll());
        listView.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < items.size()) menu(items.get(pos));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        items.clear();
        items.addAll(CommitQueue.list(this));
        List<Row> rows = new ArrayList<>();
        for (CommitQueue.Item it : items) {
            String sub = it.owner + "/" + it.repo + " · " + it.branch + " · " + Fmt.agoMs(it.at);
            Row r = new Row(R.drawable.ic_upload, false, it.path, sub, false, true);
            int color;
            int label;
            if (CommitQueue.CONFLICT.equals(it.state)) {
                color = Ui.color(this, R.color.warn);
                label = R.string.cq_conflict;
            } else if (CommitQueue.FAILED.equals(it.state)) {
                color = Ui.color(this, R.color.bad);
                label = R.string.cq_failed;
            } else {
                color = Ui.color(this, R.color.accent);
                label = R.string.cq_pending;
            }
            r.tint(color);
            r.badge(getString(label), color);
            rows.add(r);
        }
        adapter.setRows(rows);
        showEmpty(items.isEmpty(), R.string.cq_empty);
    }

    private void sendAll() {
        if (CommitQueue.pendingCount(this) == 0) {
            toast(R.string.cq_nothing);
            return;
        }
        showProgress(getString(R.string.working));
        final android.content.Context app = getApplicationContext();
        bg(() -> {
            final CommitQueue.Outcome o = CommitQueue.run(app);
            post(() -> {
                hideProgress();
                if (o.unauthorized) {
                    relogin();
                    return;
                }
                toast(getString(R.string.cq_note_text, o.applied, o.conflicts, o.failed));
                if (o.retry > 0) CommitQueueJob.schedule(app);
                render();
            });
        });
    }

    private void menu(final CommitQueue.Item it) {
        final boolean conflict = CommitQueue.CONFLICT.equals(it.state);
        final boolean failed = CommitQueue.FAILED.equals(it.state);
        List<String> opts = new ArrayList<>();
        opts.add(getString(conflict ? R.string.cq_resolve : failed ? R.string.cq_retry : R.string.cq_send_now));
        if (failed) opts.add(getString(R.string.cq_view_error));
        opts.add(getString(R.string.cq_open_file));
        opts.add(getString(R.string.cq_delete));
        choose(it.path, opts.toArray(new String[0]), (d, which) -> {
            String label = opts.get(which);
            if (label.equals(getString(R.string.cq_delete))) {
                confirm(getString(R.string.cq_delete), getString(R.string.cq_delete_msg), R.string.cq_delete, () -> {
                    CommitQueue.remove(this, it.id);
                    toast(R.string.cq_deleted);
                    render();
                });
            } else if (label.equals(getString(R.string.cq_open_file))) {
                Intent i = new Intent(this, EditorActivity.class);
                i.putExtra("owner", it.owner);
                i.putExtra("repo", it.repo);
                i.putExtra("branch", it.branch);
                i.putExtra("path", it.path);
                i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(i);
            } else if (label.equals(getString(R.string.cq_view_error))) {
                info(it.path, it.error.isEmpty() ? getString(R.string.error) : it.error);
            } else if (conflict) {
                Intent i = new Intent(this, ConflictActivity.class);
                i.putExtra("itemId", it.id);
                startActivity(i);
            } else {
                CommitQueue.setState(this, it.id, CommitQueue.PENDING, "");
                sendAll();
            }
        });
    }
}

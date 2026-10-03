package com.ghmanager.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared plumbing for every repository screen: API client, threading, dialogs, downloads. */
public abstract class BaseRepoActivity extends AppCompatActivity {

    protected interface Job {
        void run() throws Exception;
    }

    protected interface Source {
        HttpURLConnection open() throws Exception;
    }

    protected GitHubApi api;
    protected String owner;
    protected String repo;
    protected String branch;

    protected final ExecutorService io = Executors.newFixedThreadPool(3);
    protected final Handler ui = new Handler(Looper.getMainLooper());

    protected AlertDialog progressDialog;
    protected ProgressBar progressBar;
    protected TextView progressText;
    protected boolean busy = false;

    protected TextView titleView;
    protected TextView subtitleView;
    protected TextView statusView;
    protected TextView emptyView;
    protected ImageButton btnA1;
    protected ImageButton btnA2;
    protected ImageButton btnRefresh;
    protected View loadingBar;
    protected ListView listView;
    protected RowAdapter adapter;
    protected LinearLayout chipRow;
    protected View filterScroll;

    private ActivityResultLauncher<String> saveLauncher;
    private Source pendingSource;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        saveLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
                    Source s = pendingSource;
                    pendingSource = null;
                    if (uri != null && s != null) runDownload(uri, s);
                });
        Intent i = getIntent();
        owner = i.getStringExtra("owner");
        repo = i.getStringExtra("repo");
        branch = i.getStringExtra("branch");
        if (branch == null || branch.isEmpty()) branch = "main";
        api = new GitHubApi(Store.getToken(this));
    }

    // ------------------------------------------------------------------ header / list

    protected void bindHeader(String title, String subtitle) {
        titleView = findViewById(R.id.title);
        subtitleView = findViewById(R.id.subtitle);
        titleView.setText(title);
        setSubtitle(subtitle);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        btnA1 = findViewById(R.id.btnA1);
        btnA2 = findViewById(R.id.btnA2);
        btnRefresh = findViewById(R.id.btnRefresh);
        loadingBar = findViewById(R.id.loading);
        if (loadingBar instanceof ProgressBar) Ui.tint(this, (ProgressBar) loadingBar);
    }

    protected void setSubtitle(String s) {
        if (subtitleView == null) return;
        if (s == null || s.isEmpty()) {
            subtitleView.setVisibility(View.GONE);
        } else {
            subtitleView.setText(s);
            subtitleView.setVisibility(View.VISIBLE);
        }
    }

    protected void action(ImageButton b, int icon, int desc, View.OnClickListener l) {
        b.setImageResource(icon);
        b.setContentDescription(getString(desc));
        b.setVisibility(View.VISIBLE);
        b.setOnClickListener(l);
    }

    protected void loading(boolean on) {
        if (loadingBar != null) loadingBar.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
    }

    protected void initList() {
        listView = findViewById(R.id.list);
        statusView = findViewById(R.id.status);
        emptyView = findViewById(R.id.empty);
        chipRow = findViewById(R.id.chipRow);
        filterScroll = findViewById(R.id.filterScroll);
        adapter = new RowAdapter(this);
        listView.setAdapter(adapter);
    }

    protected void showStatus(String text) {
        if (statusView == null) return;
        if (text == null || text.isEmpty()) {
            statusView.setVisibility(View.GONE);
        } else {
            statusView.setText(text);
            statusView.setVisibility(View.VISIBLE);
        }
    }

    protected void showEmpty(boolean show, int textRes) {
        if (emptyView == null) return;
        emptyView.setText(textRes);
        emptyView.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ threading

    /** Posts to the UI thread unless the screen is already gone. */
    protected void post(Runnable r) {
        ui.post(() -> {
            if (!isFinishing() && !isDestroyed()) r.run();
        });
    }

    protected void bg(Job job) {
        io.execute(() -> {
            try {
                job.run();
            } catch (Exception e) {
                showError(e);
            }
        });
    }

    protected void showError(Exception e) {
        if (e instanceof GitHubApi.ApiException && ((GitHubApi.ApiException) e).code == 401) {
            post(this::relogin);
            return;
        }
        final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        post(() -> {
            hideProgress();
            loading(false);
            new AlertDialog.Builder(BaseRepoActivity.this)
                    .setTitle(R.string.error)
                    .setMessage(msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
    }

    /** Inline error for list screens (keeps the screen usable). */
    protected void fail(Exception e) {
        if (e instanceof GitHubApi.ApiException && ((GitHubApi.ApiException) e).code == 401) {
            post(this::relogin);
            return;
        }
        if (statusView == null) {
            showError(e);
            return;
        }
        final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        post(() -> {
            loading(false);
            hideProgress();
            showStatus(msg);
        });
    }

    protected void relogin() {
        Store.clear(this);
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    // ------------------------------------------------------------------ dialogs / helpers

    protected void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    protected void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    protected void confirm(String title, String msg, int positiveRes, Runnable onYes) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(positiveRes, (d, w) -> onYes.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    protected void choose(String title, String[] items, DialogInterface.OnClickListener l) {
        new AlertDialog.Builder(this).setTitle(title).setItems(items, l).show();
    }

    protected void info(String title, String msg) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    protected void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast(R.string.no_browser);
        }
    }

    protected String webUrl(String suffix) {
        return "https://github.com/" + owner + "/" + repo + suffix;
    }

    protected void copy(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            toast(R.string.copied);
        }
    }

    protected void shareText(String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, getString(R.string.share)));
    }

    protected Intent repoIntent(Class<?> cls) {
        Intent i = new Intent(this, cls);
        i.putExtra("owner", owner);
        i.putExtra("repo", repo);
        i.putExtra("branch", branch);
        return i;
    }

    protected void setChipRow(String[] labels, int selected, final ChipListener l) {
        if (chipRow == null) return;
        chipRow.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView c = Ui.chip(this, labels[i], i == selected);
            c.setOnClickListener(v -> l.onChip(idx));
            chipRow.addView(c);
        }
        if (filterScroll != null) filterScroll.setVisibility(View.VISIBLE);
    }

    protected interface ChipListener {
        void onChip(int index);
    }

    // ------------------------------------------------------------------ workflow dispatch

    private static JSONObject parseInputs(String text) {
        JSONObject out = new JSONObject();
        for (String line : text.split("\n")) {
            int i = line.indexOf('=');
            if (i <= 0) continue;
            String k = line.substring(0, i).trim();
            String v = line.substring(i + 1).trim();
            if (k.isEmpty()) continue;
            try {
                out.put(k, v);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    protected void dispatchDialog(final long workflowId, final String workflowName, final Runnable after) {
        LinearLayout box = Ui.box(this);
        final EditText ref = Ui.edit(this, getString(R.string.ref_hint), branch);
        final EditText inputs = Ui.editMulti(this, getString(R.string.inputs_hint), null, 3);
        box.addView(ref);
        box.addView(inputs);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.run_workflow) + ": " + workflowName)
                .setView(box)
                .setPositiveButton(R.string.run, (d, w) -> {
                    String r = ref.getText().toString().trim();
                    final String useRef = r.isEmpty() ? branch : r;
                    final JSONObject in = parseInputs(inputs.getText().toString());
                    bg(() -> {
                        api.dispatchWorkflow(owner, repo, workflowId, useRef, in);
                        post(() -> {
                            toast(R.string.workflow_dispatched);
                            if (after != null) after.run();
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ progress

    protected void showProgress(String text) {
        hideProgress();
        LinearLayout box = Ui.box(this);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 14), Ui.dp(this, 22), Ui.dp(this, 8));
        progressText = new TextView(this);
        progressText.setText(text);
        progressText.setTextColor(Ui.color(this, R.color.text_secondary));
        progressText.setTextSize(14);
        progressText.setPadding(0, 0, 0, Ui.dp(this, 12));
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        Ui.tint(this, progressBar);
        box.addView(progressText);
        box.addView(progressBar);
        progressDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.working)
                .setView(box)
                .setCancelable(false)
                .create();
        progressDialog.show();
    }

    protected void updateProgress(int cur, int total, String text) {
        if (progressBar == null) return;
        progressBar.setIndeterminate(false);
        progressBar.setMax(total);
        progressBar.setProgress(cur);
        progressText.setText((cur + 1) + "/" + total + "\n" + text);
    }

    protected void updateBytes(long done, long total) {
        if (progressBar == null || progressText == null) return;
        if (total > 0) {
            progressBar.setIndeterminate(false);
            progressBar.setMax(1000);
            progressBar.setProgress((int) (done * 1000 / total));
            progressText.setText(Fmt.size(done) + " / " + Fmt.size(total));
        } else {
            progressText.setText(Fmt.size(done));
        }
    }

    protected void hideProgress() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Exception ignored) {
            }
            progressDialog = null;
        }
        progressBar = null;
        progressText = null;
        busy = false;
    }

    // ------------------------------------------------------------------ downloads (save to a user-chosen location)

    protected void saveAs(String fileName, Source src) {
        pendingSource = src;
        try {
            saveLauncher.launch(fileName);
        } catch (Exception e) {
            pendingSource = null;
            toast(R.string.cannot_save);
        }
    }

    private void runDownload(final Uri uri, final Source src) {
        if (busy) return;
        busy = true;
        showProgress(getString(R.string.downloading));
        bg(() -> {
            HttpURLConnection c = src.open();
            try {
                long total = c.getContentLengthLong();
                InputStream in = c.getInputStream();
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out == null) throw new IOException("Cannot open destination");
                try {
                    GitHubApi.copy(in, out, total, (done, tot) -> post(() -> updateBytes(done, tot)));
                } finally {
                    out.close();
                    in.close();
                }
            } finally {
                c.disconnect();
            }
            post(() -> {
                hideProgress();
                toast(R.string.saved);
            });
        });
    }

    // ------------------------------------------------------------------ previous versions / rollback

    /** Lets the user save any version (commit sha, tag or branch) of the repository as a ZIP. */
    protected void downloadVersion(final String ref, String label) {
        String name = (label == null || label.isEmpty()) ? ref : label;
        saveAs(repo + "-" + name.replaceAll("[^A-Za-z0-9._-]", "-") + ".zip",
                () -> api.openZipball(owner, repo, ref));
    }

    protected void restoreVersion(String ref, String label, String detail, Runnable after) {
        restoreVersion(branch, ref, label, detail, after);
    }

    /**
     * Rolls {@code targetBranch} back to the content of {@code ref} (commit sha or tag) after an
     * explicit confirmation. It adds a new commit, so nothing is lost and the rollback is reversible.
     */
    protected void restoreVersion(final String targetBranch, final String ref, final String label,
                                  final String detail, final Runnable after) {
        String msg = getString(R.string.restore_msg, label, targetBranch);
        if (detail != null && !detail.isEmpty()) msg = msg + "\n\n" + detail;
        confirm(getString(R.string.restore_title), msg, R.string.restore_action, () -> {
            if (busy) return;
            busy = true;
            showProgress(getString(R.string.restoring));
            bg(() -> {
                final String sha = api.resolveCommitSha(owner, repo, ref);
                final String result = api.restoreToCommit(owner, repo, targetBranch, sha,
                        getString(R.string.restore_commit_msg, label));
                post(() -> {
                    hideProgress();
                    if (result == null) {
                        info(getString(R.string.restore_title), getString(R.string.restore_same));
                    } else {
                        toast(R.string.restore_done);
                        if (after != null) after.run();
                    }
                });
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}

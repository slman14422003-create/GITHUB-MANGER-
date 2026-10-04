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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

    private ActivityResultLauncher<Intent> saveLauncher;
    private Source pendingSource;
    private boolean pendingExtractApk;
    private String pendingName;
    private boolean tokenDialogShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The save location is chosen in the app's OWN file manager (never the system picker).
        saveLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
            Source s = pendingSource;
            boolean extract = pendingExtractApk;
            String name = pendingName;
            pendingSource = null;
            pendingExtractApk = false;
            pendingName = null;
            Intent d = res.getData();
            if (res.getResultCode() != RESULT_OK || d == null || s == null || name == null) return;
            java.util.ArrayList<String> paths = d.getStringArrayListExtra("paths");
            if (paths == null || paths.isEmpty()) return;
            java.io.File dir = new java.io.File(paths.get(0));
            runDownload(uniqueFile(dir, name), s, extract);
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
        Ui.press(this, findViewById(R.id.btnBack));
        Ui.press(this, btnA1);
        Ui.press(this, btnA2);
        Ui.press(this, btnRefresh);
        loadingBar = findViewById(R.id.loading);
        if (loadingBar instanceof ProgressBar) Ui.tint(this, (ProgressBar) loadingBar);
        View rows = findViewById(R.id.content);
        if (rows instanceof android.view.ViewGroup) Ui.autoGroup(this, (android.view.ViewGroup) rows);
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
            Dlg.result(BaseRepoActivity.this, false, getString(R.string.error), msg);
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

    /**
     * GitHub answered 401. The app NEVER signs the user out on its own: the token stays stored and
     * the user decides (the logout button is the only thing that clears it).
     */
    protected void relogin() {
        hideProgress();
        loading(false);
        if (isFinishing() || tokenDialogShown) return;
        tokenDialogShown = true;
        new Dlg(this)
                .setTitle(R.string.token_rejected_title)
                .setMessage(R.string.token_rejected_msg)
                .setPositiveButton(R.string.token_new, (d, w) -> {
                    Intent i = new Intent(this, LoginActivity.class);
                    i.putExtra("reauth", true);
                    startActivity(i);
                })
                .setNegativeButton(R.string.close, null)
                .setOnDismissListener(d -> tokenDialogShown = false)
                .show();
    }

    // ------------------------------------------------------------------ dialogs / helpers

    protected void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    protected void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    protected void confirm(String title, String msg, int positiveRes, Runnable onYes) {
        new Dlg(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(positiveRes, (d, w) -> onYes.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    protected void choose(String title, String[] items, DialogInterface.OnClickListener l) {
        new Dlg(this).setTitle(title).setItems(items, l).show();
    }

    protected void info(String title, String msg) {
        new Dlg(this)
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
        new Dlg(this)
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
        progressDialog = new Dlg(this)
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
        saveAs(fileName, src, false);
    }

    /** When {@code extractApk} is true the source is a ZIP and only the .apk inside it is saved. */
    protected void saveAs(String fileName, Source src, boolean extractApk) {
        pendingSource = src;
        pendingExtractApk = extractApk;
        pendingName = safeName(fileName);
        try {
            saveLauncher.launch(new Intent(this, FileManagerActivity.class)
                    .putExtra("pick", "save")
                    .putExtra("fileName", pendingName));
        } catch (Exception e) {
            pendingSource = null;
            pendingExtractApk = false;
            pendingName = null;
            toast(R.string.cannot_save);
        }
    }

    /** Strips path separators and control characters so a remote name can never escape the chosen folder. */
    private static String safeName(String n) {
        String s = n == null ? "" : n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (s.startsWith(".")) s = s.substring(1);
        return s.isEmpty() ? "download" : s;
    }

    /** name, name (1).ext, name (2).ext … so an existing file is never overwritten. */
    private static java.io.File uniqueFile(java.io.File dir, String name) {
        java.io.File f = new java.io.File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new java.io.File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new java.io.File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private void runDownload(final java.io.File dest, final Source src, final boolean extractApk) {
        if (busy) return;
        busy = true;
        showProgress(getString(R.string.downloading));
        bg(() -> {
            HttpURLConnection c = src.open();
            try {
                long total = c.getContentLengthLong();
                InputStream in = c.getInputStream();
                OutputStream out;
                try {
                    out = new java.io.FileOutputStream(dest);
                } catch (java.io.IOException openFail) {
                    in.close();
                    throw new IOException(getString(R.string.save_failed, dest.getParent()), openFail);
                }
                try {
                    GitHubApi.Progress prog = (done, tot) -> post(() -> updateBytes(done, tot));
                    if (extractApk) {
                        ZipInputStream zin = new ZipInputStream(in);
                        ZipEntry e;
                        boolean found = false;
                        while ((e = zin.getNextEntry()) != null) {
                            if (!e.isDirectory() && e.getName().toLowerCase(Locale.US).endsWith(".apk")) {
                                GitHubApi.copy(zin, out, e.getSize(), prog);
                                found = true;
                                break;
                            }
                        }
                        if (!found) throw new IOException(getString(R.string.no_apk_in_artifact));
                    } else {
                        GitHubApi.copy(in, out, total, prog);
                    }
                } catch (Exception ex) {
                    // never leave a half-written or empty file behind
                    try {
                        out.close();
                    } catch (Exception ignored) {
                    }
                    //noinspection ResultOfMethodCallIgnored
                    dest.delete();
                    throw ex;
                } finally {
                    out.close();
                    in.close();
                }
            } finally {
                c.disconnect();
            }
            post(() -> {
                hideProgress();
                if (extractApk) offerInstall(dest);
                else toast(getString(R.string.saved_to, dest.getAbsolutePath()));
            });
        });
    }

    private void offerInstall(final java.io.File apkFile) {
        new Dlg(this)
                .setTitle(R.string.saved)
                .setMessage(R.string.install_apk_msg)
                .setPositiveButton(R.string.install_now, (d, w) -> installApk(apkFile))
                .setNegativeButton(R.string.install_later, null)
                .show();
    }

    /**
     * Copies the saved APK to the app cache and installs it through a FileProvider URI. Installing
     * straight from a Storage-Access-Framework URI fails on many devices ("parse error"), and the
     * "install unknown apps" permission is checked first so the user is sent to the right screen.
     */
    private void installApk(final java.io.File saved) {
        if (!Perms.canInstall(this)) {
            new Dlg(this)
                    .setTitle(R.string.perm_install_title)
                    .setMessage(R.string.perm_install_dialog)
                    .setPositiveButton(R.string.perm_open_settings, (d, w) -> Perms.requestInstall(this))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        showProgress(getString(R.string.preparing));
        bg(() -> {
            java.io.File dir = new java.io.File(getCacheDir(), "apk");
            if (!dir.exists()) dir.mkdirs();
            java.io.File[] old = dir.listFiles();
            if (old != null) for (java.io.File o : old) o.delete();
            final java.io.File apk = new java.io.File(dir, "install.apk");
            try (InputStream in = new java.io.FileInputStream(saved);
                 OutputStream out = new java.io.FileOutputStream(apk)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            post(() -> {
                hideProgress();
                Perms.installApk(BaseRepoActivity.this, apk);
            });
        });
    }

    // ------------------------------------------------------------------ artifacts (build outputs such as the APK)

    /** Menu for one Actions artifact: save the APK itself, save the raw ZIP, or delete it. */
    protected void artifactMenu(final JSONObject art, final Runnable onChanged) {
        final long id = art.optLong("id");
        final String name = art.optString("name");
        String[] items = {getString(R.string.artifact_apk), getString(R.string.artifact_zip),
                getString(R.string.delete)};
        choose(name, items, (d, which) -> {
            if (which == 2) {
                confirm(getString(R.string.delete), getString(R.string.delete_msg, name), R.string.delete, () ->
                        bg(() -> {
                            api.deleteArtifact(owner, repo, id);
                            post(() -> {
                                if (onChanged != null) onChanged.run();
                            });
                        }));
                return;
            }
            if (art.optBoolean("expired")) {
                toast(R.string.artifact_expired);
                return;
            }
            Source src = () -> api.openDownload(api.artifactZipPath(owner, repo, id),
                    "application/vnd.github+json");
            if (which == 0) saveAs(name + ".apk", src, true);
            else saveAs(name + ".zip", src, false);
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

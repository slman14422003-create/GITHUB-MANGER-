package com.ghmanager.app;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.Locale;

/** Checks the app's own GitHub Releases for a newer APK, downloads it and starts the installer. */
public class UpdateActivity extends BaseRepoActivity {
    private LinearLayout content;
    private Updater.Info info;
    private String errorText;
    private boolean checked = false;
    private boolean autostart = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        applySource(Store.getUpdateRepo(this));
        bindHeader(getString(R.string.upd_title), owner + "/" + repo);
        content = findViewById(R.id.content);
        btnRefresh.setOnClickListener(v -> check());
        autostart = getIntent().getBooleanExtra("autostart", false);
        render();
        check();
    }

    /** Sets owner/repo from an "owner/repo" slug (BaseRepoActivity read them from intent extras). */
    private void applySource(String slug) {
        int slash = slug.indexOf('/');
        if (slash > 0 && slash < slug.length() - 1) {
            owner = slug.substring(0, slash);
            repo = slug.substring(slash + 1);
        }
    }

    private void check() {
        if (owner == null || repo == null) return;
        loading(true);
        errorText = null;
        final boolean pre = Store.updatePre(this);
        io.execute(() -> {
            try {
                final Updater.Info in = Updater.check(UpdateActivity.this, api, owner, repo, pre);
                Store.setLastUpdateCheck(UpdateActivity.this, System.currentTimeMillis());
                post(() -> {
                    loading(false);
                    info = in;
                    checked = true;
                    render();
                    if (autostart && in != null && in.newer) {
                        autostart = false;
                        download();
                    }
                });
            } catch (Exception e) {
                if (e instanceof GitHubApi.ApiException && ((GitHubApi.ApiException) e).code == 401) {
                    post(this::relogin);
                    return;
                }
                final String raw = e.getMessage() == null ? e.toString() : e.getMessage();
                final boolean notFound = e instanceof GitHubApi.ApiException
                        && ((GitHubApi.ApiException) e).code == 404;
                post(() -> {
                    loading(false);
                    checked = true;
                    info = null;
                    errorText = notFound ? getString(R.string.upd_repo_404, owner + "/" + repo)
                            : getString(R.string.upd_error, raw);
                    render();
                });
            }
        });
    }

    private void render() {
        content.removeAllViews();

        content.addView(Ui.sectionTitle(this, getString(R.string.upd_version_section)));
        addRow(new Row(R.drawable.ic_package, true, getString(R.string.upd_current),
                Updater.installedName(this) + " (" + Updater.installedCode(this) + ")", false, false), null);

        if (!checked) {
            addRow(new Row(R.drawable.ic_refresh, true, getString(R.string.upd_latest),
                    getString(R.string.upd_checking), false, false), null);
        } else if (errorText != null) {
            content.addView(Ui.noteCard(this, errorText, R.color.bad));
            Button change = Ui.block(this, Ui.button(this, R.string.upd_change_source, false));
            change.setOnClickListener(v -> editSource());
            content.addView(change);
        } else if (info == null) {
            content.addView(Ui.noteCard(this, getString(R.string.upd_none_found), R.color.text_secondary));
        } else {
            renderRelease();
        }

        content.addView(Ui.sectionTitle(this, getString(R.string.upd_settings)));
        addRow(new Row(R.drawable.ic_repo, true, getString(R.string.upd_source),
                owner + "/" + repo, false, true), v -> editSource());

        final Ui.Toggle auto = Ui.toggle(this, content, R.string.upd_auto, 0, Store.autoUpdate(this));
        auto.onChange((b, on) -> Store.setAutoUpdate(this, on));
        content.addView(auto.view);

        final Ui.Toggle pre = Ui.toggle(this, content, R.string.upd_pre, 0, Store.updatePre(this));
        pre.onChange((b, on) -> {
            Store.setUpdatePre(this, on);
            check();
        });
        content.addView(pre.view);

        content.addView(Ui.noteCard(this, getString(R.string.upd_sign_note), R.color.text_secondary));
    }

    private void renderRelease() {
        String title = info.name.isEmpty() ? info.tag : info.name;
        if (!info.name.isEmpty() && !info.name.equals(info.tag)) title = title + "  (" + info.tag + ")";

        StringBuilder sub = new StringBuilder(Fmt.size(info.assetSize));
        String ago = Fmt.ago(info.publishedAt);
        if (!ago.isEmpty()) sub.append(" · ").append(getString(R.string.upd_published, ago));
        if (info.assetName.toLowerCase(Locale.US).contains("debug")) {
            sub.append(" · ").append(getString(R.string.upd_debug_note));
        }
        Row latest = new Row(R.drawable.ic_tag, true, title, sub.toString(), false, false);
        if (info.newer) latest.badge(getString(R.string.upd_badge_new), Ui.color(this, R.color.accent_text));
        else latest.badge(getString(R.string.upd_badge_current), Ui.color(this, R.color.ok));
        addRow(latest, null);

        Button install = Ui.block(this, Ui.button(this, info.newer ? R.string.upd_download_install : R.string.upd_reinstall, info.newer));
        ((LinearLayout.LayoutParams) install.getLayoutParams()).topMargin = Ui.dp(this, 16);
        install.setOnClickListener(v -> download());
        content.addView(install);

        if (!info.htmlUrl.isEmpty()) {
            addRow(new Row(R.drawable.ic_open, false, getString(R.string.upd_view_release), null, false, true),
                    v -> openUrl(info.htmlUrl));
        }
        if (info.newer) {
            addRow(new Row(R.drawable.ic_clock, false, getString(R.string.upd_skip_version), info.tag, false, false), v -> {
                Store.setSkippedVersion(this, info.tag);
                toast(getString(R.string.upd_skipped, info.tag));
            });
        }

        String notes = Updater.cleanNotes(info.body);
        if (!notes.isEmpty()) {
            content.addView(Ui.sectionTitle(this, getString(R.string.upd_notes)));
            content.addView(Ui.noteCard(this, notes, R.color.text_primary));
        }
    }

    private void addRow(Row row, android.view.View.OnClickListener l) {
        content.addView(Ui.rowView(this, content, row, l));
    }

    private void editSource() {
        LinearLayout box = Ui.box(this);
        final EditText src = Ui.edit(this, getString(R.string.upd_source_hint), owner + "/" + repo);
        box.addView(Ui.label(this, getString(R.string.upd_source_hint)));
        box.addView(src);
        new Dlg(this)
                .setTitle(R.string.upd_source)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String v = src.getText().toString().trim();
                    int slash = v.indexOf('/');
                    if (slash <= 0 || slash >= v.length() - 1 || v.indexOf('/', slash + 1) >= 0) {
                        toast(R.string.upd_source_bad);
                        Dlg.stay(d);
                        return;
                    }
                    Store.setUpdateRepo(this, v);
                    Store.setSkippedVersion(this, "");
                    applySource(v);
                    setSubtitle(owner + "/" + repo);
                    checked = false;
                    info = null;
                    render();
                    check();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ download + install

    private static String sha256Hex(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    private void download() {
        final Updater.Info in = info;
        if (in == null) return;
        for (Transfers.Job j : Transfers.snapshot()) {
            if ("update".equals(j.tag)) {   // already downloading: do not start a second copy
                toast(R.string.tr_started_bg);
                return;
            }
        }
        final java.lang.ref.WeakReference<UpdateActivity> ref = new java.lang.ref.WeakReference<>(this);
        // The download runs in the background service: it continues if the user leaves the app and the
        // finished update is offered in a notification ("tap to install").
        Transfers.start(getApplicationContext(), getString(R.string.tr_update_title), "update",
                TransferTasks.updateDownload(api, owner, repo, in),
                (job, ok, msg) -> {
                    UpdateActivity a = ref.get();
                    boolean visible = a != null && !a.isFinishing() && !a.isDestroyed()
                            && a.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED);
                    if (ok && visible) {
                        Perms.installApk(a, TransferTasks.updateApkFile(job.app));
                    } else if (!ok) {
                        android.widget.Toast.makeText(job.app, job.app.getString(R.string.tr_failed) + ": " + msg,
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                });
        toast(R.string.tr_started_bg);
    }
}

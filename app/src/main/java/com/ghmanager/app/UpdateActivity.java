package com.ghmanager.app;

import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
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
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                post(() -> {
                    loading(false);
                    checked = true;
                    info = null;
                    errorText = getString(R.string.upd_error, msg);
                    render();
                });
            }
        });
    }

    private void render() {
        content.removeAllViews();

        content.addView(Ui.sectionTitle(this, getString(R.string.upd_current)));
        String cur = Updater.installedName(this) + " (" + Updater.installedCode(this) + ")";
        content.addView(Ui.body(this, cur, 16, R.color.text_primary));

        if (!checked) {
            content.addView(Ui.body(this, getString(R.string.upd_checking), 14, R.color.text_secondary));
        } else if (errorText != null) {
            content.addView(Ui.body(this, errorText, 14, R.color.bad));
        } else if (info == null) {
            content.addView(Ui.body(this, getString(R.string.upd_none_found), 14, R.color.text_secondary));
        } else {
            renderRelease();
        }

        content.addView(Ui.sectionTitle(this, getString(R.string.upd_settings)));
        addRow(new Row(R.drawable.ic_repo, false, getString(R.string.upd_source),
                owner + "/" + repo, false, true), v -> editSource());

        final CheckBox auto = Ui.check(this, R.string.upd_auto, Store.autoUpdate(this));
        auto.setOnCheckedChangeListener((b, on) -> Store.setAutoUpdate(this, on));
        content.addView(auto);

        final CheckBox pre = Ui.check(this, R.string.upd_pre, Store.updatePre(this));
        pre.setOnCheckedChangeListener((b, on) -> {
            Store.setUpdatePre(this, on);
            check();
        });
        content.addView(pre);

        content.addView(Ui.body(this, getString(R.string.upd_sign_note), 12, R.color.text_secondary));
    }

    private void renderRelease() {
        content.addView(Ui.sectionTitle(this, getString(R.string.upd_latest)));
        String title = info.name.isEmpty() ? info.tag : info.name;
        content.addView(Ui.body(this, title + (info.name.isEmpty() || info.name.equals(info.tag) ? "" : "  (" + info.tag + ")"),
                16, R.color.text_primary));

        String meta = Fmt.size(info.assetSize);
        String ago = Fmt.ago(info.publishedAt);
        if (!ago.isEmpty()) meta = getString(R.string.upd_file_size, meta, getString(R.string.upd_published, ago));
        content.addView(Ui.body(this, meta, 13, R.color.text_secondary));
        if (info.assetName.toLowerCase(Locale.US).contains("debug")) {
            content.addView(Ui.body(this, getString(R.string.upd_debug_note), 13, R.color.warn));
        }
        content.addView(Ui.body(this, getString(info.newer ? R.string.upd_available : R.string.upd_up_to_date),
                15, info.newer ? R.color.ok : R.color.text_secondary));

        Button install = Ui.button(this, info.newer ? R.string.upd_download_install : R.string.upd_reinstall, info.newer);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 12);
        lp.bottomMargin = Ui.dp(this, 6);
        install.setLayoutParams(lp);
        install.setOnClickListener(v -> download());
        content.addView(install);

        if (!info.htmlUrl.isEmpty()) {
            addRow(new Row(R.drawable.ic_open, false, getString(R.string.upd_view_release), null, false, false),
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
            content.addView(Ui.body(this, notes, 14, R.color.text_primary));
        }
    }

    private void addRow(Row row, android.view.View.OnClickListener l) {
        content.addView(Ui.rowView(this, content, row, l));
    }

    private void editSource() {
        LinearLayout box = Ui.box(this);
        final EditText src = Ui.edit(this, getString(R.string.upd_source_hint), owner + "/" + repo);
        box.addView(src);
        new Dlg(this)
                .setTitle(R.string.upd_source)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String v = src.getText().toString().trim();
                    int slash = v.indexOf('/');
                    if (slash <= 0 || slash >= v.length() - 1 || v.indexOf('/', slash + 1) >= 0) {
                        toast(R.string.upd_source_bad);
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
        if (in == null || busy) return;
        busy = true;
        showProgress(getString(R.string.downloading));
        bg(() -> {
            File dir = new File(getCacheDir(), "apk");
            if (!dir.exists()) dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) for (File o : old) o.delete();
            final File apk = new File(dir, "update.apk");
            HttpURLConnection c = api.openDownload(api.assetPath(owner, repo, in.assetId), "application/octet-stream");
            try {
                long total = c.getContentLengthLong();
                if (total <= 0) total = in.assetSize;
                InputStream is = c.getInputStream();
                OutputStream os = new FileOutputStream(apk);
                try {
                    GitHubApi.copy(is, os, total, (done, tot) -> post(() -> updateBytes(done, tot)));
                } finally {
                    os.close();
                    is.close();
                }
            } finally {
                c.disconnect();
            }
            if (in.assetSize > 0 && apk.length() != in.assetSize) {
                apk.delete();
                throw new java.io.IOException(getString(R.string.upd_size_bad));
            }
            if (!in.sha256.isEmpty()) {
                post(() -> {
                    if (progressText != null) progressText.setText(R.string.upd_verifying);
                });
                String got = sha256Hex(apk);
                if (!got.equalsIgnoreCase(in.sha256)) {
                    apk.delete();
                    throw new java.io.IOException(getString(R.string.upd_hash_bad));
                }
            }
            post(() -> {
                hideProgress();
                Perms.installApk(UpdateActivity.this, apk);
            });
        });
    }
}

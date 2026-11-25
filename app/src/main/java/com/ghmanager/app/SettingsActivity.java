package com.ghmanager.app;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.LinearLayout;

/** App settings: language, updates (moved here from the home screen), tools, account and About. */
public class SettingsActivity extends BaseRepoActivity {
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        if (BottomNav.fromTab(this)) BottomNav.attach(this, BottomNav.SETTINGS);
        bindHeader(getString(R.string.set_title), null);
        btnRefresh.setVisibility(android.view.View.GONE);
        content = findViewById(R.id.content);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // onCreate already drew the list; later resumes refresh it without replaying the entrance animation
        if (content != null && resumedOnce) Ui.quiet(content, this::render);
        resumedOnce = true;
    }

    private void addRow(Row row, android.view.View.OnClickListener l) {
        content.addView(Ui.rowView(this, content, row, l));
    }

    private String languageName() {
        String v = Store.language(this);
        if ("ar".equals(v)) return getString(R.string.lang_ar);
        if ("en".equals(v)) return getString(R.string.lang_en);
        return getString(R.string.lang_system);
    }

    private String themeName() {
        int t = Store.theme(this);
        if (t == Themes.LIGHT) return getString(R.string.theme_light);
        if (t == Themes.AMOLED) return getString(R.string.theme_amoled);
        if (t == Themes.SYSTEM) return getString(R.string.theme_system);
        return getString(R.string.theme_dark);
    }

    private boolean resumedOnce = false;

    private void render() {
        content.removeAllViews();

        // ---- general
        content.addView(Ui.sectionTitle(this, getString(R.string.set_general)));
        addRow(new Row(R.drawable.ic_language, true, getString(R.string.set_language), languageName(), false, true),
                v -> pickLanguage());
        addRow(new Row(R.drawable.ic_settings, true, getString(R.string.set_theme), themeName(), false, true),
                v -> pickTheme());

        // ---- updates
        content.addView(Ui.sectionTitle(this, getString(R.string.upd_title)));
        addRow(new Row(R.drawable.ic_download, true, getString(R.string.upd_check),
                getString(R.string.upd_current) + ": " + Updater.installedName(this), false, true), v -> {
            Intent i = new Intent(this, UpdateActivity.class);
            Updater.splitInto(i, Store.getUpdateRepo(this));
            startActivity(i);
        });
        final Ui.Toggle auto = Ui.toggle(this, content, R.string.upd_auto, 0, Store.autoUpdate(this));
        auto.onChange((b, on) -> Store.setAutoUpdate(this, on));
        content.addView(auto.view);

        // ---- downloads
        content.addView(Ui.sectionTitle(this, getString(R.string.dl_title)));
        final Ui.Toggle ask = Ui.toggle(this, content, R.string.dl_ask_location, R.string.dl_ask_location_sub,
                Store.askSaveLocation(this));
        ask.onChange((b, on) -> Store.setAskSaveLocation(this, on));
        content.addView(ask.view);
        addRow(new Row(R.drawable.ic_folder, true, getString(R.string.dl_open_folder),
                        TransferTasks.rootDir().getAbsolutePath(), false, true),
                v -> {
                    java.io.File d = TransferTasks.rootDir();
                    //noinspection ResultOfMethodCallIgnored
                    if (!d.exists() && Perms.hasAllFiles(this)) d.mkdirs();
                    startActivity(new Intent(this, FileManagerActivity.class).putExtra("path", d.getAbsolutePath()));
                });

        final Ui.Toggle wifi = Ui.toggle(this, content, R.string.st_wifi_only, R.string.st_wifi_only_sub,
                Store.wifiOnly(this));
        wifi.onChange((b, on) -> Store.setWifiOnly(this, on));
        content.addView(wifi.view);
        addRow(new Row(R.drawable.ic_download, true, getString(R.string.tq_title), getString(R.string.tq_row_sub),
                false, true), v -> startActivity(new Intent(this, TransfersActivity.class)));

        // ---- tools
        content.addView(Ui.sectionTitle(this, getString(R.string.app_tools)));
        addRow(new Row(R.drawable.ic_info, true, getString(R.string.nt_title), getString(R.string.nt_sub_row), false, true),
                v -> startActivity(new Intent(this, NotificationsActivity.class)));
        addRow(new Row(R.drawable.ic_folder, true, getString(R.string.fm_title), null, false, true),
                v -> startActivity(new Intent(this, FileManagerActivity.class)));
        addRow(new Row(R.drawable.ic_shield, true, getString(R.string.perm_title), null, false, true),
                v -> startActivity(new Intent(this, PermissionsActivity.class)));

        addRow(new Row(R.drawable.ic_star_fill, true, getString(R.string.pw_title), getString(R.string.pw_row_sub),
                false, true), v -> startActivity(new Intent(this, PinnedWorkflowsActivity.class)));
        addRow(new Row(R.drawable.ic_upload, true, getString(R.string.cq_title), getString(R.string.cq_row_sub),
                false, true), v -> startActivity(new Intent(this, QueueActivity.class)));
        addRow(new Row(R.drawable.ic_clipboard, true, getString(R.string.pr_title), getString(R.string.pr_sub),
                false, true), v -> startActivity(new Intent(this, PromptsActivity.class)));

        final Ui.Toggle runNotify = Ui.toggle(this, content, R.string.rw_setting, R.string.rw_setting_sub,
                Store.runNotify(this));
        runNotify.onChange((b, on) -> {
            Store.setRunNotify(this, on);
            if (on) Perms.ensureNotifications(this);
        });
        content.addView(runNotify.view);
        final Ui.Toggle syntax = Ui.toggle(this, content, R.string.syn_setting, R.string.syn_setting_sub,
                Store.syntaxOn(this));
        syntax.onChange((b, on) -> Store.setSyntaxOn(this, on));
        content.addView(syntax.view);

        // ---- security
        content.addView(Ui.sectionTitle(this, getString(R.string.sec_title)));
        final Ui.Toggle lock = Ui.toggle(this, content, R.string.lock_enable, R.string.lock_enable_sub,
                AppLock.enabled(this));
        lock.onChange((b, on) -> {
            if (on && !AppLock.available(this)) {
                toast(R.string.lock_need_screen_lock);
                lock.setChecked(false);
                return;
            }
            AppLock.setEnabled(this, on);
            render();
        });
        content.addView(lock.view);
        final Ui.Toggle verify = Ui.toggle(this, content, R.string.verify_enable, R.string.verify_enable_sub,
                Verify.enabled(this));
        verify.onChange((b, on) -> {
            if (on) {
                if (!AppLock.available(this)) {
                    toast(R.string.lock_need_screen_lock);
                    verify.setChecked(false);
                    return;
                }
                Perms.ensureNotifications(this);
                Verify.setEnabled(this, true);
                post(this::render);
            } else if (Verify.enabled(this)) {
                io.execute(() -> {
                    try {
                        if (AppLock.available(this)) Verify.requireFresh();
                        Verify.setEnabled(this, false);
                    } catch (Exception ignored) {
                    }
                    runOnUiThread(this::render);
                });
            }
        });
        content.addView(verify.view);
        if (Verify.enabled(this)) {
            for (android.view.View vr : VerifySettings.rows(this, content, this::render)) content.addView(vr);
        }
        addRow(new Row(R.drawable.ic_history, true, getString(R.string.audit_title), getString(R.string.audit_row_sub),
                false, true), v -> startActivity(new Intent(this, AuditLogActivity.class)));
        final int[] secs = {0, 30, 120, 300};
        final String[] names = {getString(R.string.lock_now), getString(R.string.lock_30s),
                getString(R.string.lock_2m), getString(R.string.lock_5m)};
        int curSecs = AppLock.delaySeconds(this);
        String curName = names[1];
        for (int i = 0; i < secs.length; i++) if (secs[i] == curSecs) curName = names[i];
        if (!AppLock.enabled(this)) curName = curName + " \u00B7 " + getString(R.string.lock_delay_off);
        addRow(new Row(R.drawable.ic_timer, true, getString(R.string.lock_delay), curName, false, true),
                v -> choose(getString(R.string.lock_delay), names, (d, which) -> {
                    AppLock.setDelaySeconds(this, secs[which]);
                    render();
                }));

        final android.view.View trashRow = Ui.rowView(this, content, new Row(R.drawable.ic_trash_sweep, true,
                getString(R.string.fm_trash), getString(R.string.fm_trash_hint), false, true),
                v -> confirm(getString(R.string.fm_empty_trash), getString(R.string.fm_empty_trash_msg),
                        R.string.delete, () -> io.execute(() -> {
                            Trash.empty(this);
                            post(this::render);
                        })));
        content.addView(trashRow);
        io.execute(() -> {
            java.util.List<Trash.Item> items = Trash.list(this);
            long bytes = 0;
            for (Trash.Item it : items) bytes += it.size;
            final String line = items.isEmpty() ? getString(R.string.fm_trash_empty)
                    : getString(R.string.st_trash_sub, items.size(), Fmt.size(bytes)) + " \u00B7 "
                    + getString(R.string.fm_trash_hint);
            post(() -> {
                android.widget.TextView t = trashRow.findViewById(R.id.sub);
                if (t != null) {
                    t.setText(line);
                    t.setVisibility(android.view.View.VISIBLE);
                }
            });
        });

        // ---- proxy mirror (for places where github.com is blocked)
        content.addView(Ui.sectionTitle(this, getString(R.string.mir_title)));
        addRow(new Row(R.drawable.ic_share, true, getString(R.string.mir_url),
                MirrorDialog.summary(this), false, true),
                v -> MirrorDialog.show(this, this::render));

        // ---- account
        content.addView(Ui.sectionTitle(this, getString(R.string.set_account)));
        Accounts.Acc cur = Accounts.active(this);
        addRow(new Row(R.drawable.ic_user, true, cur == null ? getString(R.string.acc_title) : cur.title(),
                getString(R.string.acc_manage_sub), false, true),
                v -> startActivity(new Intent(this, AccountActivity.class)));
        addRow(new Row(R.drawable.ic_add, true, getString(R.string.acc_add), null, false, true), v -> {
            Intent i = new Intent(this, LoginActivity.class);
            i.putExtra("add", true);
            startActivity(i);
        });
        if (cur != null) {
            final String id = cur.id;
            addRow(new Row(R.drawable.ic_logout, true, getString(R.string.acc_signout), null, false, false)
                            .tint(Ui.color(this, R.color.bad)),
                    v -> confirm(getString(R.string.acc_signout), getString(R.string.acc_signout_msg),
                            R.string.logout, () -> Accounts.signOut(this, id)));
        }

        // ---- about
        content.addView(Ui.sectionTitle(this, getString(R.string.set_about)));
        addRow(new Row(R.drawable.ic_info, true, getString(R.string.app_name),
                getString(R.string.set_version, Updater.installedName(this), Updater.installedCode(this)), false, false), null);
        final String slug = Store.getUpdateRepo(this);
        addRow(new Row(R.drawable.ic_repo, true, getString(R.string.set_source_code), slug, false, true),
                v -> openUrl("https://github.com/" + slug));
        addRow(new Row(R.drawable.ic_package, true, getString(R.string.set_package),
                getPackageName() + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
                false, false), v -> copy("package", getPackageName()));
        content.addView(Ui.noteCard(this, getString(R.string.set_about_text), R.color.text_secondary));
    }

    private void pickLanguage() {
        final String[] codes = {"system", "ar", "en"};
        String[] names = {getString(R.string.lang_system), getString(R.string.lang_ar), getString(R.string.lang_en)};
        choose(getString(R.string.set_language), names, (d, which) -> {
            if (codes[which].equals(Store.language(this))) return;
            Store.setLanguage(this, codes[which]);
            // recreates every open screen in the new language
            Lang.apply(this);
        });
    }

    private void pickTheme() {
        String[] names = {getString(R.string.theme_dark), getString(R.string.theme_light),
                getString(R.string.theme_amoled), getString(R.string.theme_system)};
        choose(getString(R.string.set_theme), names, (d, which) -> {
            if (which == Store.theme(this)) return;
            Store.setTheme(this, which);
            Themes.refresh(this);
        });
    }
}

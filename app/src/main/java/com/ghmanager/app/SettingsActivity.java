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
        bindHeader(getString(R.string.set_title), null);
        btnRefresh.setVisibility(android.view.View.GONE);
        content = findViewById(R.id.content);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (content != null) render();
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

    private void render() {
        content.removeAllViews();

        // ---- general
        content.addView(Ui.sectionTitle(this, getString(R.string.set_general)));
        addRow(new Row(R.drawable.ic_language, true, getString(R.string.set_language), languageName(), false, true),
                v -> pickLanguage());

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

        // ---- tools
        content.addView(Ui.sectionTitle(this, getString(R.string.app_tools)));
        addRow(new Row(R.drawable.ic_folder, true, getString(R.string.fm_title), null, false, true),
                v -> startActivity(new Intent(this, FileManagerActivity.class)));
        addRow(new Row(R.drawable.ic_shield, true, getString(R.string.perm_title), null, false, true),
                v -> startActivity(new Intent(this, PermissionsActivity.class)));

        addRow(new Row(R.drawable.ic_clipboard, true, getString(R.string.pr_title), getString(R.string.pr_sub),
                false, true), v -> startActivity(new Intent(this, PromptsActivity.class)));

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
        if (AppLock.enabled(this)) {
            final int[] secs = {0, 30, 120, 300};
            final String[] names = {getString(R.string.lock_now), getString(R.string.lock_30s),
                    getString(R.string.lock_2m), getString(R.string.lock_5m)};
            int cur = AppLock.delaySeconds(this);
            String curName = names[1];
            for (int i = 0; i < secs.length; i++) if (secs[i] == cur) curName = names[i];
            addRow(new Row(R.drawable.ic_timer, true, getString(R.string.lock_delay), curName, false, true),
                    v -> choose(getString(R.string.lock_delay), names, (d, which) -> {
                        AppLock.setDelaySeconds(this, secs[which]);
                        render();
                    }));
        }

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
}

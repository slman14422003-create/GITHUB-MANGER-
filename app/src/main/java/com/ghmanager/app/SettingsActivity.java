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

        // ---- account
        content.addView(Ui.sectionTitle(this, getString(R.string.set_account)));
        addRow(new Row(R.drawable.ic_logout, true, getString(R.string.logout), null, false, false).tint(Ui.color(this, R.color.bad)),
                v -> confirm(getString(R.string.logout), getString(R.string.set_logout_msg), R.string.logout, () -> {
                    Store.clear(this);
                    Intent i = new Intent(this, LoginActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                }));

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

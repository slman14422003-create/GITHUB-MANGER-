package com.ghmanager.app;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

/** Shows every permission the app uses, whether it is granted, and lets the user fix it in one tap. */
public class PermissionsActivity extends AppCompatActivity {
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        if (BottomNav.fromTab(this)) BottomNav.attach(this, BottomNav.PERMS);
        ((android.widget.TextView) findViewById(R.id.title)).setText(R.string.perm_title);
        ((android.widget.TextView) findViewById(R.id.subtitle)).setText(R.string.perm_subtitle);
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> render());
        content = findViewById(R.id.content);
        Ui.autoGroup(this, content);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (content == null) return;
        if (resumedOnce) Ui.quiet(content, this::render);
        else render();
        resumedOnce = true;
    }

    private boolean resumedOnce = false;

    private void render() {
        content.removeAllViews();
        content.addView(Ui.body(this, getString(R.string.perm_intro), 14, R.color.text_secondary));
        content.addView(Ui.sectionTitle(this, getString(R.string.perm_section_needed)));

        // 1) Files
        boolean files = Perms.hasAllFiles(this);
        Row r1 = new Row(R.drawable.ic_folder, true, getString(R.string.perm_files_title),
                getString(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? R.string.perm_files_sub_r : R.string.perm_files_sub),
                false, !files);
        r1.badge(getString(files ? R.string.perm_granted : R.string.perm_denied),
                Ui.color(this, files ? R.color.ok : R.color.bad));
        content.addView(Ui.rowView(this, content, r1, v -> {
            if (Perms.hasAllFiles(this)) Perms.openAppSettings(this);
            else Perms.requestAllFiles(this);
        }));

        // 2) Install APKs
        boolean install = Perms.canInstall(this);
        Row r2 = new Row(R.drawable.ic_package, true, getString(R.string.perm_install_title),
                getString(R.string.perm_install_sub), false, !install);
        r2.badge(getString(install ? R.string.perm_granted : R.string.perm_denied),
                Ui.color(this, install ? R.color.ok : R.color.bad));
        content.addView(Ui.rowView(this, content, r2, v -> {
            if (Perms.canInstall(this)) Perms.openAppSettings(this);
            else Perms.requestInstall(this);
        }));

        // 3) Internet (automatic)
        Row r3 = new Row(R.drawable.ic_open, true, getString(R.string.perm_internet_title),
                getString(R.string.perm_internet_sub), false, false);
        r3.badge(getString(R.string.perm_auto), Ui.color(this, R.color.ok));
        content.addView(Ui.rowView(this, content, r3, null));

        content.addView(Ui.sectionTitle(this, getString(R.string.perm_section_other)));
        content.addView(Ui.rowView(this, content, new Row(R.drawable.ic_settings, false,
                getString(R.string.perm_app_settings), getString(R.string.perm_app_settings_sub), false, true),
                v -> Perms.openAppSettings(this)));

        View fm = Ui.rowView(this, content, new Row(R.drawable.ic_folder, false,
                getString(R.string.fm_title), getString(R.string.fm_title_sub), false, true),
                v -> startActivity(new android.content.Intent(this, FileManagerActivity.class)));
        content.addView(fm);
    }
}

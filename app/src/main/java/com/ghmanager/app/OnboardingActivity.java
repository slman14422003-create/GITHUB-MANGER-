package com.ghmanager.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class OnboardingActivity extends AppCompatActivity {
    private static final int PAGES = 3;
    private int page = 0;
    private LinearLayout body;
    private LinearLayout dots;
    private Button next;
    private Button back;
    private OnBackPressedCallback backCallback;

    static boolean done(Context c) {
        return c.getSharedPreferences("gh", Context.MODE_PRIVATE).getBoolean("onboard_done", false);
    }

    @Override
    protected void onCreate(Bundle state) {
        Themes.apply(this);
        super.onCreate(state);
        if (state != null) page = state.getInt("page", 0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(16), dp(24), dp(16));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, dp(12), 0, dp(12));
        root.addView(dots);

        LinearLayout bar = new LinearLayout(this);
        back = Ui.button(this, R.string.ob_back, false);
        next = Ui.button(this, R.string.ob_next, true);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -2, 1f);
        a.setMarginEnd(dp(8));
        bar.addView(back, a);
        bar.addView(next, new LinearLayout.LayoutParams(0, -2, 2f));
        root.addView(bar);
        setContentView(root);

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                go(page - 1);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);
        back.setOnClickListener(v -> go(page - 1));
        next.setOnClickListener(v -> {
            if (page < PAGES - 1) go(page + 1);
            else finishSetup();
        });
        render();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        render();
    }

    private void go(int p) {
        page = Math.max(0, Math.min(PAGES - 1, p));
        render();
    }

    private void finishSetup() {
        getSharedPreferences("gh", Context.MODE_PRIVATE).edit().putBoolean("onboard_done", true).apply();
        startActivity(new Intent(this, LoginActivity.class));
        finish();
    }

    private void render() {
        body.removeAllViews();
        if (page == 0) welcome();
        else if (page == 1) appearance();
        else permissions();
        dots.removeAllViews();
        for (int i = 0; i < PAGES; i++) {
            View d = new View(this);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(dp(4));
            g.setColor(Ui.color(this, i == page ? R.color.accent : R.color.text_secondary));
            d.setBackground(g);
            d.setAlpha(i == page ? 1f : 0.35f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(i == page ? 22 : 8), dp(8));
            lp.setMargins(dp(4), 0, dp(4), 0);
            dots.addView(d, lp);
        }
        back.setVisibility(page == 0 ? View.INVISIBLE : View.VISIBLE);
        next.setText(page == PAGES - 1 ? R.string.ob_start : R.string.ob_next);
        backCallback.setEnabled(page > 0);
    }

    private void welcome() {
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_mark);
        body.addView(mark, new LinearLayout.LayoutParams(dp(88), dp(88)));
        heading(R.string.ob_welcome, R.string.ob_welcome_sub);
        item(R.string.ob_f_repos, R.string.ob_f_repos_sub);
        item(R.string.ob_f_actions, R.string.ob_f_actions_sub);
        item(R.string.ob_f_safe, R.string.ob_f_safe_sub);
    }

    private void appearance() {
        heading(R.string.ob_look, R.string.ob_look_sub);
        section(R.string.ob_language);
        final String[] codes = {"system", "ar", "en"};
        int[] names = {R.string.theme_system, R.string.ob_lang_ar, R.string.ob_lang_en};
        for (int i = 0; i < codes.length; i++) {
            final String code = codes[i];
            option(names[i], code.equals(Store.language(this)), () -> {
                Store.setLanguage(this, code);
                Lang.apply(this);
                render();
            });
        }
        section(R.string.ob_theme);
        final int[] modes = {Themes.SYSTEM, Themes.DARK, Themes.LIGHT, Themes.AMOLED};
        int[] labels = {R.string.theme_system, R.string.ob_dark, R.string.theme_light, R.string.theme_amoled};
        for (int i = 0; i < modes.length; i++) {
            final int mode = modes[i];
            option(labels[i], mode == Store.theme(this), () -> {
                Store.setTheme(this, mode);
                recreate();
            });
        }
    }

    private void permissions() {
        heading(R.string.ob_perms, R.string.ob_perms_sub);
        if (Build.VERSION.SDK_INT >= 33) {
            boolean ok = ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
            permission(R.string.ob_notif, R.string.ob_notif_sub, ok, () -> {
                android.content.SharedPreferences sp = getSharedPreferences("gh", Context.MODE_PRIVATE);
                if (sp.getBoolean("upd_notif_asked", false)
                        && !ActivityCompat.shouldShowRequestPermissionRationale(this,
                        android.Manifest.permission.POST_NOTIFICATIONS)) {
                    Perms.openAppSettings(this);
                } else {
                    sp.edit().putBoolean("upd_notif_asked", true).apply();
                    ActivityCompat.requestPermissions(this,
                            new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, Perms.REQ_NOTIF);
                }
            });
        }
        permission(R.string.ob_files, R.string.ob_files_sub, Perms.hasAllFiles(this),
                () -> Perms.requestAllFiles(this));
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }

    private TextView text(int res, int size, int color) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextSize(size);
        t.setTextColor(Ui.color(this, color));
        return t;
    }

    private void heading(int title, int sub) {
        TextView h = text(title, 26, R.color.text_primary);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setGravity(Gravity.CENTER);
        h.setPadding(0, dp(16), 0, dp(8));
        body.addView(h);
        TextView s = text(sub, 15, R.color.text_secondary);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, 0, 0, dp(16));
        body.addView(s);
    }

    private void section(int title) {
        TextView t = text(title, 13, R.color.text_secondary);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setPadding(dp(4), dp(10), dp(4), dp(4));
        body.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(5), 0, dp(5));
        body.addView(c, lp);
        return c;
    }

    private LinearLayout item(int title, int sub) {
        LinearLayout c = card();
        TextView t = text(title, 16, R.color.text_primary);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        c.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView s = text(sub, 13, R.color.text_secondary);
        s.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        c.addView(s, new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    private void option(int label, boolean selected, Runnable pick) {
        Button b = Ui.button(this, label, selected);
        b.setSelected(selected);
        b.setOnClickListener(v -> pick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(3), 0, dp(3));
        body.addView(b, lp);
    }

    private void permission(int title, int sub, boolean granted, Runnable ask) {
        LinearLayout c = item(title, sub);
        Button b = Ui.button(this, granted ? R.string.ob_granted : R.string.ob_allow, !granted);
        b.setEnabled(!granted);
        b.setOnClickListener(v -> ask.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        c.addView(b, lp);
    }
}

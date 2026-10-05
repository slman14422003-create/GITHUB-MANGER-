package com.ghmanager.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Bottom navigation shared by the four main screens (repositories, files, permissions, settings), so
 * every area of the app is one tap away from anywhere and the current place is always visible.
 * attach() wraps whatever the activity already shows; nothing in the screen's own layout changes.
 */
public final class BottomNav {
    private BottomNav() {
    }

    public static final int REPOS = 0;
    public static final int FILES = 1;
    public static final int PERMS = 2;
    public static final int SETTINGS = 3;

    private static final int[] LABELS = {R.string.repos, R.string.fm_title, R.string.perm_title, R.string.set_title};
    private static final int[] ICONS = {R.drawable.ic_repo, R.drawable.ic_folder, R.drawable.ic_shield, R.drawable.ic_settings};
    private static final Class<?>[] SCREENS = {ReposActivity.class, FileManagerActivity.class,
            PermissionsActivity.class, SettingsActivity.class};

    /** Puts the bar under the activity's content. Call right after setContentView. */
    public static void attach(final Activity a, final int selected) {
        final FrameLayout content = a.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0 || content.getTag(R.id.tag_nav) != null) return;
        content.setTag(R.id.tag_nav, Boolean.TRUE);

        View root = content.getChildAt(0);
        content.removeView(root);
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(root, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        final View bar = build(a, selected);
        wrap.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(wrap, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // the bar steps aside while the keyboard is open (it would ride on top of the keyboard otherwise)
        bar.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            WindowInsetsCompat ri = ViewCompat.getRootWindowInsets(bar);
            boolean ime = ri != null && ri.isVisible(WindowInsetsCompat.Type.ime());
            int want = ime ? View.GONE : View.VISIBLE;
            if (bar.getVisibility() != want) bar.setVisibility(want);
        });
    }

    private static View build(final Activity a, final int selected) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundResource(R.drawable.bg_bottom_bar);
        bar.setPadding(Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6));
        bar.setBaselineAligned(false);
        for (int i = 0; i < LABELS.length; i++) {
            final int idx = i;
            bar.addView(item(a, i, i == selected), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        for (int i = 0; i < bar.getChildCount(); i++) {
            final int idx = i;
            bar.getChildAt(i).setOnClickListener(v -> go(a, selected, idx));
        }
        return bar;
    }

    private static View item(Context c, int idx, boolean on) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClickable(true);
        col.setFocusable(true);
        col.setMinimumHeight(Ui.dp(c, 56));
        col.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        col.setContentDescription(c.getString(LABELS[idx]));

        // the icon sits on a pill that is filled for the current screen
        FrameLayout pill = new FrameLayout(c);
        pill.setBackgroundResource(on ? R.drawable.bg_pill_accent : 0);
        ImageView icon = new ImageView(c);
        icon.setImageResource(ICONS[idx]);
        icon.setColorFilter(Ui.color(c, on ? R.color.accent_text : R.color.text_secondary));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        pill.addView(icon, new FrameLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24), Gravity.CENTER));
        col.addView(pill, new LinearLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 30)));

        TextView t = new TextView(c);
        t.setText(LABELS[idx]);
        t.setTextSize(11);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t.setTextColor(Ui.color(c, on ? R.color.text_primary : R.color.text_secondary));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(c, 2);
        col.addView(t, tl);
        Ui.press(c, col);
        return col;
    }

    private static void go(Activity a, int current, int target) {
        if (target == current) return;
        Intent i = new Intent(a, SCREENS[target]);
        if (target == REPOS) {
            // back to the home screen: everything opened on top of it is closed
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        } else {
            i.putExtra("tab", true);
        }
        a.startActivity(i);
        a.overridePendingTransition(R.anim.fade_in_fast, R.anim.fade_out_fast);
        // tabs replace each other, so Back always returns to the repositories
        if (current != REPOS && target != REPOS) a.finish();
    }

    /** True when this screen was opened from the bottom bar (and so should show it). */
    public static boolean fromTab(Activity a) {
        return a.getIntent() != null && a.getIntent().getBooleanExtra("tab", false);
    }
}

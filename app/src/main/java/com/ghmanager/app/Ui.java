package com.ghmanager.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import androidx.core.content.ContextCompat;

public final class Ui {
    private Ui() {
    }

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    public static EditText edit(Context c, CharSequence hint, CharSequence text) {
        EditText e = new EditText(c);
        e.setHint(hint);
        if (text != null) e.setText(text);
        e.setBackgroundResource(R.drawable.bg_input);
        e.setTextColor(ContextCompat.getColor(c, R.color.text_primary));
        e.setHintTextColor(ContextCompat.getColor(c, R.color.text_hint));
        e.setTextSize(15);
        int p = dp(c, 14);
        e.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 10);
        e.setLayoutParams(lp);
        return e;
    }

    public static CheckBox check(Context c, int textRes, boolean checked) {
        CheckBox cb = new CheckBox(c);
        cb.setText(textRes);
        cb.setChecked(checked);
        cb.setTextColor(ContextCompat.getColor(c, R.color.text_primary));
        return cb;
    }

    public static LinearLayout box(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 22), dp(c, 12), dp(c, 22), 0);
        return box;
    }

    public static void tint(Context c, ProgressBar bar) {
        ColorStateList accent = ColorStateList.valueOf(ContextCompat.getColor(c, R.color.accent));
        bar.setProgressTintList(accent);
        bar.setIndeterminateTintList(accent);
    }
}

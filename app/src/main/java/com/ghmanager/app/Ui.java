package com.ghmanager.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.List;

public final class Ui {
    private Ui() {
    }

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    public static int color(Context c, int res) {
        return ContextCompat.getColor(c, res);
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

    /** Multi-line input (notes, comments, descriptions). */
    public static EditText editMulti(Context c, CharSequence hint, CharSequence text, int minLines) {
        EditText e = edit(c, hint, text);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setMinLines(minLines);
        e.setMaxLines(10);
        e.setGravity(Gravity.TOP | Gravity.START);
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

    public static TextView label(Context c, CharSequence text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(ContextCompat.getColor(c, R.color.text_secondary));
        t.setTextSize(13);
        t.setPadding(0, dp(c, 4), 0, dp(c, 4));
        return t;
    }

    public static Spinner spinner(Context c, List<String> items, int selected) {
        Spinner sp = new Spinner(c);
        ArrayAdapter<String> a = new ArrayAdapter<>(c, R.layout.spinner_item, items);
        a.setDropDownViewResource(R.layout.spinner_dropdown_item);
        sp.setAdapter(a);
        if (selected >= 0 && selected < items.size()) sp.setSelection(selected);
        sp.setBackgroundResource(R.drawable.bg_input);
        int p = dp(c, 12);
        sp.setPadding(p, p / 2, p, p / 2);
        sp.setPopupBackgroundResource(R.drawable.bg_popup);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 52));
        lp.bottomMargin = dp(c, 10);
        sp.setLayoutParams(lp);
        return sp;
    }

    /** A small selectable filter chip. */
    public static TextView chip(Context c, CharSequence text, boolean selected) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13);
        t.setSingleLine(true);
        int ph = dp(c, 14);
        int pv = dp(c, 7);
        t.setPadding(ph, pv, ph, pv);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(c, 8));
        t.setLayoutParams(lp);
        setChip(c, t, selected);
        return t;
    }

    public static void setChip(Context c, TextView t, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 18));
        g.setStroke(dp(c, 1), color(c, selected ? R.color.accent : R.color.stroke));
        g.setColor(color(c, selected ? R.color.accent_soft : R.color.bg));
        t.setBackground(g);
        t.setTextColor(color(c, selected ? R.color.accent : R.color.text_secondary));
    }

    public static TextView sectionTitle(Context c, CharSequence text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color(c, R.color.text_secondary));
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(c, 22), dp(c, 18), dp(c, 22), dp(c, 6));
        return t;
    }

    /** Body text block with horizontal page padding. */
    public static TextView body(Context c, CharSequence text, int sizeSp, int colorRes) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color(c, colorRes));
        t.setTextSize(sizeSp);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(dp(c, 22), dp(c, 6), dp(c, 22), dp(c, 6));
        t.setTextIsSelectable(true);
        return t;
    }

    public static Button button(Context c, int textRes, boolean primary) {
        Button b = new Button(c);
        b.setText(textRes);
        b.setAllCaps(false);
        b.setBackgroundResource(primary ? R.drawable.btn_primary : R.drawable.btn_secondary);
        b.setTextColor(color(c, primary ? R.color.on_accent : R.color.text_primary));
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(c, 46));
        b.setStateListAnimator(null);
        return b;
    }

    /** A horizontal progress bar made of two weighted views (0..100). */
    public static View bar(Context c, double percent, int colorRes) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 6));
        lp.setMargins(dp(c, 22), dp(c, 2), dp(c, 22), dp(c, 8));
        l.setLayoutParams(lp);
        float pct = (float) Math.max(1, Math.min(100, percent));
        View fill = new View(c);
        GradientDrawable g1 = new GradientDrawable();
        g1.setCornerRadius(dp(c, 3));
        g1.setColor(color(c, colorRes));
        fill.setBackground(g1);
        fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct));
        View rest = new View(c);
        GradientDrawable g2 = new GradientDrawable();
        g2.setCornerRadius(dp(c, 3));
        g2.setColor(color(c, R.color.neutral_soft));
        rest.setBackground(g2);
        rest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100f - pct));
        l.addView(fill);
        l.addView(rest);
        return l;
    }

    /** Inflates a row and binds it. */
    public static View rowView(Context c, ViewGroup parent, Row row, View.OnClickListener click) {
        View v = LayoutInflater.from(c).inflate(R.layout.item_row, parent, false);
        RowAdapter.bind(c, v, row);
        if (click != null) v.setOnClickListener(click);
        return v;
    }
}

package com.ghmanager.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

public final class Ui {
    private Ui() {
    }

    /** Gives a view a soft "press in, spring back" scale animation. */
    public static void press(Context c, View v) {
        if (v == null) return;
        v.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(c, R.animator.press_scale));
    }

    /** Fades and slides a freshly shown item up into place; index staggers neighbouring items. */
    public static void enter(View v, int index) {
        if (v == null) return;
        v.animate().cancel();
        // the system "remove animations" setting is honoured: show the item at once
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            v.setAlpha(1f);
            v.setTranslationY(0f);
            return;
        }
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 14));
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(Math.min(Math.max(index, 0), 9) * 28L)
                .setDuration(300)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .start();
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
        e.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        e.setMinHeight(dp(c, 52));
        int p = dp(c, 14);
        e.setPaddingRelative(dp(c, 18), p, dp(c, 18), p);
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
        cb.setTextSize(15);
        cb.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        cb.setButtonTintList(ContextCompat.getColorStateList(c, R.color.check_tint));
        cb.setMinHeight(dp(c, 48));
        cb.setPaddingRelative(dp(c, 10), 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 2);
        cb.setLayoutParams(lp);
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
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setPaddingRelative(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 4));
        return t;
    }

    public static Spinner spinner(Context c, List<String> items, int selected) {
        Spinner sp = new Spinner(c);
        ArrayAdapter<String> a = new ArrayAdapter<>(c, R.layout.spinner_item, items);
        a.setDropDownViewResource(R.layout.spinner_dropdown_item);
        sp.setAdapter(a);
        if (selected >= 0 && selected < items.size()) sp.setSelection(selected);
        sp.setBackgroundResource(R.drawable.bg_spinner);
        sp.setPaddingRelative(dp(c, 16), dp(c, 6), dp(c, 44), dp(c, 6));
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
        press(c, t);
        return t;
    }

    public static void setChip(Context c, TextView t, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 100));
        g.setStroke(dp(c, 1), color(c, selected ? R.color.accent : R.color.stroke));
        g.setColor(color(c, selected ? R.color.accent_soft : R.color.surface));
        t.setBackground(g);
        t.setTextColor(color(c, selected ? R.color.accent_text : R.color.text_secondary));
    }

    public static TextView sectionTitle(Context c, CharSequence text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color(c, R.color.text_secondary));
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setPaddingRelative(dp(c, 26), dp(c, 22), dp(c, 26), dp(c, 8));
        return t;
    }

    /** Body text block with horizontal page padding. */
    public static TextView body(Context c, CharSequence text, int sizeSp, int colorRes) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color(c, colorRes));
        t.setTextSize(sizeSp);
        t.setLineSpacing(0, 1.15f);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setPaddingRelative(dp(c, 26), dp(c, 6), dp(c, 26), dp(c, 6));
        t.setTextIsSelectable(true);
        return t;
    }

    public static Button button(Context c, int textRes, boolean primary) {
        Button b = new Button(c);
        b.setText(textRes);
        b.setAllCaps(false);
        b.setBackgroundResource(primary ? R.drawable.btn_primary : R.drawable.btn_secondary);
        b.setTextColor(color(c, primary ? R.color.on_accent : R.color.text_primary));
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setMinHeight(dp(c, 50));
        press(c, b);
        return b;
    }

    /** A horizontal progress bar made of two weighted views (0..100). */
    public static View bar(Context c, double percent, int colorRes) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 6));
        lp.setMargins(dp(c, 26), dp(c, 2), dp(c, 26), dp(c, 8));
        l.setLayoutParams(lp);
        float pct = (float) Math.max(1, Math.min(100, percent));
        View fill = new View(c);
        GradientDrawable g1 = new GradientDrawable();
        g1.setCornerRadius(dp(c, 6));
        g1.setColor(color(c, colorRes));
        fill.setBackground(g1);
        fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct));
        View rest = new View(c);
        GradientDrawable g2 = new GradientDrawable();
        g2.setCornerRadius(dp(c, 6));
        g2.setColor(color(c, R.color.neutral_soft));
        rest.setBackground(g2);
        rest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 100f - pct));
        l.addView(fill);
        l.addView(rest);
        return l;
    }

    /** Shapes a row card so rows of one section read as a single rounded group (ChatGPT style). */
    public static void shapeRow(Context c, View v, boolean first, boolean last) {
        shapeRow(c, v, first, last, R.color.surface);
    }

    /** Same as above with a custom fill (used to highlight selected rows). */
    public static void shapeRow(Context c, View v, boolean first, boolean last, int fillRes) {
        View card = v.findViewById(R.id.card);
        if (card == null) return;
        float big = dp(c, 24);
        float small = dp(c, 6);
        float top = first ? big : small;
        float bottom = last ? big : small;
        float[] radii = {top, top, top, top, bottom, bottom, bottom, bottom};
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(color(c, fillRes));
        fill.setCornerRadii(radii);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadii(radii);
        card.setBackground(new RippleDrawable(
                ColorStateList.valueOf(color(c, R.color.ripple)), fill, mask));
    }

    /** Groups every run of consecutive row cards inside a container. */
    public static void group(Context c, ViewGroup g) {
        int n = g.getChildCount();
        for (int i = 0; i < n; i++) {
            View v = g.getChildAt(i);
            if (v.findViewById(R.id.card) == null) continue;
            boolean first = i == 0 || g.getChildAt(i - 1).findViewById(R.id.card) == null;
            boolean last = i == n - 1 || g.getChildAt(i + 1).findViewById(R.id.card) == null;
            shapeRow(c, v, first, last);
        }
    }

    /** Keeps row groups shaped automatically while a screen adds or removes rows. */
    public static void autoGroup(final Context c, final ViewGroup g) {
        final Runnable r = () -> group(c, g);
        g.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                int idx = g.indexOfChild(child);
                if (idx >= 0 && idx < 14 && child.getVisibility() == View.VISIBLE && g.getTag(R.id.tag_quiet) == null) enter(child, idx);
                g.removeCallbacks(r);
                g.post(r);
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
                g.removeCallbacks(r);
                g.post(r);
            }
        });
    }

    /** Rebuilds a group's children without replaying the "slide in" animation (returning to a screen, refreshes). */
    public static void quiet(ViewGroup g, Runnable rebuild) {
        g.setTag(R.id.tag_quiet, Boolean.TRUE);
        try {
            rebuild.run();
        } finally {
            g.setTag(R.id.tag_quiet, null);
        }
    }

    /** Inflates a row and binds it. */
    public static View rowView(Context c, ViewGroup parent, Row row, View.OnClickListener click) {
        View v = LayoutInflater.from(c).inflate(R.layout.item_row, parent, false);
        RowAdapter.bind(c, v, row);
        if (click != null) v.setOnClickListener(click);
        return v;
    }

    // ------------------------------------------------------------------ settings building blocks

    /** Gives a view full-width layout params with the page margins used by every row card. */
    public static <T extends View> T block(Context c, T v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 6));
        v.setLayoutParams(lp);
        return v;
    }

    /** Rounded surface that holds a label + input (or spinner) so form fields sit on a card. */
    public static LinearLayout formCard(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundResource(R.drawable.bg_card);
        l.setPaddingRelative(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 14), dp(c, 2), dp(c, 14), dp(c, 6));
        l.setLayoutParams(lp);
        return l;
    }

    /** A rounded note / status card with wrapped text (release notes, hints, errors). */
    public static TextView noteCard(Context c, CharSequence text, int colorRes) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color(c, colorRes));
        t.setTextSize(14);
        t.setLineSpacing(0, 1.2f);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setBackgroundResource(R.drawable.bg_note);
        t.setPaddingRelative(dp(c, 18), dp(c, 14), dp(c, 18), dp(c, 14));
        t.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 14), dp(c, 4), dp(c, 14), dp(c, 6));
        t.setLayoutParams(lp);
        return t;
    }

    /** A settings row with a title, optional description and an on/off switch. */
    public static final class Toggle {
        public final View view;
        private final SwitchCompat sw;

        Toggle(View view, SwitchCompat sw) {
            this.view = view;
            this.sw = sw;
        }

        public boolean isChecked() {
            return sw.isChecked();
        }

        public void setChecked(boolean on) {
            sw.setChecked(on);
        }

        public void onChange(CompoundButton.OnCheckedChangeListener l) {
            sw.setOnCheckedChangeListener(l);
        }
    }

    /** Inflates a switch row. Add {@code result.view} to a container; consecutive rows group into one card. */
    public static Toggle toggle(Context c, ViewGroup parent, int titleRes, int subRes, boolean checked) {
        View v = LayoutInflater.from(c).inflate(R.layout.item_toggle, parent, false);
        ((TextView) v.findViewById(R.id.title)).setText(titleRes);
        TextView sub = v.findViewById(R.id.sub);
        if (subRes != 0) {
            sub.setText(subRes);
            sub.setVisibility(View.VISIBLE);
        }
        final SwitchCompat sw = v.findViewById(R.id.sw);
        // set in code: SwitchCompat's thumb/track attributes are not reliably exposed to XML
        sw.setThumbDrawable(ContextCompat.getDrawable(c, R.drawable.switch_thumb));
        sw.setTrackDrawable(ContextCompat.getDrawable(c, R.drawable.switch_track));
        sw.setShowText(false);
        sw.setChecked(checked);
        v.setOnClickListener(x -> sw.toggle());
        return new Toggle(v, sw);
    }
}

package com.ghmanager.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

/**
 * Dialog builder used everywhere in the app. It behaves like AlertDialog.Builder but styles the
 * dialog to match the app (rounded surface, pill buttons, bold title) once it is shown.
 */
public class Dlg extends AlertDialog.Builder {
    public Dlg(Context context) {
        super(context);
    }

    @Override
    public AlertDialog create() {
        final AlertDialog d = super.create();
        d.setOnShowListener(dialog -> style(d));
        return d;
    }

    /** A centered result dialog with a big status icon (success or error). */
    public static AlertDialog result(Context c, boolean ok, CharSequence title, CharSequence message) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(c, 24), Ui.dp(c, 28), Ui.dp(c, 24), Ui.dp(c, 4));

        int col = Ui.color(c, ok ? R.color.ok : R.color.bad);
        ImageView icon = new ImageView(c);
        icon.setImageResource(ok ? R.drawable.ic_check_circle : R.drawable.ic_cancel);
        icon.setImageTintList(ColorStateList.valueOf(col));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int p = Ui.dp(c, 18);
        icon.setPadding(p, p, p, p);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor((col & 0x00FFFFFF) | 0x26000000);
        icon.setBackground(circle);
        box.addView(icon, new LinearLayout.LayoutParams(Ui.dp(c, 76), Ui.dp(c, 76)));

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(Ui.color(c, R.color.text_primary));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(c, 16);
        box.addView(t, tl);

        TextView m = new TextView(c);
        m.setText(message);
        m.setTextColor(Ui.color(c, R.color.text_secondary));
        m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        m.setLineSpacing(0, 1.2f);
        m.setGravity(Gravity.CENTER);
        m.setTextIsSelectable(true);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = Ui.dp(c, 8);
        ml.bottomMargin = Ui.dp(c, 8);
        box.addView(m, ml);

        return new Dlg(c).setView(box).setPositiveButton(android.R.string.ok, null).show();
    }

    private static void style(AlertDialog d) {
        Context c = d.getContext();
        TextView title = d.findViewById(androidx.appcompat.R.id.alertTitle);
        if (title != null) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(Ui.color(c, R.color.text_primary));
        }
        TextView msg = d.findViewById(android.R.id.message);
        if (msg != null) {
            msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            msg.setTextColor(Ui.color(c, R.color.text_secondary));
            msg.setLineSpacing(0, 1.2f);
        }
        ListView lv = d.getListView();
        if (lv != null) {
            lv.setDivider(null);
            lv.setDividerHeight(0);
        }
        Button pos = d.getButton(AlertDialog.BUTTON_POSITIVE);
        Button neg = d.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neu = d.getButton(AlertDialog.BUTTON_NEUTRAL);
        boolean alone = (neg == null || neg.getVisibility() == android.view.View.GONE)
                && (neu == null || neu.getVisibility() == android.view.View.GONE);
        pill(c, pos, true, alone);
        pill(c, neg, false, false);
        pill(c, neu, false, false);
    }

    private static void pill(Context c, Button b, boolean primary, boolean fill) {
        if (b == null || b.getVisibility() == android.view.View.GONE) return;
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setMinHeight(Ui.dp(c, 44));
        b.setMinWidth(Ui.dp(c, 88));
        b.setStateListAnimator(null);
        b.setBackgroundResource(primary ? R.drawable.btn_primary : R.drawable.btn_secondary);
        b.setTextColor(Ui.color(c, primary ? R.color.on_accent : R.color.text_primary));
        ViewGroup.LayoutParams lp = b.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            m.setMargins(Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6));
            if (fill) m.width = ViewGroup.LayoutParams.MATCH_PARENT;
            b.setLayoutParams(m);
        }
    }
}

package com.ghmanager.app;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /** Option lists get roomy, rounded, start-aligned rows instead of the stock list item. */
    @Override
    public AlertDialog.Builder setItems(CharSequence[] items, DialogInterface.OnClickListener listener) {
        return setAdapter(new ChoiceAdapter(getContext(), items), listener);
    }

    private static final int[] DESTRUCTIVE = {
            R.string.delete, R.string.delete_all_caches, R.string.delete_all_completed_runs,
            R.string.delete_cancelled_runs, R.string.delete_failed_runs, R.string.delete_release,
            R.string.delete_repo, R.string.delete_run, R.string.force_cancel, R.string.cancel_run,
            R.string.col_remove, R.string.adv_prot_remove};

    /** True for labels of actions that remove or abort something (shown in red). */
    static boolean destructive(Context c, CharSequence label) {
        if (label == null) return false;
        Set<String> set = new HashSet<>();
        for (int id : DESTRUCTIVE) set.add(c.getString(id));
        return set.contains(label.toString());
    }

    private static final class ChoiceAdapter extends ArrayAdapter<CharSequence> {
        ChoiceAdapter(Context c, CharSequence[] items) {
            super(c, 0, items);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Context c = getContext();
            TextView t = convertView instanceof TextView ? (TextView) convertView : new TextView(c);
            CharSequence label = getItem(position);
            t.setText(label);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            t.setMinHeight(Ui.dp(c, 52));
            t.setPaddingRelative(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 10));
            t.setTextColor(Ui.color(c, destructive(c, label) ? R.color.bad : R.color.text_primary));
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(0xFFFFFFFF);
            mask.setCornerRadius(Ui.dp(c, 16));
            t.setBackground(new RippleDrawable(ColorStateList.valueOf(Ui.color(c, R.color.ripple)), null, mask));
            return t;
        }
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
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        TextView title = d.findViewById(androidx.appcompat.R.id.alertTitle);
        if (title != null) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(Ui.color(c, R.color.text_primary));
            title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        }
        TextView msg = d.findViewById(android.R.id.message);
        if (msg != null) {
            msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            msg.setTextColor(Ui.color(c, R.color.text_secondary));
            msg.setLineSpacing(0, 1.2f);
            msg.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        }
        ListView lv = d.getListView();
        if (lv != null) {
            lv.setDivider(null);
            lv.setDividerHeight(0);
            lv.setClipToPadding(false);
            lv.setPaddingRelative(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 12));
        }

        Button pos = d.getButton(AlertDialog.BUTTON_POSITIVE);
        Button neg = d.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neu = d.getButton(AlertDialog.BUTTON_NEUTRAL);
        List<Button> order = new ArrayList<>();
        if (shown(pos)) order.add(pos);
        if (shown(neg)) order.add(neg);
        if (shown(neu)) order.add(neu);
        if (order.isEmpty()) return;

        // Buttons are stacked full-width: the main action on top, then the secondary ones.
        ViewParent parent = order.get(0).getParent();
        if (parent instanceof LinearLayout) {
            LinearLayout bar = (LinearLayout) parent;
            bar.setOrientation(LinearLayout.VERTICAL);
            View spacer = bar.findViewById(androidx.appcompat.R.id.spacer);
            if (spacer != null) spacer.setVisibility(View.GONE);
            for (Button b : order) {
                bar.removeView(b);
                bar.addView(b);
            }
            bar.setPaddingRelative(Ui.dp(c, 18), Ui.dp(c, 6), Ui.dp(c, 18), Ui.dp(c, 16));
        }
        for (Button b : order) {
            if (b == pos) pill(c, b, destructive(c, b.getText()) ? 2 : 0);
            else pill(c, b, 1);
        }
    }

    private static boolean shown(Button b) {
        return b != null && b.getVisibility() != View.GONE;
    }

    /** kind: 0 = primary, 1 = secondary, 2 = destructive primary. */
    private static void pill(Context c, Button b, int kind) {
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setMinHeight(Ui.dp(c, 50));
        b.setMinWidth(0);
        b.setStateListAnimator(null);
        b.setBackgroundResource(kind == 1 ? R.drawable.btn_secondary
                : kind == 2 ? R.drawable.btn_danger : R.drawable.btn_primary);
        b.setTextColor(Ui.color(c, kind == 1 ? R.color.text_primary : R.color.on_accent));
        ViewGroup.LayoutParams lp = b.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams m = (LinearLayout.LayoutParams) lp;
            m.width = ViewGroup.LayoutParams.MATCH_PARENT;
            m.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            m.weight = 0;
            m.setMargins(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
            b.setLayoutParams(m);
        }
    }
}

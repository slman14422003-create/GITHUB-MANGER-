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
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
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

    /** True when the dialog shows a list of options: those open as a bottom sheet instead of a centred box. */
    private boolean hasList = false;
    private boolean forceSheet = false;

    private View customView;

    @Override
    public AlertDialog.Builder setView(View view) {
        customView = view;
        return super.setView(view);
    }

    /** Shows this dialog as a bottom sheet even though it has no option list (details, long forms). */
    public Dlg sheet() {
        forceSheet = true;
        return this;
    }

    /** Widest a centred dialog or sheet may get (tablets, landscape) so text lines stay readable. */
    private static final int MAX_WIDTH_DP = 460;
    private static final int SHEET_MAX_WIDTH_DP = 560;

    private static int windowWidth(Context c, boolean sheet) {
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int cap = Ui.dp(c, sheet ? SHEET_MAX_WIDTH_DP : MAX_WIDTH_DP);
        int want = (int) (dm.widthPixels * (sheet ? 1f : 0.94f));
        return Math.min(want, cap);
    }

    /** One place decides the size of every dialog in the app. */
    private static void size(AlertDialog d, boolean sheet) {
        Window w = d.getWindow();
        if (w == null) return;
        Context c = d.getContext();
        int width = windowWidth(c, sheet);
        w.setGravity(sheet ? (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL) : Gravity.CENTER);
        w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        // a very tall dialog never runs under the status bar: it stops at 88% of the screen and scrolls
        final View decor = w.getDecorView();
        decor.post(() -> {
            if (!d.isShowing()) return;
            int max = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.88f);
            if (decor.getHeight() > max) w.setLayout(width, max);
        });
    }

    @Override
    public AlertDialog create() {
        final AlertDialog d = super.create();
        final boolean sheet = hasList || forceSheet;
        Window w = d.getWindow();
        if (sheet && w != null) {
            w.setWindowAnimations(R.style.SheetAnim);
            w.setBackgroundDrawableResource(R.drawable.bg_sheet);
        }
        d.setOnShowListener(dialog -> {
            size(d, sheet);
            style(d, customView);
        });
        return d;
    }

    @Override
    public AlertDialog.Builder setAdapter(android.widget.ListAdapter adapter, DialogInterface.OnClickListener listener) {
        hasList = true;
        return super.setAdapter(adapter, listener);
    }

    @Override
    public AlertDialog.Builder setSingleChoiceItems(CharSequence[] items, int checked, DialogInterface.OnClickListener l) {
        hasList = true;
        return super.setSingleChoiceItems(items, checked, l);
    }

    @Override
    public AlertDialog.Builder setMultiChoiceItems(CharSequence[] items, boolean[] checked,
                                                   DialogInterface.OnMultiChoiceClickListener l) {
        hasList = true;
        return super.setMultiChoiceItems(items, checked, l);
    }

    @Override
    public AlertDialog.Builder setItems(int itemsId, DialogInterface.OnClickListener listener) {
        return setItems(getContext().getResources().getTextArray(itemsId), listener);
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
            R.string.col_remove, R.string.adv_prot_remove,
            R.string.acc_signout, R.string.acc_signout_all};

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

    private static void style(AlertDialog d, View customView) {
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
        // custom content (forms, details): same side padding everywhere, whatever the caller built
        // the dialog's own container of the custom view (found through the view, not through an id)
        ViewParent cp = customView == null ? null : customView.getParent();
        android.widget.FrameLayout custom = cp instanceof android.widget.FrameLayout ? (android.widget.FrameLayout) cp : null;
        if (custom != null && custom.getChildCount() > 0) {
            View child = custom.getChildAt(0);
            View inner = child instanceof ViewGroup && ((ViewGroup) child).getChildCount() == 1
                    && child instanceof android.widget.ScrollView ? ((ViewGroup) child).getChildAt(0) : child;
            boolean padded = inner.getPaddingStart() + inner.getPaddingEnd() > 0
                    || child.getPaddingStart() + child.getPaddingEnd() > 0;
            if (!padded) custom.setPaddingRelative(Ui.dp(c, 24), Ui.dp(c, 4), Ui.dp(c, 24), 0);
            // the keyboard opens on the first empty field, so the user can type straight away
            EditText first = firstEdit(custom);
            if (first != null && first.getText().length() == 0 && d.getWindow() != null) {
                first.requestFocus();
                d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
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

        // Two short buttons sit side by side (cancel, then the main action). Three buttons, or long
        // labels, are stacked full-width with the main action on top.
        boolean row = order.size() == 2 && pos != null && shown(pos) && !shown(neu);
        if (row) {
            for (Button b : order) if (b.getText().length() > 14) row = false;
        }
        ViewParent parent = order.get(0).getParent();
        if (parent instanceof LinearLayout) {
            LinearLayout bar = (LinearLayout) parent;
            bar.setOrientation(row ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            if (row) {
                // the stock button bar may flip itself to vertical; with weighted buttons that would
                // collapse them, so stacking is switched off when it can be (best effort)
                try {
                    bar.getClass().getMethod("setAllowStacking", boolean.class).invoke(bar, false);
                } catch (Exception ignored) {
                }
            }
            View spacer = bar.findViewById(androidx.appcompat.R.id.spacer);
            if (spacer != null) spacer.setVisibility(View.GONE);
            List<Button> placed = new ArrayList<>(order);
            if (row) {
                placed.clear();
                if (shown(neg)) placed.add(neg);
                placed.add(pos);
            }
            for (Button b : placed) {
                bar.removeView(b);
                bar.addView(b);
            }
            bar.setPaddingRelative(Ui.dp(c, 18), Ui.dp(c, 6), Ui.dp(c, 18), Ui.dp(c, 16));
        }
        for (Button b : order) {
            if (b == pos) pill(c, b, destructive(c, b.getText()) ? 2 : 0, row);
            else pill(c, b, 1, row);
        }
    }

    private static EditText firstEdit(View v) {
        if (v instanceof EditText) return (EditText) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText e = firstEdit(g.getChildAt(i));
                if (e != null) return e;
            }
        }
        return null;
    }

    private static boolean shown(Button b) {
        return b != null && b.getVisibility() != View.GONE;
    }

    /** kind: 0 = primary, 1 = secondary, 2 = destructive primary. */
    private static void pill(Context c, Button b, int kind, boolean inRow) {
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setMinHeight(Ui.dp(c, 50));
        b.setMinWidth(0);
        b.setStateListAnimator(null);
        b.setBackgroundResource(kind == 1 ? R.drawable.btn_secondary
                : kind == 2 ? R.drawable.btn_danger : R.drawable.btn_primary);
        // explicit colour for every state: the label must stay readable on the pill whatever the vendor skin does
        int txt = Ui.color(c, kind == 1 ? R.color.text_primary : R.color.on_accent);
        b.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{(txt & 0x00FFFFFF) | 0x66000000, txt}));
        b.setShadowLayer(0, 0, 0, 0);
        if (android.os.Build.VERSION.SDK_INT >= 29) b.setForceDarkAllowed(false);
        ViewGroup.LayoutParams lp = b.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams m = (LinearLayout.LayoutParams) lp;
            m.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            if (inRow) {
                m.width = 0;
                m.weight = 1;
                m.setMargins(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
            } else {
                m.width = ViewGroup.LayoutParams.MATCH_PARENT;
                m.weight = 0;
                m.setMargins(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
            }
            b.setLayoutParams(m);
        }
    }
}

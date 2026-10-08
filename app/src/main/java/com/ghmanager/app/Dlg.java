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
import android.widget.CheckBox;
import android.widget.ListAdapter;
import android.widget.ScrollView;
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
 * Dialog builder used everywhere in the app. It keeps the AlertDialog.Builder API, but the dialog
 * is drawn entirely by this class: title, scrolling body and the action buttons are plain views
 * inside one container. The platform button bar is never used, so buttons cannot lose their
 * style, overlap the content or disappear on vendor skins; long content scrolls while the title
 * and the buttons stay visible.
 */
public class Dlg extends AlertDialog.Builder {
    public Dlg(Context context) {
        super(context);
    }

    private CharSequence title, message;
    private View customView;
    private CharSequence posText, negText, neuText;
    private DialogInterface.OnClickListener posL, negL, neuL;
    private ListAdapter listAdapter;
    private DialogInterface.OnClickListener listL;
    private boolean forceSheet = false;
    private boolean dangerPositive = false;
    private int iconRes = 0, iconColorRes = 0;
    private boolean noIcon = false;

    /** Set by {@link #stay(DialogInterface)} while a button listener runs: the dialog stays open. */
    private static boolean stayOpen = false;

    /** Call from a button listener (e.g. when validation fails) so the dialog is not dismissed. */
    public static void stay(DialogInterface d) {
        stayOpen = true;
    }

    /** The positive button of a dialog built by this class (null when it has none). */
    public static Button getButton(AlertDialog d, int which) {
        if (d == null || d.getWindow() == null) return null;
        View v = d.getWindow().getDecorView().findViewWithTag("dlg_btn_" + which);
        return v instanceof Button ? (Button) v : null;
    }

    /** Shows this dialog as a bottom sheet even though it has no option list (details, long forms). */
    public Dlg sheet() {
        forceSheet = true;
        return this;
    }

    /** Draws a round tinted icon above the title (confirmations: trash, sign-out, info...). */
    public Dlg icon(int drawableRes, int colorRes) {
        iconRes = drawableRes;
        iconColorRes = colorRes;
        return this;
    }

    /** Turns off the automatic icon that destructive confirmations get. */
    public Dlg noIcon() {
        noIcon = true;
        return this;
    }

    /** The main (positive) button is drawn in red: delete / sign-out style actions. */
    public Dlg danger() {
        dangerPositive = true;
        return this;
    }

    @Override
    public AlertDialog.Builder setTitle(CharSequence t) {
        title = t;
        return this;
    }

    @Override
    public AlertDialog.Builder setTitle(int id) {
        title = getContext().getText(id);
        return this;
    }

    @Override
    public AlertDialog.Builder setMessage(CharSequence m) {
        message = m;
        return this;
    }

    @Override
    public AlertDialog.Builder setMessage(int id) {
        message = getContext().getText(id);
        return this;
    }

    @Override
    public AlertDialog.Builder setView(View view) {
        customView = view;
        return this;
    }

    @Override
    public AlertDialog.Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener l) {
        posText = text;
        posL = l;
        return this;
    }

    @Override
    public AlertDialog.Builder setPositiveButton(int id, DialogInterface.OnClickListener l) {
        return setPositiveButton(getContext().getText(id), l);
    }

    @Override
    public AlertDialog.Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener l) {
        negText = text;
        negL = l;
        return this;
    }

    @Override
    public AlertDialog.Builder setNegativeButton(int id, DialogInterface.OnClickListener l) {
        return setNegativeButton(getContext().getText(id), l);
    }

    @Override
    public AlertDialog.Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener l) {
        neuText = text;
        neuL = l;
        return this;
    }

    @Override
    public AlertDialog.Builder setNeutralButton(int id, DialogInterface.OnClickListener l) {
        return setNeutralButton(getContext().getText(id), l);
    }

    @Override
    public AlertDialog.Builder setAdapter(ListAdapter adapter, DialogInterface.OnClickListener listener) {
        listAdapter = adapter;
        listL = listener;
        return this;
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

    /** Widest a centred dialog or sheet may get (tablets, landscape) so text lines stay readable. */
    private static final int MAX_WIDTH_DP = 460;
    private static final int SHEET_MAX_WIDTH_DP = 560;

    private static int windowWidth(Context c, boolean sheet) {
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int cap = Ui.dp(c, sheet ? SHEET_MAX_WIDTH_DP : MAX_WIDTH_DP);
        int want = (int) (dm.widthPixels * (sheet ? 1f : 0.94f));
        return Math.min(want, cap);
    }

    /** A scroll view that never grows taller than a share of the screen. */
    private static final class CappedScroll extends ScrollView {
        private final int max;

        CappedScroll(Context c, int max) {
            super(c);
            this.max = max;
            setOverScrollMode(View.OVER_SCROLL_NEVER);
        }

        @Override
        protected void onMeasure(int w, int h) {
            if (View.MeasureSpec.getMode(h) != View.MeasureSpec.EXACTLY
                    && (View.MeasureSpec.getMode(h) == View.MeasureSpec.UNSPECIFIED
                    || View.MeasureSpec.getSize(h) > max)) {
                h = View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST);
            }
            super.onMeasure(w, h);
        }
    }

    private static boolean padded(View v) {
        if (v == null) return false;
        if (v.getPaddingStart() + v.getPaddingEnd() > 0) return true;
        if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() == 1) {
            View k = ((ViewGroup) v).getChildAt(0);
            return k.getPaddingStart() + k.getPaddingEnd() > 0;
        }
        return false;
    }

    private View buildContent(final Context c, final boolean sheet, final AlertDialog[] ref) {
        final int edge = sheet ? 8 : 6;
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPaddingRelative(Ui.dp(c, edge), Ui.dp(c, sheet ? 14 : 0), Ui.dp(c, edge), Ui.dp(c, edge));

        // icon header: explicit, or automatic for message dialogs whose main action is destructive
        int ic = iconRes, icCol = iconColorRes;
        if (ic == 0 && !noIcon && listAdapter == null && customView == null && posText != null
                && (dangerPositive || destructive(c, posText))) {
            ic = autoIcon(c, posText);
            icCol = R.color.bad;
        }
        if (ic != 0) {
            int col = Ui.color(c, icCol == 0 ? R.color.accent_text : icCol);
            ImageView iv = new ImageView(c);
            iv.setImageResource(ic);
            iv.setImageTintList(ColorStateList.valueOf(col));
            int pad = Ui.dp(c, 12);
            iv.setPadding(pad, pad, pad, pad);
            GradientDrawable tile = new GradientDrawable();
            tile.setShape(GradientDrawable.OVAL);
            tile.setColor((col & 0x00FFFFFF) | 0x26000000);
            iv.setBackground(tile);
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 52));
            ilp.setMarginStart(Ui.dp(c, 24));
            ilp.topMargin = Ui.dp(c, sheet ? 8 : 24);
            root.addView(iv, ilp);
        }

        if (title != null && title.length() > 0) {
            TextView t = new TextView(c);
            t.setText(title);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(Ui.color(c, R.color.text_primary));
            t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            t.setPaddingRelative(Ui.dp(c, 24), Ui.dp(c, ic != 0 ? 14 : 24), Ui.dp(c, 24), Ui.dp(c, 8));
            root.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            root.setPaddingRelative(Ui.dp(c, edge), Ui.dp(c, sheet ? 14 : 12), Ui.dp(c, edge), Ui.dp(c, edge));
        }

        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        if (listAdapter != null) {
            ListView lv = new ListView(c);
            lv.setAdapter(listAdapter);
            lv.setDivider(null);
            lv.setDividerHeight(0);
            lv.setSelector(new android.graphics.drawable.ColorDrawable(0));
            lv.setClipToPadding(false);
            lv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            lv.setPaddingRelative(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 8));
            lv.setOnItemClickListener((p, v, pos, id) -> {
                stayOpen = false;
                if (listL != null && ref[0] != null) listL.onClick(ref[0], pos);
                boolean keep = stayOpen;
                stayOpen = false;
                if (!keep && ref[0] != null) ref[0].dismiss();
            });
            root.addView(lv, bodyLp);
        } else if (customView instanceof ScrollView || customView instanceof ListView
                || (customView != null && "dlg_selfscroll".equals(customView.getTag()))) {
            if (message != null && message.length() > 0) {
                root.addView(messageView(c), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (!"dlg_selfscroll".equals(customView.getTag()) && !padded(customView)) {
                customView.setPaddingRelative(Ui.dp(c, 24), Ui.dp(c, 4), Ui.dp(c, 24), 0);
            }
            root.addView(customView, bodyLp);
        } else if (customView != null || (message != null && message.length() > 0)) {
            int cap = (int) (dm.heightPixels * (sheet ? 0.62f : 0.56f));
            CappedScroll sv = new CappedScroll(c, cap);
            sv.setFillViewport(false);
            LinearLayout inner = new LinearLayout(c);
            inner.setOrientation(LinearLayout.VERTICAL);
            if (message != null && message.length() > 0) {
                inner.addView(messageView(c), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (customView != null) {
                if (!padded(customView)) inner.setPaddingRelative(Ui.dp(c, 24), Ui.dp(c, 4), Ui.dp(c, 24), 0);
                inner.addView(customView);
            }
            sv.addView(inner, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(sv, bodyLp);
        }

        // ---- action buttons: plain views in our own row / column
        List<Object[]> btns = new ArrayList<>();   // {text, listener, which}
        if (posText != null) btns.add(new Object[]{posText, posL, AlertDialog.BUTTON_POSITIVE});
        if (neuText != null) btns.add(new Object[]{neuText, neuL, AlertDialog.BUTTON_NEUTRAL});
        if (negText != null) btns.add(new Object[]{negText, negL, AlertDialog.BUTTON_NEGATIVE});
        if (!btns.isEmpty()) {
            boolean row = btns.size() == 2;
            if (row) {
                for (Object[] b : btns) if (((CharSequence) b[0]).length() > 15) row = false;
            }
            LinearLayout bar = new LinearLayout(c);
            bar.setOrientation(row ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            bar.setPaddingRelative(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 12));
            // visual order of a row: secondary first, main action last (the platform convention)
            List<Object[]> order = new ArrayList<>(btns);
            if (row) java.util.Collections.reverse(order);
            for (Object[] b : order) {
                final int which = (Integer) b[2];
                final DialogInterface.OnClickListener l = (DialogInterface.OnClickListener) b[1];
                Button btn = new Button(c);
                btn.setText((CharSequence) b[0]);
                btn.setTag("dlg_btn_" + which);
                int kind = which == AlertDialog.BUTTON_POSITIVE
                        ? (dangerPositive || destructive(c, (CharSequence) b[0]) ? 2 : 0) : 1;
                pill(c, btn, kind);
                btn.setOnClickListener(v -> {
                    stayOpen = false;
                    if (l != null && ref[0] != null) l.onClick(ref[0], which);
                    boolean keep = stayOpen;
                    stayOpen = false;
                    if (!keep && ref[0] != null) ref[0].dismiss();
                });
                LinearLayout.LayoutParams lp;
                if (row) {
                    lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    lp.setMargins(Ui.dp(c, 4), 0, Ui.dp(c, 4), 0);
                } else {
                    lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.setMargins(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
                }
                bar.addView(btn, lp);
            }
            root.addView(bar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else if (listAdapter == null) {
            root.setPaddingRelative(root.getPaddingStart(), root.getPaddingTop(), root.getPaddingEnd(),
                    root.getPaddingBottom() + Ui.dp(c, 12));
        }
        return root;
    }

    private TextView messageView(Context c) {
        TextView msg = new TextView(c);
        msg.setText(message);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        msg.setTextColor(Ui.color(c, R.color.text_secondary));
        msg.setLineSpacing(0, 1.2f);
        msg.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        msg.setTextIsSelectable(true);
        msg.setPaddingRelative(Ui.dp(c, 24), Ui.dp(c, 4), Ui.dp(c, 24), Ui.dp(c, 8));
        return msg;
    }

    /** One place decides the size of every dialog in the app. */
    private static void size(AlertDialog d, boolean sheet) {
        Window w = d.getWindow();
        if (w == null) return;
        int width = windowWidth(d.getContext(), sheet);
        w.setGravity(sheet ? (Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL) : Gravity.CENTER);
        w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @Override
    public AlertDialog create() {
        final Context c = getContext();
        final boolean sheet = listAdapter != null || forceSheet;
        final AlertDialog[] ref = new AlertDialog[1];
        View content = buildContent(c, sheet, ref);
        super.setView(content);
        final AlertDialog d = super.create();
        ref[0] = d;
        final Window w = d.getWindow();
        if (w != null) {
            if (sheet) {
                w.setWindowAnimations(R.style.SheetAnim);
                w.setBackgroundDrawableResource(R.drawable.bg_sheet);
            }
            // the keyboard opens on the first empty field so the user can type straight away
            EditText first = firstEdit(customView);
            if (first != null && first.getText().length() == 0) {
                first.requestFocus();
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            } else {
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        }
        d.setOnShowListener(x -> size(d, sheet));
        return d;
    }

    private static final int[] DESTRUCTIVE = {
            R.string.delete, R.string.delete_all_caches, R.string.delete_all_completed_runs,
            R.string.delete_cancelled_runs, R.string.delete_failed_runs, R.string.delete_release,
            R.string.delete_repo, R.string.delete_run, R.string.force_cancel, R.string.cancel_run,
            R.string.col_remove, R.string.adv_prot_remove,
            R.string.acc_signout, R.string.acc_signout_all};

    private static int autoIcon(Context c, CharSequence label) {
        String l = label.toString();
        if (l.equals(c.getString(R.string.acc_signout)) || l.equals(c.getString(R.string.acc_signout_all))) {
            return R.drawable.ic_logout;
        }
        if (l.equals(c.getString(R.string.force_cancel)) || l.equals(c.getString(R.string.cancel_run))) {
            return R.drawable.ic_cancel;
        }
        return R.drawable.ic_delete;
    }

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

    private static EditText firstEdit(View v) {
        if (v == null) return null;
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

    /** kind: 0 = primary, 1 = secondary, 2 = destructive primary. */
    private static void pill(Context c, Button b, int kind) {
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setMinHeight(Ui.dp(c, 50));
        b.setMinimumHeight(Ui.dp(c, 50));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMaxLines(2);
        b.setGravity(Gravity.CENTER);
        b.setPaddingRelative(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8));
        b.setStateListAnimator(null);
        b.setBackgroundResource(kind == 1 ? R.drawable.btn_secondary
                : kind == 2 ? R.drawable.btn_danger : R.drawable.btn_primary);
        int txt = Ui.color(c, kind == 1 ? R.color.text_primary : R.color.on_accent);
        b.setTextColor(new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{(txt & 0x00FFFFFF) | 0x66000000, txt}));
        b.setShadowLayer(0, 0, 0, 0);
        if (android.os.Build.VERSION.SDK_INT >= 29) b.setForceDarkAllowed(false);
        Ui.press(c, b);
    }

    // ------------------------------------------------------------------ multi-select dialog

    public interface IndexCallback {
        void onChosen(List<Integer> indexes);
    }

    /**
     * Select-many dialog (delete several runs / releases / artifacts...). A header shows how many
     * are chosen and toggles "select all" / "clear"; the main button shows the count and stays
     * disabled while nothing is chosen. Tapping anywhere on a row toggles it.
     */
    public static AlertDialog multiSelect(Context c, CharSequence title, List<String> labels,
                                          int confirmTextRes, boolean danger, IndexCallback cb) {
        final int n = labels.size();
        final List<CheckBox> checks = new ArrayList<>();
        final CharSequence confirmText = c.getText(confirmTextRes);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setTag("dlg_selfscroll");

        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPaddingRelative(Ui.dp(c, 24), Ui.dp(c, 2), Ui.dp(c, 24), Ui.dp(c, 8));
        final TextView count = new TextView(c);
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        count.setTextColor(Ui.color(c, R.color.text_secondary));
        count.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        head.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView toggleAll = Ui.chip(c, c.getString(R.string.select_all), false);
        ((LinearLayout.LayoutParams) toggleAll.getLayoutParams()).setMarginEnd(0);
        head.addView(toggleAll);
        col.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPaddingRelative(Ui.dp(c, 24), Ui.dp(c, 2), Ui.dp(c, 24), Ui.dp(c, 4));
        for (String s : labels) {
            CheckBox cb2 = Ui.check(c, s, false);
            checks.add(cb2);
            box.addView(cb2);
        }
        ScrollView sv = new ScrollView(c);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.addView(box);
        col.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Dlg b = new Dlg(c);
        b.setTitle(title);
        b.setView(col);
        b.setPositiveButton(confirmText, (d, w) -> {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < checks.size(); i++) if (checks.get(i).isChecked()) out.add(i);
            if (out.isEmpty()) {
                stay(d);
                return;
            }
            if (cb != null) cb.onChosen(out);
        });
        b.setNegativeButton(R.string.cancel, null);
        if (danger) b.danger();
        final AlertDialog dialog = b.create();

        final Runnable refresh = () -> {
            int on = 0;
            for (CheckBox k : checks) if (k.isChecked()) on++;
            count.setText(c.getString(R.string.sel_count, on, n));
            boolean all = n > 0 && on == n;
            toggleAll.setText(all ? R.string.deselect_all : R.string.select_all);
            Ui.setChip(c, toggleAll, all);
            Button pos = getButton(dialog, AlertDialog.BUTTON_POSITIVE);
            if (pos != null) {
                pos.setEnabled(on > 0);
                pos.setAlpha(on > 0 ? 1f : 0.45f);
                pos.setText(on > 0 ? confirmText + " (" + on + ")" : confirmText);
            }
        };
        for (CheckBox k : checks) k.setOnCheckedChangeListener((v, isOn) -> refresh.run());
        toggleAll.setOnClickListener(v -> {
            boolean all = true;
            for (CheckBox k : checks) if (!k.isChecked()) { all = false; break; }
            for (CheckBox k : checks) k.setChecked(!all);
            refresh.run();
        });
        dialog.setOnShowListener(x -> {
            size(dialog, false);
            refresh.run();
        });
        dialog.show();
        return dialog;
    }
}

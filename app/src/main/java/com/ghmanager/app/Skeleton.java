package com.ghmanager.app;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/** Grey placeholder rows shown while a list loads, plus a soft "pulse" animation helper. */
public final class Skeleton {
    private Skeleton() {
    }

    private static final int TAG_ANIM = R.id.skeleton_anim;

    private static GradientDrawable pill(Context c, int colorRes, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(Ui.dp(c, radiusDp));
        g.setColor(Ui.color(c, colorRes));
        return g;
    }

    /** Builds a vertical stack of {@code rows} placeholder rows. */
    public static View build(Context c, int rows) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 8));
        for (int i = 0; i < rows; i++) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(pill(c, R.color.field, 14));
            row.setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.bottomMargin = Ui.dp(c, 8);
            root.addView(row, rp);

            View title = new View(c);
            title.setBackground(pill(c, R.color.neutral_soft, 6));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    Ui.dp(c, 120 + (i % 3) * 40), Ui.dp(c, 14));
            row.addView(title, tp);

            View sub = new View(c);
            sub.setBackground(pill(c, R.color.neutral_soft, 6));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    Ui.dp(c, 200 + (i % 2) * 50), Ui.dp(c, 10));
            sp.topMargin = Ui.dp(c, 10);
            row.addView(sub, sp);
        }
        return root;
    }

    private static void cancel(View v) {
        Object old = v.getTag(TAG_ANIM);
        if (old instanceof ObjectAnimator) {
            ((ObjectAnimator) old).cancel();
        } else if (old instanceof List) {
            for (Object o : (List<?>) old) if (o instanceof ObjectAnimator) ((ObjectAnimator) o).cancel();
        }
        v.setTag(TAG_ANIM, null);
        v.setAlpha(1f);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) g.getChildAt(i).setAlpha(1f);
        }
    }

    public static void pulse(View v, boolean on) {
        if (v == null) return;
        cancel(v);
        if (!on || !Motion.enabled()) return;
        long dur = Motion.slow(v.getContext()) * 3L;
        if (v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 1) {
            ViewGroup g = (ViewGroup) v;
            List<ObjectAnimator> list = new ArrayList<>();
            for (int i = 0; i < g.getChildCount(); i++) {
                ObjectAnimator a = ObjectAnimator.ofFloat(g.getChildAt(i), View.ALPHA, 1f, 0.4f);
                a.setDuration(dur);
                a.setStartDelay(i * 130L);
                a.setInterpolator(Motion.standard());
                a.setRepeatMode(ObjectAnimator.REVERSE);
                a.setRepeatCount(ObjectAnimator.INFINITE);
                a.start();
                list.add(a);
            }
            v.setTag(TAG_ANIM, list);
            return;
        }
        ObjectAnimator a = ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.45f);
        a.setDuration(dur);
        a.setInterpolator(Motion.standard());
        a.setRepeatMode(ObjectAnimator.REVERSE);
        a.setRepeatCount(ObjectAnimator.INFINITE);
        a.start();
        v.setTag(TAG_ANIM, a);
    }
}

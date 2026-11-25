package com.ghmanager.app;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

public final class Fx {
    private Fx() {
    }

    public static void reveal(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        if (!Motion.enabled()) {
            v.setAlpha(1f);
            v.setScaleX(1f);
            v.setScaleY(1f);
            return;
        }
        v.setAlpha(0f);
        v.setScaleX(0.94f);
        v.setScaleY(0.94f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(Motion.slow(v.getContext()))
                .setInterpolator(Motion.enter())
                .start();
    }

    /** Puts a view back to its resting state (no leftover offset, fade or scale from an interrupted animation). */
    public static void settle(View v) {
        if (v == null) return;
        v.setAlpha(1f);
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        v.setScaleX(1f);
        v.setScaleY(1f);
    }

    public static void slideIn(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.setTag(R.id.fx_target, Boolean.TRUE);
        v.setVisibility(View.VISIBLE);
        if (!Motion.enabled()) {
            settle(v);
            return;
        }
        v.setAlpha(0f);
        v.setTranslationY(Ui.dp(v.getContext(), 24));
        v.animate().alpha(1f).translationY(0f)
                .setDuration(Motion.slow(v.getContext()))
                .setInterpolator(Motion.enter())
                .start();
    }

    public static void slideOut(final View v, final int endVisibility) {
        if (v == null) return;
        v.animate().cancel();
        v.setTag(R.id.fx_target, Boolean.FALSE);
        if (v.getVisibility() != View.VISIBLE || !Motion.enabled()) {
            v.setVisibility(endVisibility);
            settle(v);
            return;
        }
        v.animate().alpha(0f).translationY(Ui.dp(v.getContext(), 24))
                .setDuration(Motion.base(v.getContext()))
                .setInterpolator(Motion.standard())
                .withEndAction(() -> {
                    // a newer slideIn may have taken over while this one was running: only finish if still wanted hidden
                    if (Boolean.FALSE.equals(v.getTag(R.id.fx_target))) v.setVisibility(endVisibility);
                    settle(v);
                })
                .start();
    }

    public static void show(View v, boolean on) {
        if (v == null) return;
        Object t = v.getTag(R.id.fx_target);
        // the wanted state, not the momentary visibility: an exit animation still counts as hidden
        boolean wanted = t instanceof Boolean ? (Boolean) t : v.getVisibility() == View.VISIBLE;
        if (on && (!wanted || v.getVisibility() != View.VISIBLE)) slideIn(v);
        else if (!on && wanted && v.getVisibility() == View.VISIBLE) slideOut(v, View.GONE);
    }

    public static void fade(final View v, boolean on) {
        if (v == null) return;
        boolean shown = v.getVisibility() == View.VISIBLE;
        if (on == shown) return;
        v.animate().cancel();
        if (!Motion.enabled()) {
            v.setAlpha(1f);
            v.setVisibility(on ? View.VISIBLE : View.GONE);
            return;
        }
        if (on) {
            v.setAlpha(0f);
            v.setVisibility(View.VISIBLE);
            v.animate().alpha(1f).setDuration(Motion.base(v.getContext())).start();
        } else {
            v.animate().alpha(0f).setDuration(Motion.fast(v.getContext()))
                    .withEndAction(() -> {
                        v.setVisibility(View.GONE);
                        v.setAlpha(1f);
                    }).start();
        }
    }

    public static void pop(View v) {
        if (v == null || !Motion.enabled()) return;
        v.animate().cancel();
        v.setScaleX(0.88f);
        v.setScaleY(0.88f);
        v.animate().scaleX(1f).scaleY(1f)
                .setDuration(Motion.slow(v.getContext()))
                .setInterpolator(new OvershootInterpolator(2.4f))
                .start();
    }

    public static void shake(View v) {
        if (v == null || !Motion.enabled()) return;
        int d = Ui.dp(v.getContext(), 6);
        ObjectAnimator a = ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0, d, -d, d * 0.7f, -d * 0.7f, d * 0.35f, 0);
        a.setDuration(360);
        a.start();
    }

    public static void countUp(final TextView t, int to, String suffix) {
        if (t == null) return;
        final String sfx = suffix == null ? "" : suffix;
        Object old = t.getTag(R.id.fx_anim);
        if (old instanceof ValueAnimator) ((ValueAnimator) old).cancel();
        t.setTag(R.id.fx_anim, null);
        if (!Motion.enabled() || to <= 0) {
            t.setText(to + sfx);
            return;
        }
        ValueAnimator a = ValueAnimator.ofInt(0, to);
        a.setDuration(Motion.slow(t.getContext()) * 2L);
        a.setInterpolator(Motion.enter());
        a.addUpdateListener(x -> t.setText(x.getAnimatedValue() + sfx));
        t.setTag(R.id.fx_anim, a);
        a.start();
    }

    public static void pulse(final View v, boolean on) {
        if (v == null) return;
        Object old = v.getTag(R.id.fx_loop);
        if (old instanceof Animator) ((Animator) old).cancel();
        v.setTag(R.id.fx_loop, null);
        v.setAlpha(1f);
        v.setScaleX(1f);
        v.setScaleY(1f);
        if (!on || !Motion.enabled()) return;
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.35f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.78f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.78f));
        a.setDuration(900);
        a.setInterpolator(Motion.standard());
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.start();
        v.setTag(R.id.fx_loop, a);
        if (v.getTag(R.id.fx_bound) == null) {
            v.setTag(R.id.fx_bound, Boolean.TRUE);
            v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View x) {
                }

                @Override
                public void onViewDetachedFromWindow(View x) {
                    Object o = x.getTag(R.id.fx_loop);
                    if (o instanceof Animator) ((Animator) o).cancel();
                    x.setTag(R.id.fx_loop, null);
                    x.setAlpha(1f);
                    x.setScaleX(1f);
                    x.setScaleY(1f);
                }
            });
        }
    }

    public static void bob(final View v, boolean on) {
        if (v == null) return;
        Object old = v.getTag(R.id.fx_loop);
        if (old instanceof Animator) ((Animator) old).cancel();
        v.setTag(R.id.fx_loop, null);
        v.setTranslationY(0f);
        if (!on || !Motion.enabled()) return;
        float d = Ui.dp(v.getContext(), 5);
        ObjectAnimator a = ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, -d, d);
        a.setDuration(1800);
        a.setInterpolator(Motion.standard());
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.start();
        v.setTag(R.id.fx_loop, a);
    }

    public static void stagger(ViewGroup g) {
        if (g == null) return;
        for (int i = 0; i < g.getChildCount(); i++) Ui.enter(g.getChildAt(i), i);
    }

    public static void crossfade(final View out, final View in) {
        if (in == null) return;
        if (!Motion.enabled() || out == null) {
            if (out != null) out.setVisibility(View.GONE);
            in.setAlpha(1f);
            in.setVisibility(View.VISIBLE);
            return;
        }
        in.setAlpha(0f);
        in.setVisibility(View.VISIBLE);
        in.animate().alpha(1f).setDuration(Motion.base(in.getContext())).start();
        out.animate().alpha(0f).setDuration(Motion.base(out.getContext()))
                .withEndAction(() -> {
                    out.setVisibility(View.GONE);
                    out.setAlpha(1f);
                }).start();
    }

    public static void emptyState(TextView t, int iconRes) {
        if (t == null) return;
        Context c = t.getContext();
        Drawable d = ContextCompat.getDrawable(c, iconRes);
        if (d == null) return;
        d = d.mutate();
        int s = Ui.dp(c, 56);
        d.setBounds(0, 0, s, s);
        d.setTint(Ui.color(c, R.color.text_hint));
        t.setCompoundDrawablesRelative(null, d, null, null);
        t.setCompoundDrawablePadding(Ui.dp(c, 14));
    }
}

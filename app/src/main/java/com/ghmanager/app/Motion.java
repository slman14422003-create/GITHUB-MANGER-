package com.ghmanager.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.transition.AutoTransition;
import android.transition.Transition;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * The single source of truth for animation timing. Durations live in res/values/motion.xml (the
 * XML animations use them too), so every screen, dialog, list and bar moves at the same pace.
 */
public final class Motion {
    private Motion() {
    }

    private static final Interpolator ENTER = new DecelerateInterpolator(1.6f);
    private static final Interpolator STANDARD = new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    public static int press(Context c) {
        return c.getResources().getInteger(R.integer.motion_press);
    }

    public static int fast(Context c) {
        return c.getResources().getInteger(R.integer.motion_fast);
    }

    public static int base(Context c) {
        return c.getResources().getInteger(R.integer.motion_base);
    }

    public static int slow(Context c) {
        return c.getResources().getInteger(R.integer.motion_slow);
    }

    public static int stagger(Context c) {
        return c.getResources().getInteger(R.integer.motion_stagger);
    }

    /** False when the user turned animations off in the system settings: show things at once. */
    public static boolean enabled() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    /** Things appearing: quick start, soft landing. */
    public static Interpolator enter() {
        return ENTER;
    }

    /** Things that move or resize while staying on screen. */
    public static Interpolator standard() {
        return STANDARD;
    }

    /** Layout change (expand / collapse / rows added) with the app's own pace. */
    public static Transition layout(Context c) {
        AutoTransition t = new AutoTransition();
        t.setDuration(base(c));
        t.setInterpolator(STANDARD);
        return t;
    }
}

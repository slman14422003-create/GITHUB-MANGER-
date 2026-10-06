package com.ghmanager.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * The iOS activity indicator: 12 rounded spokes, the brightest one moving clockwise in steps.
 * It runs only while it is visible and the screen is on, and stays still when the system has
 * animations turned off.
 *
 * A view of 40dp or more is treated as an overlay for a round icon button: it paints the button's own
 * disc first, so the icon underneath is hidden while the spinner turns.
 */
public class IosSpinner extends View {
    private static final int SPOKES = 12;
    private static final long STEP_MS = 83;   // about one turn per second, like iOS

    private final Paint spoke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int color;
    private int step = 0;
    private boolean running = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            step = (step + 1) % SPOKES;
            invalidate();
            postDelayed(this, STEP_MS);
        }
    };

    public IosSpinner(Context c) {
        this(c, null);
    }

    public IosSpinner(Context c, AttributeSet a) {
        this(c, a, 0);
    }

    public IosSpinner(Context c, AttributeSet a, int d) {
        super(c, a, d);
        color = Ui.color(c, R.color.text_primary);
        spoke.setStyle(Paint.Style.STROKE);
        spoke.setStrokeCap(Paint.Cap.ROUND);
        disc.setStyle(Paint.Style.FILL);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onMeasure(int w, int h) {
        int def = Ui.dp(getContext(), 24);
        setMeasuredDimension(resolveSize(def, w), resolveSize(def, h));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float size = Math.min(getWidth(), getHeight());
        boolean overlay = size >= Ui.dp(getContext(), 40);
        if (overlay) {
            disc.setColor(Ui.color(getContext(), R.color.surface));
            cv.drawCircle(cx, cy, size / 2f - 0.5f, disc);
            spoke.setStrokeWidth(Ui.dp(getContext(), 1));
        }
        float glyph = overlay ? Ui.dp(getContext(), 22) : size;
        float r = glyph / 2f;
        float inner = r * 0.46f;
        float outer = r * 0.94f;
        spoke.setStrokeWidth(r * 0.19f);
        for (int i = 0; i < SPOKES; i++) {
            int age = (step - i + SPOKES) % SPOKES;           // 0 = the head, the rest fade behind it
            int alpha = 255 - (int) (age * (255 * 0.85f) / SPOKES);
            spoke.setColor(color);
            spoke.setAlpha(alpha);
            cv.save();
            cv.rotate(i * 360f / SPOKES, cx, cy);
            cv.drawLine(cx, cy - outer, cx, cy - inner, spoke);
            cv.restore();
        }
    }

    private void update() {
        boolean should = isShown() && isAttachedToWindow() && ValueAnimator.areAnimatorsEnabled();
        if (should == running) return;
        running = should;
        removeCallbacks(tick);
        if (running) postDelayed(tick, STEP_MS);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        update();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        update();
    }
}

package com.ghmanager.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import androidx.core.graphics.ColorUtils;

public final class ShimmerView extends View {
    public static final int ACTIONS = 0;
    public static final int ROWS = 1;
    public static final int COMMITS = 2;
    public static final int ISSUES = 3;
    public static final int REPOS = 4;

    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final int kind;
    private LinearGradient gradient;
    private boolean moving = true;

    public ShimmerView(Context c) {
        this(c, ACTIONS);
    }

    public ShimmerView(Context c, int kind) {
        super(c);
        this.kind = kind;
        card.setColor(Ui.color(c, R.color.field));
        bone.setColor(Ui.color(c, R.color.neutral_soft));
        setContentDescription(c.getString(R.string.a11y_loading));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        moving = Motion.enabled();
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility == VISIBLE) moving = Motion.enabled();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        int base = Ui.color(getContext(), R.color.neutral_soft);
        int light = ColorUtils.blendARGB(base,
                Ui.color(getContext(), R.color.text_hint), 0.35f);
        gradient = new LinearGradient(0, 0, Math.max(1, w / 2), 0,
                new int[]{base, light, base}, null, Shader.TileMode.CLAMP);
        bone.setShader(gradient);
    }

    private void box(Canvas c, Paint p, float l, float t, float w, float h, float r) {
        float d = getResources().getDisplayMetrics().density;
        c.drawRoundRect(l * d, t * d, (l + w) * d, (t + h) * d, r * d, r * d, p);
    }

    private void oval(Canvas c, Paint p, float l, float t, float s) {
        float d = getResources().getDisplayMetrics().density;
        c.drawOval(l * d, t * d, (l + s) * d, (t + s) * d, p);
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth() / d;
        float h = getHeight() / d;
        if (gradient != null) {
            float f = moving ? (SystemClock.uptimeMillis() % 1300) / 1300f : 0f;
            matrix.setTranslate(-getWidth() / 2f + f * getWidth() * 1.5f, 0);
            gradient.setLocalMatrix(matrix);
            bone.setShader(gradient);
        }
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        if (rtl) {
            c.save();
            c.scale(-1f, 1f, getWidth() / 2f, 0f);
        }
        switch (kind) {
            case COMMITS:
                for (float y = 8; y < h; y += 88) commitRow(c, w, y);
                break;
            case ISSUES:
                for (float y = 8; y < h; y += 96) issueRow(c, w, y);
                break;
            case REPOS:
                for (float y = 8; y < h; y += 104) repoRow(c, w, y);
                break;
            case ROWS:
                for (int i = 0; 8 + i * 74 < h; i++) plainRow(c, w, 8 + i * 74, i);
                break;
            default:
                actions(c, w);
                break;
        }
        if (rtl) c.restore();
        if (moving && isShown()) postInvalidateOnAnimation();
    }

    private void actions(Canvas c, float w) {
        box(c, card, 14, 8, w - 28, 96, 16);
        float tile = (w - 76) / 4;
        for (int i = 0; i < 4; i++) box(c, bone, 26 + i * (tile + 8), 28, tile, 56, 12);
        for (int i = 0; i < 5; i++) {
            float y = 114 + i * 94;
            box(c, card, 14, y, w - 28, 86, 16);
            box(c, bone, 28, y + 18, 24, 24, 12);
            box(c, bone, 66, y + 18, (w - 100) * (i % 2 == 0 ? 0.85f : 0.65f), 13, 6);
            box(c, bone, 66, y + 42, (w - 100) * 0.55f, 10, 5);
            box(c, bone, 66, y + 62, 70, 12, 6);
        }
    }

    private void commitRow(Canvas c, float w, float y) {
        box(c, card, 14, y, w - 28, 80, 16);
        oval(c, bone, 28, y + 16, 22);
        box(c, bone, 62, y + 16, (w - 100) * 0.8f, 13, 6);
        box(c, bone, 62, y + 40, 58, 18, 9);
        box(c, bone, 128, y + 44, (w - 190) * 0.6f, 10, 5);
    }

    private void issueRow(Canvas c, float w, float y) {
        box(c, card, 14, y, w - 28, 88, 16);
        oval(c, bone, 28, y + 16, 22);
        box(c, bone, 62, y + 16, (w - 100) * 0.85f, 13, 6);
        box(c, bone, 62, y + 38, (w - 100) * 0.5f, 10, 5);
        box(c, bone, 62, y + 60, 54, 16, 8);
        box(c, bone, 122, y + 60, 70, 16, 8);
    }

    private void repoRow(Canvas c, float w, float y) {
        box(c, card, 14, y, w - 28, 96, 18);
        box(c, bone, 28, y + 18, 40, 40, 12);
        box(c, bone, 80, y + 18, (w - 120) * 0.5f, 14, 6);
        box(c, bone, 80, y + 42, (w - 120) * 0.8f, 10, 5);
        box(c, bone, 28, y + 70, 64, 14, 7);
        box(c, bone, 100, y + 70, 48, 14, 7);
    }

    private void plainRow(Canvas c, float w, float y, int i) {
        box(c, card, 14, y, w - 28, 66, 16);
        box(c, bone, 30, y + 18, (w - 60) * (i % 2 == 0 ? 0.6f : 0.45f), 13, 6);
        box(c, bone, 30, y + 40, (w - 60) * 0.75f, 10, 5);
    }
}

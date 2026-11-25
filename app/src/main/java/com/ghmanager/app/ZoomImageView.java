package com.ghmanager.app;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

public class ZoomImageView extends AppCompatImageView {
    private final Matrix matrix = new Matrix();
    private final float[] vals = new float[9];
    private float fitScale = 1f;
    private float lastX;
    private float lastY;
    private int lastPointer = -1;
    private final ScaleGestureDetector scaler;
    private final GestureDetector taps;

    public ZoomImageView(Context c) {
        this(c, null);
    }

    public ZoomImageView(Context c, AttributeSet a) {
        super(c, a);
        setScaleType(ScaleType.MATRIX);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float cur = currentScale();
                if (cur > fitScale * 1.2f) fit();
                else zoomBy(2.5f, e.getX(), e.getY());
                return true;
            }
        });
    }

    @Override
    public void setImageDrawable(Drawable d) {
        super.setImageDrawable(d);
        fit();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fit();
    }

    private float currentScale() {
        matrix.getValues(vals);
        return vals[Matrix.MSCALE_X];
    }

    private void fit() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || getHeight() == 0) return;
        float dw = d.getIntrinsicWidth();
        float dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        fitScale = Math.min(getWidth() / dw, getHeight() / dh);
        matrix.reset();
        matrix.postScale(fitScale, fitScale);
        matrix.postTranslate((getWidth() - dw * fitScale) / 2f, (getHeight() - dh * fitScale) / 2f);
        setImageMatrix(matrix);
    }

    private void zoomBy(float factor, float px, float py) {
        float cur = currentScale();
        float target = Math.max(fitScale, Math.min(fitScale * 8f, cur * factor));
        float f = target / cur;
        matrix.postScale(f, f, px, py);
        clamp();
        setImageMatrix(matrix);
    }

    private void clamp() {
        Drawable d = getDrawable();
        if (d == null) return;
        RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        matrix.mapRect(r);
        float dx = 0;
        float dy = 0;
        if (r.width() <= getWidth()) dx = (getWidth() - r.width()) / 2f - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < getWidth()) dx = getWidth() - r.right;
        if (r.height() <= getHeight()) dy = (getHeight() - r.height()) / 2f - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
        matrix.postTranslate(dx, dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scaler.onTouchEvent(ev);
        taps.onTouchEvent(ev);
        boolean zoomed = currentScale() > fitScale * 1.02f;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(zoomed || ev.getPointerCount() > 1);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = ev.getX();
                lastY = ev.getY();
                lastPointer = ev.getPointerId(0);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaler.isInProgress() && ev.getPointerCount() == 1 && ev.getPointerId(0) == lastPointer) {
                    float dx = ev.getX() - lastX;
                    float dy = ev.getY() - lastY;
                    if (zoomed) {
                        matrix.postTranslate(dx, dy);
                        clamp();
                        setImageMatrix(matrix);
                    }
                    lastX = ev.getX();
                    lastY = ev.getY();
                } else {
                    lastX = ev.getX();
                    lastY = ev.getY();
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lastPointer = -1;
                if (ev.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
                    int idx = ev.getActionIndex() == 0 ? 1 : 0;
                    lastX = ev.getX(idx);
                    lastY = ev.getY(idx);
                    lastPointer = ev.getPointerId(idx);
                }
                break;
            default:
                break;
        }
        return true;
    }
}

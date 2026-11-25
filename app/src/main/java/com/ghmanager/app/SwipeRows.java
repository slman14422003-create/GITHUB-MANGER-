package com.ghmanager.app;

import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.AdapterView;
import android.widget.ListView;

final class SwipeRows implements View.OnTouchListener {
    private final ListView list;
    private final int trayWidth;
    private final int slop;
    private boolean enabled = true;
    private float downX;
    private float downY;
    private View downRow;
    private boolean swiping;
    private VelocityTracker tracker;
    private View openRow;

    SwipeRows(ListView list, int trayWidth) {
        this.list = list;
        this.trayWidth = trayWidth;
        this.slop = ViewConfiguration.get(list.getContext()).getScaledTouchSlop();
    }

    void setEnabled(boolean on) {
        enabled = on;
        if (!on) closeOpen();
    }

    boolean hasOpen() {
        return openRow != null;
    }

    boolean closeOpen() {
        if (openRow == null) return false;
        settle(openRow, false);
        return true;
    }

    private boolean rtl() {
        return list.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }

    private float openOffset() {
        return rtl() ? trayWidth : -trayWidth;
    }

    private void settle(final View row, final boolean open) {
        final View card = row.findViewById(R.id.card);
        final View tray = row.findViewById(R.id.swipe_tray);
        if (card == null) return;
        if (tray != null) tray.setVisibility(View.VISIBLE);
        if (open) openRow = row;
        else if (openRow == row) openRow = null;
        float target = open ? openOffset() : 0f;
        if (!Motion.enabled()) {
            card.setTranslationX(target);
            if (!open && tray != null) tray.setVisibility(View.INVISIBLE);
            return;
        }
        card.animate().translationX(target)
                .setDuration(Motion.base(list.getContext()))
                .setInterpolator(Motion.standard())
                .withEndAction(() -> {
                    if (tray != null && openRow != row) tray.setVisibility(View.INVISIBLE);
                })
                .start();
    }

    private void reset() {
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
        swiping = false;
        downRow = null;
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                reset();
                if (!enabled) return false;
                downX = e.getX();
                downY = e.getY();
                int pos = list.pointToPosition((int) downX, (int) downY);
                downRow = pos == AdapterView.INVALID_POSITION ? null
                        : list.getChildAt(pos - list.getFirstVisiblePosition());
                if (openRow != null && openRow != downRow) closeOpen();
                tracker = VelocityTracker.obtain();
                tracker.addMovement(e);
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (tracker == null || downRow == null) return false;
                tracker.addMovement(e);
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (!swiping) {
                    if (Math.abs(dx) <= slop || Math.abs(dx) <= Math.abs(dy) * 1.5f) return false;
                    swiping = true;
                    list.requestDisallowInterceptTouchEvent(true);
                    MotionEvent cancel = MotionEvent.obtain(e);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    list.onTouchEvent(cancel);
                    cancel.recycle();
                    View tray = downRow.findViewById(R.id.swipe_tray);
                    if (tray != null) tray.setVisibility(View.VISIBLE);
                }
                View card = downRow.findViewById(R.id.card);
                if (card == null) return true;
                float base = downRow == openRow ? openOffset() : 0f;
                float lo = rtl() ? 0f : -trayWidth;
                float hi = rtl() ? trayWidth : 0f;
                card.setTranslationX(Math.max(lo, Math.min(hi, base + dx)));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean was = swiping;
                if (was && downRow != null && tracker != null) {
                    tracker.addMovement(e);
                    tracker.computeCurrentVelocity(1000);
                    float vx = tracker.getXVelocity();
                    View card = downRow.findViewById(R.id.card);
                    float progress = card == null ? 0f : Math.abs(card.getTranslationX()) / trayWidth;
                    boolean fastOpen = rtl() ? vx > 800 : vx < -800;
                    boolean fastClose = rtl() ? vx < -800 : vx > 800;
                    settle(downRow, fastOpen || (!fastClose && progress > 0.5f));
                }
                reset();
                return was;
            }
            default:
                return false;
        }
    }
}

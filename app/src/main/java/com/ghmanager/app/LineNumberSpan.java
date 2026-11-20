package com.ghmanager.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.style.LeadingMarginSpan;

/** Draws the line number in a fixed gutter and indents wrapped lines under the text, like the website. */
final class LineNumberSpan implements LeadingMarginSpan {
    private final String label;
    private final int gutter;
    private final int color;
    private final int pad;

    LineNumberSpan(int no, int gutter, int color, int pad) {
        this.label = String.valueOf(no);
        this.gutter = gutter;
        this.color = color;
        this.pad = pad;
    }

    @Override
    public int getLeadingMargin(boolean first) {
        return gutter;
    }

    @Override
    public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline, int bottom,
                                  CharSequence text, int start, int end, boolean first, Layout layout) {
        if (!first) return;
        Paint q = new Paint(p);
        q.setColor(color);
        q.setFakeBoldText(false);
        q.setStyle(Paint.Style.FILL);
        q.setTextAlign(Paint.Align.RIGHT);
        c.drawText(label, x + gutter - pad, baseline, q);
    }
}

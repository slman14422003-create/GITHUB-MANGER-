package com.ghmanager.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

public class SparklineView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path area = new Path();
    private int[] data = new int[0];

    public SparklineView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(Ui.dp(c, 2) * 0.75f);
        fill.setStyle(Paint.Style.FILL);
        dot.setStyle(Paint.Style.FILL);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setData(int[] d, int color) {
        data = d == null ? new int[0] : d;
        line.setColor(color);
        dot.setColor(color);
        fill.setColor((color & 0x00FFFFFF) | 0x26000000);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = data.length;
        if (n < 2) return;
        float pad = Ui.dp(getContext(), 2);
        float w = getWidth() - pad * 2;
        float h = getHeight() - pad * 2;
        if (w <= 0 || h <= 0) return;
        int max = 0;
        for (int v : data) max = Math.max(max, v);
        float step = w / (n - 1);
        path.reset();
        area.reset();
        float lastX = pad;
        float lastY = pad + h;
        for (int i = 0; i < n; i++) {
            float x = pad + step * i;
            float y = max == 0 ? pad + h : pad + h - (data[i] / (float) max) * h;
            if (i == 0) {
                path.moveTo(x, y);
                area.moveTo(x, pad + h);
                area.lineTo(x, y);
            } else {
                path.lineTo(x, y);
                area.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }
        area.lineTo(lastX, pad + h);
        area.close();
        canvas.drawPath(area, fill);
        canvas.drawPath(path, line);
        canvas.drawCircle(lastX, lastY, line.getStrokeWidth() * 1.4f, dot);
    }
}

package com.ghmanager.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class RunChartView extends View {
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private List<RunStats.Sample> samples = new ArrayList<>();
    private double[] rates = new double[0];
    private final int okColor;
    private final int badColor;
    private final int otherColor;
    private final int lineColor;

    public RunChartView(Context c) {
        super(c);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        setTextDirection(TEXT_DIRECTION_LTR);
        okColor = Ui.color(c, R.color.ok);
        badColor = Ui.color(c, R.color.bad);
        otherColor = Ui.color(c, R.color.text_hint);
        lineColor = Ui.color(c, R.color.info);
        bar.setStyle(Paint.Style.FILL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeWidth(Ui.dp(c, 2));
        line.setColor(lineColor);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(lineColor);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(Ui.dp(c, 1));
        grid.setColor(Ui.color(c, R.color.stroke_soft));
        text.setColor(Ui.color(c, R.color.text_secondary));
        text.setTextSize(Ui.dp(c, 10));
    }

    public void setData(List<RunStats.Sample> oldestFirst) {
        samples = oldestFirst == null ? new ArrayList<>() : oldestFirst;
        rates = RunStats.rateSeries(samples, 10);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = samples.size();
        Context c = getContext();
        float padL = Ui.dp(c, 44);
        float padR = Ui.dp(c, 36);
        float padT = Ui.dp(c, 10);
        float padB = Ui.dp(c, 8);
        float w = getWidth() - padL - padR;
        float h = getHeight() - padT - padB;
        if (n == 0 || w <= 0 || h <= 0) return;
        long max = 1;
        for (RunStats.Sample s : samples) max = Math.max(max, s.ms);
        float bottom = padT + h;
        for (int i = 0; i <= 2; i++) {
            float y = padT + h * i / 2f;
            canvas.drawLine(padL, y, padL + w, y, grid);
        }
        text.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(Fmt.duration(max), padL - Ui.dp(c, 4), padT + Ui.dp(c, 8), text);
        canvas.drawText("0", padL - Ui.dp(c, 4), bottom, text);
        text.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("100%", padL + w + Ui.dp(c, 4), padT + Ui.dp(c, 8), text);
        canvas.drawText("0%", padL + w + Ui.dp(c, 4), bottom, text);
        float slot = w / n;
        float bw = Math.max(Ui.dp(c, 2), slot * 0.68f);
        float minH = Ui.dp(c, 2);
        path.reset();
        float lx = 0;
        float ly = 0;
        for (int i = 0; i < n; i++) {
            RunStats.Sample s = samples.get(i);
            float cx = padL + slot * i + slot / 2f;
            float bh = Math.max(minH, s.ms / (float) max * h);
            bar.setColor(s.isOk() ? okColor : s.isBad() ? badColor : otherColor);
            bar.setAlpha(190);
            rect.set(cx - bw / 2f, bottom - bh, cx + bw / 2f, bottom);
            canvas.drawRoundRect(rect, Ui.dp(c, 2), Ui.dp(c, 2), bar);
            float y = bottom - (float) (rates[i] / 100.0) * h;
            if (i == 0) path.moveTo(cx, y);
            else path.lineTo(cx, y);
            lx = cx;
            ly = y;
        }
        if (n > 1) canvas.drawPath(path, line);
        canvas.drawCircle(lx, ly, line.getStrokeWidth() * 1.6f, dot);
    }
}

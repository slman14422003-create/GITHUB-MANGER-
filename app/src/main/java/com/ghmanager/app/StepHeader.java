package com.ghmanager.app;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** One step row of the job viewer: status dot, name, duration (live while running) and an open/closed arrow. */
final class StepHeader {
    final FrameLayout root;
    final LiveBoard.Dot dot;
    final TextView name;
    final TextView time;
    final ImageView arrow;
    long start;
    int code;
    private final Context ctx;

    StepHeader(Context ctx) {
        this.ctx = ctx;
        root = new FrameLayout(ctx);
        root.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 3), Ui.dp(ctx, 8), Ui.dp(ctx, 3));
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(ctx, 46));
        row.setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 12), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.color(ctx, R.color.surface_high));
        bg.setCornerRadius(Ui.radius(ctx, R.dimen.radius_s));
        row.setBackground(bg);
        dot = new LiveBoard.Dot(ctx, 7, 23);
        row.addView(dot, new LinearLayout.LayoutParams(Ui.dp(ctx, 26), Ui.dp(ctx, 46)));
        name = new TextView(ctx);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTextSize(14f);
        name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nl.setMarginStart(Ui.dp(ctx, 6));
        row.addView(name, nl);
        time = new TextView(ctx);
        time.setTextSize(12f);
        time.setTypeface(Typeface.MONOSPACE);
        time.setTextDirection(View.TEXT_DIRECTION_LTR);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.setMarginEnd(Ui.dp(ctx, 8));
        row.addView(time, tl);
        arrow = new ImageView(ctx);
        arrow.setImageResource(R.drawable.ic_arrow_down);
        arrow.setColorFilter(Ui.color(ctx, R.color.text_hint));
        row.addView(arrow, new LinearLayout.LayoutParams(Ui.dp(ctx, 20), Ui.dp(ctx, 20)));
        root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setTag(this);
    }

    void bind(StepLog.Step s, boolean open) {
        code = s.code;
        start = s.start;
        dot.set(s.code, false, false);
        name.setText(s.name);
        name.setTextColor(Ui.color(ctx, s.code == 1 ? R.color.info
                : s.code == 3 ? R.color.bad : s.code == 2 ? R.color.text_primary : R.color.text_secondary));
        name.setTypeface(s.code == 1 || s.code == 3 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        arrow.setRotation(open ? 180f : 0f);
        if (s.code == 1 && s.start > 0) {
            time.setText(LiveBoard.clock(System.currentTimeMillis() - s.start));
            time.setTextColor(Ui.color(ctx, R.color.info));
        } else if (s.start > 0 && s.end >= s.start) {
            time.setText(Fmt.duration(s.end - s.start));
            time.setTextColor(Ui.color(ctx, R.color.text_hint));
        } else {
            time.setText("");
        }
    }

    void tick() {
        if (code == 1 && start > 0) {
            time.setText(LiveBoard.clock(System.currentTimeMillis() - start));
        }
    }
}

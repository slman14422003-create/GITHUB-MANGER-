package com.ghmanager.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "GitHub status" button for the top bar: a small coloured dot on the button shows the state of GitHub's
 * servers (green, yellow, blue for maintenance, red). Tap it for a sheet with every service and the open
 * incidents. Refreshes itself every minute while the screen is visible.
 */
public final class GhStatusCard {
    private static final long EVERY_MS = 60_000;

    private final Activity a;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final View button;
    private final View dot;
    private GhStatus.Result last;
    private boolean running = false;
    private boolean busy = false;
    private boolean openWhenReady = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            refresh();
            ui.postDelayed(this, EVERY_MS);
        }
    };

    public GhStatusCard(Activity activity, View button, View dot) {
        this.a = activity;
        this.button = button;
        this.dot = dot;
        button.setOnClickListener(v -> details());
        paint();
    }

    public void start() {
        if (running) return;
        running = true;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    public void stop() {
        running = false;
        ui.removeCallbacks(tick);
    }

    public void destroy() {
        stop();
        io.shutdownNow();
    }

    public void refresh() {
        if (busy) return;
        busy = true;
        paint();
        io.execute(() -> {
            final GhStatus.Result r = GhStatus.fetch();
            ui.post(() -> {
                busy = false;
                if (a.isFinishing() || a.isDestroyed()) return;
                // a failed check keeps the last good answer on screen
                if (r != null) last = r;
                paint();
                if (openWhenReady) {
                    openWhenReady = false;
                    if (last != null) details();
                    else Toast.makeText(a, R.string.gs_unknown, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // ------------------------------------------------------------------ look

    private static int severity(String indicator) {
        switch (indicator) {
            case "none":
                return 0;
            case "minor":
                return 1;
            case "maintenance":
                return 1;
            case "major":
                return 2;
            default:
                return 3;
        }
    }

    private int colorFor(String indicator) {
        switch (indicator) {
            case "none":
                return Ui.color(a, R.color.ok);
            case "minor":
                return Ui.color(a, R.color.warn);
            case "maintenance":
                return Ui.color(a, R.color.info);
            default:
                return Ui.color(a, R.color.bad);
        }
    }

    private int compColor(String status) {
        switch (status) {
            case "operational":
                return Ui.color(a, R.color.ok);
            case "degraded_performance":
            case "partial_outage":
                return Ui.color(a, R.color.warn);
            case "under_maintenance":
                return Ui.color(a, R.color.info);
            default:
                return Ui.color(a, R.color.bad);
        }
    }

    private String compLabel(String status) {
        switch (status) {
            case "operational":
                return a.getString(R.string.gs_c_operational);
            case "degraded_performance":
                return a.getString(R.string.gs_c_degraded);
            case "partial_outage":
                return a.getString(R.string.gs_c_partial);
            case "under_maintenance":
                return a.getString(R.string.gs_c_maint);
            default:
                return a.getString(R.string.gs_c_major);
        }
    }

    private void setDot(int color, boolean pulse) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        // a thin ring in the page colour keeps the dot readable on top of the button
        g.setStroke(Ui.dp(a, 2), Ui.color(a, R.color.bg));
        dot.setBackground(g);
        Skeleton.pulse(dot, pulse);
    }

    private int titleRes(String ind) {
        switch (ind) {
            case "none":
                return R.string.gs_ok;
            case "minor":
                return R.string.gs_minor;
            case "maintenance":
                return R.string.gs_maint;
            case "major":
                return R.string.gs_major;
            default:
                return R.string.gs_critical;
        }
    }

    /** Colours the dot and keeps the button's spoken description in step with the state. */
    private void paint() {
        if (last == null) {
            setDot(Ui.color(a, R.color.text_hint), busy);
            button.setContentDescription(a.getString(busy ? R.string.gs_loading : R.string.gs_unknown));
            return;
        }
        String ind = last.indicator;
        setDot(colorFor(ind), severity(ind) > 0);
        button.setContentDescription(a.getString(R.string.gs_title) + ": " + a.getString(titleRes(ind)));
    }

    // ------------------------------------------------------------------ details

    private TextView text(String s, int sp, int colorRes, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Ui.color(a, colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return t;
    }

    private View summary() {
        String ind = last.indicator;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_option);
        row.setPaddingRelative(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 16), Ui.dp(a, 14));
        View d = new View(a);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(colorFor(ind));
        d.setBackground(g);
        row.addView(d, new LinearLayout.LayoutParams(Ui.dp(a, 14), Ui.dp(a, 14)));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cl.setMarginStart(Ui.dp(a, 12));
        row.addView(col, cl);
        col.addView(text(a.getString(titleRes(ind)), 15, R.color.text_primary, true));
        String age = DateUtils.getRelativeTimeSpanString(last.checkedAt, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS).toString();
        col.addView(text(a.getString(R.string.gs_checked, age), 12, R.color.text_secondary, false));
        return row;
    }

    private void details() {
        if (last == null) {
            // nothing fetched yet: check now and open the sheet as soon as the answer arrives
            openWhenReady = true;
            Toast.makeText(a, R.string.gs_loading, Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        LinearLayout box = Ui.box(a);
        box.addView(summary());
        box.addView(Ui.sectionTitle(a, a.getString(R.string.gs_components)));
        for (GhStatus.Comp k : last.components) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 8));
            View d = new View(a);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(compColor(k.status));
            d.setBackground(g);
            row.addView(d, new LinearLayout.LayoutParams(Ui.dp(a, 10), Ui.dp(a, 10)));
            TextView n = text(k.name, 14, R.color.text_primary, false);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nl.setMarginStart(Ui.dp(a, 12));
            row.addView(n, nl);
            boolean ok = "operational".equals(k.status);
            row.addView(text(compLabel(k.status), 12, ok ? R.color.text_secondary : R.color.text_primary, !ok));
            box.addView(row);
        }
        box.addView(Ui.sectionTitle(a, a.getString(R.string.gs_incidents)));
        if (last.incidents.isEmpty()) {
            box.addView(text(a.getString(R.string.gs_no_incidents), 13, R.color.text_secondary, false));
        }
        for (final GhStatus.Incident x : last.incidents) {
            LinearLayout card2 = new LinearLayout(a);
            card2.setOrientation(LinearLayout.VERTICAL);
            card2.setBackgroundResource(R.drawable.bg_option);
            card2.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(a, 6);
            card2.setLayoutParams(lp);
            card2.addView(text(x.name, 14, R.color.text_primary, true));
            card2.addView(text(statusName(x.status), 12, R.color.warn, false));
            String body = x.latest.length() > 260 ? x.latest.substring(0, 260) + "…" : x.latest;
            if (!body.isEmpty()) {
                TextView b = text(body, 12, R.color.text_secondary, false);
                b.setPadding(0, Ui.dp(a, 6), 0, 0);
                card2.addView(b);
            }
            box.addView(card2);
        }
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        new Dlg(a).sheet()
                .setTitle(R.string.gs_title)
                .setView(sv)
                .setPositiveButton(R.string.gs_open, (d, w) -> {
                    try {
                        a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GhStatus.PAGE)));
                    } catch (Exception e) {
                        Toast.makeText(a, R.string.no_browser, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
        // a fresh check while the sheet is open keeps the next opening up to date
        refresh();
    }

    private String statusName(String s) {
        switch (s) {
            case "investigating":
                return a.getString(R.string.gs_i_investigating);
            case "identified":
                return a.getString(R.string.gs_i_identified);
            case "monitoring":
                return a.getString(R.string.gs_i_monitoring);
            default:
                return s;
        }
    }
}

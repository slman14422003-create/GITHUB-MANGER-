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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "GitHub status" card for the home screen: a coloured dot, one line about the state of GitHub's servers and
 * when it was checked. Tap it for every service and the open incidents. Refreshes itself every minute while
 * the screen is visible.
 */
public final class GhStatusCard {
    private static final long EVERY_MS = 60_000;

    private final Activity a;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final LinearLayout card;
    private final View dot;
    private final TextView title;
    private final TextView sub;
    private GhStatus.Result last;
    private boolean running = false;
    private boolean busy = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            refresh();
            ui.postDelayed(this, EVERY_MS);
        }
    };

    public GhStatusCard(Activity activity, ViewGroup parent, int index) {
        a = activity;
        card = new LinearLayout(a);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_ripple);
        card.setClickable(true);
        card.setFocusable(true);
        card.setPaddingRelative(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.setMargins(Ui.dp(a, 14), Ui.dp(a, 8), Ui.dp(a, 14), Ui.dp(a, 4));
        card.setLayoutParams(cl);

        dot = new View(a);
        card.addView(dot, new LinearLayout.LayoutParams(Ui.dp(a, 12), Ui.dp(a, 12)));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(Ui.dp(a, 12));
        card.addView(col, tl);
        title = new TextView(a);
        title.setTextColor(Ui.color(a, R.color.text_primary));
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        col.addView(title);
        sub = new TextView(a);
        sub.setTextColor(Ui.color(a, R.color.text_secondary));
        sub.setTextSize(12);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sub.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        col.addView(sub);

        ImageView go = new ImageView(a);
        go.setImageResource(R.drawable.ic_chevron);
        go.setColorFilter(Ui.color(a, R.color.text_hint));
        go.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(go, new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));

        card.setOnClickListener(v -> details());
        Ui.press(a, card);
        parent.addView(card, Math.min(index, parent.getChildCount()));
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
        io.execute(() -> {
            final GhStatus.Result r = GhStatus.fetch();
            ui.post(() -> {
                busy = false;
                if (a.isFinishing() || a.isDestroyed()) return;
                // a failed check keeps the last good answer on screen (only its age shows)
                if (r != null) last = r;
                else if (last == null) last = null;
                paint();
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
        dot.setBackground(g);
        Skeleton.pulse(dot, pulse);
    }

    private void paint() {
        if (last == null) {
            setDot(Ui.color(a, R.color.text_hint), false);
            title.setText(busy ? R.string.gs_loading : R.string.gs_unknown);
            sub.setText(R.string.gs_tap);
            return;
        }
        String ind = last.indicator;
        setDot(colorFor(ind), severity(ind) > 0);
        int t;
        switch (ind) {
            case "none":
                t = R.string.gs_ok;
                break;
            case "minor":
                t = R.string.gs_minor;
                break;
            case "maintenance":
                t = R.string.gs_maint;
                break;
            case "major":
                t = R.string.gs_major;
                break;
            default:
                t = R.string.gs_critical;
        }
        title.setText(t);
        String age = DateUtils.getRelativeTimeSpanString(last.checkedAt, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS).toString();
        String line;
        if (severity(ind) == 0) {
            line = a.getString(R.string.gs_all_ok);
        } else {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (GhStatus.Comp k : last.components) {
                if ("operational".equals(k.status)) continue;
                if (n++ > 0) sb.append("، ");
                if (n > 3) {
                    sb.append("…");
                    break;
                }
                sb.append(k.name);
            }
            line = sb.length() > 0 ? sb.toString() : (last.incidents.isEmpty() ? last.description : last.incidents.get(0).name);
        }
        sub.setText(line + " · " + a.getString(R.string.gs_checked, age));
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

    private void details() {
        if (last == null) {
            refresh();
            return;
        }
        LinearLayout box = Ui.box(a);
        box.addView(Ui.sectionTitle(a, a.getString(R.string.gs_components)));
        for (GhStatus.Comp k : last.components) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(a, 6), Ui.dp(a, 7), Ui.dp(a, 6), Ui.dp(a, 7));
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
            TextView s = text(compLabel(k.status), 12, "operational".equals(k.status) ? R.color.text_secondary
                    : R.color.text_primary, !"operational".equals(k.status));
            row.addView(s);
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
        new Dlg(a)
                .setTitle(R.string.gs_title)
                .setView(sv)
                .setPositiveButton(R.string.gs_open, (d, w) -> {
                    try {
                        a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GhStatus.PAGE)));
                    } catch (Exception e) {
                        android.widget.Toast.makeText(a, R.string.no_browser, android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
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

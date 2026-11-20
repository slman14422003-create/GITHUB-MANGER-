package com.ghmanager.app;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Compact live pipeline for one running workflow inside the runs list: every job (stage) with ALL of
 * its steps, a status marker for each and a running clock. It is rebuilt with each refresh of the
 * list and its clocks are advanced every second by {@link #tick(View)}.
 */
final class LiveSteps {
    private LiveSteps() {
    }

    /** Fills {@code host} with the jobs and steps; returns false (and hides it) when there is nothing to show. */
    static boolean bind(Context c, LinearLayout host, JSONArray jobs) {
        host.removeAllViews();
        if (jobs == null || jobs.length() == 0) {
            host.setVisibility(View.GONE);
            return false;
        }
        host.setVisibility(View.VISIBLE);
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.optJSONObject(i);
            if (job == null) continue;
            int st = LiveBoard.code(job.optString("status"), job.optString("conclusion"));
            host.addView(row(c, job, st, true, false, false));
            JSONArray steps = job.optJSONArray("steps");
            int n = steps == null ? 0 : steps.length();
            for (int k = 0; k < n; k++) {
                JSONObject step = steps.optJSONObject(k);
                if (step == null) continue;
                int ss = LiveBoard.code(step.optString("status"), step.optString("conclusion"));
                host.addView(row(c, step, ss, false, k > 0, k < n - 1));
            }
        }
        return true;
    }

    private static View row(Context c, JSONObject o, int st, boolean isJob, boolean prev, boolean next) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(c, isJob ? 32 : 28));
        if (isJob) row.setPadding(0, Ui.dp(c, 4), 0, 0);

        LiveBoard.Dot dot = isJob ? new LiveBoard.Dot(c, 8, 14) : new LiveBoard.Dot(c, 5, 14);
        dot.set(st, prev, next);
        row.addView(dot, new LinearLayout.LayoutParams(Ui.dp(c, isJob ? 24 : 20),
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView name = new TextView(c);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        name.setTextSize(isJob ? 14f : 13f);
        name.setText(isJob ? o.optString("name") : o.optInt("number") + ". " + o.optString("name"));
        name.setTextColor(Ui.color(c, st == 1 ? R.color.info : st == 3 ? R.color.bad
                : isJob || st == 2 ? R.color.text_primary : R.color.text_secondary));
        name.setTypeface(isJob || st == 1 || st == 3 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nl.setMarginStart(Ui.dp(c, 6));
        row.addView(name, nl);

        TextView time = new TextView(c);
        time.setTextSize(12f);
        time.setTypeface(Typeface.MONOSPACE);
        time.setTextDirection(View.TEXT_DIRECTION_LTR);
        long start = Fmt.parse(Fmt.s(o, "started_at"));
        long end = Fmt.parse(Fmt.s(o, "completed_at"));
        if (st == 1 && start > 0) {
            time.setTag(start);   // running: the clock is advanced every second by tick()
            time.setText(LiveBoard.clock(System.currentTimeMillis() - start));
            time.setTextColor(Ui.color(c, R.color.info));
        } else if (start > 0 && end >= start && (isJob || end - start >= 1000)) {
            time.setText(Fmt.duration(end - start));
            time.setTextColor(Ui.color(c, R.color.text_hint));
        } else if (isJob && st == 0) {
            time.setText(Status.label(o.optString("status"), o.optString("conclusion")));
            time.setTextColor(Ui.color(c, R.color.text_hint));
        }
        row.addView(time);
        return row;
    }

    /** Advances the running clocks inside a bound host (call about once a second). */
    static void tick(View host) {
        if (!(host instanceof ViewGroup) || host.getVisibility() != View.VISIBLE) return;
        long now = System.currentTimeMillis();
        ViewGroup g = (ViewGroup) host;
        for (int i = 0; i < g.getChildCount(); i++) {
            View r = g.getChildAt(i);
            if (!(r instanceof ViewGroup)) continue;
            ViewGroup rg = (ViewGroup) r;
            View last = rg.getChildAt(rg.getChildCount() - 1);
            if (last instanceof TextView && last.getTag() instanceof Long) {
                ((TextView) last).setText(LiveBoard.clock(now - (Long) last.getTag()));
            }
        }
    }
}

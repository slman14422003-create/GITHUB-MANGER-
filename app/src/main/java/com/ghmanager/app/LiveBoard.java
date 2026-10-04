package com.ghmanager.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Live view of a workflow run: an animated progress ring with a running clock, and one card per job
 * with a segmented step bar and a step timeline. It is updated in place (no rebuilding), so the
 * animations and timers keep running smoothly while the screen polls GitHub.
 */
public class LiveBoard extends LinearLayout {

    public interface Listener {
        void onJob(JSONObject job);

        void onJobMenu(JSONObject job);
    }

    // state codes: 0 waiting, 1 running, 2 success, 3 failed, 4 skipped / cancelled
    static int code(String status, String conclusion) {
        switch (Status.state(status, conclusion)) {
            case "success":
                return 2;
            case "failure":
            case "timed_out":
            case "startup_failure":
                return 3;
            case "cancelled":
            case "skipped":
            case "neutral":
            case "stale":
                return 4;
            case "in_progress":
                return 1;
            default:
                return 0;
        }
    }

    static int stateColor(Context c, int code) {
        switch (code) {
            case 1:
                return Ui.color(c, R.color.info);
            case 2:
                return Ui.color(c, R.color.ok);
            case 3:
                return Ui.color(c, R.color.bad);
            case 4:
                return Ui.color(c, R.color.text_secondary);
            default:
                return Ui.color(c, R.color.text_hint);
        }
    }

    static String clock(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return h + ":" + (m < 10 ? "0" : "") + m + ":" + (sec < 10 ? "0" : "") + sec;
        return (m < 10 ? "0" : "") + m + ":" + (sec < 10 ? "0" : "") + sec;
    }

    private static float spin() {
        return (SystemClock.uptimeMillis() % 1400L) / 1400f * 360f;
    }

    private static float pulse() {
        return 0.5f + 0.5f * (float) Math.sin(SystemClock.uptimeMillis() / 260.0);
    }

    // ------------------------------------------------------------------ views

    /** Big progress ring with the percentage (or a result mark) in the middle. */
    static final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float target = 0f;
        private float shown = 0f;
        private boolean active = false;
        private boolean indeterminate = false;
        private String center = "";

        Ring(Context c) {
            super(c);
            track.setStyle(Paint.Style.STROKE);
            track.setColor(Ui.color(c, R.color.neutral_soft));
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeCap(Paint.Cap.ROUND);
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeCap(Paint.Cap.ROUND);
            glow.setColor(0xFFFFFFFF);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTypeface(Typeface.DEFAULT_BOLD);
            txt.setColor(Ui.color(c, R.color.text_primary));
        }

        void set(float fraction, int color, boolean isActive, boolean spinOnly, String text) {
            target = Math.max(0f, Math.min(1f, fraction));
            arc.setColor(color);
            active = isActive;
            indeterminate = spinOnly;
            center = text;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float s = Math.min(getWidth(), getHeight());
            float stroke = s * 0.11f;
            float pad = stroke / 2f + 2f;
            box.set(pad, pad, s - pad, s - pad);
            track.setStrokeWidth(stroke);
            arc.setStrokeWidth(stroke);
            glow.setStrokeWidth(stroke * 0.55f);
            cv.drawArc(box, 0, 360, false, track);

            shown += (target - shown) * 0.12f;
            if (Math.abs(target - shown) < 0.002f) shown = target;

            if (indeterminate) {
                cv.drawArc(box, spin(), 100, false, arc);
            } else if (shown > 0f) {
                cv.drawArc(box, -90, 360f * shown, false, arc);
            }
            if (active) {
                glow.setAlpha((int) (40 + 70 * pulse()));
                cv.drawArc(box, spin(), 26, false, glow);
            }
            txt.setTextSize(s * (center.length() > 3 ? 0.2f : 0.28f));
            float y = s / 2f - (txt.descent() + txt.ascent()) / 2f;
            cv.drawText(center, s / 2f, y, txt);
            if (active || shown != target) postInvalidateOnAnimation();
        }
    }

    /** Status marker of a job or a step; also draws the timeline connector. */
    static final class Dot extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Path path = new Path();
        private final float radius;
        private final float cy;
        private int state = 0;
        private boolean prev = false;
        private boolean next = false;

        Dot(Context c, int radiusDp, int centerYDp) {
            super(c);
            radius = Ui.dp(c, radiusDp);
            cy = Ui.dp(c, centerYDp);
        }

        void set(int st, boolean hasPrev, boolean hasNext) {
            if (st != state || hasPrev != prev || hasNext != next) {
                state = st;
                prev = hasPrev;
                next = hasNext;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f;
            float line = Ui.dp(getContext(), 2);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(line);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(Ui.color(getContext(), R.color.stroke));
            if (prev) cv.drawLine(cx, 0, cx, cy, p);
            if (next) cv.drawLine(cx, cy, cx, getHeight(), p);
            int col = stateColor(getContext(), state);
            if (state == 2 || state == 3) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(col);
                cv.drawCircle(cx, cy, radius, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2f, radius * 0.2f));
                p.setColor(Ui.color(getContext(), R.color.bg));
                path.reset();
                if (state == 2) {
                    path.moveTo(cx - radius * 0.42f, cy + radius * 0.02f);
                    path.lineTo(cx - radius * 0.1f, cy + radius * 0.34f);
                    path.lineTo(cx + radius * 0.46f, cy - radius * 0.32f);
                } else {
                    path.moveTo(cx - radius * 0.34f, cy - radius * 0.34f);
                    path.lineTo(cx + radius * 0.34f, cy + radius * 0.34f);
                    path.moveTo(cx + radius * 0.34f, cy - radius * 0.34f);
                    path.lineTo(cx - radius * 0.34f, cy + radius * 0.34f);
                }
                cv.drawPath(path, p);
            } else if (state == 1) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2f, radius * 0.28f));
                p.setColor(Ui.color(getContext(), R.color.neutral_soft));
                cv.drawCircle(cx, cy, radius * 0.86f, p);
                p.setColor(col);
                box.set(cx - radius * 0.86f, cy - radius * 0.86f, cx + radius * 0.86f, cy + radius * 0.86f);
                cv.drawArc(box, spin(), 110, false, p);
                postInvalidateOnAnimation();
            } else if (state == 4) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Ui.color(getContext(), R.color.neutral_soft));
                cv.drawCircle(cx, cy, radius, p);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(col);
                p.setStrokeWidth(Math.max(2f, radius * 0.2f));
                cv.drawLine(cx - radius * 0.4f, cy, cx + radius * 0.4f, cy, p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2f, radius * 0.22f));
                p.setColor(col);
                cv.drawCircle(cx, cy, radius * 0.82f, p);
            }
        }
    }

    /** One segment per step; the running one pulses. */
    static final class SegBar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int[] st = new int[0];

        SegBar(Context c) {
            super(c);
        }

        void set(int[] states) {
            st = states;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            int n = Math.max(1, st.length);
            float gap = Ui.dp(getContext(), 3);
            float h = getHeight();
            float w = (getWidth() - gap * (n - 1)) / n;
            boolean running = false;
            for (int i = 0; i < n; i++) {
                int code = st.length == 0 ? 0 : st[i];
                int col = code == 0 ? Ui.color(getContext(), R.color.neutral_soft)
                        : code == 4 ? Ui.color(getContext(), R.color.stroke) : stateColor(getContext(), code);
                p.setColor(col);
                p.setAlpha(255);
                if (code == 1) {
                    running = true;
                    p.setAlpha((int) (120 + 135 * pulse()));
                }
                float x = i * (w + gap);
                r.set(x, 0, x + w, h);
                cv.drawRoundRect(r, h / 2f, h / 2f, p);
            }
            if (running) postInvalidateOnAnimation();
        }
    }

    private static TextView text(Context c, int sp, int colorRes, boolean bold, boolean mono) {
        TextView t = new TextView(c);
        t.setTextSize(sp);
        t.setTextColor(Ui.color(c, colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        if (mono) t.setTypeface(Typeface.MONOSPACE);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return t;
    }

    /** A step line inside a job card. */
    final class StepRow extends LinearLayout {
        final Dot dot;
        final TextView name;
        final TextView time;
        long startMs;
        long endMs;
        int st;

        StepRow(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(Ui.dp(c, 30));
            dot = new Dot(c, 6, 15);
            addView(dot, new LayoutParams(Ui.dp(c, 20), ViewGroup.LayoutParams.MATCH_PARENT));
            name = text(c, 13, R.color.text_secondary, false, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LayoutParams nl = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nl.setMarginStart(Ui.dp(c, 8));
            addView(name, nl);
            time = text(c, 12, R.color.text_hint, false, true);
            addView(time);
        }

        void bind(JSONObject step, boolean hasPrev, boolean hasNext) {
            st = code(step.optString("status"), step.optString("conclusion"));
            dot.set(st, hasPrev, hasNext);
            name.setText(step.optInt("number") + ". " + step.optString("name"));
            name.setTextColor(Ui.color(getContext(), st == 1 ? R.color.info
                    : st == 3 ? R.color.bad : st == 2 ? R.color.text_primary : R.color.text_secondary));
            name.setTypeface(st == 1 || st == 3 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            startMs = Fmt.parse(Fmt.s(step, "started_at"));
            endMs = Fmt.parse(Fmt.s(step, "completed_at"));
            tick();
        }

        void tick() {
            if (st == 1 && startMs > 0) {
                time.setText(clock(System.currentTimeMillis() - startMs));
                time.setTextColor(Ui.color(getContext(), R.color.info));
            } else if (startMs > 0 && endMs >= startMs && endMs - startMs >= 1000) {
                time.setText(Fmt.duration(endMs - startMs));
                time.setTextColor(Ui.color(getContext(), R.color.text_hint));
            } else {
                time.setText("");
            }
        }
    }

    /** One job: header, segmented bar and collapsible step timeline. */
    final class JobCard extends LinearLayout {
        JSONObject job;
        final Dot dot;
        final TextView name;
        final TextView time;
        final SegBar bar;
        final LinearLayout steps;
        final TextView empty;
        boolean expanded = false;
        boolean userSet = false;
        long startMs;
        long endMs;
        int st;

        JobCard(final Context c) {
            super(c);
            setOrientation(VERTICAL);
            setBackgroundResource(R.drawable.bg_card);
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(c, 14), Ui.dp(c, 6), Ui.dp(c, 14), Ui.dp(c, 6));
            setLayoutParams(lp);

            LinearLayout head = new LinearLayout(c);
            head.setOrientation(HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPaddingRelative(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 6), Ui.dp(c, 4));
            head.setClickable(true);
            head.setFocusable(true);
            addView(head, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            dot = new Dot(c, 10, 14);
            head.addView(dot, new LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28)));
            name = text(c, 15, R.color.text_primary, true, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LayoutParams nl = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nl.setMarginStart(Ui.dp(c, 8));
            head.addView(name, nl);
            time = text(c, 13, R.color.text_secondary, false, true);
            head.addView(time);
            ImageView log = new ImageView(c);
            log.setImageResource(R.drawable.ic_list);
            log.setColorFilter(Ui.color(c, R.color.text_secondary));
            log.setContentDescription(c.getString(R.string.live_log));
            log.setScaleType(ImageView.ScaleType.CENTER);
            log.setBackgroundResource(R.drawable.bg_icon_btn);
            log.setClickable(true);
            LayoutParams ll = new LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40));
            ll.setMarginStart(Ui.dp(c, 4));
            head.addView(log, ll);
            log.setOnClickListener(v -> {
                if (listener != null && job != null) listener.onJob(job);
            });

            bar = new SegBar(c);
            LayoutParams bl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 6));
            bl.setMargins(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 16), Ui.dp(c, 8));
            addView(bar, bl);

            steps = new LinearLayout(c);
            steps.setOrientation(VERTICAL);
            steps.setPaddingRelative(Ui.dp(c, 14), 0, Ui.dp(c, 14), Ui.dp(c, 10));
            addView(steps, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            empty = text(c, 13, R.color.text_hint, false, false);
            empty.setText(R.string.live_no_steps);
            empty.setPadding(Ui.dp(c, 6), Ui.dp(c, 4), 0, Ui.dp(c, 4));
            steps.addView(empty);

            head.setOnClickListener(v -> {
                userSet = true;
                expanded = !expanded;
                TransitionManager.beginDelayedTransition(LiveBoard.this);
                steps.setVisibility(expanded ? View.VISIBLE : View.GONE);
            });
            head.setOnLongClickListener(v -> {
                if (listener != null && job != null) listener.onJobMenu(job);
                return true;
            });
            Ui.press(c, head);
        }

        void bind(JSONObject j) {
            job = j;
            Context c = getContext();
            name.setText(j.optString("name"));
            st = code(j.optString("status"), j.optString("conclusion"));
            dot.set(st, false, false);
            startMs = Fmt.parse(Fmt.s(j, "started_at"));
            endMs = Fmt.parse(Fmt.s(j, "completed_at"));

            JSONArray arr = j.optJSONArray("steps");
            int n = arr == null ? 0 : arr.length();
            int[] states = new int[n];
            // rows: index 0 of `steps` is the "empty" hint, the step rows follow it
            int have = steps.getChildCount() - 1;
            while (have < n) {
                steps.addView(new StepRow(c));
                have++;
            }
            while (have > n) {
                steps.removeViewAt(steps.getChildCount() - 1);
                have--;
            }
            for (int i = 0; i < n; i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                StepRow row = (StepRow) steps.getChildAt(i + 1);
                row.bind(s, i > 0, i < n - 1);
                states[i] = row.st;
            }
            bar.set(states);
            empty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
            if (!userSet) expanded = st == 1 || st == 3;
            steps.setVisibility(expanded ? View.VISIBLE : View.GONE);
            tick();
        }

        void tick() {
            long now = System.currentTimeMillis();
            if (st == 1 && startMs > 0) {
                time.setText(clock(now - startMs));
                time.setTextColor(Ui.color(getContext(), R.color.info));
            } else if (startMs > 0 && endMs >= startMs) {
                time.setText(Fmt.duration(endMs - startMs));
                time.setTextColor(Ui.color(getContext(), R.color.text_secondary));
            } else {
                time.setText(job == null ? "" : Status.label(job.optString("status"), job.optString("conclusion")));
                time.setTextColor(Ui.color(getContext(), R.color.text_hint));
            }
            for (int i = 1; i < steps.getChildCount(); i++) {
                View v = steps.getChildAt(i);
                if (v instanceof StepRow) ((StepRow) v).tick();
            }
        }
    }

    // ------------------------------------------------------------------ board

    private final Listener listener;
    private final Ring ring;
    private final TextView heroStatus;
    private final TextView heroNow;
    private final TextView heroTime;
    private final TextView heroSub;
    private final LinearLayout jobsHost;
    private final Map<Long, JobCard> cards = new LinkedHashMap<>();
    private long runStart = 0;
    private long runEnd = 0;
    private boolean runActive = false;

    public LiveBoard(Context c, Listener l) {
        super(c);
        listener = l;
        setOrientation(VERTICAL);

        LinearLayout hero = new LinearLayout(c);
        hero.setOrientation(HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setBackgroundResource(R.drawable.bg_card);
        int pad = Ui.dp(c, 18);
        hero.setPadding(pad, pad, pad, pad);
        LayoutParams hl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.setMargins(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 6));
        addView(hero, hl);

        ring = new Ring(c);
        hero.addView(ring, new LayoutParams(Ui.dp(c, 92), Ui.dp(c, 92)));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        LayoutParams cl = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cl.setMarginStart(Ui.dp(c, 16));
        hero.addView(col, cl);
        heroStatus = text(c, 18, R.color.text_primary, true, false);
        col.addView(heroStatus);
        heroNow = text(c, 13, R.color.text_secondary, false, false);
        heroNow.setMaxLines(2);
        heroNow.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(heroNow);
        heroTime = text(c, 24, R.color.text_primary, true, true);
        heroTime.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LayoutParams tl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = Ui.dp(c, 6);
        col.addView(heroTime, tl);
        heroSub = text(c, 12, R.color.text_hint, false, false);
        col.addView(heroSub);

        addView(Ui.sectionTitle(c, c.getString(R.string.live_jobs)));
        jobsHost = new LinearLayout(c);
        jobsHost.setOrientation(VERTICAL);
        addView(jobsHost, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Applies a fresh snapshot of the run and its jobs. */
    public void update(JSONObject run, JSONArray jobs) {
        if (run == null) return;
        Context c = getContext();
        String s = run.optString("status");
        String cc = run.optString("conclusion");
        int st = code(s, cc);
        boolean active = Status.isActive(s);
        int color = Status.color(c, s, cc);
        heroStatus.setText(Status.label(s, cc));
        heroStatus.setTextColor(color);

        int jobsTotal = jobs == null ? 0 : jobs.length();
        int totalSteps = 0;
        int doneSteps = 0;
        int curJob = 0;
        String now = "";
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < jobsTotal; i++) {
            JSONObject j = jobs.optJSONObject(i);
            if (j == null) continue;
            JSONArray steps = j.optJSONArray("steps");
            int n = steps == null ? 0 : steps.length();
            boolean jobDone = "completed".equals(j.optString("status"));
            if (n == 0) {
                totalSteps += 1;
                if (jobDone) doneSteps += 1;
            } else {
                totalSteps += n;
                for (int k = 0; k < n; k++) {
                    JSONObject step = steps.optJSONObject(k);
                    if (step == null) continue;
                    if ("completed".equals(step.optString("status"))) doneSteps++;
                    if (now.isEmpty() && "in_progress".equals(step.optString("status"))) {
                        now = step.optString("name");
                        curJob = i + 1;
                    }
                }
            }
            long id = j.optLong("id");
            seen.add(id);
            JobCard card = cards.get(id);
            if (card == null) {
                card = new JobCard(c);
                cards.put(id, card);
                jobsHost.addView(card);
            }
            card.bind(j);
        }
        Iterator<Map.Entry<Long, JobCard>> it = cards.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, JobCard> e = it.next();
            if (!seen.contains(e.getKey())) {
                jobsHost.removeView(e.getValue());
                it.remove();
            }
        }

        float frac = totalSteps == 0 ? 0f : doneSteps / (float) totalSteps;
        if (!active && st == 2) frac = 1f;
        String centerText;
        if (active) centerText = Math.round(frac * 100f) + "%";
        else if (st == 2) centerText = "✓";
        else if (st == 3) centerText = "✕";
        else centerText = "–";
        ring.set(frac, color, active, active && totalSteps == 0, centerText);

        if (active) {
            if (!now.isEmpty()) heroNow.setText(c.getString(R.string.live_now, now));
            else heroNow.setText("queued".equals(s) || "waiting".equals(s) || "pending".equals(s)
                    ? R.string.live_queued : R.string.live_starting);
            if (totalSteps > 0) {
                heroSub.setText(c.getString(R.string.live_progress, Math.min(doneSteps + 1, totalSteps),
                        totalSteps, Math.max(curJob, 1), Math.max(jobsTotal, 1)));
            } else {
                heroSub.setText("");
            }
        } else {
            heroNow.setText(st == 2 ? R.string.live_done_ok : st == 3 ? R.string.live_done_fail : R.string.live_done_other);
            heroSub.setText("");
        }

        long created = Fmt.parse(Fmt.s(run, "run_started_at"));
        if (created <= 0) created = Fmt.parse(Fmt.s(run, "created_at"));
        runStart = created;
        runEnd = active ? 0 : Fmt.parse(Fmt.s(run, "updated_at"));
        runActive = active;
        tick();
    }

    /** Refreshes every running clock; call about once a second. */
    public void tick() {
        if (runStart > 0) {
            long end = runActive ? System.currentTimeMillis() : runEnd;
            heroTime.setText(clock(end - runStart));
        } else {
            heroTime.setText("");
        }
        for (JobCard card : cards.values()) card.tick();
    }
}

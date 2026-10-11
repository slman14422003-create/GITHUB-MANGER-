package com.ghmanager.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A floating card at the bottom of every screen that shows each running upload / download / update
 * with a real progress bar: percentage, size, speed and time left, plus a cancel button. When a
 * transfer ends the card shows the result for a moment, and tapping it opens the file or the
 * installer. Screens never have to know about it: App attaches it to whatever activity is resumed.
 */
final class TransferDock implements Transfers.Listener {
    private static final long TICK_BUSY = 250;
    private static final long TICK_IDLE = 500;
    private static final int MAX_ROWS = 3;

    private static final Map<Activity, TransferDock> DOCKS = new WeakHashMap<>();

    static void resume(Activity a) {
        TransferDock d = DOCKS.get(a);
        if (d == null) {
            d = new TransferDock(a);
            DOCKS.put(a, d);
        }
        d.start();
    }

    static void pause(Activity a) {
        TransferDock d = DOCKS.get(a);
        if (d != null) d.stop();
    }

    /** The dock holds its activity, so it must be dropped with it (a weak key alone would never be freed). */
    static void destroy(Activity a) {
        TransferDock d = DOCKS.remove(a);
        if (d != null) d.stop();
    }

    // ------------------------------------------------------------------ one row per transfer

    private final class Item {
        final Transfers.Job job;
        final LinearLayout root;
        final TextView title;
        final TextView pct;
        final ProgressBar bar;
        final TextView detail;
        final ImageView cancel;
        boolean finishing;
        boolean indeterminate;

        Item(Transfers.Job j) {
            job = j;
            root = new LinearLayout(act);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPaddingRelative(Ui.dp(act, 16), Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 12));

            LinearLayout head = new LinearLayout(act);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            root.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            title = new TextView(act);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setTextSize(14);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            title.setTextColor(Ui.color(act, R.color.text_primary));
            title.setText(j.title);
            head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            pct = new TextView(act);
            pct.setTextSize(14);
            pct.setTypeface(Typeface.DEFAULT_BOLD);
            pct.setTextColor(Ui.color(act, R.color.accent_text));
            pct.setTextDirection(View.TEXT_DIRECTION_LTR);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            pl.setMarginStart(Ui.dp(act, 8));
            head.addView(pct, pl);

            cancel = new ImageView(act);
            cancel.setImageResource(R.drawable.ic_cancel);
            cancel.setColorFilter(Ui.color(act, R.color.text_secondary));
            cancel.setScaleType(ImageView.ScaleType.CENTER);
            cancel.setBackgroundResource(R.drawable.bg_icon_btn);
            cancel.setContentDescription(act.getString(R.string.tr_cancel));
            cancel.setClickable(true);
            cancel.setOnClickListener(v -> job.cancel());
            Ui.press(act, cancel);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40));
            cl.setMarginStart(Ui.dp(act, 2));
            cl.setMarginEnd(Ui.dp(act, 0));
            head.addView(cancel, cl);

            bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgressDrawable(act.getDrawable(R.drawable.progress_pill));
            bar.setIndeterminateTintList(ColorStateList.valueOf(Ui.color(act, R.color.accent)));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Ui.dp(act, 4));
            bl.setMarginEnd(Ui.dp(act, 8));
            bl.topMargin = Ui.dp(act, 2);
            root.addView(bar, bl);

            detail = new TextView(act);
            detail.setTextSize(12);
            detail.setMaxLines(3);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            detail.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            detail.setTextDirection(View.TEXT_DIRECTION_LTR);
            detail.setTextColor(Ui.color(act, R.color.text_secondary));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            dl.topMargin = Ui.dp(act, 6);
            dl.setMarginEnd(Ui.dp(act, 8));
            root.addView(detail, dl);
        }

        /** Live numbers of a running transfer. */
        void bind() {
            if (finishing) return;
            float f = job.fraction();
            setIndeterminate(f < 0f);
            if (f >= 0f) {
                int v = Math.round(f * 1000f);
                Ui.progress(bar, v);
                pct.setText(Math.round(f * 100f) + "%");
                detail.setText(job.detail());
            } else {
                pct.setText("");
                String t = job.text();
                String d = job.detail();
                detail.setText(t.isEmpty() ? d : d.isEmpty() ? t : t + "  ·  " + d);
            }
        }

        private void setIndeterminate(boolean on) {
            if (on == indeterminate && bar.isIndeterminate() == on) return;
            indeterminate = on;
            bar.setIndeterminate(on);
        }

        /** Shows the result for a moment, then fades the row out. */
        void finish(final Transfers.Job j) {
            finishing = true;
            setIndeterminate(false);
            cancel.setVisibility(View.GONE);
            boolean ok = j.state == 1;
            int col = Ui.color(act, ok ? R.color.ok : R.color.bad);
            bar.setProgressTintList(ColorStateList.valueOf(col));
            if (ok) Ui.progress(bar, 1000);
            pct.setTextColor(col);
            pct.setText(ok ? "✓" : "✕");
            String msg = j.message == null ? "" : j.message.trim();
            detail.setText(msg.isEmpty() ? act.getString(ok ? R.string.tr_done : R.string.tr_failed) : msg);
            if (ok && j.openIntent != null) {
                root.setClickable(true);
                root.setOnClickListener(v -> {
                    try {
                        act.startActivity(j.openIntent);
                    } catch (Exception ignored) {
                    }
                });
            }
            h.postDelayed(() -> removeItem(this), ok ? 4000 : 7000);
        }
    }

    // ------------------------------------------------------------------ dock

    private final Activity act;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Map<Integer, Item> items = new LinkedHashMap<>();
    private LinearLayout card;
    private boolean shown = false;
    private boolean running = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            tickNow();
            h.postDelayed(this, items.isEmpty() ? TICK_IDLE : TICK_BUSY);
        }
    };

    private TransferDock(Activity a) {
        act = a;
    }

    private void start() {
        if (running) return;
        running = true;
        Transfers.addListener(this);
        tickNow();
        h.postDelayed(tick, TICK_BUSY);
    }

    private void stop() {
        running = false;
        h.removeCallbacksAndMessages(null);
        Transfers.removeListener(this);
        if (card != null) {
            ViewGroup p = (ViewGroup) card.getParent();
            if (p != null) p.removeView(card);
            card.animate().cancel();
        }
        card = null;
        shown = false;
        items.clear();
    }

    @Override
    public void onFinished(Transfers.Job job) {
        Item it = items.get(job.id);
        if (it == null) {
            // finished before the next tick ever saw it: still show the result
            if (!running || job.state == 3) return;
            it = addItem(job);
            if (it == null) return;
        }
        if (job.state == 3) {   // cancelled by the user: nothing to report
            removeItem(it);
            return;
        }
        it.finish(job);
        updateVisibility();
    }

    private void tickNow() {
        List<Transfers.Job> jobs = Transfers.snapshot();
        Set<Integer> live = new HashSet<>();
        for (Transfers.Job j : jobs) {
            live.add(j.id);
            Item it = items.get(j.id);
            if (it == null) it = addItem(j);
            if (it != null) it.bind();
        }
        List<Item> gone = new ArrayList<>();
        for (Map.Entry<Integer, Item> e : items.entrySet()) {
            if (!live.contains(e.getKey()) && !e.getValue().finishing) gone.add(e.getValue());
        }
        for (Item it : gone) removeItem(it);
        updateVisibility();
    }

    private Item addItem(Transfers.Job j) {
        if (items.size() >= MAX_ROWS) return null;
        if (!ensureCard()) return null;
        Item it = new Item(j);
        items.put(j.id, it);
        card.addView(it.root);
        if (Motion.enabled()) {
            it.root.setAlpha(0f);
            it.root.animate().alpha(1f).setDuration(Motion.base(act)).setInterpolator(Motion.enter()).start();
        }
        return it;
    }

    private void removeItem(final Item it) {
        if (items.remove(it.job.id) == null) return;
        final View v = it.root;
        if (Motion.enabled() && v.getParent() != null) {
            v.animate().alpha(0f).setDuration(Motion.fast(act)).setInterpolator(Motion.standard())
                    .withEndAction(() -> {
                        ViewGroup p = (ViewGroup) v.getParent();
                        if (p != null) p.removeView(v);
                        updateVisibility();
                    }).start();
        } else {
            ViewGroup p = (ViewGroup) v.getParent();
            if (p != null) p.removeView(v);
        }
        updateVisibility();
    }

    private boolean ensureCard() {
        if (card != null) return true;
        FrameLayout content = act.findViewById(android.R.id.content);
        if (content == null) return false;
        card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setElevation(Ui.dp(act, 8));
        card.setClickable(true);   // touches never fall through to the screen below
        card.setVisibility(View.GONE);
        // sit above the bottom bar when this screen has one
        boolean nav = content.getTag(R.id.tag_nav) != null;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins(Ui.dp(act, 14), 0, Ui.dp(act, 14), Ui.dp(act, nav ? 88 : 12));
        content.addView(card, lp);
        shown = false;
        return true;
    }

    /** The card slides in with the first transfer and slides away after the last one. */
    private void updateVisibility() {
        if (card == null) return;
        boolean want = !items.isEmpty();
        if (want == shown) return;
        shown = want;
        card.animate().cancel();
        if (want) {
            card.setVisibility(View.VISIBLE);
            if (Motion.enabled()) {
                card.setAlpha(0f);
                card.setTranslationY(Ui.dp(act, 24));
                card.animate().alpha(1f).translationY(0f).setDuration(Motion.slow(act))
                        .setInterpolator(Motion.enter()).start();
            } else {
                card.setAlpha(1f);
                card.setTranslationY(0f);
            }
        } else if (Motion.enabled()) {
            card.animate().alpha(0f).translationY(Ui.dp(act, 24)).setDuration(Motion.base(act))
                    .setInterpolator(Motion.standard())
                    .withEndAction(() -> {
                        if (card != null && !shown) card.setVisibility(View.GONE);
                    }).start();
        } else {
            card.setVisibility(View.GONE);
        }
    }
}

package com.ghmanager.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Shows every running upload / download / update from the top bar of each screen. A round button
 * sits in the header: a ring around it fills with the real progress, a badge counts parallel jobs,
 * and after the last job the ring keeps the result colour. Tapping it opens a panel under the
 * header with live progress (percentage, size, speed, time left, cancel) and the recent results;
 * tapping a finished row opens the file or the installer. Screens never have to know about it:
 * App attaches it to whatever activity is resumed.
 */
final class TransferDock implements Transfers.Listener {
    private static final long TICK_BUSY = 250;
    private static final long TICK_IDLE = 600;

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

    // ------------------------------------------------------------------ progress ring around the button

    private static final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private float fraction = 0f;
        private float spin = 0f;
        private boolean indeterminate = false;
        private ValueAnimator spinner;

        Ring(Activity a) {
            super(a);
            float w = Ui.dp(a, 3);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(w);
            track.setColor(Ui.color(a, R.color.stroke));
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(w);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(Ui.color(a, R.color.accent));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(float f, int color, boolean spinning) {
            arc.setColor(color);
            fraction = Math.max(0f, Math.min(1f, f));
            if (spinning != indeterminate) {
                indeterminate = spinning;
                if (spinning) startSpin();
                else stopSpin();
            }
            invalidate();
        }

        private void startSpin() {
            if (spinner != null) return;
            spinner = ValueAnimator.ofFloat(0f, 360f);
            spinner.setDuration(1000);
            spinner.setRepeatCount(ValueAnimator.INFINITE);
            spinner.setInterpolator(new LinearInterpolator());
            spinner.addUpdateListener(an -> {
                spin = (Float) an.getAnimatedValue();
                invalidate();
            });
            spinner.start();
        }

        void stopSpin() {
            if (spinner != null) {
                spinner.cancel();
                spinner = null;
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            stopSpin();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (indeterminate) startSpin();
        }

        @Override
        protected void onDraw(Canvas c) {
            float inset = track.getStrokeWidth() / 2f + Ui.dp(getContext(), 2);
            box.set(inset, inset, getWidth() - inset, getHeight() - inset);
            c.drawOval(box, track);
            if (indeterminate) c.drawArc(box, spin - 90f, 100f, false, arc);
            else if (fraction > 0f) c.drawArc(box, -90f, 360f * fraction, false, arc);
        }
    }

    // ------------------------------------------------------------------ rows inside the panel

    private final class Item {
        final Transfers.Job job;
        final LinearLayout root;
        final TextView pct;
        final ProgressBar bar;
        final TextView detail;
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

            TextView title = new TextView(act);
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

            ImageView cancel = new ImageView(act);
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
            float f = job.fraction();
            setIndeterminate(f < 0f);
            if (f >= 0f) {
                Ui.progress(bar, Math.round(f * 1000f));
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
    }

    private View resultRow(final Transfers.Job j) {
        boolean ok = j.state == 1;
        int col = Ui.color(act, ok ? R.color.ok : R.color.bad);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPaddingRelative(Ui.dp(act, 16), Ui.dp(act, 10), Ui.dp(act, 16), Ui.dp(act, 12));

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(act);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        title.setTextColor(Ui.color(act, R.color.text_primary));
        title.setText(j.title);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView mark = new TextView(act);
        mark.setTextSize(15);
        mark.setTypeface(Typeface.DEFAULT_BOLD);
        mark.setTextColor(col);
        mark.setText(ok ? "✓" : "✕");
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.setMarginStart(Ui.dp(act, 8));
        head.addView(mark, ml);

        String msg = j.message == null ? "" : j.message.trim();
        TextView detail = new TextView(act);
        detail.setTextSize(12);
        detail.setMaxLines(3);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        detail.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        detail.setTextColor(Ui.color(act, R.color.text_secondary));
        detail.setText(msg.isEmpty() ? act.getString(ok ? R.string.tr_done : R.string.tr_failed) : msg);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dl.topMargin = Ui.dp(act, 4);
        root.addView(detail, dl);

        if (ok && j.openIntent != null) {
            root.setClickable(true);
            root.setBackgroundResource(R.drawable.bg_card_ripple);
            root.setOnClickListener(v -> {
                try {
                    act.startActivity(j.openIntent);
                } catch (Exception ignored) {
                }
            });
        }
        return root;
    }

    private View divider() {
        View v = new View(act);
        v.setBackgroundColor(Ui.color(act, R.color.stroke_soft));
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 1)));
        return v;
    }

    private TextView sectionLabel(int res) {
        TextView t = new TextView(act);
        t.setText(res);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setTextColor(Ui.color(act, R.color.text_hint));
        t.setPaddingRelative(Ui.dp(act, 16), Ui.dp(act, 10), Ui.dp(act, 16), Ui.dp(act, 2));
        return t;
    }

    // ------------------------------------------------------------------ dock

    private final Activity act;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Map<Integer, Item> items = new LinkedHashMap<>();
    private FrameLayout button;
    private Ring ring;
    private TextView badge;
    private FrameLayout panel;
    private LinearLayout card;
    private LinearLayout list;
    private TextView clearBtn;
    private String sig = "";
    private boolean running = false;
    private boolean buttonShown = false;
    private OnBackPressedCallback backCb;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            tickNow();
            h.postDelayed(this, Transfers.activeCount() > 0 ? TICK_BUSY : TICK_IDLE);
        }
    };

    private TransferDock(Activity a) {
        act = a;
    }

    private void start() {
        if (running) return;
        running = true;
        Transfers.addListener(this);
        if (backCb == null && act instanceof ComponentActivity) {
            backCb = new OnBackPressedCallback(false) {
                @Override
                public void handleOnBackPressed() {
                    closePanel(true);
                }
            };
            ((ComponentActivity) act).getOnBackPressedDispatcher().addCallback((ComponentActivity) act, backCb);
        }
        attachButton();
        tickNow();
        h.postDelayed(tick, TICK_BUSY);
    }

    private void stop() {
        running = false;
        h.removeCallbacksAndMessages(null);
        Transfers.removeListener(this);
        closePanel(false);
        if (backCb != null) {
            backCb.setEnabled(false);
            backCb.remove();
            backCb = null;
        }
        if (ring != null) ring.stopSpin();
        if (button != null) {
            ViewGroup p = (ViewGroup) button.getParent();
            if (p != null) p.removeView(button);
            button.animate().cancel();
        }
        button = null;
        ring = null;
        badge = null;
        buttonShown = false;
        items.clear();
        sig = "";
    }

    @Override
    public void onFinished(Transfers.Job job) {
        if (!running) return;
        tickNow();
        if (button != null && job.state != 3 && Motion.enabled()) {
            button.animate().cancel();
            button.setScaleX(1f);
            button.setScaleY(1f);
            button.animate().scaleX(1.18f).scaleY(1.18f).setDuration(Motion.fast(act))
                    .setInterpolator(Motion.enter())
                    .withEndAction(() -> {
                        if (button != null) {
                            button.animate().scaleX(1f).scaleY(1f).setDuration(Motion.base(act))
                                    .setInterpolator(Motion.standard()).start();
                        }
                    }).start();
        }
    }

    // ------------------------------------------------------------------ the button in the header

    private void attachButton() {
        if (button != null && button.getParent() != null) return;

        button = new FrameLayout(act);
        button.setBackgroundResource(R.drawable.bg_icon_btn);
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(act.getString(R.string.tr_btn));
        button.setVisibility(View.GONE);
        button.setOnClickListener(v -> {
            if (panel != null) closePanel(true);
            else openPanel();
        });
        Ui.press(act, button);

        ring = new Ring(act);
        button.addView(ring, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView icon = new ImageView(act);
        icon.setImageResource(R.drawable.ic_upload);
        icon.setColorFilter(Ui.color(act, R.color.text_primary));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(icon, new FrameLayout.LayoutParams(Ui.dp(act, 22), Ui.dp(act, 22), Gravity.CENTER));

        badge = new TextView(act);
        badge.setBackgroundResource(R.drawable.bg_pill_accent);
        badge.setTextColor(Ui.color(act, R.color.accent_text));
        badge.setTextSize(10);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setMinWidth(Ui.dp(act, 16));
        badge.setMinHeight(Ui.dp(act, 16));
        badge.setPadding(Ui.dp(act, 4), 0, Ui.dp(act, 4), 0);
        badge.setVisibility(View.GONE);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        bp.setMargins(0, Ui.dp(act, 2), Ui.dp(act, 2), 0);
        button.addView(badge, bp);

        // next to the refresh button of the screen's own header
        View refresh = act.findViewById(R.id.btnRefresh);
        ViewGroup frame = refresh == null ? null : (ViewGroup) refresh.getParent();
        ViewGroup header = frame == null ? null : (ViewGroup) frame.getParent();
        if (header instanceof LinearLayout) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(act, 48), Ui.dp(act, 48));
            lp.setMarginEnd(Ui.dp(act, 6));
            header.addView(button, header.indexOfChild(frame), lp);
            return;
        }
        // a screen without the shared header: float in the top corner
        FrameLayout content = act.findViewById(android.R.id.content);
        if (content == null) {
            button = null;
            return;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(act, 48), Ui.dp(act, 48),
                Gravity.TOP | Gravity.END);
        lp.setMargins(0, Ui.dp(act, 10), Ui.dp(act, 12), 0);
        content.addView(button, lp);
    }

    private void showButton(boolean on) {
        if (button == null || on == buttonShown) return;
        buttonShown = on;
        button.animate().cancel();
        if (on) {
            button.setVisibility(View.VISIBLE);
            if (Motion.enabled()) {
                button.setAlpha(0f);
                button.setScaleX(0.6f);
                button.setScaleY(0.6f);
                button.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.base(act))
                        .setInterpolator(Motion.enter()).start();
            } else {
                button.setAlpha(1f);
                button.setScaleX(1f);
                button.setScaleY(1f);
            }
        } else {
            closePanel(true);
            button.setVisibility(View.GONE);
        }
    }

    private void tickNow() {
        if (button == null) {
            attachButton();
            if (button == null) return;
        }
        List<Transfers.Job> run = Transfers.snapshot();
        List<Transfers.Job> hist = Transfers.history();
        showButton(!run.isEmpty() || !hist.isEmpty());

        if (!run.isEmpty()) {
            float sum = 0f;
            int known = 0;
            for (Transfers.Job j : run) {
                float f = j.fraction();
                if (f >= 0f) {
                    sum += f;
                    known++;
                }
            }
            if (known == 0) ring.set(0f, Ui.color(act, R.color.accent), true);
            else ring.set(sum / known, Ui.color(act, R.color.accent), false);
            if (run.size() > 1) {
                badge.setText(String.valueOf(run.size()));
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }
        } else if (!hist.isEmpty()) {
            boolean ok = hist.get(0).state == 1;
            ring.set(1f, Ui.color(act, ok ? R.color.ok : R.color.bad), false);
            badge.setVisibility(View.GONE);
        }

        if (panel != null) refreshPanel(run, hist);
    }

    // ------------------------------------------------------------------ the panel under the header

    private void openPanel() {
        if (panel != null || button == null) return;
        final FrameLayout content = act.findViewById(android.R.id.content);
        if (content == null) return;

        panel = new FrameLayout(act);
        View scrim = new View(act);
        scrim.setBackgroundColor(0x66000000);
        scrim.setOnClickListener(v -> closePanel(true));
        panel.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setElevation(Ui.dp(act, 8));
        card.setClickable(true);   // touches inside the card never reach the scrim

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(Ui.dp(act, 16), Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 4));
        TextView title = new TextView(act);
        title.setText(R.string.tr_panel_title);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        title.setTextColor(Ui.color(act, R.color.text_primary));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        clearBtn = new TextView(act);
        clearBtn.setTextSize(13);
        clearBtn.setTypeface(Typeface.DEFAULT_BOLD);
        clearBtn.setTextColor(Ui.color(act, R.color.accent_text));
        clearBtn.setPadding(Ui.dp(act, 12), Ui.dp(act, 8), Ui.dp(act, 12), Ui.dp(act, 8));
        clearBtn.setClickable(true);
        clearBtn.setOnClickListener(v -> {
            if (Transfers.activeCount() > 0) Transfers.cancelAll();
            else Transfers.clearHistory();
            sig = "";
            tickNow();
        });
        top.addView(clearBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(act) {
            @Override
            protected void onMeasure(int w, int hSpec) {
                int cap = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // right under the header that holds the button
        View header = (View) button.getParent();
        int[] hl = new int[2];
        int[] cl = new int[2];
        header.getLocationInWindow(hl);
        content.getLocationInWindow(cl);
        int topMargin = hl[1] + header.getHeight() - cl[1] - content.getPaddingTop() + Ui.dp(act, 4);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        lp.setMargins(Ui.dp(act, 14), Math.max(0, topMargin), Ui.dp(act, 14), 0);
        panel.addView(card, lp);
        content.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        if (backCb != null) backCb.setEnabled(true);
        sig = "";
        refreshPanel(Transfers.snapshot(), Transfers.history());

        if (Motion.enabled()) {
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(Motion.base(act)).start();
            card.setAlpha(0f);
            card.setTranslationY(-Ui.dp(act, 12));
            card.animate().alpha(1f).translationY(0f).setDuration(Motion.base(act))
                    .setInterpolator(Motion.enter()).start();
        }
    }

    private void closePanel(boolean animate) {
        if (backCb != null) backCb.setEnabled(false);
        final FrameLayout p = panel;
        if (p == null) return;
        panel = null;
        card = null;
        list = null;
        clearBtn = null;
        items.clear();
        sig = "";
        if (animate && Motion.enabled() && p.getParent() != null) {
            p.animate().alpha(0f).setDuration(Motion.fast(act)).setInterpolator(Motion.standard())
                    .withEndAction(() -> {
                        ViewGroup parent = (ViewGroup) p.getParent();
                        if (parent != null) parent.removeView(p);
                    }).start();
        } else {
            ViewGroup parent = (ViewGroup) p.getParent();
            if (parent != null) parent.removeView(p);
        }
    }

    private static String signature(List<Transfers.Job> run, List<Transfers.Job> hist) {
        StringBuilder sb = new StringBuilder();
        for (Transfers.Job j : run) sb.append('r').append(j.id).append(',');
        for (Transfers.Job j : hist) sb.append('h').append(j.id).append(',');
        return sb.toString();
    }

    private void refreshPanel(List<Transfers.Job> run, List<Transfers.Job> hist) {
        if (panel == null || list == null) return;
        String now = signature(run, hist);
        if (!now.equals(sig)) {
            sig = now;
            list.removeAllViews();
            items.clear();
            if (run.isEmpty() && hist.isEmpty()) {
                TextView none = new TextView(act);
                none.setText(R.string.tr_none);
                none.setTextSize(14);
                none.setGravity(Gravity.CENTER);
                none.setTextColor(Ui.color(act, R.color.text_hint));
                none.setPadding(Ui.dp(act, 16), Ui.dp(act, 24), Ui.dp(act, 16), Ui.dp(act, 24));
                list.addView(none);
            }
            if (!run.isEmpty()) {
                list.addView(sectionLabel(R.string.tr_running_section));
                for (Transfers.Job j : run) {
                    Item it = new Item(j);
                    items.put(j.id, it);
                    list.addView(it.root);
                }
            }
            if (!hist.isEmpty()) {
                list.addView(sectionLabel(R.string.tr_recent_section));
                for (int i = 0; i < hist.size(); i++) {
                    if (i > 0) list.addView(divider());
                    list.addView(resultRow(hist.get(i)));
                }
            }
            if (clearBtn != null) {
                clearBtn.setText(run.isEmpty() ? R.string.tr_clear : R.string.tr_cancel_all);
                clearBtn.setVisibility(run.isEmpty() && hist.isEmpty() ? View.GONE : View.VISIBLE);
            }
        }
        for (Item it : new ArrayList<>(items.values())) it.bind();
    }
}

package com.ghmanager.app;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TransfersActivity extends BaseRepoActivity {
    private static final long TICK_MS = 400;

    private LinearLayout content;
    private LinearLayout list;
    private TextView summary;
    private String signature = "";
    private final Map<Integer, Holder> holders = new HashMap<>();
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ticker.postDelayed(this, TICK_MS);
        }
    };

    private static final class Holder {
        Transfers.Job job;
        TextView sub;
        ProgressBar bar;
        ImageButton pause;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        bindHeader(getString(R.string.tq_title), getString(R.string.tq_sub));
        btnRefresh.setVisibility(View.GONE);
        content = findViewById(R.id.content);
        buildTop();
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ticker.removeCallbacks(tick);
        ticker.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(tick);
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(Ui.dp(this, 8));
        return lp;
    }

    private void buildTop() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = Ui.dp(this, 18);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 6));
        card.setLayoutParams(clp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_download);
        ic.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.accent_text)));
        head.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
        summary = new TextView(this);
        summary.setTextColor(Ui.color(this, R.color.text_primary));
        summary.setTextSize(16);
        summary.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        summary.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.setMarginStart(Ui.dp(this, 12));
        head.addView(summary, slp);
        card.addView(head);

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.setPadding(0, Ui.dp(this, 14), 0, 0);
        android.widget.Button pauseAll = Ui.button(this, R.string.tq_pause_all, false);
        pauseAll.setOnClickListener(v -> {
            Transfers.pauseAll();
            refresh();
        });
        android.widget.Button resumeAll = Ui.button(this, R.string.tq_resume_all, true);
        resumeAll.setOnClickListener(v -> {
            Transfers.resumeAll();
            refresh();
        });
        r1.addView(pauseAll, rowLp());
        r1.addView(resumeAll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(r1);

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.setPadding(0, Ui.dp(this, 8), 0, 0);
        android.widget.Button retry = Ui.button(this, R.string.tr_retry_failed, false);
        retry.setOnClickListener(v -> {
            int n = Transfers.retryFailed();
            toast(getString(R.string.tq_retried, n));
            refresh();
        });
        android.widget.Button cancel = Ui.button(this, R.string.tr_cancel_all, false);
        cancel.setOnClickListener(v -> confirm(getString(R.string.tr_cancel_all), getString(R.string.tq_cancel_msg),
                R.string.tr_cancel_all, () -> {
                    Transfers.cancelAll();
                    refresh();
                }));
        r2.addView(retry, rowLp());
        r2.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(r2);
        content.addView(card);

        final Ui.Toggle wifi = Ui.toggle(this, content, R.string.st_wifi_only, R.string.st_wifi_only_sub,
                Store.wifiOnly(this));
        wifi.onChange((b, on) -> Store.setWifiOnly(this, on));
        content.addView(wifi.view);
    }

    private String sigOf(List<Transfers.Job> live, List<Transfers.Job> done) {
        StringBuilder sb = new StringBuilder();
        for (Transfers.Job j : live) {
            sb.append(j.id).append(j.started ? 's' : 'q').append(j.isPaused() ? 'p' : 'r').append(';');
        }
        sb.append('|');
        for (Transfers.Job j : done) sb.append(j.id).append(j.state).append(';');
        return sb.toString();
    }

    private void refresh() {
        if (list == null) return;
        List<Transfers.Job> live = Transfers.snapshot();
        List<Transfers.Job> done = Transfers.history();
        int running = 0;
        int queued = 0;
        for (Transfers.Job j : live) {
            if (j.started) running++;
            else queued++;
        }
        summary.setText(getString(R.string.tq_summary, running, queued, done.size()));
        String sig = sigOf(live, done);
        if (!sig.equals(signature)) {
            signature = sig;
            rebuild(live, done);
        }
        for (Holder h : holders.values()) update(h);
    }

    private void rebuild(List<Transfers.Job> live, List<Transfers.Job> done) {
        holders.clear();
        list.removeAllViews();
        List<Transfers.Job> running = new ArrayList<>();
        List<Transfers.Job> queued = new ArrayList<>();
        for (Transfers.Job j : live) (j.started ? running : queued).add(j);
        if (!running.isEmpty()) {
            list.addView(Ui.sectionTitle(this, getString(R.string.tq_running)));
            for (Transfers.Job j : running) list.addView(liveCard(j));
        }
        if (!queued.isEmpty()) {
            list.addView(Ui.sectionTitle(this, getString(R.string.tq_queued)));
            for (Transfers.Job j : queued) list.addView(liveCard(j));
        }
        if (!done.isEmpty()) {
            list.addView(Ui.sectionTitle(this, getString(R.string.tq_finished)));
            for (Transfers.Job j : done) list.addView(doneCard(j));
            TextView clear = Ui.chip(this, getString(R.string.tr_clear), false);
            clear.setOnClickListener(v -> {
                Transfers.clearHistory();
                refresh();
            });
            LinearLayout wrap = new LinearLayout(this);
            wrap.setPaddingRelative(Ui.dp(this, 22), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 20));
            wrap.addView(clear);
            list.addView(wrap);
        }
        if (running.isEmpty() && queued.isEmpty() && done.isEmpty()) {
            TextView e = Ui.body(this, getString(R.string.tq_empty), 15, R.color.text_hint);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, Ui.dp(this, 48), 0, 0);
            Fx.emptyState(e, R.drawable.ic_inbox);
            list.addView(e);
            Fx.reveal(e);
        }
        Fx.stagger(list);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        int pad = Ui.dp(this, 16);
        card.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4));
        card.setLayoutParams(lp);
        return card;
    }

    private ImageButton iconBtn(int icon, int desc, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, R.color.text_primary)));
        b.setBackgroundResource(R.drawable.bg_icon_btn);
        b.setContentDescription(getString(desc));
        b.setOnClickListener(l);
        Ui.press(this, b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
        lp.setMarginStart(Ui.dp(this, 6));
        b.setLayoutParams(lp);
        return b;
    }

    private View liveCard(final Transfers.Job job) {
        LinearLayout card = card();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(job.title);
        title.setTextColor(Ui.color(this, R.color.text_primary));
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        texts.addView(title);
        TextView sub = new TextView(this);
        sub.setTextColor(Ui.color(this, R.color.text_secondary));
        sub.setTextSize(12);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        texts.addView(sub);
        top.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Holder h = new Holder();
        h.job = job;
        h.sub = sub;
        h.pause = iconBtn(R.drawable.ic_pause, R.string.tr_pause, v -> {
            if (job.isPaused()) job.resume();
            else job.pause();
            refresh();
        });
        top.addView(h.pause);
        top.addView(iconBtn(R.drawable.ic_cancel, R.string.cancel, v -> {
            job.cancel();
            refresh();
        }));
        card.addView(top);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        Ui.tint(this, bar);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        blp.topMargin = Ui.dp(this, 12);
        card.addView(bar, blp);
        h.bar = bar;
        holders.put(job.id, h);
        update(h);
        return card;
    }

    private void update(Holder h) {
        Transfers.Job j = h.job;
        if (!j.started) {
            h.sub.setText(getString(R.string.tq_waiting));
            h.bar.setIndeterminate(false);
            h.bar.setProgress(0);
            h.pause.setVisibility(View.GONE);
            return;
        }
        h.pause.setVisibility(View.VISIBLE);
        h.pause.setImageResource(j.isPaused() ? R.drawable.ic_play : R.drawable.ic_pause);
        h.pause.setContentDescription(getString(j.isPaused() ? R.string.tr_resume : R.string.tr_pause));
        String t = j.text();
        String d = j.detail();
        h.sub.setText(t.isEmpty() ? d : (d.isEmpty() ? t : t + "\n" + d));
        h.sub.setSingleLine(false);
        h.sub.setMaxLines(3);
        float f = j.fraction();
        if (f < 0) {
            h.bar.setIndeterminate(true);
        } else {
            h.bar.setIndeterminate(false);
            Ui.progress(h.bar, (int) (f * 1000));
        }
    }

    private View doneCard(final Transfers.Job job) {
        LinearLayout card = card();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        int color;
        int icon;
        if (job.state == 1) {
            color = Ui.color(this, R.color.ok);
            icon = R.drawable.ic_check_circle;
        } else if (job.state == 3) {
            color = Ui.color(this, R.color.text_secondary);
            icon = R.drawable.ic_cancel;
        } else {
            color = Ui.color(this, R.color.bad);
            icon = R.drawable.ic_cancel;
        }
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor((color & 0x00FFFFFF) | 0x24000000);
        iv.setBackground(g);
        iv.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        top.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(job.title);
        title.setTextColor(Ui.color(this, R.color.text_primary));
        title.setTextSize(15);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        texts.addView(title);
        String msg = job.message == null ? "" : job.message;
        int nl = msg.indexOf('\n');
        if (nl > 0) msg = msg.substring(0, nl);
        TextView sub = new TextView(this);
        sub.setText(msg.isEmpty() ? Fmt.agoMs(job.endedAt) : msg);
        sub.setTextColor(Ui.color(this, R.color.text_secondary));
        sub.setTextSize(12);
        sub.setMaxLines(2);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        texts.addView(sub);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(Ui.dp(this, 12));
        top.addView(texts, tlp);
        if (job.state != 1) {
            top.addView(iconBtn(R.drawable.ic_refresh, R.string.tq_retry, v -> {
                Transfers.retry(job);
                refresh();
            }));
        } else if (job.openIntent != null) {
            top.addView(iconBtn(R.drawable.ic_open, R.string.tq_open, v -> {
                try {
                    startActivity(job.openIntent);
                } catch (Exception e) {
                    toast(R.string.nothing_here);
                }
            }));
        }
        card.addView(top);
        return card;
    }
}

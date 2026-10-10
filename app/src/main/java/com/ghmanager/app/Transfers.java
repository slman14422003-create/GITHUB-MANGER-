package com.ghmanager.app;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Long running work (uploads, downloads, app updates) lives here, NOT inside an Activity. A
 * foreground service ({@link TransferService}) keeps the process alive, so leaving the app or
 * closing a screen never stops a transfer. Progress is shown in a notification.
 */
public final class Transfers {
    private Transfers() {
    }

    /** Thrown inside a task when the user cancelled it. */
    public static final class Cancelled extends RuntimeException {
        public Cancelled() {
            super("cancelled");
        }
    }

    public interface Task {
        void run(Job job) throws Exception;
    }

    /** Called on the main thread when a job ends. Must not capture an Activity strongly. */
    public interface Done {
        void on(Job job, boolean ok, String message);
    }

    public interface Listener {
        void onFinished(Job job);
    }

    /** Opens a (possibly redirected) download connection; used by download tasks. */
    public interface Src {
        HttpURLConnection open() throws Exception;
    }

    public static final class Job {
        public final int id;
        public final String title;
        /** e.g. "upload:owner/repo" - lets a screen know which jobs concern it. */
        public final String tag;
        public final Context app;
        volatile String text = "";
        volatile long done;
        volatile long total;
        volatile boolean cancelled;
        /** done/total are byte counts (otherwise item counts). */
        volatile boolean bytes;
        /** Smoothed transfer speed in bytes per second. */
        volatile float speed;
        private long sampleT;
        private long sampleD;
        /** 0 running, 1 finished ok, 2 failed, 3 cancelled. */
        public volatile int state;
        /** Result text shown in the final notification. */
        public volatile String message = "";
        /** What a tap on the final notification opens (a folder, the installer ...). */
        public volatile Intent openIntent;

        Job(Context app, int id, String title, String tag) {
            this.app = app;
            this.id = id;
            this.title = title;
            this.tag = tag == null ? "" : tag;
        }

        /** Progress counted in items (files uploaded ...). */
        public void progress(String t, long d, long tot) {
            update(t, d, tot, false);
        }

        /** Progress counted in bytes: also feeds the speed and the time left. */
        public void progressBytes(String t, long d, long tot) {
            update(t, d, tot, true);
        }

        private void update(String t, long d, long tot, boolean isBytes) {
            text = t == null ? "" : t;
            bytes = isBytes;
            if (isBytes) {
                long now = SystemClock.elapsedRealtime();
                if (sampleT == 0 || d < sampleD) {
                    sampleT = now;
                    sampleD = d;
                } else if (now - sampleT >= 500) {
                    float inst = (d - sampleD) * 1000f / (now - sampleT);
                    speed = speed <= 0f ? inst : speed * 0.6f + inst * 0.4f;
                    sampleT = now;
                    sampleD = d;
                }
            }
            done = d;
            total = tot;
            TransferService.refresh();
        }

        /** 0..1, or -1 while the size is not known yet. */
        public float fraction() {
            long t = total;
            return t > 0 ? Math.max(0f, Math.min(1f, done / (float) t)) : -1f;
        }

        public String text() {
            return text;
        }

        public void cancel() {
            cancelled = true;
        }

        /** "42%  ·  12 MB / 29 MB  ·  1.2 MB/s  ·  14s left" (items instead of bytes for uploads). */
        public String detail() {
            long d = done;
            long t = total;
            StringBuilder sb = new StringBuilder();
            if (t > 0) sb.append(Math.min(100, d * 100 / t)).append('%');
            if (bytes) {
                if (sb.length() > 0) sb.append("  ·  ");
                sb.append(Fmt.size(d));
                if (t > 0) sb.append(" / ").append(Fmt.size(t));
                float sp = speed;
                if (sp > 0f) {
                    sb.append("  ·  ").append(Fmt.size((long) sp)).append("/s");
                    if (t > d) {
                        sb.append("  ·  ").append(app.getString(R.string.tr_eta,
                                LiveBoard.clock((long) ((t - d) / sp * 1000f))));
                    }
                }
            } else if (t > 0) {
                sb.append("  ·  ").append(d).append('/').append(t);
            }
            return sb.toString();
        }

        public boolean cancelled() {
            return cancelled;
        }

        public void check() {
            if (cancelled) throw new Cancelled();
        }
    }

    private static final AtomicInteger NEXT = new AtomicInteger(1);
    private static final AtomicInteger FINISHED = new AtomicInteger(0);
    private static final CopyOnWriteArrayList<Job> JOBS = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static Job start(Context c, String title, String tag, Task task, Done done) {
        final Context app = c.getApplicationContext();
        final Job job = new Job(app, NEXT.getAndIncrement(), title, tag);
        JOBS.add(job);
        TransferService.begin(app);
        POOL.execute(() -> {
            if (job.cancelled) {
                finish(job, false, "", true, done);
                return;
            }
            TransferService.refresh();
            try {
                task.run(job);
                finish(job, !job.cancelled, job.message, job.cancelled, done);
            } catch (Throwable e) {
                boolean cancel = job.cancelled || e instanceof Cancelled;
                String m = e.getMessage();
                finish(job, false, m == null || m.isEmpty() ? e.toString() : m, cancel, done);
            }
        });
        return job;
    }

    private static void finish(final Job job, final boolean ok, final String msg, final boolean cancelled,
                               final Done done) {
        JOBS.remove(job);
        FINISHED.incrementAndGet();
        job.message = msg == null ? "" : msg;
        job.state = cancelled ? 3 : ok ? 1 : 2;
        TransferService.finished(job.app, job, ok, cancelled);
        MAIN.post(() -> {
            try {
                if (done != null) done.on(job, ok, job.message);
            } catch (Throwable ignored) {
            }
            for (Listener l : LISTENERS) {
                try {
                    l.onFinished(job);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public static void cancelAll() {
        for (Job j : JOBS) j.cancelled = true;
    }

    public static List<Job> snapshot() {
        return new ArrayList<>(JOBS);
    }

    public static int activeCount() {
        return JOBS.size();
    }

    /** Grows every time a job ends; a screen compares it with the value it saw to know it must reload. */
    public static int finishedCount() {
        return FINISHED.get();
    }

    public static void addListener(Listener l) {
        if (!LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }
}

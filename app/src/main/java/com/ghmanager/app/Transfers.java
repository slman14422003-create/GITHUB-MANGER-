package com.ghmanager.app;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

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

        public void progress(String t, long d, long tot) {
            text = t == null ? "" : t;
            done = d;
            total = tot;
            TransferService.refresh();
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

package com.ghmanager.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class Verify {
    private Verify() {
    }

    static final String DELETE = "delete";
    static final String FORCE = "force";
    static final String SECRETS = "secrets";
    static final String COLLAB = "collab";
    static final String PROTECTION = "protection";
    static final String MERGE = "merge";
    static final String ACTIONS = "actions";
    static final String REPO = "repo";
    static final String[] ALL = {DELETE, FORCE, SECRETS, COLLAB, PROTECTION, MERGE, ACTIONS, REPO};
    static final int[] TRUST_OPTIONS = {0, 60, 120, 300, 900, 1800};
    static final int DEFAULT_TRUST = 120;

    private static final String KEY = "verify_sensitive";
    private static final String K_OPS = "ver_ops";
    private static final String K_TRUST = "ver_trust";
    private static volatile Context app;
    private static volatile long trustedUntil;
    private static volatile long refusedUntil;
    private static volatile int failStreak;
    private static volatile CountDownLatch latch;
    private static volatile long seq;
    private static volatile boolean passed;

    static void init(Context c) {
        app = c.getApplicationContext();
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("gh", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return sp(c).getBoolean(KEY, false);
    }

    static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY, on).apply();
        trustedUntil = 0;
    }

    static Set<String> ops(Context c) {
        Set<String> v = sp(c).getStringSet(K_OPS, null);
        Set<String> out = new HashSet<>();
        if (v == null) {
            for (String s : ALL) out.add(s);
        } else {
            for (String s : v) out.add(s);
        }
        return out;
    }

    static void setOps(Context c, Set<String> ops) {
        sp(c).edit().putStringSet(K_OPS, new HashSet<>(ops)).apply();
        trustedUntil = 0;
    }

    static int trustSeconds(Context c) {
        return Math.max(0, sp(c).getInt(K_TRUST, DEFAULT_TRUST));
    }

    static void setTrustSeconds(Context c, int s) {
        sp(c).edit().putInt(K_TRUST, Math.max(0, s)).apply();
        trustedUntil = 0;
    }

    static boolean required(Context c, String category) {
        return enabled(c) && (category == null || ops(c).contains(category));
    }

    static String category(String method, String path, JSONObject body) {
        if (method == null || path == null) return null;
        if (method.equals("DELETE")) {
            if (path.startsWith("/user/starred/")) return null;
            if (path.contains("/actions/secrets/")) return SECRETS;
            if (path.contains("/collaborators/")) return COLLAB;
            if (path.endsWith("/protection")) return PROTECTION;
            return DELETE;
        }
        if (method.equals("PATCH")) {
            if (path.matches("/repos/[^/]+/[^/]+")) return REPO;
            if (path.contains("/git/refs/") && body != null && body.optBoolean("force")) return FORCE;
            return null;
        }
        if (method.equals("PUT")) {
            if (path.contains("/actions/secrets/")) return SECRETS;
            if (path.contains("/collaborators/")) return COLLAB;
            if (path.endsWith("/protection")) return PROTECTION;
            if (path.endsWith("/merge")) return MERGE;
            if (path.contains("/actions/permissions")) return ACTIONS;
        }
        return null;
    }

    static boolean sensitive(String method, String path, JSONObject body) {
        return category(method, path, body) != null;
    }

    static synchronized void require(String category) throws IOException {
        Context c = Verify.app;
        if (c == null || !required(c, category)) return;
        run(c, false);
    }

    static synchronized void requireFresh() throws IOException {
        Context c = Verify.app;
        if (c == null || !enabled(c)) return;
        run(c, true);
    }

    private static void run(Context c, boolean fresh) throws IOException {
        long now = SystemClock.elapsedRealtime();
        if (!fresh && now < trustedUntil) return;
        if (now < refusedUntil || Looper.myLooper() == Looper.getMainLooper()) throw refused(c);
        passed = false;
        final long tok = ++seq;
        final CountDownLatch mine = new CountDownLatch(1);
        latch = mine;
        try {
            c.startActivity(new Intent(c, VerifyActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(VerifyActivity.EXTRA_TOKEN, tok));
            if (!mine.await(2, TimeUnit.MINUTES)) passed = false;
        } catch (InterruptedException e) {
            passed = false;
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            passed = false;
        }
        boolean ok = passed;
        seq++;
        latch = null;
        passed = false;
        if (!ok) {
            failStreak++;
            long wait = Math.min(600_000L, 10_000L << Math.min(failStreak - 1, 6));
            refusedUntil = SystemClock.elapsedRealtime() + wait;
            throw refused(c);
        }
        failStreak = 0;
        refusedUntil = 0;
        trustedUntil = SystemClock.elapsedRealtime() + trustSeconds(c) * 1000L;
    }

    private static IOException refused(Context c) {
        return new IOException(c.getString(R.string.verify_failed));
    }

    static void finish(long tok, boolean ok) {
        CountDownLatch l = latch;
        if (l == null || tok != seq || l.getCount() == 0) return;
        passed = ok;
        l.countDown();
    }
}

package com.ghmanager.app;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * Optional app lock: when it is on, the app asks for the phone's own screen lock (fingerprint, face,
 * PIN, pattern or password) on a cold start and after the app stayed in the background longer than
 * the chosen delay. Nothing in the app is reachable until the phone unlocks it.
 */
public final class AppLock {
    private AppLock() {
    }

    private static final String K_ON = "upd_lock_on";
    private static final String K_DELAY = "upd_lock_delay";

    /** true while the lock screen has not been passed yet in this process / background period. */
    private static volatile boolean locked = true;
    private static long leftAt = 0;
    private static int started = 0;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("gh", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return sp(c).getBoolean(K_ON, false);
    }

    public static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(K_ON, on).apply();
        locked = false;
    }

    /** Seconds the app may stay in the background before it locks again (0 = every time). */
    public static int delaySeconds(Context c) {
        return sp(c).getInt(K_DELAY, 30);
    }

    public static void setDelaySeconds(Context c, int s) {
        sp(c).edit().putInt(K_DELAY, s).apply();
    }

    /** True when the phone has a screen lock, which the app lock needs. */
    public static boolean available(Context c) {
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isDeviceSecure();
    }

    static void unlocked() {
        locked = false;
    }

    static boolean isLocked() {
        return locked;
    }

    /** Called by App for every activity that starts / stops. */
    static void onStarted(android.app.Activity a) {
        if (started == 0 && leftAt != 0) {
            long away = SystemClock.elapsedRealtime() - leftAt;
            if (away > delaySeconds(a) * 1000L) locked = true;
        }
        started++;
        if (a instanceof LockActivity) return;
        if (enabled(a) && locked && available(a)) {
            Intent i = new Intent(a, LockActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            a.startActivity(i);
        }
    }

    static void onStopped() {
        started = Math.max(0, started - 1);
        if (started == 0) leftAt = SystemClock.elapsedRealtime();
    }
}

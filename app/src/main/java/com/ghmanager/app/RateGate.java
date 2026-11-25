package com.ghmanager.app;

final class RateGate {
    private long until = 0;

    synchronized void block(long waitMs, long now) {
        if (waitMs <= 0) return;
        long u = now + waitMs;
        if (u > until) until = u;
    }

    synchronized boolean blocked(long now) {
        return now < until;
    }

    synchronized long remainingMs(long now) {
        return Math.max(0, until - now);
    }

    synchronized int seconds(long now) {
        long r = remainingMs(now);
        return (int) ((r + 999) / 1000);
    }

    synchronized void clear() {
        until = 0;
    }

    static boolean isRateLimited(int code, long waitMs, String message) {
        if (waitMs > 0) return true;
        if (code != 403 && code != 429) return false;
        String m = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        return code == 429 || m.contains("rate limit") || m.contains("abuse") || m.contains("secondary");
    }

    static long fallbackWaitMs(long waitMs) {
        return waitMs > 0 ? waitMs : 60000L;
    }
}

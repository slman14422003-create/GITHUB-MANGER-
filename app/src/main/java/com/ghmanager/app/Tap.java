package com.ghmanager.app;

import android.os.SystemClock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards against a fast double tap firing the same kind of action twice — two activities pushed
 * from one row, a delete sent twice, a dialog button submitted twice. Guards are kept per "kind"
 * (not one global clock) so that, say, a dialog button whose own handler opens a new screen is not
 * blocked by the tap that confirmed the dialog: the two are different kinds of action.
 */
final class Tap {
    private Tap() {
    }

    private static final long WINDOW_MS = 500;
    private static final ConcurrentHashMap<String, Long> last = new ConcurrentHashMap<>();

    /** True at most once per {@link #WINDOW_MS} for this kind; call at the start of the handler. */
    static boolean ok(String kind) {
        long now = SystemClock.elapsedRealtime();
        Long prev = last.put(kind, now);
        return prev == null || now - prev >= WINDOW_MS;
    }

    /** Default kind, for simple one-off call sites that do not nest another guarded action. */
    static boolean ok() {
        return ok("default");
    }
}

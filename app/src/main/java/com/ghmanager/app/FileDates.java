package com.ghmanager.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

final class FileDates {
    private FileDates() {
    }

    interface Listener {
        void onDate(String path, long millis);

        void onDone();

        boolean cancelled();
    }

    private static final Map<String, Long> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    private static String key(String o, String r, String b, String p) {
        return o + "/" + r + "@" + b + ":" + p;
    }

    static long cached(String o, String r, String b, String p) {
        Long v = CACHE.get(key(o, r, b, p));
        return v == null ? 0L : v;
    }

    static void invalidate() {
        CACHE.clear();
    }

    static void load(final GitHubApi api, final String o, final String r, final String b, List<String> paths,
                     final Listener l) {
        final AtomicInteger left = new AtomicInteger(paths.size());
        if (paths.isEmpty()) {
            l.onDone();
            return;
        }
        for (final String p : paths) {
            POOL.execute(() -> {
                try {
                    if (l.cancelled()) return;
                    long have = cached(o, r, b, p);
                    if (have == 0) {
                        JSONArray a = api.listCommitsPath(o, r, b, p, 1, 1);
                        JSONObject first = a.length() > 0 ? a.optJSONObject(0) : null;
                        JSONObject cm = first == null ? null : first.optJSONObject("commit");
                        JSONObject who = cm == null ? null : cm.optJSONObject("committer");
                        if (who == null && cm != null) who = cm.optJSONObject("author");
                        have = who == null ? 0 : Fmt.parse(Fmt.s(who, "date"));
                        if (have > 0) CACHE.put(key(o, r, b, p), have);
                    }
                    if (have > 0) l.onDate(p, have);
                } catch (Exception ignored) {
                } finally {
                    if (left.decrementAndGet() == 0) l.onDone();
                }
            });
        }
    }
}

package com.ghmanager.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lazily loaded, cached per-repository extras for the list: 12-week commit activity and the last Actions run. */
final class RepoStats {
    private RepoStats() {
    }

    static final class Info {
        int[] weeks;
        long weeksAt;
        String run;
        long runAt;
        long failAt;
    }

    interface Callback {
        void done(Info info);
    }

    private static final long WEEKS_TTL = 6L * 3600 * 1000;
    private static final long RUN_TTL = 5L * 60 * 1000;
    private static final long FAIL_TTL = 2L * 60 * 1000;
    private static final int KEEP = 400;
    private static final Map<String, Info> MEM = new HashMap<>();
    private static final Set<String> BUSY = new HashSet<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("gh_repo_stats", Context.MODE_PRIVATE);
    }

    private static String key(Context c, String fn) {
        return Accounts.activeId(c) + "|" + fn;
    }

    static Info cached(Context c, String fn) {
        String k = key(c, fn);
        synchronized (MEM) {
            Info i = MEM.get(k);
            if (i != null) return i;
        }
        try {
            String s = sp(c).getString(k, null);
            if (s == null) return null;
            JSONObject j = new JSONObject(s);
            Info i = new Info();
            JSONArray w = j.optJSONArray("w");
            if (w != null && w.length() > 0) {
                i.weeks = new int[w.length()];
                for (int x = 0; x < w.length(); x++) i.weeks[x] = w.optInt(x);
            }
            i.weeksAt = j.optLong("wa");
            i.run = j.has("r") ? j.optString("r") : null;
            i.runAt = j.optLong("ra");
            synchronized (MEM) {
                MEM.put(k, i);
            }
            return i;
        } catch (Exception e) {
            return null;
        }
    }

    static boolean stale(Info i) {
        if (i == null) return true;
        long now = System.currentTimeMillis();
        if (now - i.failAt < FAIL_TTL) return false;
        return now - i.weeksAt > WEEKS_TTL || now - i.runAt > RUN_TTL;
    }

    private static String state(JSONObject r) {
        if (!"completed".equals(r.optString("status"))) return "active";
        String c = r.optString("conclusion");
        if ("success".equals(c)) return "success";
        if ("failure".equals(c) || "timed_out".equals(c) || "startup_failure".equals(c)) return "failure";
        return "neutral";
    }

    private static void persist(Context c, String k, Info i) {
        try {
            JSONObject j = new JSONObject();
            if (i.weeks != null) {
                JSONArray w = new JSONArray();
                for (int v : i.weeks) w.put(v);
                j.put("w", w);
            }
            j.put("wa", i.weeksAt);
            if (i.run != null) j.put("r", i.run);
            j.put("ra", i.runAt);
            SharedPreferences p = sp(c);
            SharedPreferences.Editor ed = p.edit();
            Map<String, ?> all = p.getAll();
            if (all.size() > KEEP) {
                int drop = all.size() - KEEP + 40;
                for (String old : all.keySet()) {
                    if (drop-- <= 0) break;
                    ed.remove(old);
                }
            }
            ed.putString(k, j.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static void load(Context c, final String owner, final String name, final Handler ui, final Callback cb) {
        final Context app = c.getApplicationContext();
        final String fn = owner + "/" + name;
        final String k = key(app, fn);
        synchronized (BUSY) {
            if (!BUSY.add(k)) return;
        }
        POOL.execute(() -> {
            try {
                GitHubApi api = new GitHubApi(app);
                Info cur = cached(app, fn);
                Info n = new Info();
                if (cur != null) {
                    n.weeks = cur.weeks;
                    n.weeksAt = cur.weeksAt;
                    n.run = cur.run;
                    n.runAt = cur.runAt;
                }
                long now = System.currentTimeMillis();
                boolean failed = false;
                if (cur == null || now - cur.weeksAt > WEEKS_TTL) {
                    try {
                        String body = api.call("GET", GitHubApi.repo(owner, name) + "/stats/participation", null);
                        JSONArray all = new JSONObject(body).optJSONArray("all");
                        if (all != null && all.length() > 0) {
                            int[] w = new int[12];
                            for (int x = 0; x < 12; x++) {
                                int src = all.length() - 12 + x;
                                w[x] = src >= 0 ? all.optInt(src) : 0;
                            }
                            n.weeks = w;
                            n.weeksAt = now;
                        } else {
                            failed = true;
                        }
                    } catch (Exception e) {
                        failed = true;
                    }
                }
                if (cur == null || now - cur.runAt > RUN_TTL) {
                    try {
                        JSONObject r = api.listRuns(owner, name, 0, null, null, 1, 1);
                        JSONArray runs = r.optJSONArray("workflow_runs");
                        n.run = runs == null || runs.length() == 0 ? "none" : state(runs.getJSONObject(0));
                        n.runAt = now;
                    } catch (Exception e) {
                        failed = true;
                    }
                }
                if (failed) n.failAt = now;
                synchronized (MEM) {
                    MEM.put(k, n);
                }
                persist(app, k, n);
                final Info result = n;
                ui.post(() -> cb.done(result));
            } finally {
                synchronized (BUSY) {
                    BUSY.remove(k);
                }
            }
        });
    }
}

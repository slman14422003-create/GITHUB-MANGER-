package com.ghmanager.app;

import android.content.Context;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole file tree of a branch, fetched with ONE request and kept in memory and on disk. Opening
 * a folder then costs nothing: the list is built locally, and a tiny conditional request (HTTP 304
 * when nothing changed) checks in the background whether the repository moved on.
 */
public final class RepoTree {
    private RepoTree() {
    }

    /** A snapshot is trusted without asking GitHub again for this long (folder-to-folder navigation). */
    private static final long FRESH_MS = 15_000L;

    public static final class Snapshot {
        final String etag;
        final boolean truncated;
        final JSONArray raw;   // [[path, type, sha, size], ...] kept only to write the disk copy
        final Map<String, List<JSONObject>> dirs;
        volatile long checkedAt;

        Snapshot(String etag, boolean truncated, JSONArray raw, Map<String, List<JSONObject>> dirs) {
            this.etag = etag == null ? "" : etag;
            this.truncated = truncated;
            this.raw = raw;
            this.dirs = dirs;
            this.checkedAt = System.currentTimeMillis();
        }

        /** Children of a folder ("" = root), folders first, same shape as the GitHub contents API. */
        public List<JSONObject> list(String path) {
            List<JSONObject> l = dirs.get(path);
            return l == null ? new ArrayList<JSONObject>() : new ArrayList<>(l);
        }
    }

    private static final LruCache<String, Snapshot> MEM = new LruCache<>(4);

    // ------------------------------------------------------------------ keys / disk

    private static String key(Context c, String o, String r, String b) {
        String acct = "";
        try {
            String t = Store.getToken(c);
            if (t != null) {
                byte[] d = MessageDigest.getInstance("SHA-256").digest(t.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
                acct = sb.toString();
            }
        } catch (Exception ignored) {
        }
        return acct + "|" + o + "/" + r + "@" + b;
    }

    private static File file(Context c, String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return new File(new File(c.getCacheDir(), "tree"), sb + ".json");
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ build

    private static Snapshot build(String etag, boolean truncated, JSONArray raw) throws Exception {
        Map<String, List<JSONObject>> dirs = new HashMap<>();
        dirs.put("", new ArrayList<JSONObject>());
        for (int i = 0; i < raw.length(); i++) {
            JSONArray e = raw.getJSONArray(i);
            String path = e.getString(0);
            String type = e.getString(1);
            int slash = path.lastIndexOf('/');
            String parent = slash < 0 ? "" : path.substring(0, slash);
            String name = slash < 0 ? path : path.substring(slash + 1);
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("path", path);
            boolean dir = "tree".equals(type);
            o.put("type", dir ? "dir" : "file");
            o.put("sha", e.optString(2, ""));
            o.put("size", e.optLong(3, 0));
            List<JSONObject> l = dirs.get(parent);
            if (l == null) {
                l = new ArrayList<>();
                dirs.put(parent, l);
            }
            l.add(o);
            if (dir && !dirs.containsKey(path)) dirs.put(path, new ArrayList<JSONObject>());
        }
        Comparator<JSONObject> cmp = new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject x, JSONObject y) {
                boolean dx = "dir".equals(x.optString("type"));
                boolean dy = "dir".equals(y.optString("type"));
                if (dx != dy) return dx ? -1 : 1;
                return x.optString("name").compareToIgnoreCase(y.optString("name"));
            }
        };
        for (List<JSONObject> l : dirs.values()) Collections.sort(l, cmp);
        return new Snapshot(etag, truncated, raw, dirs);
    }

    private static JSONArray compact(JSONArray tree) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < tree.length(); i++) {
            JSONObject e = tree.getJSONObject(i);
            String type = e.optString("type");
            if (!"blob".equals(type) && !"tree".equals(type) && !"commit".equals(type)) continue;
            JSONArray a = new JSONArray();
            a.put(e.getString("path"));
            a.put(type);
            a.put(e.optString("sha", ""));
            a.put(e.optLong("size", 0));
            out.put(a);
        }
        return out;
    }

    // ------------------------------------------------------------------ public API

    /** Last known tree of this branch (memory, then disk), or null. Never touches the network. */
    public static Snapshot cached(Context c, String o, String r, String b) {
        final String k = key(c, o, r, b);
        Snapshot s = MEM.get(k);
        if (s != null) return s;
        try {
            File f = file(c, k);
            if (f == null || !f.isFile()) return null;
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            JSONObject root = new JSONObject(new String(buf, StandardCharsets.UTF_8));
            s = build(root.optString("etag"), false, root.getJSONArray("t"));
            s.checkedAt = 0;   // came from disk: ask GitHub whether it is still current
            MEM.put(k, s);
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Brings the tree up to date. Returns {@code have} itself when nothing changed (or it was checked a
     * moment ago), otherwise the new snapshot. A truncated tree (huge repository) is returned but not
     * cached: the caller then falls back to per-folder listing.
     */
    public static Snapshot fetch(Context c, GitHubApi api, String o, String r, String b, Snapshot have) throws Exception {
        if (have != null && System.currentTimeMillis() - have.checkedAt < FRESH_MS) return have;
        String[] etagOut = new String[]{""};
        JSONObject root = api.treeRecursive(o, r, b, have == null ? null : have.etag, etagOut);
        if (root == null) {
            have.checkedAt = System.currentTimeMillis();
            return have;
        }
        boolean truncated = root.optBoolean("truncated", false);
        JSONArray raw = compact(root.getJSONArray("tree"));
        Snapshot s = build(etagOut[0], truncated, raw);
        if (!truncated) {
            final String k = key(c, o, r, b);
            MEM.put(k, s);
            save(c, k, s);
        }
        return s;
    }

    private static void save(Context c, String k, Snapshot s) {
        try {
            File f = file(c, k);
            if (f == null) return;
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            JSONObject root = new JSONObject();
            root.put("etag", s.etag);
            root.put("t", s.raw);
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(f)) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        } catch (Exception ignored) {
        }
    }

    /** Forget a branch's tree (call after any commit made by this app). */
    public static void invalidate(Context c, String o, String r, String b) {
        try {
            String k = key(c, o, r, b);
            MEM.remove(k);
            File f = file(c, k);
            if (f != null) //noinspection ResultOfMethodCallIgnored
                f.delete();
        } catch (Exception ignored) {
        }
    }

    /** Sign-out: nothing of the account may stay on disk or in memory. */
    public static void clearAll(Context c) {
        try {
            MEM.evictAll();
            File dir = new File(c.getCacheDir(), "tree");
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
                f.delete();
        } catch (Exception ignored) {
        }
    }

    /** True when both listings show the same entries (used to avoid redrawing for nothing). */
    public static boolean same(List<JSONObject> a, List<JSONObject> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).optString("path").equals(b.get(i).optString("path"))) return false;
            if (!a.get(i).optString("sha").equals(b.get(i).optString("sha"))) return false;
        }
        return true;
    }
}

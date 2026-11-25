package com.ghmanager.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class PendingReviews {
    private PendingReviews() {
    }

    public static final class Comment {
        public final String path;
        public final String side;
        public final int line;
        public String body;

        public Comment(String path, String side, int line, String body) {
            this.path = path;
            this.side = side;
            this.line = line;
            this.body = body;
        }

        public String key() {
            return PatchLines.key(path, side, line);
        }
    }

    public static final class Review {
        public String commitId = "";
        public String body = "";
        public final List<Comment> comments = new ArrayList<>();

        public boolean isEmpty() {
            return comments.isEmpty() && body.isEmpty();
        }

        public Comment find(String path, String side, int line) {
            String k = PatchLines.key(path, side, line);
            for (Comment c : comments) if (c.key().equals(k)) return c;
            return null;
        }

        public void upsert(Comment c) {
            Comment old = find(c.path, c.side, c.line);
            if (old != null) old.body = c.body;
            else comments.add(c);
        }

        public boolean remove(String path, String side, int line) {
            Comment old = find(path, side, line);
            return old != null && comments.remove(old);
        }

        public JSONArray apiComments() throws Exception {
            JSONArray a = new JSONArray();
            for (Comment c : comments) {
                JSONObject o = new JSONObject();
                o.put("path", c.path);
                o.put("line", c.line);
                o.put("side", c.side);
                o.put("body", c.body);
                a.put(o);
            }
            return a;
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("commitId", commitId);
            o.put("body", body);
            o.put("comments", apiComments());
            return o;
        }

        public static Review fromJson(JSONObject o) {
            Review r = new Review();
            if (o == null) return r;
            r.commitId = o.optString("commitId");
            r.body = o.optString("body");
            JSONArray a = o.optJSONArray("comments");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject c = a.optJSONObject(i);
                    if (c == null) continue;
                    String side = c.optString("side");
                    if (!"LEFT".equals(side)) side = "RIGHT";
                    int line = c.optInt("line");
                    String path = c.optString("path");
                    String body = c.optString("body");
                    if (path.isEmpty() || line <= 0 || body.isEmpty()) continue;
                    r.upsert(new Comment(path, side, line, body));
                }
            }
            return r;
        }
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "reviews");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static String key(String acc, String owner, String repo, long number) {
        return GitSha.sha1(acc + "\n" + owner + "\n" + repo + "\n" + number);
    }

    private static File file(Context c, String owner, String repo, long number) {
        return new File(dir(c), key(Accounts.activeId(c), owner, repo, number) + ".json");
    }

    public static synchronized Review load(Context c, String owner, String repo, long number) {
        File f = file(c, owner, repo, number);
        if (!f.isFile()) return new Review();
        try {
            return Review.fromJson(new JSONObject(Drafts.readAll(f)));
        } catch (Exception e) {
            f.delete();
            return new Review();
        }
    }

    public static synchronized void save(Context c, String owner, String repo, long number, Review r) {
        File f = file(c, owner, repo, number);
        if (r.isEmpty()) {
            f.delete();
            return;
        }
        try {
            Drafts.writeAtomic(f, r.toJson().toString());
        } catch (Exception ignored) {
        }
    }

    public static synchronized void clear(Context c, String owner, String repo, long number) {
        file(c, owner, repo, number).delete();
    }

    public static synchronized void clearAll(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs == null) return;
        for (File f : fs) f.delete();
    }
}

package com.ghmanager.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public final class FileMarks {
    private FileMarks() {
    }

    private static final int MAX_RECENT = 40;
    private static final int MAX_FAV = 200;

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("gh_marks", Context.MODE_PRIVATE);
    }

    private static String key(String kind, String owner, String repo) {
        return kind + ":" + owner + "/" + repo;
    }

    private static List<String> read(Context c, String key) {
        List<String> out = new ArrayList<>();
        String raw = sp(c).getString(key, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "");
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void write(Context c, String key, List<String> list) {
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        sp(c).edit().putString(key, a.toString()).apply();
    }

    public static List<String> recents(Context c, String owner, String repo) {
        return read(c, key("recent", owner, repo));
    }

    public static void touch(Context c, String owner, String repo, String path) {
        if (path == null || path.isEmpty()) return;
        String k = key("recent", owner, repo);
        List<String> l = read(c, k);
        l.remove(path);
        l.add(0, path);
        while (l.size() > MAX_RECENT) l.remove(l.size() - 1);
        write(c, k, l);
    }

    public static void clearRecents(Context c, String owner, String repo) {
        sp(c).edit().remove(key("recent", owner, repo)).apply();
    }

    public static List<String> favorites(Context c, String owner, String repo) {
        return read(c, key("fav", owner, repo));
    }

    public static boolean isFavorite(Context c, String owner, String repo, String path) {
        return favorites(c, owner, repo).contains(path);
    }

    public static void setFavorite(Context c, String owner, String repo, String path, boolean on) {
        if (path == null || path.isEmpty()) return;
        String k = key("fav", owner, repo);
        List<String> l = read(c, k);
        l.remove(path);
        if (on) {
            l.add(0, path);
            while (l.size() > MAX_FAV) l.remove(l.size() - 1);
        }
        write(c, k, l);
    }

    public static void clearAll(Context c) {
        sp(c).edit().clear().apply();
    }
}

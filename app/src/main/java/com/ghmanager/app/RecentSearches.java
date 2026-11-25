package com.ghmanager.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

final class RecentSearches {
    private static final int MAX = 15;

    private RecentSearches() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("gh_search", Context.MODE_PRIVATE);
    }

    private static String key(Context c) {
        String id = Accounts.activeId(c);
        return "recent:" + (id == null ? "" : id);
    }

    static List<String> all(Context c) {
        List<String> out = new ArrayList<>();
        String raw = sp(c).getString(key(c), "");
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

    private static void write(Context c, List<String> list) {
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        sp(c).edit().putString(key(c), a.toString()).apply();
    }

    static void add(Context c, String q) {
        if (!SearchQuery.valid(q)) return;
        write(c, RecentList.push(all(c), q, MAX));
    }

    static void remove(Context c, String q) {
        write(c, RecentList.remove(all(c), q));
    }

    static void clear(Context c) {
        sp(c).edit().remove(key(c)).apply();
    }

    static void clearAll(Context c) {
        sp(c).edit().clear().apply();
    }
}

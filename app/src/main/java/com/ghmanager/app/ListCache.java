package com.ghmanager.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ListCache {
    private ListCache() {
    }

    private static final int MAX_ENTRIES = 60;
    private static final int MAX_CHARS = 900 * 1024;

    public static final class Hit {
        public final JSONArray data;
        public final long at;

        Hit(JSONArray data, long at) {
            this.data = data;
            this.at = at;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("gh_list_cache", Context.MODE_PRIVATE);
    }

    private static String key(Context c, String name) {
        try {
            String id = Accounts.activeId(c);
            if (id.isEmpty()) return null;
            byte[] d = MessageDigest.getInstance("SHA-256").digest((id + "|" + name).getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder("l_");
            for (int i = 0; i < 10; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static Hit get(Context c, String name) {
        try {
            String k = key(c, name);
            if (k == null) return null;
            String s = sp(c).getString(k, null);
            if (s == null) return null;
            JSONObject j = new JSONObject(s);
            return new Hit(j.getJSONArray("d"), j.optLong("at", 0));
        } catch (Exception e) {
            return null;
        }
    }

    public static void put(Context c, String name, JSONArray arr) {
        try {
            String k = key(c, name);
            if (k == null || arr == null) return;
            JSONObject j = new JSONObject();
            j.put("at", System.currentTimeMillis());
            j.put("d", arr);
            String s = j.toString();
            if (s.length() > MAX_CHARS) return;
            SharedPreferences p = sp(c);
            SharedPreferences.Editor ed = p.edit();
            Map<String, ?> all = p.getAll();
            if (all.size() >= MAX_ENTRIES) {
                List<String[]> aged = new ArrayList<>();
                for (Map.Entry<String, ?> e : all.entrySet()) {
                    long at = 0;
                    try {
                        at = new JSONObject(String.valueOf(e.getValue())).optLong("at", 0);
                    } catch (Exception ignored) {
                    }
                    aged.add(new String[]{e.getKey(), String.valueOf(at)});
                }
                java.util.Collections.sort(aged, (a, b) -> Long.compare(Long.parseLong(a[1]), Long.parseLong(b[1])));
                for (int i = 0; i < Math.min(20, aged.size()); i++) ed.remove(aged.get(i)[0]);
            }
            ed.putString(k, s).apply();
        } catch (Exception ignored) {
        }
    }

    public static void clear(Context c) {
        try {
            sp(c).edit().clear().apply();
        } catch (Exception ignored) {
        }
    }
}

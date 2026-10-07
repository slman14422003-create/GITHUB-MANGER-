package com.ghmanager.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Saves the last repository list so it can be shown instantly on the next visit. */
public final class RepoCache {
    private RepoCache() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("gh_repo_cache", Context.MODE_PRIVATE);
    }

    /** One cache entry per account, so a list is never shown under another account. */
    private static String key(Context c) {
        try {
            String t = Store.getToken(c);
            if (t == null || t.isEmpty()) return null;
            byte[] d = MessageDigest.getInstance("SHA-256").digest(t.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder("repos_");
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static JSONArray load(Context c) {
        try {
            String k = key(c);
            if (k == null) return null;
            String s = sp(c).getString(k, null);
            return s == null ? null : new JSONArray(s);
        } catch (Exception e) {
            return null;
        }
    }

    public static void save(Context c, JSONArray arr) {
        try {
            String k = key(c);
            if (k == null || arr == null) return;
            sp(c).edit().putString(k, arr.toString()).apply();
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

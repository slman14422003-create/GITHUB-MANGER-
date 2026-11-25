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

    private static String keyFor(String accountId) {
        try {
            if (accountId == null || accountId.isEmpty()) return null;
            byte[] d = MessageDigest.getInstance("SHA-256").digest(accountId.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder("repos_");
            for (int i = 0; i < 8; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String key(Context c) {
        return keyFor(Accounts.activeId(c));
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

    public static long savedAt(Context c) {
        try {
            String k = key(c);
            return k == null ? 0 : sp(c).getLong(k + "_at", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    public static void save(Context c, JSONArray arr) {
        try {
            String k = key(c);
            if (k == null || arr == null) return;
            sp(c).edit().putString(k, arr.toString()).putLong(k + "_at", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void clearAccount(Context c, String accountId) {
        try {
            String k = keyFor(accountId);
            if (k == null) return;
            sp(c).edit().remove(k).remove(k + "_at").apply();
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

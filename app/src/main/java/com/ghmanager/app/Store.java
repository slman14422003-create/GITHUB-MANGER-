package com.ghmanager.app;

import android.content.Context;
import android.content.SharedPreferences;

public class Store {
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("gh", Context.MODE_PRIVATE);
    }

    public static String getToken(Context c) {
        return sp(c).getString("token", "");
    }

    public static void setToken(Context c, String t) {
        sp(c).edit().putString("token", t).apply();
    }

    /** Signs out: removes everything except the in-app update preferences (keys starting with upd_). */
    public static void clear(Context c) {
        SharedPreferences p = sp(c);
        SharedPreferences.Editor e = p.edit();
        for (String k : p.getAll().keySet()) {
            if (!k.startsWith("upd_")) e.remove(k);
        }
        e.apply();
    }

    // ------------------------------------------------------------------ in-app updates

    /** "owner/repo" the app downloads its own new versions from. */
    public static String getUpdateRepo(Context c) {
        String v = sp(c).getString("upd_repo", "");
        return v == null || v.trim().isEmpty() ? Updater.DEFAULT_REPO : v.trim();
    }

    public static void setUpdateRepo(Context c, String v) {
        sp(c).edit().putString("upd_repo", v == null ? "" : v.trim()).apply();
    }

    public static boolean autoUpdate(Context c) {
        return sp(c).getBoolean("upd_auto", true);
    }

    public static void setAutoUpdate(Context c, boolean on) {
        sp(c).edit().putBoolean("upd_auto", on).apply();
    }

    public static boolean updatePre(Context c) {
        return sp(c).getBoolean("upd_pre", false);
    }

    public static void setUpdatePre(Context c, boolean on) {
        sp(c).edit().putBoolean("upd_pre", on).apply();
    }

    public static long lastUpdateCheck(Context c) {
        return sp(c).getLong("upd_last", 0L);
    }

    public static void setLastUpdateCheck(Context c, long t) {
        sp(c).edit().putLong("upd_last", t).apply();
    }

    public static String skippedVersion(Context c) {
        return sp(c).getString("upd_skip", "");
    }

    public static void setSkippedVersion(Context c, String tag) {
        sp(c).edit().putString("upd_skip", tag == null ? "" : tag).apply();
    }
}

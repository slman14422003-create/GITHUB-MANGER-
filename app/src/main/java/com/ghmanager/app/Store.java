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

    public static void clear(Context c) {
        sp(c).edit().clear().apply();
    }
}

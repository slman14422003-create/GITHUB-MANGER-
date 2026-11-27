package com.ghmanager.app;

import android.content.Context;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class RepoAccess {
    private static final ConcurrentHashMap<String, Boolean> KNOWN = new ConcurrentHashMap<>();

    private RepoAccess() {
    }

    private static String key(String o, String r) {
        return (o == null ? "" : o).toLowerCase(Locale.ROOT) + "/" + (r == null ? "" : r).toLowerCase(Locale.ROOT);
    }

    static boolean fromJson(JSONObject repoJson) {
        if (repoJson == null) return false;
        JSONObject p = repoJson.optJSONObject("permissions");
        return p != null && p.optBoolean("push", false);
    }

    static void record(String o, String r, boolean write) {
        if (o == null || r == null || o.isEmpty() || r.isEmpty()) return;
        KNOWN.put(key(o, r), write);
    }

    static void recordJson(String o, String r, JSONObject repoJson) {
        record(o, r, fromJson(repoJson));
    }

    static Boolean known(String o, String r) {
        if (o == null || r == null || o.isEmpty() || r.isEmpty()) return null;
        return KNOWN.get(key(o, r));
    }

    static void clear() {
        KNOWN.clear();
    }

    static String message(Context c) {
        Context x = c != null ? c : App.context();
        return x.getString(R.string.read_only_blocked);
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static boolean writeBlocked(String method, String target) {
        if (method == null || target == null) return false;
        if (method.equals("GET") || method.equals("HEAD")) return false;
        int i = target.indexOf("/repos/");
        if (i < 0) return false;
        int a = i + 7;
        int b = target.indexOf('/', a);
        if (b < 0) return false;
        int end = target.length();
        int c = target.indexOf('/', b + 1);
        int q = target.indexOf('?', b + 1);
        if (c >= 0) end = c;
        if (q >= 0 && q < end) end = q;
        if (end <= b + 1) return false;
        Boolean k = KNOWN.get(key(decode(target.substring(a, b)), decode(target.substring(b + 1, end))));
        return k != null && !k;
    }
}

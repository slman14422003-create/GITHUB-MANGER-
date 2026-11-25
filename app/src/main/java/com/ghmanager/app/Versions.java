package com.ghmanager.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small helpers for release tag numbers. */
public final class Versions {
    private Versions() {
    }

    private static final Pattern LAST_NUMBER = Pattern.compile("(\\d+)(?!.*\\d)");

    /** "v1.2.9" -> "v1.2.10"; "" when the tag holds no number. */
    public static String next(String tag) {
        if (tag == null) return "";
        Matcher m = LAST_NUMBER.matcher(tag);
        if (!m.find()) return "";
        try {
            long n = Long.parseLong(m.group(1)) + 1;
            return tag.substring(0, m.start(1)) + n + tag.substring(m.end(1));
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /** Next tag after the newest published release in the list ("v1.0.0" when there is none). */
    public static String nextAfter(JSONArray releases) {
        for (int i = 0; releases != null && i < releases.length(); i++) {
            JSONObject o = releases.optJSONObject(i);
            if (o == null || o.optBoolean("draft")) continue;
            String n = next(o.optString("tag_name"));
            if (!n.isEmpty()) return n;
        }
        return "v1.0.0";
    }

    /** True for "v1.2.3", "1.2", "release-5" style values (letters, digits, dot, dash, underscore). */
    public static boolean validTag(String t) {
        return t != null && t.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}") && !t.contains("..") && !t.endsWith(".lock");
    }
}

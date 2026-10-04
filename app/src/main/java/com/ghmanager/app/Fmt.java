package com.ghmanager.app;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Formatting helpers (sizes, durations, relative dates in Arabic). */
public final class Fmt {
    private Fmt() {
    }

    public static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }

    /** Parses GitHub timestamps like 2026-10-03T10:12:00Z. Returns 0 when unparsable. */
    public static long parse(String iso) {
        if (iso == null || iso.isEmpty() || "null".equals(iso)) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(iso);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    public static String date(String iso) {
        long t = parse(iso);
        if (t == 0) return "";
        SimpleDateFormat f = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US);
        return f.format(new Date(t));
    }

    private static String unit(long n, String one, String two, String few, String many) {
        if (n == 1) return one;
        if (n == 2) return two;
        if (n >= 3 && n <= 10) return n + " " + few;
        return n + " " + many;
    }

    private static String en(long n, String word) {
        return n + " " + word + (n == 1 ? "" : "s") + " ago";
    }

    public static String ago(String iso) {
        long t = parse(iso);
        if (t == 0) return "";
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        boolean ar = Lang.isAr();
        if (s < 45) return ar ? "الآن" : "just now";
        long m = s / 60;
        if (m < 60) {
            m = Math.max(1, m);
            return ar ? "منذ " + unit(m, "دقيقة", "دقيقتين", "دقائق", "دقيقة") : en(m, "minute");
        }
        long h = m / 60;
        if (h < 24) return ar ? "منذ " + unit(h, "ساعة", "ساعتين", "ساعات", "ساعة") : en(h, "hour");
        long d = h / 24;
        if (d < 30) return ar ? "منذ " + unit(d, "يوم", "يومين", "أيام", "يومًا") : en(d, "day");
        long mo = d / 30;
        if (mo < 12) return ar ? "منذ " + unit(mo, "شهر", "شهرين", "أشهر", "شهرًا") : en(mo, "month");
        long y = Math.max(1, d / 365);
        return ar ? "منذ " + unit(y, "سنة", "سنتين", "سنوات", "سنة") : en(y, "year");
    }

    public static String duration(long ms) {
        if (ms < 0) ms = 0;
        boolean ar = Lang.isAr();
        long s = ms / 1000;
        if (s < 60) return s + (ar ? "ث" : "s");
        long m = s / 60;
        s = s % 60;
        if (m < 60) return ar ? m + "د " + s + "ث" : m + "m " + s + "s";
        long h = m / 60;
        m = m % 60;
        return ar ? h + "س " + m + "د" : h + "h " + m + "m";
    }

    /** Safe string getter: returns "" for missing keys and JSON null. */
    public static String s(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return "";
        return o.optString(key, "");
    }

    public static String shortSha(String sha) {
        if (sha == null) return "";
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    public static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }
}

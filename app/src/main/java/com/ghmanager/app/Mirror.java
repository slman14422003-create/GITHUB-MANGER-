package com.ghmanager.app;

import android.content.Context;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Proxy mirror (a Cloudflare Worker the user deploys). When it is on, every request that targets a
 * GitHub host is sent to  {mirror}/{host}/{path}  instead, and the Worker forwards it to GitHub.
 * That is how the app keeps working where github.com itself is blocked.
 */
public final class Mirror {
    private Mirror() {
    }

    private static volatile boolean on = false;
    private static volatile String base = "";
    private static volatile String key = "";

    private static final Pattern AZURE = Pattern.compile("^productionresultssa\\d+\\.blob\\.core\\.windows\\.net$");

    /** Reads the saved settings into memory. Call at startup and after every change. */
    public static void load(Context c) {
        on = Store.mirrorOn(c);
        base = clean(Store.mirrorUrl(c));
        key = Store.mirrorKey(c);
    }

    /** Normalised https base without a trailing slash, or "" when the value is not a valid https URL. */
    public static String clean(String u) {
        if (u == null) return "";
        String s = u.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return "";
        if (!s.toLowerCase(Locale.ROOT).startsWith("https://")) return "";
        if (s.length() <= 8 || s.indexOf('.', 8) < 0) return "";
        return s;
    }

    public static boolean active() {
        return on && !base.isEmpty();
    }

    public static String key() {
        return key;
    }

    /** True when this URL already points at the mirror. */
    public static boolean isMirrored(String url) {
        return active() && url != null && url.startsWith(base + "/");
    }

    private static boolean githubHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        int colon = h.indexOf(':');
        if (colon > 0) h = h.substring(0, colon);
        return h.equals("github.com") || h.endsWith(".github.com") || h.endsWith(".githubusercontent.com")
                || AZURE.matcher(h).matches();
    }

    /** Rewrites a GitHub URL to go through the mirror (unchanged when the mirror is off). */
    public static String map(String url) {
        if (!active() || url == null || !url.startsWith("https://")) return url;
        if (isMirrored(url)) return url;
        String rest = url.substring(8);
        int slash = rest.indexOf('/');
        String host = slash < 0 ? rest : rest.substring(0, slash);
        if (!githubHost(host)) return url;
        return base + "/" + rest;
    }

    /** Calls the Worker once and returns GitHub's own answer (a short sentence) when everything works. */
    public static String test(String rawBase, String accessKey) throws Exception {
        String b = clean(rawBase);
        if (b.isEmpty()) throw new Exception("URL");
        HttpURLConnection c = (HttpURLConnection) new URL(b + "/api.github.com/zen").openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "GitHubManagerApp");
        if (accessKey != null && !accessKey.isEmpty()) c.setRequestProperty("X-Mirror-Key", accessKey);
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) != -1 && sb.length() < 400) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            is.close();
        }
        c.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code);
        return sb.toString().trim();
    }
}

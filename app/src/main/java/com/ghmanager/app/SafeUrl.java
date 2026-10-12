package com.ghmanager.app;

import android.net.Uri;

import java.util.Locale;

/** Links that come from API data or from a server answer are opened only when they are plain https web addresses. */
final class SafeUrl {
    private SafeUrl() {
    }

    static boolean https(String url) {
        if (url == null || url.length() > 2048) return false;
        try {
            Uri u = Uri.parse(url.trim());
            String host = u.getHost();
            return "https".equalsIgnoreCase(u.getScheme()) && host != null && !host.isEmpty()
                    && u.getUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    /** https and a host that is github.com or one of its subdomains (the sign-in page, releases, commits). */
    static boolean github(String url) {
        if (!https(url)) return false;
        String host = Uri.parse(url.trim()).getHost().toLowerCase(Locale.ROOT);
        return host.equals("github.com") || host.endsWith(".github.com");
    }
}

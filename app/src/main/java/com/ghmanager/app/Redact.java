package com.ghmanager.app;

import java.util.regex.Pattern;

/** Removes anything that looks like a GitHub token from text that is shown, copied or logged. */
public final class Redact {
    private Redact() {
    }

    private static final Pattern TOKEN = Pattern.compile(
            "(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|Bearer\\s+[A-Za-z0-9._~+/=-]{20,})");

    public static String text(String s) {
        if (s == null || s.isEmpty()) return s;
        return TOKEN.matcher(s).replaceAll("***");
    }
}

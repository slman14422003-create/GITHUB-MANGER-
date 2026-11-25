package com.ghmanager.app;

import java.util.Locale;

final class SearchQuery {
    static final int MAX_LEN = 256;
    static final int MIN_LEN = 2;

    static final int REPOS = 0;
    static final int CODE = 1;
    static final int ISSUES = 2;
    static final int PULLS = 3;
    static final int MIXED = 4;

    private SearchQuery() {
    }

    static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim().replaceAll(" {2,}", " ");
        if (s.length() > MAX_LEN) s = s.substring(0, MAX_LEN).trim();
        return s;
    }

    static boolean valid(String normalized) {
        return normalized != null && normalized.length() >= MIN_LEN;
    }

    static String scopeQualifier(String repoFull, String login, boolean everywhere, int kind) {
        if (repoFull != null && !repoFull.isEmpty()) {
            return kind == REPOS ? "" : "repo:" + repoFull;
        }
        boolean hasLogin = login != null && !login.isEmpty();
        if (kind == CODE) return hasLogin ? "user:" + login : "";
        if (everywhere) return "";
        return hasLogin ? "user:" + login : "";
    }

    static String build(String normalized, String repoFull, String login, boolean everywhere, int kind) {
        StringBuilder sb = new StringBuilder(normalized);
        String scope = scopeQualifier(repoFull, login, everywhere, kind);
        if (!scope.isEmpty() && !hasQualifier(normalized, scope.substring(0, scope.indexOf(':')))) {
            sb.append(' ').append(scope);
        }
        if (kind == ISSUES) sb.append(" is:issue");
        else if (kind == PULLS) sb.append(" is:pr");
        return sb.toString();
    }

    static boolean hasQualifier(String q, String key) {
        String l = " " + q.toLowerCase(Locale.ROOT);
        return l.contains(" " + key.toLowerCase(Locale.ROOT) + ":");
    }

    static String repoFromApiUrl(String url) {
        if (url == null) return "";
        int i = url.indexOf("/repos/");
        if (i < 0) return "";
        String rest = url.substring(i + 7);
        int slash = rest.indexOf('/');
        if (slash <= 0) return "";
        int end = rest.indexOf('/', slash + 1);
        String full = end < 0 ? rest : rest.substring(0, end);
        return full.indexOf('/') > 0 && full.indexOf('/') < full.length() - 1 ? full : "";
    }
}

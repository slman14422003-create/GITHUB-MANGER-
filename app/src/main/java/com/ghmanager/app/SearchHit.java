package com.ghmanager.app;

import org.json.JSONObject;

final class SearchHit {
    final int kind;
    final String title;
    final String sub;
    final String owner;
    final String repo;
    final String path;
    final String branch;
    final String state;
    final long number;
    final boolean pull;
    final boolean merged;
    final String when;

    private SearchHit(int kind, String title, String sub, String owner, String repo, String path, String branch,
                      String state, long number, boolean pull, boolean merged, String when) {
        this.kind = kind;
        this.title = title;
        this.sub = sub;
        this.owner = owner;
        this.repo = repo;
        this.path = path;
        this.branch = branch;
        this.state = state;
        this.number = number;
        this.pull = pull;
        this.merged = merged;
        this.when = when;
    }

    static SearchHit repo(JSONObject o) {
        String full = o.optString("full_name");
        JSONObject own = o.optJSONObject("owner");
        String owner = own == null ? "" : own.optString("login");
        StringBuilder sub = new StringBuilder();
        String d = o.isNull("description") ? "" : o.optString("description");
        if (!d.isEmpty()) sub.append(d);
        String lang = o.isNull("language") ? "" : o.optString("language");
        StringBuilder meta = new StringBuilder();
        if (!lang.isEmpty()) meta.append(lang);
        int stars = o.optInt("stargazers_count");
        if (stars > 0) meta.append(meta.length() > 0 ? " · " : "").append('\u2605').append(' ').append(stars);
        if (meta.length() > 0) sub.append(sub.length() > 0 ? "\n" : "").append(meta);
        return new SearchHit(SearchQuery.REPOS, full, sub.toString(), owner, o.optString("name"), "",
                o.optString("default_branch", "main"), "", 0, false, false, "");
    }

    static SearchHit code(JSONObject o) {
        JSONObject r = o.optJSONObject("repository");
        String full = r == null ? "" : r.optString("full_name");
        JSONObject own = r == null ? null : r.optJSONObject("owner");
        String owner = own == null ? "" : own.optString("login");
        String name = r == null ? "" : r.optString("name");
        String path = o.optString("path");
        return new SearchHit(SearchQuery.CODE, o.optString("name"), full + " · " + path, owner, name, path, "",
                "", 0, false, false, "");
    }

    static SearchHit issue(JSONObject o) {
        boolean pull = o.has("pull_request");
        JSONObject pr = o.optJSONObject("pull_request");
        boolean merged = pr != null && !pr.isNull("merged_at") && !pr.optString("merged_at").isEmpty();
        String full = SearchQuery.repoFromApiUrl(o.optString("repository_url"));
        String owner = "";
        String name = "";
        int slash = full.indexOf('/');
        if (slash > 0) {
            owner = full.substring(0, slash);
            name = full.substring(slash + 1);
        }
        long number = o.optLong("number");
        JSONObject u = o.optJSONObject("user");
        StringBuilder sub = new StringBuilder(full);
        if (u != null) sub.append(sub.length() > 0 ? " · " : "").append(u.optString("login"));
        return new SearchHit(pull ? SearchQuery.PULLS : SearchQuery.ISSUES, "#" + number + "  " + o.optString("title"),
                sub.toString(), owner, name, "", "", o.optString("state"), number, pull, merged,
                o.optString("updated_at"));
    }

    String full() {
        return owner + "/" + repo;
    }
}

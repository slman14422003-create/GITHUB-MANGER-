package com.ghmanager.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** GitHub's public status page (githubstatus.com): overall state, every service, and open incidents. */
public final class GhStatus {
    private GhStatus() {
    }

    public static final String URL = "https://www.githubstatus.com/api/v2/summary.json";
    public static final String PAGE = "https://www.githubstatus.com";

    public static final class Comp {
        public String name = "";
        /** operational, degraded_performance, partial_outage, major_outage, under_maintenance */
        public String status = "operational";
    }

    public static final class Incident {
        public String name = "";
        public String status = "";
        public String impact = "";
        public String latest = "";
        public String link = "";
    }

    public static final class Result {
        /** none, minor, major, critical, maintenance */
        public String indicator = "none";
        public String description = "";
        public final List<Comp> components = new ArrayList<>();
        public final List<Incident> incidents = new ArrayList<>();
        public long checkedAt = 0;
    }

    /** Downloads and parses the summary; null when the status site cannot be reached. */
    public static Result fetch() {
        try {
            byte[] b = GitHubApi.fetchBytes(URL, 400 * 1024);
            if (b == null) return null;
            Result r = parse(new String(b, StandardCharsets.UTF_8));
            if (r != null) r.checkedAt = System.currentTimeMillis();
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    public static Result parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            Result r = new Result();
            JSONObject st = o.optJSONObject("status");
            if (st != null) {
                r.indicator = st.optString("indicator", "none");
                r.description = st.optString("description", "");
            }
            JSONArray comps = o.optJSONArray("components");
            for (int i = 0; comps != null && i < comps.length(); i++) {
                JSONObject c = comps.optJSONObject(i);
                if (c == null || c.optBoolean("group")) continue;
                String name = c.optString("name");
                if (name.isEmpty() || name.startsWith("Visit ")) continue;
                Comp k = new Comp();
                k.name = name;
                k.status = c.optString("status", "operational");
                r.components.add(k);
            }
            JSONArray inc = o.optJSONArray("incidents");
            for (int i = 0; inc != null && i < inc.length(); i++) {
                JSONObject c = inc.optJSONObject(i);
                if (c == null) continue;
                Incident x = new Incident();
                x.name = c.optString("name");
                x.status = c.optString("status");
                x.impact = c.optString("impact");
                x.link = c.optString("shortlink");
                JSONArray ups = c.optJSONArray("incident_updates");
                if (ups != null && ups.length() > 0) {
                    JSONObject u = ups.optJSONObject(0);
                    if (u != null) x.latest = u.optString("body");
                }
                r.incidents.add(x);
            }
            // a worse component than the headline says (or no headline) still raises the overall state
            if ("none".equals(r.indicator)) {
                for (Comp k : r.components) {
                    if ("major_outage".equals(k.status)) r.indicator = "major";
                    else if (!"operational".equals(k.status) && "none".equals(r.indicator)) r.indicator = "minor";
                }
            }
            return r;
        } catch (Exception e) {
            return null;
        }
    }
}

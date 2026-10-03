package com.ghmanager.app;

import android.os.Bundle;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;

/** Repository statistics: counters, languages, traffic and top contributors. */
public class InsightsActivity extends BaseRepoActivity {
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        bindHeader(getString(R.string.insights), repo);
        content = findViewById(R.id.content);
        btnRefresh.setOnClickListener(v -> load());
        load();
    }

    private void load() {
        loading(true);
        io.execute(() -> {
            try {
                final JSONObject r = api.getRepo(owner, repo);
                JSONObject lang = null;
                JSONArray contrib = null;
                JSONObject views = null;
                JSONObject clones = null;
                try {
                    lang = api.languages(owner, repo);
                } catch (Exception ignored) {
                }
                try {
                    contrib = api.contributors(owner, repo);
                } catch (Exception ignored) {
                }
                try {
                    views = api.trafficViews(owner, repo);
                } catch (Exception ignored) {
                }
                try {
                    clones = api.trafficClones(owner, repo);
                } catch (Exception ignored) {
                }
                final JSONObject fl = lang;
                final JSONArray fc = contrib;
                final JSONObject fv = views;
                final JSONObject fcl = clones;
                post(() -> {
                    loading(false);
                    render(r, fl, fc, fv, fcl);
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void line(String text) {
        content.addView(Ui.body(this, text, 14, R.color.text_primary));
    }

    private void render(JSONObject r, JSONObject lang, JSONArray contrib, JSONObject views, JSONObject clones) {
        content.removeAllViews();

        content.addView(Ui.sectionTitle(this, getString(R.string.overview)));
        line("★ " + getString(R.string.stars_label) + ": " + r.optInt("stargazers_count"));
        line(getString(R.string.forks) + ": " + r.optInt("forks_count"));
        line(getString(R.string.watchers_label) + ": " + r.optInt("subscribers_count"));
        line(getString(R.string.open_issues_label) + ": " + r.optInt("open_issues_count"));
        line(getString(R.string.size_label) + ": " + Fmt.size(r.optLong("size") * 1024));
        line(getString(R.string.created_label) + ": " + Fmt.date(Fmt.s(r, "created_at")));
        line(getString(R.string.last_push_label) + ": " + Fmt.date(Fmt.s(r, "pushed_at")));
        String license = r.optJSONObject("license") == null ? "" : r.optJSONObject("license").optString("name");
        if (!license.isEmpty() && !"null".equals(license)) line(getString(R.string.license_label) + ": " + license);

        content.addView(Ui.sectionTitle(this, getString(R.string.languages)));
        if (lang == null || lang.length() == 0) {
            line(getString(R.string.nothing_here));
        } else {
            double total = 0;
            Iterator<String> it = lang.keys();
            while (it.hasNext()) total += lang.optDouble(it.next());
            it = lang.keys();
            while (it.hasNext()) {
                String k = it.next();
                double pct = total == 0 ? 0 : lang.optDouble(k) * 100.0 / total;
                content.addView(Ui.body(this, k + "  " + String.format(Locale.US, "%.1f%%", pct), 13,
                        R.color.text_primary));
                content.addView(Ui.bar(this, pct, R.color.accent));
            }
        }

        content.addView(Ui.sectionTitle(this, getString(R.string.traffic)));
        if (views == null && clones == null) {
            line(getString(R.string.traffic_unavailable));
        } else {
            if (views != null) {
                line(getString(R.string.views_label, views.optInt("count"), views.optInt("uniques")));
            }
            if (clones != null) {
                line(getString(R.string.clones_label, clones.optInt("count"), clones.optInt("uniques")));
            }
        }

        content.addView(Ui.sectionTitle(this, getString(R.string.top_contributors)));
        if (contrib == null || contrib.length() == 0) {
            line(getString(R.string.nothing_here));
        } else {
            for (int i = 0; i < contrib.length(); i++) {
                JSONObject c = contrib.optJSONObject(i);
                if (c == null) continue;
                content.addView(Ui.rowView(this, content,
                        new Row(R.drawable.ic_commit, false, c.optString("login"),
                                getString(R.string.commits_count, c.optInt("contributions")), false, false), null));
            }
        }
    }
}

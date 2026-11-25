package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

public class RunCompareActivity extends BaseRepoActivity {
    private static final int MAX_COMMITS = 30;
    private static final int MAX_FILES = 60;
    private long runId;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        runId = getIntent().getLongExtra("runId", 0);
        bindHeader(getString(R.string.cmp_title), repo);
        content = findViewById(R.id.content);
        btnRefresh.setOnClickListener(v -> load());
        load();
    }

    private void load() {
        loading(true);
        io.execute(() -> {
            try {
                final JSONObject bad = api.getRun(owner, repo, runId);
                final JSONObject good = api.lastSuccessfulRun(owner, repo, bad);
                if (good == null) {
                    post(() -> {
                        loading(false);
                        content.removeAllViews();
                        content.addView(Ui.body(this, getString(R.string.cmp_none), 14, R.color.text_secondary));
                    });
                    return;
                }
                JSONObject cmp = null;
                String gs = Fmt.s(good, "head_sha");
                String bs = Fmt.s(bad, "head_sha");
                if (!gs.isEmpty() && !bs.isEmpty() && !gs.equals(bs)) {
                    try {
                        cmp = api.compareRefs(owner, repo, gs, bs);
                    } catch (GitHubApi.ApiException e) {
                        if (e.code == 401) throw e;
                    } catch (java.io.IOException ignored) {
                    }
                }
                JSONArray jg = api.listJobs(owner, repo, good.optLong("id"));
                JSONArray jb = api.listJobs(owner, repo, bad.optLong("id"));
                final JSONObject fc = cmp;
                final JSONArray fjg = jg == null ? new JSONArray() : jg;
                final JSONArray fjb = jb == null ? new JSONArray() : jb;
                post(() -> {
                    loading(false);
                    showStatus(null);
                    render(good, bad, fc, fjg, fjb);
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private static long runMs(JSONObject run) {
        long a = Fmt.parse(Fmt.s(run, "run_started_at"));
        long b = Fmt.parse(Fmt.s(run, "updated_at"));
        return a > 0 && b >= a ? b - a : 0;
    }

    private static long spanMs(JSONObject o) {
        long a = Fmt.parse(Fmt.s(o, "started_at"));
        long b = Fmt.parse(Fmt.s(o, "completed_at"));
        return a > 0 && b >= a ? b - a : 0;
    }

    private static String state(JSONObject o) {
        return Status.state(o.optString("status"), o.isNull("conclusion") ? "" : o.optString("conclusion"));
    }

    private String desc(JSONObject o) {
        if (o == null) return "—";
        String l = Status.label(o.optString("status"), o.isNull("conclusion") ? "" : o.optString("conclusion"));
        long ms = spanMs(o);
        return ms > 0 ? l + " " + Fmt.duration(ms) : l;
    }

    private Row runRow(JSONObject run) {
        String s = run.optString("status");
        String c = run.isNull("conclusion") ? "" : run.optString("conclusion");
        long ms = runMs(run);
        String sub = Fmt.shortSha(Fmt.s(run, "head_sha")) + " · " + Fmt.ago(Fmt.s(run, "created_at"))
                + (ms > 0 ? " · " + Fmt.duration(ms) : "");
        Row r = new Row(Status.icon(s, c), false, "#" + run.optInt("run_number") + " " + Fmt.s(run, "name"), sub,
                false, true);
        r.tint(Status.color(this, s, c));
        return r;
    }

    private void addRun(JSONObject run) {
        final long id = run.optLong("id");
        content.addView(Ui.rowView(this, content, runRow(run), v -> {
            Intent i = repoIntent(RunDetailActivity.class);
            i.putExtra("runId", id);
            startActivity(i);
        }));
    }

    private void render(JSONObject good, JSONObject bad, JSONObject cmp, JSONArray jg, JSONArray jb) {
        content.removeAllViews();
        content.addView(Ui.sectionTitle(this, getString(R.string.cmp_runs)));
        addRun(good);
        addRun(bad);
        long gm = runMs(good);
        long bm = runMs(bad);
        String dur = getString(R.string.cmp_vs, Fmt.duration(gm), Fmt.duration(bm));
        long diff = bm - gm;
        if (gm > 0 && bm > 0 && Math.abs(diff) >= 1000) {
            dur += "\n" + getString(diff > 0 ? R.string.cmp_slower : R.string.cmp_faster, Fmt.duration(Math.abs(diff)));
        }
        content.addView(Ui.sectionTitle(this, getString(R.string.cmp_duration)));
        content.addView(Ui.body(this, dur, 14, R.color.text_primary));

        content.addView(Ui.sectionTitle(this, getString(R.string.cmp_commits_title)));
        if (Fmt.s(good, "head_sha").equals(Fmt.s(bad, "head_sha"))) {
            content.addView(Ui.body(this, getString(R.string.cmp_same_commit), 14, R.color.text_secondary));
        } else if (cmp == null) {
            content.addView(Ui.body(this, getString(R.string.cmp_unavailable), 14, R.color.text_secondary));
        } else {
            addCommits(cmp);
            addFiles(cmp);
        }
        addJobs(jg, jb);
    }

    private void addCommits(JSONObject cmp) {
        JSONArray commits = cmp.optJSONArray("commits");
        int n = commits == null ? 0 : commits.length();
        int total = Math.max(n, cmp.optInt("total_commits", n));
        content.addView(Ui.body(this, getString(R.string.cmp_commits, total), 13, R.color.text_secondary));
        for (int i = 0; i < Math.min(n, MAX_COMMITS); i++) {
            JSONObject c = commits.optJSONObject(n - 1 - i);
            if (c == null) continue;
            JSONObject cm = c.optJSONObject("commit");
            String msg = cm == null ? "" : Fmt.firstLine(cm.optString("message"));
            JSONObject au = cm == null ? null : cm.optJSONObject("author");
            String who = au == null ? "" : au.optString("name");
            final String url = Fmt.s(c, "html_url");
            Row r = new Row(R.drawable.ic_commit, false, msg, Fmt.shortSha(Fmt.s(c, "sha"))
                    + (who.isEmpty() ? "" : " · " + who), false, true);
            content.addView(Ui.rowView(this, content, r, v -> openUrl(url)));
        }
    }

    private void addFiles(JSONObject cmp) {
        JSONArray files = cmp.optJSONArray("files");
        int n = files == null ? 0 : files.length();
        content.addView(Ui.sectionTitle(this, getString(R.string.cmp_files, n)));
        if (n == 0) {
            content.addView(Ui.body(this, getString(R.string.cmp_no_files), 14, R.color.text_secondary));
            return;
        }
        for (int i = 0; i < Math.min(n, MAX_FILES); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            final String url = Fmt.s(f, "blob_url");
            String st = f.optString("status");
            Row r = new Row(R.drawable.ic_file, false, f.optString("filename"),
                    st + " · +" + f.optInt("additions") + " -" + f.optInt("deletions"), false, true);
            int color = "added".equals(st) ? Ui.color(this, R.color.ok)
                    : "removed".equals(st) ? Ui.color(this, R.color.bad) : Ui.color(this, R.color.info);
            r.tint(color);
            content.addView(Ui.rowView(this, content, r, v -> {
                if (!url.isEmpty()) openUrl(url);
            }));
        }
    }

    private static JSONObject byName(JSONArray arr, String name, String key) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && name.equals(o.optString(key))) return o;
        }
        return null;
    }

    private static boolean slower(long a, long b) {
        if (a <= 0 || b <= 0) return false;
        long d = Math.abs(b - a);
        return d >= 5000 && d * 2 >= a;
    }

    private void addJobs(JSONArray jg, JSONArray jb) {
        content.addView(Ui.sectionTitle(this, getString(R.string.cmp_jobs)));
        for (int i = 0; i < jb.length(); i++) {
            JSONObject b = jb.optJSONObject(i);
            if (b == null) continue;
            JSONObject g = byName(jg, b.optString("name"), "name");
            Row r = new Row(Status.icon(b.optString("status"), b.optString("conclusion")), false, b.optString("name"),
                    getString(R.string.cmp_vs, desc(g), desc(b)), false, false);
            r.tint(Status.color(this, b.optString("status"), b.isNull("conclusion") ? "" : b.optString("conclusion")));
            if (g == null || !state(g).equals(state(b))) {
                r.badge(getString(R.string.cmp_changed), Ui.color(this, R.color.warn));
            }
            content.addView(Ui.rowView(this, content, r, null));
        }
        content.addView(Ui.sectionTitle(this, getString(R.string.cmp_steps)));
        int shown = 0;
        for (int i = 0; i < jb.length(); i++) {
            JSONObject b = jb.optJSONObject(i);
            if (b == null) continue;
            JSONObject g = byName(jg, b.optString("name"), "name");
            JSONArray sb = b.optJSONArray("steps");
            if (sb == null) continue;
            JSONArray sg = g == null || g.optJSONArray("steps") == null ? new JSONArray() : g.optJSONArray("steps");
            for (int k = 0; k < sb.length(); k++) {
                JSONObject s = sb.optJSONObject(k);
                if (s == null) continue;
                JSONObject o = byName(sg, s.optString("name"), "name");
                boolean diff = o == null || !state(o).equals(state(s)) || slower(spanMs(o), spanMs(s));
                if (!diff) continue;
                Row r = new Row(Status.icon(s.optString("status"), s.optString("conclusion")), false,
                        b.optString("name") + " › " + s.optString("name"),
                        getString(R.string.cmp_vs, desc(o), desc(s)), false, false);
                r.tint(Status.color(this, s.optString("status"), s.isNull("conclusion") ? "" : s.optString("conclusion")));
                content.addView(Ui.rowView(this, content, r, null));
                shown++;
            }
        }
        if (shown == 0) content.addView(Ui.body(this, getString(R.string.cmp_no_steps), 14, R.color.text_secondary));
    }
}

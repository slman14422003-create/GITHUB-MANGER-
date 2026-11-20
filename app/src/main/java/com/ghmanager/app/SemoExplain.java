package com.ghmanager.app;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class SemoExplain {
    private SemoExplain() {
    }

    private static final Object[][] RULES = {
            {"cannot find symbol|package .* does not exist|Compilation failed|compile\\w*(Java|Kotlin)\\w* FAILED|Unresolved reference|error: incompatible types|Execution failed for task ':[\\w:]*compile", R.string.ex_r0_t, R.string.ex_r0_a},
            {"AAPT: error|resource .* not found|error: resource|Android resource linking failed|aapt2", R.string.ex_r1_t, R.string.ex_r1_a},
            {"Could not resolve|Could not find [\\w.\\-]+:[\\w.\\-]+|Failed to resolve|Could not GET|Could not HEAD|Failed to transform", R.string.ex_r2_t, R.string.ex_r2_a},
            {"Unsupported class file major version|Unsupported Java|invalid source release|requires Java \\d+|Could not target platform|java.lang.UnsupportedClassVersionError", R.string.ex_r3_t, R.string.ex_r3_a},
            {"SDK location not found|Failed to install the following Android SDK|licenses? .* (have not been|not) accepted|Could not determine the dependencies of task .*:lint", R.string.ex_r4_t, R.string.ex_r4_a},
            {"Keystore was tampered|Failed to read key|keystore.*(not found|missing|invalid)|Release signing is not configured|signingConfig|Tag number over 30 is not supported", R.string.ex_r5_t, R.string.ex_r5_a},
            {"npm ERR!|npm error|ERESOLVE|Cannot find module|Module not found|yarn error|pnpm.*ERR", R.string.ex_r6_t, R.string.ex_r6_a},
            {"There were failing tests|tests? completed, \\d+ failed|AssertionError|assertion failed|FAIL\\s+\\S+\\.(test|spec)|\\d+ (tests? )?failed", R.string.ex_r7_t, R.string.ex_r7_a},
            {"Traceback \\(most recent call last\\)|ModuleNotFoundError|ImportError|SyntaxError|IndentationError|NameError", R.string.ex_r8_t, R.string.ex_r8_a},
            {"Resource not accessible by integration|Permission denied|EACCES|HttpError: Forbidden|403 Forbidden|must have admin rights", R.string.ex_r9_t, R.string.ex_r9_a},
            {"Input required and not supplied|secret.*(not found|is empty|missing)|token.*(is empty|missing)|Context access might be invalid", R.string.ex_r10_t, R.string.ex_r10_a},
            {"command not found|No such file or directory|cannot find file|ENOENT|can't open file|File not found", R.string.ex_r11_t, R.string.ex_r11_a},
            {"OutOfMemoryError|Java heap space|GC overhead limit|exit code 137|ENOMEM|JavaScript heap out of memory", R.string.ex_r12_t, R.string.ex_r12_a},
            {"exceeded the maximum execution time|operation was canceled|ETIMEDOUT|Timeout of \\d+|timed out", R.string.ex_r13_t, R.string.ex_r13_a},
            {"Invalid workflow file|yaml.*(error|invalid)|mapping values are not allowed|Unexpected value|did not find expected|The workflow is not valid", R.string.ex_r14_t, R.string.ex_r14_a},
            {"Unable to resolve action|Can't find 'action.yml'|action .* not found|Unexpected input\\(s\\)|Set up job.*failed", R.string.ex_r15_t, R.string.ex_r15_a},
            {"No space left on device|ENOSPC|disk quota", R.string.ex_r16_t, R.string.ex_r16_a},
            {"API rate limit exceeded|rate limit|Could not resolve host|ECONNRESET|Connection reset|Temporary failure in name resolution|Network is unreachable|503 Service", R.string.ex_r17_t, R.string.ex_r17_a},
            {"ktlint|spotless|eslint|prettier|checkstyle|detekt|lint.*(error|violation)|Formatting check", R.string.ex_r18_t, R.string.ex_r18_a},
            {"docker: Error|failed to solve|pull access denied|manifest unknown|Dockerfile.*error", R.string.ex_r19_t, R.string.ex_r19_a},
            {"fatal: |could not read Username|Authentication failed|remote: Permission|refusing to allow|non-fast-forward|rejected\\)", R.string.ex_r20_t, R.string.ex_r20_a},
            {"Process completed with exit code \\d+|exited with code \\d+|exit status \\d+", R.string.ex_r21_t, R.string.ex_r21_a}
    };

    private static Pattern[] compiled;

    private static synchronized Pattern[] patterns() {
        if (compiled == null) {
            compiled = new Pattern[RULES.length];
            for (int i = 0; i < RULES.length; i++) {
                compiled[i] = Pattern.compile((String) RULES[i][0], Pattern.CASE_INSENSITIVE);
            }
        }
        return compiled;
    }

    static final class Finding {
        final int title;
        final int advice;

        Finding(int title, int advice) {
            this.title = title;
            this.advice = advice;
        }
    }

    static final class Report {
        final List<Finding> findings = new ArrayList<>();
        final List<String> evidence = new ArrayList<>();
        String job = "";
        String step = "";
        long jobId;
        boolean noFailure;
    }

    static Report analyze(List<LogFmt.Line> lines, String job, String step) {
        Report r = new Report();
        r.job = job == null ? "" : job;
        r.step = step == null ? "" : step;
        Pattern[] ps = patterns();
        int generic = RULES.length - 1;
        Map<Integer, Integer> firstHit = new LinkedHashMap<>();
        for (int i = 0; i < ps.length - 1; i++) {
            for (int k = 0; k < lines.size(); k++) {
                if (ps[i].matcher(lines.get(k).plain).find()) {
                    firstHit.put(i, k);
                    break;
                }
            }
            if (firstHit.size() >= 3) break;
        }
        if (firstHit.isEmpty()) {
            for (int k = 0; k < lines.size(); k++) {
                if (ps[generic].matcher(lines.get(k).plain).find()) {
                    firstHit.put(generic, k);
                    break;
                }
            }
        }
        for (Map.Entry<Integer, Integer> e : firstHit.entrySet()) {
            r.findings.add(new Finding((Integer) RULES[e.getKey()][1], (Integer) RULES[e.getKey()][2]));
            addEvidence(r, lines.get(e.getValue()).plain);
        }
        for (LogFmt.Line l : lines) {
            if (r.evidence.size() >= 5) break;
            if (l.err) addEvidence(r, l.plain);
        }
        return r;
    }

    private static void addEvidence(Report r, String line) {
        String t = line == null ? "" : line.trim();
        if (t.isEmpty()) return;
        if (t.length() > 220) t = t.substring(0, 220) + "\u2026";
        if (!r.evidence.contains(t)) r.evidence.add(t);
    }

    static Report forRun(Context c, GitHubApi api, String owner, String repo, long runId) throws Exception {
        JSONArray jobs = api.listJobs(owner, repo, runId);
        JSONObject failed = null;
        for (int i = 0; jobs != null && i < jobs.length(); i++) {
            JSONObject j = jobs.getJSONObject(i);
            String con = j.optString("conclusion");
            if ("failure".equals(con) || "timed_out".equals(con)) {
                failed = j;
                break;
            }
        }
        if (failed == null) {
            Report none = new Report();
            none.noFailure = true;
            return none;
        }
        String raw = api.readTail(api.jobLogsPath(owner, repo, failed.optLong("id")),
                "application/vnd.github+json", 400 * 1024).text;
        List<LogFmt.Line> lines = LogFmt.parse(c, raw);
        String stepName = "";
        JSONArray steps = failed.optJSONArray("steps");
        for (int i = 0; steps != null && i < steps.length(); i++) {
            JSONObject s = steps.getJSONObject(i);
            if ("failure".equals(s.optString("conclusion"))) {
                stepName = s.optString("name");
                break;
            }
        }
        List<LogFmt.Line> use = lines;
        if (!stepName.isEmpty() && steps != null) {
            for (StepLog.Step st : StepLog.build(steps, lines)) {
                if (stepName.equals(st.name) && !st.lines.isEmpty()) {
                    use = st.lines;
                    break;
                }
            }
        }
        Report r = analyze(use, failed.optString("name"), stepName);
        r.jobId = failed.optLong("id");
        return r;
    }

    private static TextView card(Context c, CharSequence text, int colorRes) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(Ui.color(c, colorRes));
        t.setTextSize(14);
        t.setLineSpacing(0, 1.2f);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setBackgroundResource(R.drawable.bg_note);
        t.setPaddingRelative(Ui.dp(c, 16), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 12));
        t.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(c, 8);
        t.setLayoutParams(lp);
        return t;
    }

    private static View scroll(Context c, LinearLayout box) {
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(box);
        return sv;
    }

    static View view(Context c, Report r) {
        LinearLayout box = Ui.box(c);
        if (r.noFailure) {
            box.addView(card(c, c.getString(R.string.ex_no_failed), R.color.text_secondary));
            return scroll(c, box);
        }
        StringBuilder meta = new StringBuilder();
        if (!r.job.isEmpty()) meta.append(c.getString(R.string.ex_job, r.job));
        if (!r.step.isEmpty()) {
            if (meta.length() > 0) meta.append('\n');
            meta.append(c.getString(R.string.ex_step, r.step));
        }
        if (meta.length() > 0) box.addView(card(c, meta.toString(), R.color.text_secondary));
        if (r.findings.isEmpty()) {
            box.addView(card(c, c.getString(R.string.ex_none), R.color.text_primary));
        }
        for (Finding f : r.findings) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            String title = c.getString(f.title);
            sb.append(title).append('\n');
            sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, title.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.ForegroundColorSpan(Ui.color(c, R.color.warn)), 0, title.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(c.getString(f.advice));
            box.addView(card(c, sb, R.color.text_primary));
        }
        if (!r.evidence.isEmpty()) {
            box.addView(Ui.label(c, c.getString(R.string.ex_evidence)));
            StringBuilder ev = new StringBuilder();
            for (String e : r.evidence) {
                if (ev.length() > 0) ev.append('\n');
                ev.append(e);
            }
            TextView t = card(c, ev.toString(), R.color.text_secondary);
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextSize(12);
            t.setTextDirection(View.TEXT_DIRECTION_LTR);
            box.addView(t);
        }
        TextView note = Ui.label(c, c.getString(R.string.ex_note));
        note.setTextColor(Ui.color(c, R.color.text_hint));
        box.addView(note);
        return scroll(c, box);
    }

    static void show(Context c, Report r, final Runnable openLog) {
        Dlg d = new Dlg(c);
        d.sheet().setTitle(R.string.ex_title).setView(view(c, r));
        d.setPositiveButton(R.string.close, null);
        if (openLog != null && !r.noFailure) d.setNeutralButton(R.string.ex_open_log, (x, w) -> openLog.run());
        d.show();
    }

    static final class Summary {
        String type = "";
        String headline = "";
        final List<String> lines = new ArrayList<>();
        final List<String> risks = new ArrayList<>();
    }

    private static int typeOf(String msg) {
        String m = msg == null ? "" : msg.trim().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher cm = Pattern.compile("^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(\\(.*?\\))?!?:")
                .matcher(m);
        if (cm.find()) {
            switch (cm.group(1)) {
                case "feat": return R.string.sm_t_feat;
                case "fix": return R.string.sm_t_fix;
                case "docs": return R.string.sm_t_docs;
                case "style": return R.string.sm_t_style;
                case "refactor": return R.string.sm_t_refactor;
                case "perf": return R.string.sm_t_perf;
                case "test": return R.string.sm_t_test;
                case "build": return R.string.sm_t_build;
                case "ci": return R.string.sm_t_ci;
                case "revert": return R.string.sm_t_revert;
                default: return R.string.sm_t_chore;
            }
        }
        if (m.startsWith("merge")) return R.string.sm_t_merge;
        if (m.startsWith("revert")) return R.string.sm_t_revert;
        if (Pattern.compile("\\b(fix|bug|patch|resolve|hotfix|crash)").matcher(m).find()) return R.string.sm_t_fix;
        if (Pattern.compile("\\b(add|new|implement|introduce|support|create)").matcher(m).find()) return R.string.sm_t_feat;
        if (Pattern.compile("\\b(remove|delete|drop|clean)").matcher(m).find()) return R.string.sm_t_remove;
        if (Pattern.compile("\\b(refactor|rename|move|restructure)").matcher(m).find()) return R.string.sm_t_refactor;
        if (Pattern.compile("\\b(doc|readme|comment)").matcher(m).find()) return R.string.sm_t_docs;
        if (Pattern.compile("\\b(test|spec)").matcher(m).find()) return R.string.sm_t_test;
        if (Pattern.compile("\\b(update|bump|upgrade|change|improve|tweak)").matcher(m).find()) return R.string.sm_t_update;
        return R.string.sm_t_other;
    }

    static Summary summarize(Context c, JSONObject commit) {
        Summary s = new Summary();
        JSONObject cm = commit.optJSONObject("commit");
        String message = cm == null ? "" : cm.optString("message");
        s.type = c.getString(typeOf(message));
        JSONArray files = commit.optJSONArray("files");
        int n = files == null ? 0 : files.length();
        int added = 0, modified = 0, removed = 0, renamed = 0;
        int plus = 0, minus = 0;
        boolean ci = false, build = false, secret = false, binary = false, manifest = false;
        Map<String, Integer> areas = new HashMap<>();
        Map<String, Integer> kinds = new HashMap<>();
        for (int i = 0; i < n; i++) {
            JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            String st = f.optString("status");
            if ("added".equals(st)) added++;
            else if ("removed".equals(st)) removed++;
            else if ("renamed".equals(st)) renamed++;
            else modified++;
            plus += f.optInt("additions");
            minus += f.optInt("deletions");
            String name = f.optString("filename");
            String low = name.toLowerCase(Locale.ROOT);
            if (low.startsWith(".github/workflows/")) ci = true;
            if (low.endsWith("build.gradle") || low.endsWith("build.gradle.kts") || low.endsWith("settings.gradle")
                    || low.endsWith("package.json") || low.endsWith("pom.xml") || low.endsWith("requirements.txt")
                    || low.endsWith("dockerfile") || low.endsWith("gradle.properties") || low.endsWith("cargo.toml")
                    || low.endsWith("package-lock.json") || low.endsWith("yarn.lock")) build = true;
            if (low.endsWith("androidmanifest.xml")) manifest = true;
            if (low.endsWith(".env") || low.endsWith(".pem") || low.endsWith(".keystore") || low.endsWith(".jks")
                    || low.contains("id_rsa") || low.contains("secret") || low.endsWith(".p12")) secret = true;
            if (!f.has("patch") && f.optInt("changes") > 0) binary = true;
            int slash = name.indexOf('/');
            String area = slash < 0 ? "/" : name.substring(0, slash);
            Integer ac = areas.get(area);
            areas.put(area, ac == null ? 1 : ac + 1);
            int dot = name.lastIndexOf('.');
            String ext = dot < 0 || dot < name.lastIndexOf('/') ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!ext.isEmpty()) {
                Integer kc = kinds.get(ext);
                kinds.put(ext, kc == null ? 1 : kc + 1);
            }
        }
        JSONObject stats = commit.optJSONObject("stats");
        if (stats != null && n == 0) {
            plus = stats.optInt("additions");
            minus = stats.optInt("deletions");
        }
        s.headline = c.getString(R.string.sm_head, s.type, n, plus, minus);
        JSONObject au = cm == null ? null : cm.optJSONObject("author");
        if (au != null) {
            s.lines.add(c.getString(R.string.sm_by, au.optString("name"), Fmt.ago(Fmt.s(au, "date"))));
        }
        s.lines.add(c.getString(R.string.sm_status, added, modified, removed, renamed));
        String a = top(areas, 3);
        if (!a.isEmpty()) s.lines.add(c.getString(R.string.sm_areas, a));
        String k = top(kinds, 4);
        if (!k.isEmpty()) s.lines.add(c.getString(R.string.sm_kinds, k));
        if (ci) s.risks.add(c.getString(R.string.sm_risk_ci));
        if (build) s.risks.add(c.getString(R.string.sm_risk_build));
        if (manifest) s.risks.add(c.getString(R.string.sm_risk_manifest));
        if (secret) s.risks.add(c.getString(R.string.sm_risk_secret));
        if (minus > 40 && minus > plus * 2) s.risks.add(c.getString(R.string.sm_risk_delete));
        if (plus + minus > 800) s.risks.add(c.getString(R.string.sm_risk_big));
        if (binary) s.risks.add(c.getString(R.string.sm_risk_binary));
        return s;
    }

    private static String top(Map<String, Integer> m, int max) {
        List<Map.Entry<String, Integer>> es = new ArrayList<>(m.entrySet());
        java.util.Collections.sort(es, (x, y) -> y.getValue() - x.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(max, es.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(es.get(i).getKey()).append(" \u00D7").append(es.get(i).getValue());
        }
        return sb.toString();
    }

    static View summaryView(Context c, Summary s) {
        LinearLayout box = Ui.box(c);
        String msgTitle = s.headline;
        TextView head = card(c, msgTitle, R.color.text_primary);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(head);
        StringBuilder body = new StringBuilder();
        for (String l : s.lines) {
            if (body.length() > 0) body.append('\n');
            body.append(l);
        }
        box.addView(card(c, body.toString(), R.color.text_secondary));
        box.addView(Ui.label(c, c.getString(R.string.sm_risks)));
        if (s.risks.isEmpty()) {
            box.addView(card(c, c.getString(R.string.sm_risk_none), R.color.ok));
        } else {
            for (String r : s.risks) box.addView(card(c, "\u26A0 " + r, R.color.warn));
        }
        TextView note = Ui.label(c, c.getString(R.string.sm_note));
        note.setTextColor(Ui.color(c, R.color.text_hint));
        box.addView(note);
        return scroll(c, box);
    }

    static void showSummary(Context c, JSONObject commit) {
        Summary s = summarize(c, commit);
        new Dlg(c).sheet().setTitle(R.string.sm_title).setView(summaryView(c, s))
                .setPositiveButton(R.string.close, null).show();
    }
}

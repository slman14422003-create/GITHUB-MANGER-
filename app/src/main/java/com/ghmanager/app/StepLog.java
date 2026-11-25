package com.ghmanager.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Files the lines of a job log under the steps of that job, the way the GitHub website shows them.
 * The API gives the steps with their start times and the log as one text; each line carries its own
 * timestamp and every step that runs a command or an action opens with a "##[group]Run" line.
 */
final class StepLog {
    private StepLog() {
    }

    static final class Step {
        int number;
        String name = "";
        int code;
        long start;
        long end;
        final List<LogFmt.Line> lines = new ArrayList<>();
    }

    static List<Step> build(JSONArray steps, List<LogFmt.Line> lines) {
        List<Step> out = new ArrayList<>();
        if (steps == null) return out;
        int n = steps.length();
        long[] sec = new long[n];
        boolean[] ok = new boolean[n];
        for (int k = 0; k < n; k++) {
            JSONObject o = steps.optJSONObject(k);
            Step s = new Step();
            if (o != null) {
                s.number = o.optInt("number", k + 1);
                s.name = o.optString("name");
                s.code = LiveBoard.code(o.optString("status"), o.optString("conclusion"));
                s.start = Fmt.parse(Fmt.s(o, "started_at"));
                s.end = Fmt.parse(Fmt.s(o, "completed_at"));
                String st = o.optString("status");
                String con = o.optString("conclusion");
                ok[k] = s.start > 0 && !"skipped".equals(con) && !"pending".equals(st) && !"queued".equals(st);
                sec[k] = s.start / 1000L;
            }
            out.add(s);
        }
        int cur = -1;
        for (int k = 0; k < n; k++) {
            if (ok[k]) {
                cur = k;
                break;
            }
        }
        long last = 0;
        for (LogFmt.Line ln : lines) {
            long t = ln.ts > 0 ? ln.ts / 1000L : last;
            last = t;
            if (cur < 0) continue;
            while (true) {
                int nx = next(ok, cur);
                if (nx < 0) break;
                if (sec[nx] < t) {
                    cur = nx;
                } else if (sec[nx] == t && ln.marker) {
                    cur = pick(out, ok, sec, nx, t, ln.plain);
                    break;
                } else {
                    break;
                }
            }
            Step s = out.get(cur);
            s.lines.add(ln);
            ln.no = s.lines.size();
        }
        return out;
    }

    private static int next(boolean[] ok, int from) {
        for (int k = from + 1; k < ok.length; k++) if (ok[k]) return k;
        return -1;
    }

    /** Several steps can start in the same second: take the one whose name matches the opening line. */
    private static int pick(List<Step> steps, boolean[] ok, long[] sec, int first, long t, String marker) {
        String m = marker.replace("\u25B6 ", "").trim().toLowerCase(Locale.ROOT);
        int k = first;
        while (k >= 0 && sec[k] == t) {
            String nm = steps.get(k).name.trim().toLowerCase(Locale.ROOT);
            if (!nm.isEmpty() && (m.equals(nm) || m.startsWith(nm) || nm.startsWith(m))) return k;
            k = next(ok, k);
        }
        return first;
    }
}

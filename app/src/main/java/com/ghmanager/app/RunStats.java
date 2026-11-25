package com.ghmanager.app;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class RunStats {
    static final String OK = "success";
    static final String BAD = "failure";
    static final String OTHER = "other";

    static final class Sample {
        final long ms;
        final String state;

        Sample(long ms, String state) {
            this.ms = Math.max(0, ms);
            this.state = state == null ? "" : state;
        }

        boolean counted() {
            return isOk() || isBad();
        }

        boolean isOk() {
            return state.equals("success");
        }

        boolean isBad() {
            return state.equals("failure") || state.equals("timed_out") || state.equals("startup_failure");
        }
    }

    final int total;
    final int counted;
    final int success;
    final int failed;
    final double rate;
    final long avg;
    final long median;
    final long p90;
    final long min;
    final long max;

    private RunStats(int total, int counted, int success, int failed, double rate, long avg, long median,
                     long p90, long min, long max) {
        this.total = total;
        this.counted = counted;
        this.success = success;
        this.failed = failed;
        this.rate = rate;
        this.avg = avg;
        this.median = median;
        this.p90 = p90;
        this.min = min;
        this.max = max;
    }

    static Sample from(JSONObject run) {
        if (run == null || !"completed".equals(run.optString("status"))) return null;
        String c = run.isNull("conclusion") ? "" : run.optString("conclusion");
        long a = Fmt.parse(Fmt.s(run, "run_started_at"));
        if (a == 0) a = Fmt.parse(Fmt.s(run, "created_at"));
        long b = Fmt.parse(Fmt.s(run, "updated_at"));
        long ms = a > 0 && b >= a ? b - a : 0;
        return new Sample(ms, c.isEmpty() ? "completed" : c);
    }

    static RunStats of(List<Sample> samples) {
        int total = samples == null ? 0 : samples.size();
        List<Long> d = new ArrayList<>();
        int ok = 0;
        int bad = 0;
        if (samples != null) {
            for (Sample s : samples) {
                if (!s.counted()) continue;
                if (s.isOk()) ok++;
                else bad++;
                if (s.ms > 0) d.add(s.ms);
            }
        }
        int counted = ok + bad;
        double rate = counted == 0 ? 0 : ok * 100.0 / counted;
        long[] arr = new long[d.size()];
        long sum = 0;
        for (int i = 0; i < arr.length; i++) {
            arr[i] = d.get(i);
            sum += arr[i];
        }
        Arrays.sort(arr);
        long avg = arr.length == 0 ? 0 : sum / arr.length;
        return new RunStats(total, counted, ok, bad, rate, avg, median(arr), percentile(arr, 90),
                arr.length == 0 ? 0 : arr[0], arr.length == 0 ? 0 : arr[arr.length - 1]);
    }

    static long median(long[] sorted) {
        int n = sorted.length;
        if (n == 0) return 0;
        if (n % 2 == 1) return sorted[n / 2];
        return (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    static long percentile(long[] sorted, double p) {
        int n = sorted.length;
        if (n == 0) return 0;
        int rank = (int) Math.ceil(p * n / 100.0);
        return sorted[Math.min(n, Math.max(1, rank)) - 1];
    }

    static double[] rateSeries(List<Sample> oldestFirst, int window) {
        int n = oldestFirst == null ? 0 : oldestFirst.size();
        double[] out = new double[n];
        double last = 0;
        int w = Math.max(1, window);
        for (int i = 0; i < n; i++) {
            int ok = 0;
            int all = 0;
            for (int j = Math.max(0, i - w + 1); j <= i; j++) {
                Sample s = oldestFirst.get(j);
                if (!s.counted()) continue;
                all++;
                if (s.isOk()) ok++;
            }
            if (all > 0) last = ok * 100.0 / all;
            out[i] = last;
        }
        return out;
    }

    static List<Sample> fromRuns(org.json.JSONArray runsNewestFirst, int limit) {
        List<Sample> out = new ArrayList<>();
        if (runsNewestFirst == null) return out;
        for (int i = 0; i < runsNewestFirst.length() && out.size() < limit; i++) {
            Sample s = from(runsNewestFirst.optJSONObject(i));
            if (s != null) out.add(0, s);
        }
        return out;
    }
}

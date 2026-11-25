package com.ghmanager.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RunStatsTest {
    private static RunStats.Sample s(long ms, String state) {
        return new RunStats.Sample(ms, state);
    }

    @Test
    public void emptyHasZeros() {
        RunStats st = RunStats.of(new ArrayList<>());
        assertEquals(0, st.total);
        assertEquals(0.0, st.rate, 0.0001);
        assertEquals(0, st.avg);
        assertEquals(0, st.median);
        assertEquals(0, st.p90);
    }

    @Test
    public void nullListIsEmpty() {
        assertEquals(0, RunStats.of(null).total);
    }

    @Test
    public void rateIgnoresCancelledAndSkipped() {
        List<RunStats.Sample> l = Arrays.asList(s(1000, "success"), s(1000, "failure"), s(500, "cancelled"),
                s(500, "skipped"), s(1000, "success"), s(1000, "timed_out"));
        RunStats st = RunStats.of(l);
        assertEquals(6, st.total);
        assertEquals(4, st.counted);
        assertEquals(2, st.success);
        assertEquals(2, st.failed);
        assertEquals(50.0, st.rate, 0.0001);
    }

    @Test
    public void averageMedianOddAndEven() {
        RunStats odd = RunStats.of(Arrays.asList(s(1000, "success"), s(5000, "success"), s(3000, "failure")));
        assertEquals(3000, odd.avg);
        assertEquals(3000, odd.median);
        RunStats even = RunStats.of(Arrays.asList(s(1000, "success"), s(2000, "success"), s(3000, "success"),
                s(10000, "success")));
        assertEquals(4000, even.avg);
        assertEquals(2500, even.median);
    }

    @Test
    public void p90UsesNearestRank() {
        List<RunStats.Sample> l = new ArrayList<>();
        for (int i = 1; i <= 10; i++) l.add(s(i * 1000L, "success"));
        assertEquals(9000, RunStats.of(l).p90);
        List<RunStats.Sample> one = Arrays.asList(s(7000, "success"));
        assertEquals(7000, RunStats.of(one).p90);
        List<RunStats.Sample> twenty = new ArrayList<>();
        for (int i = 1; i <= 20; i++) twenty.add(s(i * 1000L, "success"));
        assertEquals(18000, RunStats.of(twenty).p90);
    }

    @Test
    public void durationsExcludeOtherStatesAndZero() {
        RunStats st = RunStats.of(Arrays.asList(s(2000, "success"), s(999999, "cancelled"), s(0, "failure")));
        assertEquals(2000, st.avg);
        assertEquals(2000, st.min);
        assertEquals(2000, st.max);
    }

    @Test
    public void percentileEdges() {
        assertEquals(0, RunStats.percentile(new long[0], 90));
        assertEquals(5, RunStats.percentile(new long[]{5}, 0));
        assertEquals(3, RunStats.percentile(new long[]{1, 2, 3}, 100));
    }

    @Test
    public void rateSeriesRollsOverWindow() {
        List<RunStats.Sample> l = Arrays.asList(s(1, "failure"), s(1, "success"), s(1, "success"), s(1, "success"));
        double[] r = RunStats.rateSeries(l, 2);
        assertArrayEquals(new double[]{0, 50, 100, 100}, r, 0.0001);
    }

    @Test
    public void rateSeriesCarriesPreviousOverUncounted() {
        List<RunStats.Sample> l = Arrays.asList(s(1, "success"), s(1, "cancelled"));
        assertArrayEquals(new double[]{100, 100}, RunStats.rateSeries(l, 1), 0.0001);
        assertEquals(0, RunStats.rateSeries(null, 5).length);
    }

    @Test
    public void fromRunReadsDurationAndConclusion() throws Exception {
        JSONObject run = new JSONObject("{\"status\":\"completed\",\"conclusion\":\"success\","
                + "\"run_started_at\":\"2026-11-01T10:00:00Z\",\"updated_at\":\"2026-11-01T10:02:30Z\"}");
        RunStats.Sample sm = RunStats.from(run);
        assertEquals(150000, sm.ms);
        assertEquals("success", sm.state);
    }

    @Test
    public void fromRunSkipsUnfinished() throws Exception {
        assertNull(RunStats.from(new JSONObject("{\"status\":\"in_progress\"}")));
        assertNull(RunStats.from(null));
    }

    @Test
    public void fromRunHandlesNullConclusionAndBadDates() throws Exception {
        JSONObject run = new JSONObject("{\"status\":\"completed\",\"conclusion\":null}");
        RunStats.Sample sm = RunStats.from(run);
        assertEquals(0, sm.ms);
        assertEquals("completed", sm.state);
    }

    @Test
    public void fromRunsReversesToOldestFirstAndLimits() throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 3; i >= 1; i--) {
            a.put(new JSONObject("{\"status\":\"completed\",\"conclusion\":\"" + (i == 3 ? "failure" : "success")
                    + "\",\"run_started_at\":\"2026-11-01T10:00:00Z\",\"updated_at\":\"2026-11-01T10:00:0" + i + "Z\"}"));
        }
        a.put(new JSONObject("{\"status\":\"queued\"}"));
        List<RunStats.Sample> l = RunStats.fromRuns(a, 2);
        assertEquals(2, l.size());
        assertEquals(2000, l.get(0).ms);
        assertEquals(3000, l.get(1).ms);
        assertEquals("success", l.get(0).state);
        assertEquals("failure", l.get(1).state);
    }
}

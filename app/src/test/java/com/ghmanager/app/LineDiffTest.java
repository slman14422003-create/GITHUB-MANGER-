package com.ghmanager.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class LineDiffTest {

    private static String rebuild(LineDiff.Result r, char keep) {
        List<String> out = new ArrayList<>();
        for (LineDiff.Op o : r.ops) {
            if (o.type == ' ' || o.type == keep) out.add(o.text);
        }
        return String.join("\n", out);
    }

    private static String repeat(String prefix, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append('\n');
            sb.append(prefix).append(i);
        }
        return sb.toString();
    }

    @Test
    public void identicalTextHasNoChanges() {
        LineDiff.Result r = LineDiff.compute("a\nb\nc", "a\nb\nc");
        assertFalse(r.changed());
        assertEquals(0, r.added);
        assertEquals(0, r.removed);
        assertFalse(r.coarse);
        assertEquals(3, r.ops.size());
        for (LineDiff.Op o : r.ops) assertEquals(' ', o.type);
    }

    @Test
    public void nullAndEmptyAreEquivalent() {
        assertFalse(LineDiff.compute(null, null).changed());
        assertFalse(LineDiff.compute("", "").changed());
        assertFalse(LineDiff.compute(null, "").changed());
        assertFalse(LineDiff.compute("", null).changed());
        assertEquals(0, LineDiff.compute(null, null).ops.size());
    }

    @Test
    public void nullToTextIsAllAdded() {
        LineDiff.Result r = LineDiff.compute(null, "a\nb");
        assertEquals(2, r.added);
        assertEquals(0, r.removed);
        assertTrue(r.changed());
        assertEquals('+', r.ops.get(0).type);
        assertEquals('+', r.ops.get(1).type);
    }

    @Test
    public void textToEmptyIsAllRemoved() {
        LineDiff.Result r = LineDiff.compute("a\nb", "");
        assertEquals(0, r.added);
        assertEquals(2, r.removed);
        assertTrue(r.changed());
    }

    @Test
    public void insertedLine() {
        LineDiff.Result r = LineDiff.compute("a\nb", "a\nx\nb");
        assertEquals(1, r.added);
        assertEquals(0, r.removed);
        assertEquals(3, r.ops.size());
        assertEquals(' ', r.ops.get(0).type);
        assertEquals('+', r.ops.get(1).type);
        assertEquals("x", r.ops.get(1).text);
        assertEquals(' ', r.ops.get(2).type);
    }

    @Test
    public void deletedLine() {
        LineDiff.Result r = LineDiff.compute("a\nx\nb", "a\nb");
        assertEquals(0, r.added);
        assertEquals(1, r.removed);
        assertEquals(3, r.ops.size());
        assertEquals('-', r.ops.get(1).type);
        assertEquals("x", r.ops.get(1).text);
    }

    @Test
    public void replacedLine() {
        LineDiff.Result r = LineDiff.compute("a\nb\nc", "a\nX\nc");
        assertEquals(1, r.added);
        assertEquals(1, r.removed);
        assertFalse(r.coarse);
        assertEquals("a\nb\nc", rebuild(r, '-'));
        assertEquals("a\nX\nc", rebuild(r, '+'));
    }

    @Test
    public void crlfIsIgnored() {
        LineDiff.Result r = LineDiff.compute("a\r\nb\r\nc\r\n", "a\nb\nc\n");
        assertFalse(r.changed());
        assertEquals(3, r.ops.size());
    }

    @Test
    public void trailingEmptyLineIsIgnored() {
        assertFalse(LineDiff.compute("a\nb\n", "a\nb").changed());
        assertFalse(LineDiff.compute("a\nb", "a\nb\n").changed());
    }

    @Test
    public void crlfAndTrailingNewlineTogether() {
        assertFalse(LineDiff.compute("a\r\nb\r\n", "a\nb").changed());
    }

    @Test
    public void middleBlankLineIsStillADifference() {
        LineDiff.Result r = LineDiff.compute("a\nb", "a\n\nb");
        assertEquals(1, r.added);
        assertEquals(0, r.removed);
    }

    @Test
    public void smallDiffIsNotCoarse() {
        LineDiff.Result r = LineDiff.compute(repeat("o", 50), repeat("o", 50) + "\nextra");
        assertFalse(r.coarse);
        assertEquals(1, r.added);
        assertEquals(0, r.removed);
    }

    @Test
    public void coarseWhenDistanceExceedsLimit() {
        String oldText = repeat("old", 1600);
        String newText = repeat("new", 1600);
        LineDiff.Result r = LineDiff.compute(oldText, newText);
        assertTrue(r.coarse);
        assertEquals(1600, r.removed);
        assertEquals(1600, r.added);
        assertEquals(3200, r.ops.size());
        for (int i = 0; i < 1600; i++) assertEquals('-', r.ops.get(i).type);
        for (int i = 1600; i < 3200; i++) assertEquals('+', r.ops.get(i).type);
        assertEquals(oldText, rebuild(r, '-'));
        assertEquals(newText, rebuild(r, '+'));
    }

    @Test
    public void coarseKeepsCommonPrefixAndSuffix() {
        String oldText = "head\n" + repeat("old", 1600) + "\ntail";
        String newText = "head\n" + repeat("new", 1600) + "\ntail";
        LineDiff.Result r = LineDiff.compute(oldText, newText);
        assertTrue(r.coarse);
        assertEquals(' ', r.ops.get(0).type);
        assertEquals("head", r.ops.get(0).text);
        assertEquals(' ', r.ops.get(r.ops.size() - 1).type);
        assertEquals("tail", r.ops.get(r.ops.size() - 1).text);
        assertEquals(oldText, rebuild(r, '-'));
        assertEquals(newText, rebuild(r, '+'));
    }

    @Test
    public void rebuildsOldAndNewFromOps() {
        String oldText = "a\nb\nc\nd\ne\nf\ng";
        String newText = "a\nB\nc\nd\nX\nY\ng\nh";
        LineDiff.Result r = LineDiff.compute(oldText, newText);
        assertFalse(r.coarse);
        assertEquals(oldText, rebuild(r, '-'));
        assertEquals(newText, rebuild(r, '+'));
        int added = 0;
        int removed = 0;
        for (LineDiff.Op o : r.ops) {
            if (o.type == '+') added++;
            if (o.type == '-') removed++;
        }
        assertEquals(added, r.added);
        assertEquals(removed, r.removed);
    }

    @Test
    public void rebuildsWhenLinesMoveAround() {
        String oldText = "1\n2\n3\n4\n5\n6";
        String newText = "4\n5\n6\n1\n2\n3";
        LineDiff.Result r = LineDiff.compute(oldText, newText);
        assertEquals(oldText, rebuild(r, '-'));
        assertEquals(newText, rebuild(r, '+'));
        assertTrue(r.changed());
    }

    @Test
    public void rebuildsWithRepeatedLines() {
        String oldText = "x\nx\ny\nx\nx";
        String newText = "x\ny\nx\ny\nx";
        LineDiff.Result r = LineDiff.compute(oldText, newText);
        assertEquals(oldText, rebuild(r, '-'));
        assertEquals(newText, rebuild(r, '+'));
    }
}

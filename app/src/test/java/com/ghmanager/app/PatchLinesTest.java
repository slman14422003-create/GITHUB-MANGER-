package com.ghmanager.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class PatchLinesTest {
    private static final String PATCH = "@@ -10,4 +10,5 @@ class A {\n"
            + " keep1\n"
            + "-gone\n"
            + "+added1\n"
            + "+added2\n"
            + " keep2\n"
            + "@@ -40,2 +41,2 @@\n"
            + "-x\n"
            + "+y\n"
            + "\\ No newline at end of file\n";

    @Test
    public void numbersFollowHunks() {
        List<PatchLines.Line> l = PatchLines.parse(PATCH);
        assertEquals(10, l.size());
        assertEquals('@', l.get(0).type);
        assertFalse(l.get(0).commentable());
        assertEquals(' ', l.get(1).type);
        assertEquals("RIGHT", l.get(1).side());
        assertEquals(10, l.get(1).number());
        assertEquals('-', l.get(2).type);
        assertEquals("LEFT", l.get(2).side());
        assertEquals(11, l.get(2).number());
        assertEquals("RIGHT", l.get(3).side());
        assertEquals(11, l.get(3).number());
        assertEquals(12, l.get(4).number());
        assertEquals(13, l.get(5).number());
        assertEquals("LEFT", l.get(7).side());
        assertEquals(40, l.get(7).number());
        assertEquals(41, l.get(8).number());
    }

    @Test
    public void noNewlineMarkerIsNotCommentable() {
        List<PatchLines.Line> l = PatchLines.parse(PATCH);
        assertFalse(l.get(9).commentable());
    }

    @Test
    public void contextLineKeepsBothCounters() {
        List<PatchLines.Line> l = PatchLines.parse("@@ -1,2 +1,2 @@\n a\n-b\n+c\n");
        assertEquals(1, l.get(1).oldNo);
        assertEquals(1, l.get(1).newNo);
        assertEquals(2, l.get(2).oldNo);
        assertEquals(2, l.get(3).newNo);
    }

    @Test
    public void emptyContextLineInsideHunkCounts() {
        List<PatchLines.Line> l = PatchLines.parse("@@ -1,3 +1,3 @@\n a\n\n+b\n c\n");
        assertEquals(5, l.size());
        assertEquals(' ', l.get(2).type);
        assertEquals(2, l.get(2).number());
        assertEquals(3, l.get(3).number());
        assertEquals(4, l.get(4).number());
    }

    @Test
    public void hunkWithoutCountsParses() {
        List<PatchLines.Line> l = PatchLines.parse("@@ -5 +7 @@\n+a\n");
        assertTrue(l.get(1).commentable());
        assertEquals(7, l.get(1).number());
    }

    @Test
    public void malformedHeaderDisablesComments() {
        List<PatchLines.Line> l = PatchLines.parse("@@ broken\n+a\n");
        assertFalse(l.get(1).commentable());
    }

    @Test
    public void emptyPatch() {
        assertTrue(PatchLines.parse("").isEmpty());
        assertTrue(PatchLines.parse(null).isEmpty());
    }

    @Test
    public void keysDistinguishSides() {
        assertFalse(PatchLines.key("a", "LEFT", 3).equals(PatchLines.key("a", "RIGHT", 3)));
        assertFalse(PatchLines.key("a", "LEFT", 3).equals(PatchLines.key("a", "LEFT", 4)));
    }
}

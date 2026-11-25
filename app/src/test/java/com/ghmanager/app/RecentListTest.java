package com.ghmanager.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RecentListTest {
    @Test
    public void pushDedupesIgnoringCaseAndCaps() {
        List<String> l = new ArrayList<>();
        l = RecentList.push(l, "a", 3);
        l = RecentList.push(l, "b", 3);
        l = RecentList.push(l, "A", 3);
        assertEquals(Arrays.asList("A", "b"), l);
        l = RecentList.push(l, "c", 3);
        l = RecentList.push(l, "d", 3);
        assertEquals(Arrays.asList("d", "c", "A"), l);
        assertEquals(l, RecentList.push(l, "  ", 3));
    }

    @Test
    public void removeAndMatch() {
        List<String> l = Arrays.asList("d", "c", "A");
        assertEquals(Arrays.asList("d", "A"), RecentList.remove(l, "c"));
        assertEquals(Arrays.asList("A"), RecentList.prefixMatches(l, "a", 5));
    }
}

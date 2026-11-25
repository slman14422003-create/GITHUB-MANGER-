package com.ghmanager.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SearchMemoTest {
    @Test
    public void ttlAndLru() {
        SearchMemo<String> m = new SearchMemo<>(2, 1000);
        m.put("a", "1", 0);
        m.put("b", "2", 0);
        assertEquals("1", m.get("a", 500));
        m.put("c", "3", 500);
        assertNull(m.get("b", 500));
        assertNull(m.get("a", 1500));
        assertEquals("3", m.get("c", 1200));
    }

    @Test
    public void keyIncludesAllParts() {
        assertEquals("1|2|s|q", SearchMemo.key(1, "q", "s", 2));
    }
}

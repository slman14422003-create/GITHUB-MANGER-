package com.ghmanager.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class SearchHitTest {
    @Test
    public void parsesRepo() throws Exception {
        JSONObject o = new JSONObject("{\"full_name\":\"o/r\",\"name\":\"r\",\"owner\":{\"login\":\"o\"},"
                + "\"description\":\"d\",\"language\":\"Java\",\"stargazers_count\":5,\"default_branch\":\"dev\"}");
        SearchHit h = SearchHit.repo(o);
        assertEquals("o/r", h.title);
        assertEquals("o", h.owner);
        assertEquals("r", h.repo);
        assertEquals("dev", h.branch);
        assertTrue(h.sub.contains("Java"));
    }

    @Test
    public void parsesCode() throws Exception {
        JSONObject o = new JSONObject("{\"name\":\"A.java\",\"path\":\"src/A.java\","
                + "\"repository\":{\"full_name\":\"o/r\",\"name\":\"r\",\"owner\":{\"login\":\"o\"}}}");
        SearchHit h = SearchHit.code(o);
        assertEquals("A.java", h.title);
        assertEquals("src/A.java", h.path);
        assertEquals("o/r · src/A.java", h.sub);
    }

    @Test
    public void parsesIssueAndPull() throws Exception {
        JSONObject i = new JSONObject("{\"number\":7,\"title\":\"T\",\"state\":\"open\","
                + "\"repository_url\":\"https://api.github.com/repos/o/r\",\"user\":{\"login\":\"u\"}}");
        SearchHit hi = SearchHit.issue(i);
        assertFalse(hi.pull);
        assertEquals(SearchQuery.ISSUES, hi.kind);
        assertEquals(7L, hi.number);
        assertEquals("o", hi.owner);
        JSONObject p = new JSONObject("{\"number\":8,\"title\":\"P\",\"state\":\"closed\","
                + "\"repository_url\":\"https://api.github.com/repos/o/r\","
                + "\"pull_request\":{\"merged_at\":\"2026-01-01T00:00:00Z\"}}");
        SearchHit hp = SearchHit.issue(p);
        assertTrue(hp.pull);
        assertTrue(hp.merged);
        assertEquals(SearchQuery.PULLS, hp.kind);
    }
}

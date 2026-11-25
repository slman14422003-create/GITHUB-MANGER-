package com.ghmanager.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PendingReviewsTest {
    @Test
    public void upsertReplacesSameLine() {
        PendingReviews.Review r = new PendingReviews.Review();
        r.upsert(new PendingReviews.Comment("a.java", "RIGHT", 5, "one"));
        r.upsert(new PendingReviews.Comment("a.java", "RIGHT", 5, "two"));
        r.upsert(new PendingReviews.Comment("a.java", "LEFT", 5, "left"));
        assertEquals(2, r.comments.size());
        assertEquals("two", r.find("a.java", "RIGHT", 5).body);
        assertEquals("left", r.find("a.java", "LEFT", 5).body);
    }

    @Test
    public void removeWorks() {
        PendingReviews.Review r = new PendingReviews.Review();
        r.upsert(new PendingReviews.Comment("a", "RIGHT", 1, "x"));
        assertTrue(r.remove("a", "RIGHT", 1));
        assertFalse(r.remove("a", "RIGHT", 1));
        assertTrue(r.isEmpty());
    }

    @Test
    public void jsonRoundTrip() throws Exception {
        PendingReviews.Review r = new PendingReviews.Review();
        r.commitId = "abc";
        r.body = "hello";
        r.upsert(new PendingReviews.Comment("dir/b.txt", "LEFT", 9, "why"));
        PendingReviews.Review back = PendingReviews.Review.fromJson(new JSONObject(r.toJson().toString()));
        assertEquals("abc", back.commitId);
        assertEquals("hello", back.body);
        assertEquals(1, back.comments.size());
        assertNotNull(back.find("dir/b.txt", "LEFT", 9));
        assertNull(back.find("dir/b.txt", "RIGHT", 9));
    }

    @Test
    public void apiCommentsShape() throws Exception {
        PendingReviews.Review r = new PendingReviews.Review();
        r.upsert(new PendingReviews.Comment("p", "RIGHT", 3, "b"));
        JSONArray a = r.apiComments();
        JSONObject o = a.getJSONObject(0);
        assertEquals("p", o.getString("path"));
        assertEquals(3, o.getInt("line"));
        assertEquals("RIGHT", o.getString("side"));
        assertEquals("b", o.getString("body"));
    }

    @Test
    public void invalidStoredCommentsAreDropped() throws Exception {
        JSONObject o = new JSONObject("{\"commitId\":\"c\",\"comments\":[{\"path\":\"\",\"line\":1,\"body\":\"x\"},"
                + "{\"path\":\"a\",\"line\":0,\"body\":\"x\"},{\"path\":\"a\",\"line\":2,\"side\":\"weird\",\"body\":\"ok\"}]}");
        PendingReviews.Review r = PendingReviews.Review.fromJson(o);
        assertEquals(1, r.comments.size());
        assertEquals("RIGHT", r.comments.get(0).side);
    }

    @Test
    public void reviewEventRules() {
        assertTrue(ReviewDialog.valid("APPROVE", "", 0));
        assertFalse(ReviewDialog.valid("REQUEST_CHANGES", "", 3));
        assertTrue(ReviewDialog.valid("REQUEST_CHANGES", "fix", 0));
        assertFalse(ReviewDialog.valid("COMMENT", "", 0));
        assertTrue(ReviewDialog.valid("COMMENT", "", 1));
        assertTrue(ReviewDialog.valid("COMMENT", "hi", 0));
        assertFalse(ReviewDialog.valid("OTHER", "hi", 1));
    }
}

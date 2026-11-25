package com.ghmanager.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GitShaTest {
    @Test
    public void emptyBlobMatchesGit() {
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", GitSha.blob(new byte[0]));
    }

    @Test
    public void helloBlobMatchesGit() {
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitSha.blob("hello\n"));
    }

    @Test
    public void utf8LengthIsBytes() {
        assertFalse(GitSha.blob("é").equals(GitSha.blob("e")));
    }

    @Test
    public void sameRules() {
        assertTrue(CommitQueue.same(null, null));
        assertTrue(CommitQueue.same(null, ""));
        assertTrue(CommitQueue.same("", null));
        assertTrue(CommitQueue.same("a", "a"));
        assertFalse(CommitQueue.same("a", null));
        assertFalse(CommitQueue.same(null, "a"));
        assertFalse(CommitQueue.same("a", "b"));
    }

    @Test
    public void draftKeysAreScopedAndStable() {
        String k = Drafts.key("acc", "o", "r", "main", "a/b.txt");
        assertEquals(k, Drafts.key("acc", "o", "r", "main", "a/b.txt"));
        assertFalse(k.equals(Drafts.key("acc2", "o", "r", "main", "a/b.txt")));
        assertFalse(k.equals(Drafts.key("acc", "o", "r", "dev", "a/b.txt")));
        assertFalse(k.equals(Drafts.key("acc", "o", "r", "main", "a/c.txt")));
        assertFalse(Drafts.key("a", "bc", "r", "m", "p").equals(Drafts.key("ab", "c", "r", "m", "p")));
    }

    @Test
    public void reviewKeysAreScoped() {
        assertFalse(PendingReviews.key("a", "o", "r", 1).equals(PendingReviews.key("a", "o", "r", 2)));
        assertFalse(PendingReviews.key("a", "o", "r", 1).equals(PendingReviews.key("b", "o", "r", 1)));
    }
}

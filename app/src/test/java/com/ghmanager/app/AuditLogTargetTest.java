package com.ghmanager.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AuditLogTargetTest {
    @Test
    public void stripsRepoPrefix() {
        assertEquals("/actions/runs/5", AuditLog.targetOf("/repos/o/r/actions/runs/5"));
        assertEquals("/git/refs/heads/x", AuditLog.targetOf("/repos/o/r/git/refs/heads/x"));
    }

    @Test
    public void repoItselfHasEmptyTarget() {
        assertEquals("", AuditLog.targetOf("/repos/o/r"));
    }

    @Test
    public void dropsQueryAndFragment() {
        assertEquals("/contents/a.txt", AuditLog.targetOf("/repos/o/r/contents/a.txt?ref=main"));
        assertEquals("/contents/a.txt", AuditLog.targetOf("/repos/o/r/contents/a.txt#x"));
    }

    @Test
    public void nonRepoPathsKeptWhole() {
        assertEquals("/user/following/x", AuditLog.targetOf("/user/following/x"));
    }

    @Test
    public void nullIsEmpty() {
        assertEquals("", AuditLog.targetOf(null));
        assertEquals("", AuditLog.repoOf(null));
    }

    @Test
    public void tokensAreRedacted() {
        String t = "ghp_" + "a".repeat(30);
        String out = AuditLog.targetOf("/repos/o/r/contents/" + t);
        assertFalse(out.contains(t));
        assertTrue(out.contains("***"));
    }

    @Test
    public void targetIsCapped() {
        String out = AuditLog.targetOf("/repos/o/r/" + "a".repeat(400));
        assertEquals(140, out.length());
    }

    @Test
    public void repoOfExtractsOwnerAndName() {
        assertEquals("o/r", AuditLog.repoOf("/repos/o/r/issues/1"));
        assertEquals("", AuditLog.repoOf("/user/starred/o/r"));
    }
}

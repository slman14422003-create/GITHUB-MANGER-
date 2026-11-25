package com.ghmanager.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

public class VerifyCategoryTest {
    @Test
    public void deleteCategories() {
        assertEquals(Verify.DELETE, Verify.category("DELETE", "/repos/o/r", null));
        assertEquals(Verify.DELETE, Verify.category("DELETE", "/repos/o/r/actions/runs/1", null));
        assertEquals(Verify.SECRETS, Verify.category("DELETE", "/repos/o/r/actions/secrets/X", null));
        assertEquals(Verify.COLLAB, Verify.category("DELETE", "/repos/o/r/collaborators/u", null));
        assertEquals(Verify.PROTECTION, Verify.category("DELETE", "/repos/o/r/branches/main/protection", null));
        assertNull(Verify.category("DELETE", "/user/starred/o/r", null));
    }

    @Test
    public void patchCategories() throws Exception {
        assertEquals(Verify.REPO, Verify.category("PATCH", "/repos/o/r", new JSONObject("{}")));
        assertNull(Verify.category("PATCH", "/repos/o/r/issues/1", new JSONObject("{}")));
        assertEquals(Verify.FORCE, Verify.category("PATCH", "/repos/o/r/git/refs/heads/x",
                new JSONObject("{\"force\":true}")));
        assertNull(Verify.category("PATCH", "/repos/o/r/git/refs/heads/x", new JSONObject("{\"force\":false}")));
        assertNull(Verify.category("PATCH", "/repos/o/r/git/refs/heads/x", null));
    }

    @Test
    public void putCategories() {
        assertEquals(Verify.SECRETS, Verify.category("PUT", "/repos/o/r/actions/secrets/X", null));
        assertEquals(Verify.COLLAB, Verify.category("PUT", "/repos/o/r/collaborators/u", null));
        assertEquals(Verify.PROTECTION, Verify.category("PUT", "/repos/o/r/branches/main/protection", null));
        assertEquals(Verify.MERGE, Verify.category("PUT", "/repos/o/r/pulls/3/merge", null));
        assertEquals(Verify.ACTIONS, Verify.category("PUT", "/repos/o/r/actions/permissions", null));
        assertEquals(Verify.ACTIONS, Verify.category("PUT", "/repos/o/r/actions/permissions/workflow", null));
        assertNull(Verify.category("PUT", "/repos/o/r/contents/a.txt", null));
    }

    @Test
    public void readsAndPostsAreNotSensitive() {
        assertNull(Verify.category("GET", "/repos/o/r", null));
        assertNull(Verify.category("POST", "/repos/o/r/actions/runs/1/rerun", null));
    }

    @Test
    public void nullArguments() {
        assertNull(Verify.category(null, "/repos/o/r", null));
        assertNull(Verify.category("DELETE", null, null));
    }
}

package com.ghmanager.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class VerifySensitiveTest {

    private static boolean s(String method, String path, String body) throws Exception {
        return Verify.sensitive(method, path, body == null ? null : new JSONObject(body));
    }

    @Test
    public void deleteIsSensitive() throws Exception {
        assertTrue(s("DELETE", "/repos/o/r", null));
        assertTrue(s("DELETE", "/repos/o/r/contents/a.txt", "{\"message\":\"m\"}"));
        assertTrue(s("DELETE", "/repos/o/r/git/refs/heads/x", null));
        assertTrue(s("DELETE", "/repos/o/r/actions/runs/1", null));
        assertTrue(s("DELETE", "/user/following/x", null));
    }

    @Test
    public void deleteOfStarIsNotSensitive() throws Exception {
        assertFalse(s("DELETE", "/user/starred/o/r", null));
        assertFalse(s("DELETE", "/user/starred/o/r", "{}"));
        assertFalse(s("DELETE", "/user/starred/", null));
    }

    @Test
    public void patchOfRepoIsSensitive() throws Exception {
        assertTrue(s("PATCH", "/repos/o/r", null));
        assertTrue(s("PATCH", "/repos/o/r", "{\"private\":true}"));
        assertTrue(s("PATCH", "/repos/owner-1/repo.name", "{}"));
    }

    @Test
    public void patchOfOtherRepoPathsIsNotSensitive() throws Exception {
        assertFalse(s("PATCH", "/repos/o/r/issues/1", "{\"state\":\"closed\"}"));
        assertFalse(s("PATCH", "/repos/o/r/", null));
        assertFalse(s("PATCH", "/repos/o", null));
        assertFalse(s("PATCH", "/user", "{\"name\":\"n\"}"));
    }

    @Test
    public void patchOfRefIsSensitiveOnlyWhenForced() throws Exception {
        String ref = "/repos/o/r/git/refs/heads/main";
        assertTrue(s("PATCH", ref, "{\"sha\":\"x\",\"force\":true}"));
        assertFalse(s("PATCH", ref, "{\"sha\":\"x\",\"force\":false}"));
        assertFalse(s("PATCH", ref, "{\"sha\":\"x\"}"));
        assertFalse(s("PATCH", ref, "{}"));
        assertFalse(s("PATCH", ref, null));
    }

    @Test
    public void forceOnNonRefPatchIsNotSensitive() throws Exception {
        assertFalse(s("PATCH", "/repos/o/r/issues/1", "{\"force\":true}"));
    }

    @Test
    public void putOnSensitiveEndpoints() throws Exception {
        assertTrue(s("PUT", "/repos/o/r/actions/secrets/NAME", "{\"encrypted_value\":\"e\"}"));
        assertTrue(s("PUT", "/repos/o/r/actions/secrets/NAME", null));
        assertTrue(s("PUT", "/repos/o/r/collaborators/user", "{\"permission\":\"push\"}"));
        assertTrue(s("PUT", "/repos/o/r/branches/main/protection", "{}"));
        assertTrue(s("PUT", "/repos/o/r/pulls/3/merge", "{}"));
        assertTrue(s("PUT", "/repos/o/r/actions/permissions", "{}"));
        assertTrue(s("PUT", "/repos/o/r/actions/permissions/workflow", "{}"));
    }

    @Test
    public void putOnOtherEndpointsIsNotSensitive() throws Exception {
        assertFalse(s("PUT", "/repos/o/r/contents/a.txt", "{\"message\":\"m\",\"content\":\"YQ==\"}"));
        assertFalse(s("PUT", "/repos/o/r/contents/a.txt", null));
        assertFalse(s("PUT", "/user/starred/o/r", null));
        assertFalse(s("PUT", "/repos/o/r/branches/main/protection/required_status_checks", "{}"));
        assertFalse(s("PUT", "/repos/o/r/subscription", "{}"));
    }

    @Test
    public void getIsNeverSensitive() throws Exception {
        assertFalse(s("GET", "/repos/o/r", null));
        assertFalse(s("GET", "/repos/o/r/actions/secrets/NAME", null));
        assertFalse(s("GET", "/repos/o/r/collaborators/user", null));
        assertFalse(s("GET", "/repos/o/r/branches/main/protection", null));
        assertFalse(s("GET", "/repos/o/r/git/refs/heads/main", "{\"force\":true}"));
    }

    @Test
    public void postIsNeverSensitive() throws Exception {
        assertFalse(s("POST", "/repos/o/r/forks", null));
        assertFalse(s("POST", "/repos/o/r/pulls/3/merge", "{}"));
        assertFalse(s("POST", "/repos/o/r/git/refs", "{\"ref\":\"refs/heads/x\",\"sha\":\"s\"}"));
        assertFalse(s("POST", "/repos/o/r/actions/secrets/NAME", null));
        assertFalse(s("POST", "/repos/o/r/collaborators/user", null));
        assertFalse(s("POST", "/repos/o/r/git/refs/heads/main", "{\"force\":true}"));
    }
}

package com.ghmanager.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.ghmanager.app.GitHubApi.ApiException;
import com.ghmanager.app.GitHubApi.BlobInfo;
import com.ghmanager.app.GitHubApi.HeadMoved;
import com.ghmanager.app.GitHubApi.TreeEntry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class ApiParseTest {

    private static final String TOKEN = "ghp_" + "a1B2c3D4e5F6g7H8i9J0".repeat(2);

    private static JSONArray errs(String json) throws Exception {
        return new JSONArray(json);
    }

    private static final String TREE = "{\"sha\":\"root\",\"tree\":["
            + "{\"path\":\"src\",\"type\":\"tree\",\"sha\":\"t0\",\"mode\":\"040000\"},"
            + "{\"path\":\"src/a.txt\",\"type\":\"blob\",\"sha\":\"s1\",\"mode\":\"100644\",\"size\":12},"
            + "{\"path\":\"src/run.sh\",\"type\":\"blob\",\"sha\":\"s2\",\"mode\":\"100755\",\"size\":30},"
            + "{\"path\":\"src/nomode\",\"type\":\"blob\",\"sha\":\"s3\"},"
            + "{\"path\":\"src/sub\",\"type\":\"tree\",\"sha\":\"t1\"},"
            + "{\"path\":\"src/sub/b.txt\",\"type\":\"blob\",\"sha\":\"s4\",\"size\":5},"
            + "{\"path\":\"srcx/c.txt\",\"type\":\"blob\",\"sha\":\"s5\",\"size\":7},"
            + "{\"path\":\"other/d.txt\",\"type\":\"blob\",\"sha\":\"s6\"}"
            + "]}";

    @Test
    public void toExceptionUsesMessage() {
        ApiException e = ApiParse.toException(404, "{\"message\":\"Not Found\"}");
        assertEquals(404, e.code);
        assertEquals("Not Found", e.getMessage());
    }

    @Test
    public void toExceptionAppendsFirstErrorObjectMessage() {
        ApiException e = ApiParse.toException(422,
                "{\"message\":\"Validation Failed\",\"errors\":[{\"message\":\"name already exists\"},{\"message\":\"other\"}]}");
        assertEquals(422, e.code);
        assertEquals("Validation Failed - name already exists", e.getMessage());
    }

    @Test
    public void toExceptionAppendsFirstErrorString() {
        ApiException e = ApiParse.toException(422, "{\"message\":\"Bad\",\"errors\":[\"oops\"]}");
        assertEquals("Bad - oops", e.getMessage());
    }

    @Test
    public void toExceptionIgnoresErrorObjectWithoutMessage() {
        ApiException e = ApiParse.toException(422, "{\"message\":\"Bad\",\"errors\":[{\"code\":\"invalid\"}]}");
        assertEquals("Bad", e.getMessage());
    }

    @Test
    public void toExceptionIgnoresEmptyErrorsArray() {
        ApiException e = ApiParse.toException(422, "{\"message\":\"Bad\",\"errors\":[]}");
        assertEquals("Bad", e.getMessage());
    }

    @Test
    public void toExceptionKeepsShortNonJsonText() {
        ApiException e = ApiParse.toException(502, "Bad Gateway");
        assertEquals(502, e.code);
        assertEquals("Bad Gateway", e.getMessage());
    }

    @Test
    public void toExceptionTruncatesLongNonJsonText() {
        String body = "x".repeat(500);
        ApiException e = ApiParse.toException(500, body);
        assertEquals(300, e.getMessage().length());
        assertEquals(body.substring(0, 300), e.getMessage());
    }

    @Test
    public void toExceptionEmptyBodyFallsBackToHttpCode() {
        assertEquals("HTTP 500", ApiParse.toException(500, "").getMessage());
        assertEquals("HTTP 500", ApiParse.toException(500, null).getMessage());
        assertEquals(500, ApiParse.toException(500, null).code);
    }

    @Test
    public void toExceptionHidesTokenInJsonMessage() {
        ApiException e = ApiParse.toException(401, "{\"message\":\"bad credentials " + TOKEN + " rejected\"}");
        assertEquals("bad credentials *** rejected", e.getMessage());
        assertFalse(e.getMessage().contains("ghp_"));
    }

    @Test
    public void toExceptionHidesTokenInPlainText() {
        ApiException e = ApiParse.toException(401, "token " + TOKEN);
        assertEquals("token ***", e.getMessage());
    }

    @Test
    public void toExceptionHidesTokenInErrorsArray() {
        ApiException e = ApiParse.toException(422, "{\"message\":\"Bad\",\"errors\":[\"" + TOKEN + "\"]}");
        assertEquals("Bad - ***", e.getMessage());
    }

    @Test
    public void rateWaitFromRetryAfter() {
        assertEquals(5000L, ApiParse.rateWaitMs("5", null, null, 0L));
        assertEquals(3000L, ApiParse.rateWaitMs(" 3 ", null, null, 0L));
        assertEquals(60000L, ApiParse.rateWaitMs("60", "10", "1", 0L));
    }

    @Test
    public void rateWaitRetryAfterHasMinimumOfOneSecond() {
        assertEquals(1000L, ApiParse.rateWaitMs("0", null, null, 0L));
        assertEquals(1000L, ApiParse.rateWaitMs("-5", null, null, 0L));
    }

    @Test
    public void rateWaitRetryAfterWinsOverReset() {
        assertEquals(2000L, ApiParse.rateWaitMs("2", "0", "99999999999", 0L));
    }

    @Test
    public void rateWaitFromFutureReset() {
        long now = 10_000_000L;
        assertEquals(10_001_000L, ApiParse.rateWaitMs(null, "0", "20000", now));
        assertEquals(10_001_000L, ApiParse.rateWaitMs("", "0", " 20000 ", now));
    }

    @Test
    public void rateWaitFromPastResetHasMinimumOfOneSecond() {
        assertEquals(1000L, ApiParse.rateWaitMs(null, "0", "1", 10_000_000L));
        assertEquals(1000L, ApiParse.rateWaitMs(null, "0", "10000", 10_000_000L));
    }

    @Test
    public void rateWaitNonNumericValuesGiveZero() {
        assertEquals(0L, ApiParse.rateWaitMs("abc", null, null, 0L));
        assertEquals(0L, ApiParse.rateWaitMs("abc", "0", "20000", 0L));
        assertEquals(0L, ApiParse.rateWaitMs(null, "0", "abc", 0L));
        assertEquals(0L, ApiParse.rateWaitMs(null, "0", "", 0L));
    }

    @Test
    public void rateWaitNothingGivesZero() {
        assertEquals(0L, ApiParse.rateWaitMs(null, null, null, 1000L));
        assertEquals(0L, ApiParse.rateWaitMs("", null, null, 1000L));
        assertEquals(0L, ApiParse.rateWaitMs("   ", null, null, 1000L));
        assertEquals(0L, ApiParse.rateWaitMs(null, "5", "20000", 1000L));
        assertEquals(0L, ApiParse.rateWaitMs(null, "0", null, 1000L));
    }

    @Test
    public void graphqlStaleDataIsHeadMoved() throws Exception {
        ApiException e = ApiParse.graphqlError(errs("[{\"type\":\"STALE_DATA\",\"message\":\"stale\"}]"));
        assertTrue(e instanceof HeadMoved);
        assertEquals(409, e.code);
        assertEquals("stale", e.getMessage());
    }

    @Test
    public void graphqlExpectedBranchMessageIsHeadMoved() throws Exception {
        ApiException e = ApiParse.graphqlError(
                errs("[{\"type\":\"OTHER\",\"message\":\"Expected branch to point to abc but it did not\"}]"));
        assertTrue(e instanceof HeadMoved);
        assertEquals(409, e.code);
    }

    @Test
    public void graphqlRateLimited() throws Exception {
        ApiException e = ApiParse.graphqlError(errs("[{\"type\":\"RATE_LIMITED\",\"message\":\"slow down\"}]"));
        assertFalse(e instanceof HeadMoved);
        assertEquals(429, e.code);
        assertEquals(60000L, e.waitMs);
        assertEquals("slow down", e.getMessage());
    }

    @Test
    public void graphqlNotFound() throws Exception {
        ApiException e = ApiParse.graphqlError(errs("[{\"type\":\"NOT_FOUND\",\"message\":\"missing\"}]"));
        assertEquals(404, e.code);
        assertEquals(0L, e.waitMs);
    }

    @Test
    public void graphqlForbiddenAndScopes() throws Exception {
        assertEquals(403, ApiParse.graphqlError(errs("[{\"type\":\"FORBIDDEN\",\"message\":\"no\"}]")).code);
        assertEquals(403, ApiParse.graphqlError(errs("[{\"type\":\"INSUFFICIENT_SCOPES\",\"message\":\"no\"}]")).code);
    }

    @Test
    public void graphqlOtherIsUnprocessable() throws Exception {
        assertEquals(422, ApiParse.graphqlError(errs("[{\"type\":\"SOMETHING\",\"message\":\"x\"}]")).code);
        assertEquals(422, ApiParse.graphqlError(errs("[{\"message\":\"x\"}]")).code);
    }

    @Test
    public void graphqlEmptyArrayUsesDefaultMessage() throws Exception {
        ApiException e = ApiParse.graphqlError(errs("[]"));
        assertEquals(422, e.code);
        assertEquals("GraphQL error", e.getMessage());
    }

    @Test
    public void graphqlHidesToken() throws Exception {
        ApiException e = ApiParse.graphqlError(errs("[{\"type\":\"FORBIDDEN\",\"message\":\"" + TOKEN + "\"}]"));
        assertEquals("***", e.getMessage());
    }

    @Test
    public void commitOidReadsOid() throws Exception {
        JSONObject res = new JSONObject("{\"data\":{\"createCommitOnBranch\":{\"commit\":{\"oid\":\"abc123\"}}}}");
        assertEquals("abc123", ApiParse.commitOid(res));
    }

    @Test
    public void commitOidIgnoresEmptyErrorsArray() throws Exception {
        JSONObject res = new JSONObject(
                "{\"errors\":[],\"data\":{\"createCommitOnBranch\":{\"commit\":{\"oid\":\"abc123\"}}}}");
        assertEquals("abc123", ApiParse.commitOid(res));
    }

    @Test
    public void commitOidThrowsGraphqlErrors() throws Exception {
        JSONObject res = new JSONObject("{\"errors\":[{\"type\":\"NOT_FOUND\",\"message\":\"gone\"}]}");
        ApiException e = assertThrows(ApiException.class, () -> ApiParse.commitOid(res));
        assertEquals(404, e.code);
        assertEquals("gone", e.getMessage());
    }

    @Test
    public void commitOidStaleDataThrowsHeadMoved() throws Exception {
        JSONObject res = new JSONObject("{\"errors\":[{\"type\":\"STALE_DATA\",\"message\":\"stale\"}]}");
        assertThrows(HeadMoved.class, () -> ApiParse.commitOid(res));
    }

    @Test
    public void commitOidEmptyAnswerThrows() throws Exception {
        String[] bodies = {
                "{}",
                "{\"data\":null}",
                "{\"data\":{}}",
                "{\"data\":{\"createCommitOnBranch\":{}}}",
                "{\"data\":{\"createCommitOnBranch\":{\"commit\":{}}}}",
                "{\"data\":{\"createCommitOnBranch\":{\"commit\":{\"oid\":\"\"}}}}"
        };
        for (String b : bodies) {
            JSONObject res = new JSONObject(b);
            ApiException e = assertThrows(b, ApiException.class, () -> ApiParse.commitOid(res));
            assertEquals(b, 422, e.code);
            assertEquals(b, "GraphQL: empty answer", e.getMessage());
        }
    }

    @Test
    public void commitTreeShaReadsTreeSha() throws Exception {
        assertEquals("t1", ApiParse.commitTreeSha("{\"sha\":\"c\",\"tree\":{\"sha\":\"t1\"}}"));
    }

    @Test
    public void commitTreeShaMissingTreeThrows() {
        assertThrows(Exception.class, () -> ApiParse.commitTreeSha("{\"sha\":\"c\"}"));
        assertThrows(Exception.class, () -> ApiParse.commitTreeSha("{\"tree\":{}}"));
    }

    @Test
    public void blobsUnderReturnsOnlyBlobsInsideFolder() throws Exception {
        List<TreeEntry> out = ApiParse.blobsUnder(TREE, "src");
        assertEquals(4, out.size());
        assertEquals("src/a.txt", out.get(0).path);
        assertEquals("s1", out.get(0).sha);
        assertEquals("100644", out.get(0).mode);
        assertEquals("src/run.sh", out.get(1).path);
        assertEquals("100755", out.get(1).mode);
        assertEquals("src/nomode", out.get(2).path);
        assertEquals("100644", out.get(2).mode);
        assertEquals("src/sub/b.txt", out.get(3).path);
        assertEquals("s4", out.get(3).sha);
    }

    @Test
    public void blobsUnderDoesNotMatchSiblingPrefix() throws Exception {
        for (TreeEntry e : ApiParse.blobsUnder(TREE, "src")) {
            assertTrue(e.path.startsWith("src/"));
        }
    }

    @Test
    public void blobsUnderUnknownFolderIsEmpty() throws Exception {
        assertTrue(ApiParse.blobsUnder(TREE, "nothing").isEmpty());
        assertTrue(ApiParse.blobsUnder("{\"tree\":[]}", "src").isEmpty());
    }

    @Test
    public void blobsUnderBlobWithoutShaThrows() {
        String res = "{\"tree\":[{\"path\":\"src/a\",\"type\":\"blob\"}]}";
        assertThrows(Exception.class, () -> ApiParse.blobsUnder(res, "src"));
    }

    @Test
    public void blobShaMapMapsBlobsOnly() throws Exception {
        Map<String, String> m = ApiParse.blobShaMap(TREE);
        assertEquals(6, m.size());
        assertEquals("s1", m.get("src/a.txt"));
        assertEquals("s2", m.get("src/run.sh"));
        assertEquals("s3", m.get("src/nomode"));
        assertEquals("s4", m.get("src/sub/b.txt"));
        assertEquals("s5", m.get("srcx/c.txt"));
        assertEquals("s6", m.get("other/d.txt"));
        assertFalse(m.containsKey("src"));
        assertFalse(m.containsKey("src/sub"));
    }

    @Test
    public void blobShaMapEmptyTree() throws Exception {
        assertTrue(ApiParse.blobShaMap("{\"tree\":[]}").isEmpty());
    }

    @Test
    public void blobInfosReadsSizeAndSha() throws Exception {
        List<BlobInfo> out = ApiParse.blobInfos(TREE);
        assertEquals(6, out.size());
        assertEquals("src/a.txt", out.get(0).path);
        assertEquals(12L, out.get(0).size);
        assertEquals("s1", out.get(0).sha);
        assertEquals("src/run.sh", out.get(1).path);
        assertEquals(30L, out.get(1).size);
    }

    @Test
    public void blobInfosMissingSizeIsZeroAndMissingShaIsEmpty() throws Exception {
        List<BlobInfo> out = ApiParse.blobInfos("{\"tree\":[{\"path\":\"a\",\"type\":\"blob\"}]}");
        assertEquals(1, out.size());
        assertEquals("a", out.get(0).path);
        assertEquals(0L, out.get(0).size);
        assertEquals("", out.get(0).sha);
    }

    @Test
    public void blobInfosSkipsTrees() throws Exception {
        for (BlobInfo b : ApiParse.blobInfos(TREE)) {
            assertNotNull(b.path);
            assertFalse(b.path.equals("src"));
            assertFalse(b.path.equals("src/sub"));
        }
    }

    @Test
    public void blobInfosBlobWithoutPathThrows() {
        assertThrows(Exception.class, () -> ApiParse.blobInfos("{\"tree\":[{\"type\":\"blob\",\"sha\":\"s\"}]}"));
    }

    @Test
    public void malformedJsonThrows() {
        assertThrows(Exception.class, () -> ApiParse.commitTreeSha("{not json"));
        assertThrows(Exception.class, () -> ApiParse.treeArray("not json"));
        assertThrows(Exception.class, () -> ApiParse.treeArray(""));
        assertThrows(Exception.class, () -> ApiParse.treeArray("{}"));
        assertThrows(Exception.class, () -> ApiParse.blobsUnder("[", "src"));
        assertThrows(Exception.class, () -> ApiParse.blobShaMap("{\"tree\":"));
        assertThrows(Exception.class, () -> ApiParse.blobInfos("{"));
        assertThrows(Exception.class, () -> ApiParse.blobInfos("{\"tree\":{}}"));
    }
}

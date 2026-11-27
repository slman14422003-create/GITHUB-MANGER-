package com.ghmanager.app;

import android.util.Base64;

import com.ghmanager.app.GitHubApi.Addition;
import com.ghmanager.app.GitHubApi.ApiException;
import com.ghmanager.app.GitHubApi.BlobInfo;
import com.ghmanager.app.GitHubApi.HeadMoved;
import com.ghmanager.app.GitHubApi.Piece;
import com.ghmanager.app.GitHubApi.Progress;
import com.ghmanager.app.GitHubApi.TextResult;
import com.ghmanager.app.GitHubApi.TreeEntry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.ghmanager.app.ApiCore.*;

final class GitDataApi extends ApiPart {
    private final BranchApi branches;

    GitDataApi(ApiCore core, BranchApi branches) {
        super(core);
        this.branches = branches;
    }

    /**
     * The whole file tree of a branch in ONE request (instead of one request per folder). When
     * {@code etag} is still current GitHub answers 304 with no body and this returns null.
     * The new validator is written to {@code etagOut[0]}.
     */
    public JSONObject treeRecursive(String o, String r, String branch, String etag, String[] etagOut) throws Exception {
        String ref = branch;
        boolean conditional = etag != null && !etag.isEmpty() && branch.indexOf('/') < 0;
        if (branch.indexOf('/') >= 0) {
            // a ref with a slash is not accepted in the tree path: resolve it to the tree sha first
            ref = getCommitTree(o, r, branches.getBranchSha(o, r, branch));
        }
        String url = BASE + repo(o, r) + "/git/trees/" + enc(ref) + "?recursive=1";
        HttpURLConnection c = open("GET", url, JSON, true);
        c.setInstanceFollowRedirects(false);
        if (conditional) c.setRequestProperty("If-None-Match", etag);
        boolean reusable = false;
        try {
            int code = c.getResponseCode();
            if (code == 304) {
                try {
                    c.getInputStream().close();
                } catch (Exception ignored) {
                }
                reusable = true;
                return null;
            }
            if (code >= 300 && code < 400) throw new ApiException(code, "Redirect");
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String resp = is == null ? "" : readAll(is);
            if (code >= 400) throw toException(code, resp);
            if (etagOut != null && etagOut.length > 0) {
                String tag = c.getHeaderField("ETag");
                etagOut[0] = tag == null ? "" : tag;
            }
            reusable = true;
            return new JSONObject(resp);
        } finally {
            if (!reusable) c.disconnect();
        }
    }

    private String getCommitTree(String o, String r, String commitSha) throws Exception {
        String res = request("GET", repo(o, r) + "/git/commits/" + commitSha, null);
        return ApiParse.commitTreeSha(res);
    }

    public String createBlob(String o, String r, byte[] data) throws Exception {
        JSONObject b = new JSONObject();
        b.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
        b.put("encoding", "base64");
        return new JSONObject(request("POST", repo(o, r) + "/git/blobs", b)).getString("sha");
    }

    public String createCommitOnBranch(String o, String r, String branch, String expectedHead, String message,
                                       List<Addition> adds) throws Exception {
        JSONArray arr = new JSONArray();
        for (Addition a : adds) {
            JSONObject j = new JSONObject();
            j.put("path", a.path);
            j.put("contents", Base64.encodeToString(a.data, Base64.NO_WRAP));
            arr.put(j);
        }
        String head = message;
        String rest = "";
        int nl = message.indexOf('\n');
        if (nl > 0) {
            head = message.substring(0, nl);
            rest = message.substring(nl + 1).trim();
        }
        JSONObject msg = new JSONObject();
        msg.put("headline", head);
        if (!rest.isEmpty()) msg.put("body", rest);
        JSONObject br = new JSONObject();
        br.put("repositoryNameWithOwner", o + "/" + r);
        br.put("branchName", branch);
        JSONObject changes = new JSONObject();
        changes.put("additions", arr);
        JSONObject input = new JSONObject();
        input.put("branch", br);
        input.put("message", msg);
        input.put("fileChanges", changes);
        input.put("expectedHeadOid", expectedHead);
        JSONObject vars = new JSONObject();
        vars.put("input", input);
        JSONObject body = new JSONObject();
        body.put("query", "mutation($input: CreateCommitOnBranchInput!){createCommitOnBranch(input:$input){commit{oid}}}");
        body.put("variables", vars);
        core.guardRepo(o, r);
        JSONObject res = new JSONObject(request("POST", "/graphql", body));
        return ApiParse.commitOid(res);
    }

    public String commitEntries(String o, String r, String branch, List<TreeEntry> entries, String message) throws Exception {
        String headSha = branches.getBranchSha(o, r, branch);
        String newCommit = commitTree(o, r, headSha, entries, message);
        moveBranch(o, r, branch, newCommit, false);
        return newCommit;
    }

    public String commitTree(String o, String r, String parentSha, List<TreeEntry> entries, String message) throws Exception {
        String tree = getCommitTree(o, r, parentSha);
        for (int i = 0; i < entries.size(); i += 400) {
            JSONArray arr = new JSONArray();
            for (int j = i; j < Math.min(i + 400, entries.size()); j++) {
                TreeEntry t = entries.get(j);
                JSONObject e = new JSONObject();
                e.put("path", t.path);
                e.put("mode", t.mode);
                e.put("type", "blob");
                if (t.content != null) e.put("content", t.content);
                else e.put("sha", t.sha == null ? JSONObject.NULL : t.sha);
                arr.put(e);
            }
            JSONObject tb = new JSONObject();
            tb.put("base_tree", tree);
            tb.put("tree", arr);
            tree = new JSONObject(request("POST", repo(o, r) + "/git/trees", tb)).getString("sha");
        }
        JSONObject cb = new JSONObject();
        cb.put("message", message);
        cb.put("tree", tree);
        cb.put("parents", new JSONArray().put(parentSha));
        return new JSONObject(request("POST", repo(o, r) + "/git/commits", cb)).getString("sha");
    }

    public void moveBranch(String o, String r, String branch, String sha, boolean force) throws Exception {
        JSONObject ub = new JSONObject();
        ub.put("sha", sha);
        ub.put("force", force);
        request("PATCH", repo(o, r) + "/git/refs/heads/" + enc(branch), ub);
    }

    /** All blobs under a folder (recursive), with their sha and mode. */
    public List<TreeEntry> listBlobsUnder(String o, String r, String branch, String folder) throws Exception {
        String headSha = branches.getBranchSha(o, r, branch);
        String tree = getCommitTree(o, r, headSha);
        String res = request("GET", repo(o, r) + "/git/trees/" + tree + "?recursive=1", null);
        return ApiParse.blobsUnder(res, folder);
    }

    /** path -> blob sha for every file of the branch (lets an upload skip what is already there). */
    public java.util.Map<String, String> blobShaMap(String o, String r, String branch) throws Exception {
        String headSha = branches.getBranchSha(o, r, branch);
        String tree = getCommitTree(o, r, headSha);
        String res = request("GET", repo(o, r) + "/git/trees/" + tree + "?recursive=1", null);
        return ApiParse.blobShaMap(res);
    }

    /** All file paths under a folder (recursive). */
    public List<String> listFilesUnder(String o, String r, String branch, String folder) throws Exception {
        List<String> out = new ArrayList<>();
        for (TreeEntry t : listBlobsUnder(o, r, branch, folder)) out.add(t.path);
        return out;
    }

    /** All blobs in the whole repository (recursive), with size but not content — cheap to call. */
    public List<BlobInfo> listAllBlobs(String o, String r, String branch) throws Exception {
        String headSha = branches.getBranchSha(o, r, branch);
        String tree = getCommitTree(o, r, headSha);
        String res = request("GET", repo(o, r) + "/git/trees/" + tree + "?recursive=1", null);
        return ApiParse.blobInfos(res);
    }

    /**
     * Blobs under the given paths, found with one non-recursive tree request per folder. Used when the
     * recursive tree of a huge repository comes back truncated.
     */
    public List<BlobInfo> listBlobsForPaths(String o, String r, String branch, List<String> sel) throws Exception {
        String root = getCommitTree(o, r, branches.getBranchSha(o, r, branch));
        List<BlobInfo> out = new ArrayList<>();
        walkTree(o, r, root, "", sel, out, 0);
        return out;
    }

    private void walkTree(String o, String r, String treeSha, String prefix, List<String> sel,
                          List<BlobInfo> out, int depth) throws Exception {
        if (depth > 40) return;
        JSONArray arr = ApiParse.treeArray(request("GET", repo(o, r) + "/git/trees/" + treeSha, null));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.getJSONObject(i);
            String name = e.getString("path");
            String path = prefix.isEmpty() ? name : prefix + "/" + name;
            boolean covered = false;
            boolean onTheWay = false;
            for (String s : sel) {
                if (path.equals(s) || path.startsWith(s + "/")) covered = true;
                else if (s.startsWith(path + "/")) onTheWay = true;
            }
            String type = e.optString("type");
            if ("blob".equals(type) && covered) {
                out.add(new BlobInfo(path, e.optLong("size", 0), e.optString("sha", "")));
            } else if ("tree".equals(type) && (covered || onTheWay)) {
                walkTree(o, r, e.getString("sha"), path, sel, out, depth + 1);
            }
        }
    }

    /**
     * Rolls a branch back to the exact content of {@code targetSha} by adding ONE new commit on top
     * of the current head. History is kept and nothing is force-pushed, so the rollback can itself
     * be undone later. Returns the new commit sha, or null when the branch already has that content.
     */
    public String restoreToCommit(String o, String r, String branch, String targetSha, String message) throws Exception {
        String headSha = branches.getBranchSha(o, r, branch);
        String targetTree = getCommitTree(o, r, targetSha);
        if (targetTree.equals(getCommitTree(o, r, headSha))) return null;
        JSONObject cb = new JSONObject();
        cb.put("message", message);
        cb.put("tree", targetTree);
        cb.put("parents", new JSONArray().put(headSha));
        String newCommit = new JSONObject(request("POST", repo(o, r) + "/git/commits", cb)).getString("sha");
        JSONObject ub = new JSONObject();
        ub.put("sha", newCommit);
        ub.put("force", false);
        request("PATCH", repo(o, r) + "/git/refs/heads/" + enc(branch), ub);
        return newCommit;
    }
}

package com.ghmanager.app;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GitHubApi {

    public static class ApiException extends Exception {
        public final int code;

        public ApiException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    /** sha == null means delete the path. */
    public static class TreeEntry {
        public final String path;
        public final String sha;
        public final String mode;

        public TreeEntry(String path, String sha) {
            this(path, sha, "100644");
        }

        public TreeEntry(String path, String sha, String mode) {
            this.path = path;
            this.sha = sha;
            this.mode = mode == null ? "100644" : mode;
        }
    }

    public interface Progress {
        void on(long done, long total);
    }

    public static class TextResult {
        public final String text;
        public final boolean truncated;

        TextResult(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }

    private static final String BASE = "https://api.github.com";
    private static final String UPLOAD_BASE = "https://uploads.github.com";
    private static final String JSON = "application/vnd.github+json";
    private final String token;

    public GitHubApi(String token) {
        this.token = token;
    }

    // ------------------------------------------------------------------ helpers

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Encodes each segment of a path, keeping the slashes. */
    public static String enc(String path) throws IOException {
        StringBuilder sb = new StringBuilder();
        String[] parts = path.split("/");
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(URLEncoder.encode(p, "UTF-8").replace("+", "%20"));
        }
        return sb.toString();
    }

    /** Encodes a query value. */
    public static String qe(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    public static String repo(String o, String r) {
        return "/repos/" + o + "/" + r;
    }

    private HttpURLConnection open(String method, String url, String accept, boolean api) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        if (api) {
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        }
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("User-Agent", "GitHubManagerApp");
        return c;
    }

    private static ApiException toException(int code, String resp) {
        String msg = resp == null ? "" : resp;
        try {
            JSONObject j = new JSONObject(msg);
            msg = j.optString("message", msg);
            JSONArray errs = j.optJSONArray("errors");
            if (errs != null && errs.length() > 0) {
                Object first = errs.get(0);
                if (first instanceof JSONObject) {
                    String m = ((JSONObject) first).optString("message", "");
                    if (!m.isEmpty()) msg = msg + " - " + m;
                } else {
                    msg = msg + " - " + first;
                }
            }
        } catch (Exception ignored) {
            if (msg.length() > 300) msg = msg.substring(0, 300);
        }
        if (msg.isEmpty()) msg = "HTTP " + code;
        return new ApiException(code, msg);
    }

    private String request(String method, String path, JSONObject body) throws IOException, ApiException {
        HttpURLConnection c = open(method, BASE + path, JSON, true);
        boolean needsBody = method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
        if (body == null && needsBody) body = new JSONObject();
        if (body != null) {
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(data.length);
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.close();
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String resp = is == null ? "" : readAll(is);
        c.disconnect();
        if (code >= 400) throw toException(code, resp);
        return resp;
    }

    /** Generic call, returns the raw response body ("" for 204). */
    public String call(String method, String path, JSONObject body) throws Exception {
        return request(method, path, body);
    }

    public JSONObject obj(String path) throws Exception {
        return new JSONObject(request("GET", path, null));
    }

    public JSONArray arr(String path) throws Exception {
        return new JSONArray(request("GET", path, null));
    }

    /**
     * Opens a download. The first hop carries the token; any redirect (logs, artifacts,
     * assets, zipballs) is followed WITHOUT the token because the target URL is pre-signed.
     */
    public HttpURLConnection openDownload(String path, String accept) throws Exception {
        HttpURLConnection c = open("GET", BASE + path, accept, true);
        c.setInstanceFollowRedirects(false);
        int code = c.getResponseCode();
        int hops = 0;
        while (code >= 300 && code < 400 && hops < 5) {
            String loc = c.getHeaderField("Location");
            c.disconnect();
            if (loc == null) throw new ApiException(code, "Redirect without Location");
            c = open("GET", loc, "*/*", false);
            c.setInstanceFollowRedirects(false);
            code = c.getResponseCode();
            hops++;
        }
        if (code >= 400) {
            InputStream es = c.getErrorStream();
            String resp = es == null ? "" : readAll(es);
            c.disconnect();
            throw toException(code, resp);
        }
        return c;
    }

    public static long copy(InputStream in, OutputStream out, long total, Progress p) throws IOException {
        byte[] buf = new byte[32768];
        long done = 0;
        long last = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            done += n;
            if (p != null) {
                long now = System.currentTimeMillis();
                if (now - last > 150) {
                    p.on(done, total);
                    last = now;
                }
            }
        }
        out.flush();
        if (p != null) p.on(done, total);
        return done;
    }

    /** Reads a text download, keeping only the last maxBytes bytes. */
    public TextResult readTail(String path, String accept, int maxBytes) throws Exception {
        HttpURLConnection c = openDownload(path, accept);
        try {
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            final int hardCap = 24 * 1024 * 1024;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > hardCap) break;
            }
            is.close();
            byte[] all = bos.toByteArray();
            boolean cut = false;
            if (all.length > maxBytes) {
                all = Arrays.copyOfRange(all, all.length - maxBytes, all.length);
                cut = true;
            }
            return new TextResult(new String(all, StandardCharsets.UTF_8), cut);
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ user / repos

    public JSONObject getUser() throws Exception {
        return new JSONObject(request("GET", "/user", null));
    }

    public JSONArray listRepos() throws Exception {
        JSONArray all = new JSONArray();
        for (int page = 1; page <= 10; page++) {
            String res = request("GET", "/user/repos?per_page=100&sort=updated&page=" + page
                    + "&affiliation=owner,collaborator,organization_member", null);
            JSONArray arr = new JSONArray(res);
            for (int i = 0; i < arr.length(); i++) all.put(arr.get(i));
            if (arr.length() < 100) break;
        }
        return all;
    }

    public JSONObject createRepo(String name, boolean isPrivate) throws Exception {
        JSONObject b = new JSONObject();
        b.put("name", name);
        b.put("private", isPrivate);
        b.put("auto_init", true);
        return new JSONObject(request("POST", "/user/repos", b));
    }

    public JSONObject getRepo(String o, String r) throws Exception {
        return obj(repo(o, r));
    }

    public JSONObject updateRepo(String o, String r, JSONObject patch) throws Exception {
        return new JSONObject(request("PATCH", repo(o, r), patch));
    }

    public void deleteRepo(String o, String r) throws Exception {
        request("DELETE", repo(o, r), null);
    }

    public JSONObject languages(String o, String r) throws Exception {
        return obj(repo(o, r) + "/languages");
    }

    public JSONArray contributors(String o, String r) throws Exception {
        String res = request("GET", repo(o, r) + "/contributors?per_page=10", null).trim();
        if (res.isEmpty()) return new JSONArray();
        return new JSONArray(res);
    }

    public JSONObject trafficViews(String o, String r) throws Exception {
        return obj(repo(o, r) + "/traffic/views");
    }

    public JSONObject trafficClones(String o, String r) throws Exception {
        return obj(repo(o, r) + "/traffic/clones");
    }

    public JSONArray topics(String o, String r) throws Exception {
        return obj(repo(o, r) + "/topics").optJSONArray("names");
    }

    public void setTopics(String o, String r, List<String> names) throws Exception {
        JSONObject b = new JSONObject();
        JSONArray a = new JSONArray();
        for (String n : names) a.put(n);
        b.put("names", a);
        request("PUT", repo(o, r) + "/topics", b);
    }

    public boolean isStarred(String o, String r) throws Exception {
        try {
            request("GET", "/user/starred/" + o + "/" + r, null);
            return true;
        } catch (ApiException e) {
            if (e.code == 404) return false;
            throw e;
        }
    }

    public void star(String o, String r, boolean on) throws Exception {
        request(on ? "PUT" : "DELETE", "/user/starred/" + o + "/" + r, null);
    }

    // ------------------------------------------------------------------ branches

    public JSONArray listBranches(String o, String r) throws Exception {
        return new JSONArray(request("GET", repo(o, r) + "/branches?per_page=100", null));
    }

    public String getBranchSha(String o, String r, String branch) throws Exception {
        String res = request("GET", repo(o, r) + "/git/ref/heads/" + enc(branch), null);
        return new JSONObject(res).getJSONObject("object").getString("sha");
    }

    public void createBranch(String o, String r, String name, String fromSha) throws Exception {
        JSONObject b = new JSONObject();
        b.put("ref", "refs/heads/" + name);
        b.put("sha", fromSha);
        request("POST", repo(o, r) + "/git/refs", b);
    }

    public void deleteBranch(String o, String r, String name) throws Exception {
        request("DELETE", repo(o, r) + "/git/refs/heads/" + enc(name), null);
    }

    /** Returns true when a merge commit was created, false when there was nothing to merge. */
    public boolean mergeBranches(String o, String r, String base, String head, String message) throws Exception {
        JSONObject b = new JSONObject();
        b.put("base", base);
        b.put("head", head);
        if (message != null && !message.isEmpty()) b.put("commit_message", message);
        String res = request("POST", repo(o, r) + "/merges", b);
        return !res.trim().isEmpty();
    }

    // ------------------------------------------------------------------ contents

    public JSONArray listContents(String o, String r, String path, String branch) throws Exception {
        String p = repo(o, r) + "/contents" + (path.isEmpty() ? "" : "/" + enc(path))
                + "?ref=" + qe(branch);
        String res = request("GET", p, null).trim();
        if (res.startsWith("[")) return new JSONArray(res);
        return new JSONArray().put(new JSONObject(res));
    }

    public JSONObject getContent(String o, String r, String path, String branch) throws Exception {
        String p = repo(o, r) + "/contents/" + enc(path) + "?ref=" + qe(branch);
        return new JSONObject(request("GET", p, null));
    }

    public String contentRawPath(String o, String r, String path, String branch) throws IOException {
        return repo(o, r) + "/contents/" + enc(path) + "?ref=" + qe(branch);
    }

    /** Returns null when the file is larger than maxBytes. */
    public byte[] getFileBytes(String o, String r, String path, String branch, int maxBytes) throws Exception {
        HttpURLConnection c = openDownload(contentRawPath(o, r, path, branch), "application/vnd.github.raw");
        try {
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > maxBytes) return null;
            }
            is.close();
            return bos.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    public JSONObject putFileContent(String o, String r, String path, byte[] data, String message,
                                     String branch, String sha) throws Exception {
        JSONObject b = new JSONObject();
        b.put("message", message);
        b.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
        b.put("branch", branch);
        if (sha != null) b.put("sha", sha);
        return new JSONObject(request("PUT", repo(o, r) + "/contents/" + enc(path), b));
    }

    public HttpURLConnection openZipball(String o, String r, String ref) throws Exception {
        return openDownload(repo(o, r) + "/zipball/" + enc(ref), JSON);
    }

    public HttpURLConnection openTarball(String o, String r, String ref) throws Exception {
        return openDownload(repo(o, r) + "/tarball/" + enc(ref), JSON);
    }

    private String getCommitTree(String o, String r, String commitSha) throws Exception {
        String res = request("GET", repo(o, r) + "/git/commits/" + commitSha, null);
        return new JSONObject(res).getJSONObject("tree").getString("sha");
    }

    public String createBlob(String o, String r, byte[] data) throws Exception {
        JSONObject b = new JSONObject();
        b.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
        b.put("encoding", "base64");
        return new JSONObject(request("POST", repo(o, r) + "/git/blobs", b)).getString("sha");
    }

    /** Used only to initialize an empty repository (no branch exists yet). */
    public void putFile(String o, String r, String path, byte[] data, String message) throws Exception {
        JSONObject b = new JSONObject();
        b.put("message", message);
        b.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
        request("PUT", repo(o, r) + "/contents/" + enc(path), b);
    }

    /** Creates a single commit containing all entries (add/replace, or delete when sha == null). */
    public String commitEntries(String o, String r, String branch, List<TreeEntry> entries, String message) throws Exception {
        String headSha = getBranchSha(o, r, branch);
        String tree = getCommitTree(o, r, headSha);
        for (int i = 0; i < entries.size(); i += 100) {
            JSONArray arr = new JSONArray();
            for (int j = i; j < Math.min(i + 100, entries.size()); j++) {
                TreeEntry t = entries.get(j);
                JSONObject e = new JSONObject();
                e.put("path", t.path);
                e.put("mode", t.mode);
                e.put("type", "blob");
                e.put("sha", t.sha == null ? JSONObject.NULL : t.sha);
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
        cb.put("parents", new JSONArray().put(headSha));
        String newCommit = new JSONObject(request("POST", repo(o, r) + "/git/commits", cb)).getString("sha");
        JSONObject ub = new JSONObject();
        ub.put("sha", newCommit);
        ub.put("force", false);
        request("PATCH", repo(o, r) + "/git/refs/heads/" + enc(branch), ub);
        return newCommit;
    }

    /** All blobs under a folder (recursive), with their sha and mode. */
    public List<TreeEntry> listBlobsUnder(String o, String r, String branch, String folder) throws Exception {
        String headSha = getBranchSha(o, r, branch);
        String tree = getCommitTree(o, r, headSha);
        String res = request("GET", repo(o, r) + "/git/trees/" + tree + "?recursive=1", null);
        JSONArray arr = new JSONObject(res).getJSONArray("tree");
        List<TreeEntry> out = new ArrayList<>();
        String prefix = folder + "/";
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.getJSONObject(i);
            if ("blob".equals(e.optString("type")) && e.optString("path").startsWith(prefix)) {
                out.add(new TreeEntry(e.getString("path"), e.getString("sha"), e.optString("mode", "100644")));
            }
        }
        return out;
    }

    /** All file paths under a folder (recursive). */
    public List<String> listFilesUnder(String o, String r, String branch, String folder) throws Exception {
        List<String> out = new ArrayList<>();
        for (TreeEntry t : listBlobsUnder(o, r, branch, folder)) out.add(t.path);
        return out;
    }

    // ------------------------------------------------------------------ commits

    public JSONArray listCommits(String o, String r, String branch, int page, int perPage) throws Exception {
        return arr(repo(o, r) + "/commits?sha=" + qe(branch) + "&per_page=" + perPage + "&page=" + page);
    }

    public JSONObject getCommit(String o, String r, String sha) throws Exception {
        return obj(repo(o, r) + "/commits/" + sha);
    }

    // ------------------------------------------------------------------ actions

    public JSONArray listWorkflows(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/workflows?per_page=100").optJSONArray("workflows");
    }

    public JSONObject listRuns(String o, String r, long workflowId, String status, String branch,
                               int page, int perPage) throws Exception {
        StringBuilder p = new StringBuilder(repo(o, r));
        if (workflowId > 0) p.append("/actions/workflows/").append(workflowId).append("/runs");
        else p.append("/actions/runs");
        p.append("?per_page=").append(perPage).append("&page=").append(page);
        if (status != null && !status.isEmpty()) p.append("&status=").append(qe(status));
        if (branch != null && !branch.isEmpty()) p.append("&branch=").append(qe(branch));
        return obj(p.toString());
    }

    public JSONObject getRun(String o, String r, long runId) throws Exception {
        return obj(repo(o, r) + "/actions/runs/" + runId);
    }

    public JSONArray listJobs(String o, String r, long runId) throws Exception {
        return obj(repo(o, r) + "/actions/runs/" + runId + "/jobs?per_page=100&filter=latest").optJSONArray("jobs");
    }

    public JSONArray listRunArtifacts(String o, String r, long runId) throws Exception {
        return obj(repo(o, r) + "/actions/runs/" + runId + "/artifacts?per_page=100").optJSONArray("artifacts");
    }

    public void rerunRun(String o, String r, long runId) throws Exception {
        request("POST", repo(o, r) + "/actions/runs/" + runId + "/rerun", null);
    }

    public void rerunFailed(String o, String r, long runId) throws Exception {
        request("POST", repo(o, r) + "/actions/runs/" + runId + "/rerun-failed-jobs", null);
    }

    public void rerunJob(String o, String r, long jobId) throws Exception {
        request("POST", repo(o, r) + "/actions/jobs/" + jobId + "/rerun", null);
    }

    public void cancelRun(String o, String r, long runId, boolean force) throws Exception {
        request("POST", repo(o, r) + "/actions/runs/" + runId + (force ? "/force-cancel" : "/cancel"), null);
    }

    public void deleteRun(String o, String r, long runId) throws Exception {
        request("DELETE", repo(o, r) + "/actions/runs/" + runId, null);
    }

    public String jobLogsPath(String o, String r, long jobId) {
        return repo(o, r) + "/actions/jobs/" + jobId + "/logs";
    }

    public String runLogsPath(String o, String r, long runId) {
        return repo(o, r) + "/actions/runs/" + runId + "/logs";
    }

    public void dispatchWorkflow(String o, String r, long workflowId, String ref, JSONObject inputs) throws Exception {
        JSONObject b = new JSONObject();
        b.put("ref", ref);
        if (inputs != null && inputs.length() > 0) b.put("inputs", inputs);
        request("POST", repo(o, r) + "/actions/workflows/" + workflowId + "/dispatches", b);
    }

    public void setWorkflowEnabled(String o, String r, long workflowId, boolean enabled) throws Exception {
        request("PUT", repo(o, r) + "/actions/workflows/" + workflowId + (enabled ? "/enable" : "/disable"), null);
    }

    public JSONArray listArtifacts(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/artifacts?per_page=100").optJSONArray("artifacts");
    }

    public void deleteArtifact(String o, String r, long id) throws Exception {
        request("DELETE", repo(o, r) + "/actions/artifacts/" + id, null);
    }

    public String artifactZipPath(String o, String r, long id) {
        return repo(o, r) + "/actions/artifacts/" + id + "/zip";
    }

    public JSONArray listCaches(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/caches?per_page=100&sort=last_accessed_at&direction=desc")
                .optJSONArray("actions_caches");
    }

    public JSONObject cacheUsage(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/cache/usage");
    }

    public void deleteCache(String o, String r, long id) throws Exception {
        request("DELETE", repo(o, r) + "/actions/caches/" + id, null);
    }

    public JSONArray listVariables(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/variables?per_page=100").optJSONArray("variables");
    }

    public void createVariable(String o, String r, String name, String value) throws Exception {
        JSONObject b = new JSONObject();
        b.put("name", name);
        b.put("value", value);
        request("POST", repo(o, r) + "/actions/variables", b);
    }

    public void updateVariable(String o, String r, String name, String value) throws Exception {
        JSONObject b = new JSONObject();
        b.put("name", name);
        b.put("value", value);
        request("PATCH", repo(o, r) + "/actions/variables/" + enc(name), b);
    }

    public void deleteVariable(String o, String r, String name) throws Exception {
        request("DELETE", repo(o, r) + "/actions/variables/" + enc(name), null);
    }

    public JSONArray listSecrets(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/secrets?per_page=100").optJSONArray("secrets");
    }

    public void deleteSecret(String o, String r, String name) throws Exception {
        request("DELETE", repo(o, r) + "/actions/secrets/" + enc(name), null);
    }

    // ------------------------------------------------------------------ releases

    public JSONArray listReleases(String o, String r, int page, int perPage) throws Exception {
        return arr(repo(o, r) + "/releases?per_page=" + perPage + "&page=" + page);
    }

    public JSONObject getRelease(String o, String r, long id) throws Exception {
        return obj(repo(o, r) + "/releases/" + id);
    }

    public JSONObject createRelease(String o, String r, JSONObject body) throws Exception {
        return new JSONObject(request("POST", repo(o, r) + "/releases", body));
    }

    public JSONObject updateRelease(String o, String r, long id, JSONObject body) throws Exception {
        return new JSONObject(request("PATCH", repo(o, r) + "/releases/" + id, body));
    }

    public void deleteRelease(String o, String r, long id) throws Exception {
        request("DELETE", repo(o, r) + "/releases/" + id, null);
    }

    public void deleteTag(String o, String r, String tag) throws Exception {
        request("DELETE", repo(o, r) + "/git/refs/tags/" + enc(tag), null);
    }

    public JSONObject generateNotes(String o, String r, String tag, String target) throws Exception {
        JSONObject b = new JSONObject();
        b.put("tag_name", tag);
        if (target != null && !target.isEmpty()) b.put("target_commitish", target);
        return new JSONObject(request("POST", repo(o, r) + "/releases/generate-notes", b));
    }

    public void deleteAsset(String o, String r, long id) throws Exception {
        request("DELETE", repo(o, r) + "/releases/assets/" + id, null);
    }

    public String assetPath(String o, String r, long id) {
        return repo(o, r) + "/releases/assets/" + id;
    }

    public JSONObject uploadAsset(String o, String r, long releaseId, String name, String contentType,
                                  InputStream in, long size, Progress p) throws Exception {
        String url = UPLOAD_BASE + repo(o, r) + "/releases/" + releaseId + "/assets?name=" + qe(name);
        HttpURLConnection c = open("POST", url, JSON, true);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", contentType == null ? "application/octet-stream" : contentType);
        if (size > 0) c.setFixedLengthStreamingMode(size);
        else c.setChunkedStreamingMode(65536);
        c.setReadTimeout(600000);
        OutputStream os = c.getOutputStream();
        try {
            copy(in, os, size, p);
        } finally {
            os.close();
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String resp = is == null ? "" : readAll(is);
        c.disconnect();
        if (code >= 400) throw toException(code, resp);
        return new JSONObject(resp);
    }

    // ------------------------------------------------------------------ issues / pulls

    public JSONArray listIssues(String o, String r, String state, int page, int perPage) throws Exception {
        return arr(repo(o, r) + "/issues?state=" + qe(state) + "&per_page=" + perPage + "&page=" + page);
    }

    public JSONObject getIssue(String o, String r, long number) throws Exception {
        return obj(repo(o, r) + "/issues/" + number);
    }

    public JSONArray listComments(String o, String r, long number) throws Exception {
        return arr(repo(o, r) + "/issues/" + number + "/comments?per_page=100");
    }

    public JSONObject createIssue(String o, String r, String title, String body) throws Exception {
        JSONObject b = new JSONObject();
        b.put("title", title);
        if (body != null && !body.isEmpty()) b.put("body", body);
        return new JSONObject(request("POST", repo(o, r) + "/issues", b));
    }

    public void setIssueState(String o, String r, long number, boolean open) throws Exception {
        JSONObject b = new JSONObject();
        b.put("state", open ? "open" : "closed");
        request("PATCH", repo(o, r) + "/issues/" + number, b);
    }

    public void addComment(String o, String r, long number, String body) throws Exception {
        JSONObject b = new JSONObject();
        b.put("body", body);
        request("POST", repo(o, r) + "/issues/" + number + "/comments", b);
    }

    public JSONObject getPull(String o, String r, long number) throws Exception {
        return obj(repo(o, r) + "/pulls/" + number);
    }

    public void mergePull(String o, String r, long number, String method) throws Exception {
        JSONObject b = new JSONObject();
        b.put("merge_method", method);
        request("PUT", repo(o, r) + "/pulls/" + number + "/merge", b);
    }
}

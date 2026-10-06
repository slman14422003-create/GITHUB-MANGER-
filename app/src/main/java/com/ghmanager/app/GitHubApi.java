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
        final int cap = 32 * 1024 * 1024;   // a hostile mirror must not be able to exhaust memory
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
            if (bos.size() > cap) {
                is.close();
                throw new IOException("Response too large");
            }
        }
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
        return "/repos/" + seg(o) + "/" + seg(r);
    }

    /** One path segment, encoded, so an owner or repo name can never add path parts or a query. */
    private static String seg(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    private HttpURLConnection open(String method, String url, String accept, boolean api) throws IOException {
        // never talk plain HTTP (a redirect to http:// must not downgrade a download)
        if (!url.startsWith("https://")) throw new IOException("Blocked non-HTTPS URL");
        if (api && !Mirror.isMirrored(url)) {
            // the token may only ever be sent to GitHub's own API hosts (a crafted path such as
            // "@evil.com" must never be able to redirect it somewhere else)
            String host = new URL(url).getHost().toLowerCase(java.util.Locale.ROOT);
            if (!host.equals("api.github.com") && !host.equals("uploads.github.com")) {
                throw new IOException("Blocked host");
            }
        }
        final String real = Mirror.map(url);
        HttpURLConnection c = (HttpURLConnection) new URL(real).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        if (Mirror.isMirrored(real) && !Mirror.key().isEmpty()) {
            c.setRequestProperty("X-Mirror-Key", Mirror.key());
        }
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
        return new ApiException(code, Redact.text(msg));
    }

    private String request(String method, String path, JSONObject body) throws IOException, ApiException {
        boolean needsBody = method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
        if (body == null && needsBody) body = new JSONObject();
        byte[] data = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        String url = BASE + path;
        // Redirects are followed by hand: every hop is checked against the allowed hosts again, so the
        // token can never be forwarded to another site by a redirect.
        for (int hop = 0; ; hop++) {
            HttpURLConnection c = open(method, url, JSON, true);
            c.setInstanceFollowRedirects(false);
            try {
                if (data != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setFixedLengthStreamingMode(data.length);
                    OutputStream os = c.getOutputStream();
                    os.write(data);
                    os.close();
                }
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && code != 304 && hop < 3) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new ApiException(code, "Redirect without Location");
                    url = new URL(new URL(url), loc).toString();
                    continue;
                }
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String resp = is == null ? "" : readAll(is);
                if (code >= 400) throw toException(code, resp);
                return resp;
            } finally {
                c.disconnect();
            }
        }
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

    /** Like getUser() plus "_scopes": the scopes GitHub reports for a classic / OAuth token ("" otherwise). */
    public JSONObject getUserMeta() throws Exception {
        HttpURLConnection c = open("GET", BASE + "/user", JSON, true);
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String resp = is == null ? "" : readAll(is);
        String scopes = c.getHeaderField("X-OAuth-Scopes");
        c.disconnect();
        if (code >= 400) throw toException(code, resp);
        JSONObject u = new JSONObject(resp);
        u.put("_scopes", scopes == null ? "" : scopes.trim());
        return u;
    }

    public JSONObject updateProfile(JSONObject patch) throws Exception {
        return new JSONObject(request("PATCH", "/user", patch));
    }

    public JSONArray listEmails() throws Exception {
        return new JSONArray(request("GET", "/user/emails", null));
    }

    public JSONArray listSshKeys() throws Exception {
        return new JSONArray(request("GET", "/user/keys?per_page=100", null));
    }

    public void addSshKey(String title, String key) throws Exception {
        JSONObject b = new JSONObject();
        b.put("title", title);
        b.put("key", key);
        request("POST", "/user/keys", b);
    }

    public void deleteSshKey(long id) throws Exception {
        request("DELETE", "/user/keys/" + id, null);
    }

    public JSONArray listOrgs() throws Exception {
        return new JSONArray(request("GET", "/user/orgs?per_page=100", null));
    }

    /** The "core" bucket of /rate_limit: limit, remaining, reset (epoch seconds). */
    public JSONObject rateLimit() throws Exception {
        JSONObject r = obj("/rate_limit").optJSONObject("resources");
        JSONObject core = r == null ? null : r.optJSONObject("core");
        return core == null ? new JSONObject() : core;
    }

    /** Unauthenticated form POST (GitHub device flow). Returns the body whatever the status is. */
    public static String postForm(String url, String form) throws Exception {
        if (!url.startsWith("https://")) throw new IOException("Blocked non-HTTPS URL");
        String real = Mirror.map(url);
        HttpURLConnection c = (HttpURLConnection) new URL(real).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("User-Agent", "GitHubManagerApp");
        if (Mirror.isMirrored(real) && !Mirror.key().isEmpty()) {
            c.setRequestProperty("X-Mirror-Key", Mirror.key());
        }
        byte[] data = form.getBytes(StandardCharsets.UTF_8);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(data.length);
        OutputStream os = c.getOutputStream();
        os.write(data);
        os.close();
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String resp = is == null ? "" : readAll(is);
        c.disconnect();
        return resp;
    }

    /** Small unauthenticated download (avatars). Returns null on any failure. */
    public static byte[] fetchBytes(String url, int maxBytes) {
        return fetchBytes(url, maxBytes, false, 15000);
    }

    /**
     * @param forceMirror use the saved mirror even when its switch is off
     * @param connectMs   how long to wait for the connection before giving up
     */
    public static byte[] fetchBytes(String url, int maxBytes, boolean forceMirror, int connectMs) {
        try {
            if (!url.startsWith("https://")) return null;
            String real = forceMirror ? Mirror.mapForced(url) : Mirror.map(url);
            HttpURLConnection c = (HttpURLConnection) new URL(real).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "GitHubManagerApp");
            if (Mirror.pointsAtMirror(real) && !Mirror.key().isEmpty()) {
                c.setRequestProperty("X-Mirror-Key", Mirror.key());
            }
            if (c.getResponseCode() != 200) {
                c.disconnect();
                return null;
            }
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > maxBytes) {
                    is.close();
                    c.disconnect();
                    return null;
                }
            }
            is.close();
            c.disconnect();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
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

    // ------------------------------------------------------------------ versions / rollback

    /** Resolves a commit sha, branch or tag (annotated or not) to the full sha of its commit. */
    public String resolveCommitSha(String o, String r, String ref) throws Exception {
        return obj(repo(o, r) + "/commits/" + enc(ref)).getString("sha");
    }

    /** First parent of a commit, or null when it is the very first commit of the history. */
    public String getParentSha(String o, String r, String commitSha) throws Exception {
        JSONArray parents = obj(repo(o, r) + "/git/commits/" + commitSha).optJSONArray("parents");
        if (parents == null || parents.length() == 0) return null;
        return parents.getJSONObject(0).getString("sha");
    }

    /**
     * Rolls a branch back to the exact content of {@code targetSha} by adding ONE new commit on top
     * of the current head. History is kept and nothing is force-pushed, so the rollback can itself
     * be undone later. Returns the new commit sha, or null when the branch already has that content.
     */
    public String restoreToCommit(String o, String r, String branch, String targetSha, String message) throws Exception {
        String headSha = getBranchSha(o, r, branch);
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

    // ------------------------------------------------------------------ actions

    public JSONArray listWorkflows(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/workflows?per_page=100").optJSONArray("workflows");
    }

    public JSONObject listRuns(String o, String r, long workflowId, String status, String branch,
                               int page, int perPage) throws Exception {
        return listRuns(o, r, workflowId, status, branch, null, null, page, perPage);
    }

    /** Same as above with GitHub's own "event" and "actor" filters. */
    public JSONObject listRuns(String o, String r, long workflowId, String status, String branch,
                               String event, String actor, int page, int perPage) throws Exception {
        StringBuilder p = new StringBuilder(repo(o, r));
        if (workflowId > 0) p.append("/actions/workflows/").append(workflowId).append("/runs");
        else p.append("/actions/runs");
        p.append("?per_page=").append(perPage).append("&page=").append(page);
        if (status != null && !status.isEmpty()) p.append("&status=").append(qe(status));
        if (branch != null && !branch.isEmpty()) p.append("&branch=").append(qe(branch));
        if (event != null && !event.isEmpty()) p.append("&event=").append(qe(event));
        if (actor != null && !actor.isEmpty()) p.append("&actor=").append(qe(actor));
        return obj(p.toString());
    }

    public JSONObject getRun(String o, String r, long runId) throws Exception {
        return obj(repo(o, r) + "/actions/runs/" + runId);
    }

    public JSONObject getJob(String o, String r, long jobId) throws Exception {
        return obj(repo(o, r) + "/actions/jobs/" + jobId);
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

    // ------------------------------------------------------------------ secrets (encrypted writes)

    public JSONObject secretsPublicKey(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/secrets/public-key");
    }

    /** Creates or updates an Actions secret: the value is sealed with the repository public key first. */
    public void putSecret(String o, String r, String name, String value) throws Exception {
        JSONObject pk = secretsPublicKey(o, r);
        byte[] key = Base64.decode(pk.getString("key"), Base64.DEFAULT);
        byte[] sealed = SealedBox.seal(value.getBytes(StandardCharsets.UTF_8), key);
        JSONObject b = new JSONObject();
        b.put("encrypted_value", Base64.encodeToString(sealed, Base64.NO_WRAP));
        b.put("key_id", pk.getString("key_id"));
        request("PUT", repo(o, r) + "/actions/secrets/" + enc(name), b);
    }

    // ------------------------------------------------------------------ Actions permissions

    public JSONObject actionsPermissions(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/permissions");
    }

    public void setActionsPermissions(String o, String r, boolean enabled, String allowedActions) throws Exception {
        JSONObject b = new JSONObject();
        b.put("enabled", enabled);
        if (enabled && allowedActions != null && !allowedActions.isEmpty()) b.put("allowed_actions", allowedActions);
        request("PUT", repo(o, r) + "/actions/permissions", b);
    }

    public JSONObject workflowPermissions(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/permissions/workflow");
    }

    public void setWorkflowPermissions(String o, String r, String defaultPerm, boolean canApprove) throws Exception {
        JSONObject b = new JSONObject();
        b.put("default_workflow_permissions", defaultPerm);
        b.put("can_approve_pull_request_reviews", canApprove);
        request("PUT", repo(o, r) + "/actions/permissions/workflow", b);
    }

    public JSONObject getWorkflow(String o, String r, long workflowId) throws Exception {
        return obj(repo(o, r) + "/actions/workflows/" + workflowId);
    }

    // ------------------------------------------------------------------ security

    /** True when Dependabot alerts are enabled (the API answers 204 when on and 404 when off). */
    public boolean vulnerabilityAlerts(String o, String r) throws Exception {
        try {
            request("GET", repo(o, r) + "/vulnerability-alerts", null);
            return true;
        } catch (ApiException e) {
            if (e.code == 404) return false;
            throw e;
        }
    }

    public void setVulnerabilityAlerts(String o, String r, boolean on) throws Exception {
        request(on ? "PUT" : "DELETE", repo(o, r) + "/vulnerability-alerts", null);
    }

    public boolean automatedSecurityFixes(String o, String r) throws Exception {
        try {
            return obj(repo(o, r) + "/automated-security-fixes").optBoolean("enabled");
        } catch (ApiException e) {
            if (e.code == 404) return false;
            throw e;
        }
    }

    public void setAutomatedSecurityFixes(String o, String r, boolean on) throws Exception {
        request(on ? "PUT" : "DELETE", repo(o, r) + "/automated-security-fixes", null);
    }

    // ------------------------------------------------------------------ branch protection

    /** Returns null when the branch has no protection rule. */
    public JSONObject getBranchProtection(String o, String r, String branch) throws Exception {
        try {
            return new JSONObject(request("GET", repo(o, r) + "/branches/" + enc(branch) + "/protection", null));
        } catch (ApiException e) {
            if (e.code == 404) return null;
            throw e;
        }
    }

    public void putBranchProtection(String o, String r, String branch, JSONObject body) throws Exception {
        request("PUT", repo(o, r) + "/branches/" + enc(branch) + "/protection", body);
    }

    public void deleteBranchProtection(String o, String r, String branch) throws Exception {
        request("DELETE", repo(o, r) + "/branches/" + enc(branch) + "/protection", null);
    }

    // ------------------------------------------------------------------ collaborators

    public JSONArray collaborators(String o, String r) throws Exception {
        return arr(repo(o, r) + "/collaborators?per_page=100&affiliation=all");
    }

    public JSONArray invitations(String o, String r) throws Exception {
        return arr(repo(o, r) + "/invitations?per_page=100");
    }

    public void addCollaborator(String o, String r, String user, String permission) throws Exception {
        JSONObject b = new JSONObject();
        b.put("permission", permission);
        request("PUT", repo(o, r) + "/collaborators/" + enc(user), b);
    }

    public void removeCollaborator(String o, String r, String user) throws Exception {
        request("DELETE", repo(o, r) + "/collaborators/" + enc(user), null);
    }

    public void deleteInvitation(String o, String r, long id) throws Exception {
        request("DELETE", repo(o, r) + "/invitations/" + id, null);
    }
}

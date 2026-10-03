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

        public TreeEntry(String path, String sha) {
            this.path = path;
            this.sha = sha;
        }
    }

    private static final String BASE = "https://api.github.com";
    private final String token;

    public GitHubApi(String token) {
        this.token = token;
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String enc(String path) throws IOException {
        StringBuilder sb = new StringBuilder();
        String[] parts = path.split("/");
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(URLEncoder.encode(p, "UTF-8").replace("+", "%20"));
        }
        return sb.toString();
    }

    private static String repo(String o, String r) {
        return "/repos/" + o + "/" + r;
    }

    private String request(String method, String path, JSONObject body) throws IOException, ApiException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "GitHubManagerApp");
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
        if (code >= 400) {
            String msg = resp;
            try {
                msg = new JSONObject(resp).optString("message", resp);
            } catch (Exception ignored) {
            }
            throw new ApiException(code, msg);
        }
        return resp;
    }

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

    public JSONArray listBranches(String o, String r) throws Exception {
        return new JSONArray(request("GET", repo(o, r) + "/branches?per_page=100", null));
    }

    public JSONArray listContents(String o, String r, String path, String branch) throws Exception {
        String p = repo(o, r) + "/contents" + (path.isEmpty() ? "" : "/" + enc(path))
                + "?ref=" + URLEncoder.encode(branch, "UTF-8");
        String res = request("GET", p, null).trim();
        if (res.startsWith("[")) return new JSONArray(res);
        return new JSONArray().put(new JSONObject(res));
    }

    public String getBranchSha(String o, String r, String branch) throws Exception {
        String res = request("GET", repo(o, r) + "/git/ref/heads/" + enc(branch), null);
        return new JSONObject(res).getJSONObject("object").getString("sha");
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
                e.put("mode", "100644");
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

    /** All file paths under a folder (recursive). */
    public List<String> listFilesUnder(String o, String r, String branch, String folder) throws Exception {
        String headSha = getBranchSha(o, r, branch);
        String tree = getCommitTree(o, r, headSha);
        String res = request("GET", repo(o, r) + "/git/trees/" + tree + "?recursive=1", null);
        JSONArray arr = new JSONObject(res).getJSONArray("tree");
        List<String> out = new ArrayList<>();
        String prefix = folder + "/";
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.getJSONObject(i);
            if ("blob".equals(e.optString("type")) && e.optString("path").startsWith(prefix)) {
                out.add(e.getString("path"));
            }
        }
        return out;
    }
}

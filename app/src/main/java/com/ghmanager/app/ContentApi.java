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

final class ContentApi extends ApiPart {
    ContentApi(ApiCore core) {
        super(core);
    }

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

    /** Used only to initialize an empty repository (no branch exists yet). */
    public void putFile(String o, String r, String path, byte[] data, String message) throws Exception {
        JSONObject b = new JSONObject();
        b.put("message", message);
        b.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
        request("PUT", repo(o, r) + "/contents/" + enc(path), b);
    }
}

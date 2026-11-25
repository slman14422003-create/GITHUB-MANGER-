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

final class ReleaseApi extends ApiPart {
    ReleaseApi(ApiCore core) {
        super(core);
    }

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

    public JSONObject generateNotes(String o, String r, String tag, String target, String previousTag) throws Exception {
        JSONObject b = new JSONObject();
        b.put("tag_name", tag);
        if (target != null && !target.isEmpty()) b.put("target_commitish", target);
        if (previousTag != null && !previousTag.isEmpty()) b.put("previous_tag_name", previousTag);
        return new JSONObject(request("POST", repo(o, r) + "/releases/generate-notes", b));
    }
}

package com.ghmanager.app;

import com.ghmanager.app.GitHubApi.ApiException;
import com.ghmanager.app.GitHubApi.BlobInfo;
import com.ghmanager.app.GitHubApi.HeadMoved;
import com.ghmanager.app.GitHubApi.TreeEntry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ApiParse {
    private ApiParse() {
    }

    static ApiException toException(int code, String resp) {
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

    static long rateWaitMs(String retryAfter, String remaining, String reset, long nowMs) {
        try {
            if (retryAfter != null && !retryAfter.trim().isEmpty()) {
                return Math.max(1, Long.parseLong(retryAfter.trim())) * 1000L;
            }
            if ("0".equals(remaining)) {
                if (reset != null) return Math.max(1000L, Long.parseLong(reset.trim()) * 1000L - nowMs + 1000L);
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    static ApiException graphqlError(JSONArray errs) {
        JSONObject e = errs.optJSONObject(0);
        String msg = e == null ? "GraphQL error" : e.optString("message", "GraphQL error");
        String type = e == null ? "" : e.optString("type", "");
        if ("STALE_DATA".equals(type) || msg.contains("Expected branch to point")) {
            return new HeadMoved(Redact.text(msg));
        }
        if ("RATE_LIMITED".equals(type)) {
            ApiException x = new ApiException(429, Redact.text(msg));
            x.waitMs = 60000L;
            return x;
        }
        int code = "NOT_FOUND".equals(type) ? 404
                : ("FORBIDDEN".equals(type) || "INSUFFICIENT_SCOPES".equals(type)) ? 403 : 422;
        return new ApiException(code, Redact.text(msg));
    }

    static String commitOid(JSONObject res) throws ApiException {
        JSONArray errs = res.optJSONArray("errors");
        if (errs != null && errs.length() > 0) throw graphqlError(errs);
        JSONObject data = res.optJSONObject("data");
        JSONObject c = data == null ? null : data.optJSONObject("createCommitOnBranch");
        JSONObject commit = c == null ? null : c.optJSONObject("commit");
        String oid = commit == null ? "" : commit.optString("oid", "");
        if (oid.isEmpty()) throw new ApiException(422, "GraphQL: empty answer");
        return oid;
    }

    static String commitTreeSha(String res) throws Exception {
        return new JSONObject(res).getJSONObject("tree").getString("sha");
    }

    static JSONArray treeArray(String res) throws Exception {
        return new JSONObject(res).getJSONArray("tree");
    }

    static List<TreeEntry> blobsUnder(String res, String folder) throws Exception {
        JSONArray arr = treeArray(res);
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

    static Map<String, String> blobShaMap(String res) throws Exception {
        JSONArray arr = treeArray(res);
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.getJSONObject(i);
            if ("blob".equals(e.optString("type"))) out.put(e.getString("path"), e.getString("sha"));
        }
        return out;
    }

    static List<BlobInfo> blobInfos(String res) throws Exception {
        JSONArray arr = treeArray(res);
        List<BlobInfo> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject e = arr.getJSONObject(i);
            if ("blob".equals(e.optString("type"))) {
                out.add(new BlobInfo(e.getString("path"), e.optLong("size", 0), e.optString("sha", "")));
            }
        }
        return out;
    }
}

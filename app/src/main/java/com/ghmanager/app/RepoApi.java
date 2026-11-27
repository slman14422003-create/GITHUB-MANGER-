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

final class RepoApi extends ApiPart {
    RepoApi(ApiCore core) {
        super(core);
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
        JSONObject j = obj(repo(o, r));
        RepoAccess.recordJson(o, r, j);
        return j;
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
}

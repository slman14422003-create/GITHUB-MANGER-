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

final class UserApi extends ApiPart {
    UserApi(ApiCore core) {
        super(core);
    }

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
}

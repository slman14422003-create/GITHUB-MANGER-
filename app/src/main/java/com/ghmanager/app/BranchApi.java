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

final class BranchApi extends ApiPart {
    BranchApi(ApiCore core) {
        super(core);
    }

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
}

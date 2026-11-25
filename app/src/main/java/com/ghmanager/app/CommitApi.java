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

final class CommitApi extends ApiPart {
    CommitApi(ApiCore core) {
        super(core);
    }

    public JSONArray listCommits(String o, String r, String branch, int page, int perPage) throws Exception {
        return arr(repo(o, r) + "/commits?sha=" + qe(branch) + "&per_page=" + perPage + "&page=" + page);
    }

    public JSONObject getCommit(String o, String r, String sha) throws Exception {
        return obj(repo(o, r) + "/commits/" + sha);
    }

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

    public JSONObject compare(String o, String r, String base, String head) throws Exception {
        return obj(repo(o, r) + "/compare/" + enc(base) + "..." + enc(head) + "?per_page=100");
    }

    public JSONArray listCommitsPath(String o, String r, String branch, String path, int page, int perPage) throws Exception {
        return arr(repo(o, r) + "/commits?sha=" + qe(branch) + "&path=" + qe(path) + "&per_page=" + perPage
                + "&page=" + page);
    }
}

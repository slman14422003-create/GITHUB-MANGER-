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

final class IssueApi extends ApiPart {
    IssueApi(ApiCore core) {
        super(core);
    }

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

    public void mergePull(String o, String r, long number, String method, String title, String message) throws Exception {
        JSONObject b = new JSONObject();
        b.put("merge_method", method);
        if (title != null && !title.isEmpty()) b.put("commit_title", title);
        if (message != null && !message.isEmpty()) b.put("commit_message", message);
        request("PUT", repo(o, r) + "/pulls/" + number + "/merge", b);
    }

    public JSONArray listPullFiles(String o, String r, long number, int page) throws Exception {
        return arr(repo(o, r) + "/pulls/" + number + "/files?per_page=100&page=" + page);
    }

    public JSONArray listPullCommits(String o, String r, long number) throws Exception {
        return arr(repo(o, r) + "/pulls/" + number + "/commits?per_page=100");
    }

    public JSONArray listReviews(String o, String r, long number) throws Exception {
        return arr(repo(o, r) + "/pulls/" + number + "/reviews?per_page=50");
    }

    public void createReview(String o, String r, long number, String event, String body) throws Exception {
        JSONObject b = new JSONObject();
        b.put("event", event);
        if (body != null && !body.isEmpty()) b.put("body", body);
        request("POST", repo(o, r) + "/pulls/" + number + "/reviews", b);
    }

    public JSONArray listPullComments(String o, String r, long number, int page) throws Exception {
        return arr(repo(o, r) + "/pulls/" + number + "/comments?per_page=100&page=" + page);
    }

    public JSONObject createReview(String o, String r, long number, String commitId, String event, String body,
                                   JSONArray comments) throws Exception {
        JSONObject b = new JSONObject();
        if (commitId != null && !commitId.isEmpty()) b.put("commit_id", commitId);
        b.put("event", event);
        if (body != null && !body.isEmpty()) b.put("body", body);
        if (comments != null && comments.length() > 0) b.put("comments", comments);
        String res = request("POST", repo(o, r) + "/pulls/" + number + "/reviews", b);
        return res == null || res.trim().isEmpty() ? new JSONObject() : new JSONObject(res);
    }

    public void submitReview(String o, String r, long number, long reviewId, String event, String body)
            throws Exception {
        JSONObject b = new JSONObject();
        b.put("event", event);
        if (body != null && !body.isEmpty()) b.put("body", body);
        request("POST", repo(o, r) + "/pulls/" + number + "/reviews/" + reviewId + "/events", b);
    }

    public void deleteReview(String o, String r, long number, long reviewId) throws Exception {
        request("DELETE", repo(o, r) + "/pulls/" + number + "/reviews/" + reviewId, null);
    }

    public JSONObject createPull(String o, String r, String title, String body, String head, String base,
                                 boolean draft) throws Exception {
        JSONObject b = new JSONObject();
        b.put("title", title);
        b.put("head", head);
        b.put("base", base);
        if (body != null && !body.isEmpty()) b.put("body", body);
        b.put("draft", draft);
        return new JSONObject(request("POST", repo(o, r) + "/pulls", b));
    }
}

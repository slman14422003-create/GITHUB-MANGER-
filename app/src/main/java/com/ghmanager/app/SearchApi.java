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

final class SearchApi extends ApiPart {
    SearchApi(ApiCore core) {
        super(core);
    }

    public JSONObject searchCode(String o, String r, String q, int page, int perPage) throws Exception {
        return obj("/search/code?q=" + qe(q + " repo:" + o + "/" + r) + "&per_page=" + perPage + "&page=" + page);
    }

    public JSONObject searchRepos(String q, int page, int perPage) throws Exception {
        return obj("/search/repositories?q=" + qe(q) + "&per_page=" + perPage + "&page=" + page);
    }

    public JSONObject searchRepos(String q, String sort, String order, int page, int perPage) throws Exception {
        StringBuilder p = new StringBuilder("/search/repositories?q=").append(qe(q));
        if (sort != null && !sort.isEmpty()) {
            p.append("&sort=").append(qe(sort)).append("&order=")
                    .append(qe(order == null || order.isEmpty() ? "desc" : order));
        }
        p.append("&per_page=").append(perPage).append("&page=").append(page);
        return obj(p.toString());
    }

    public JSONObject searchCodeQuery(String q, int page, int perPage) throws Exception {
        return obj("/search/code?q=" + qe(q) + "&per_page=" + perPage + "&page=" + page);
    }

    public JSONObject searchIssues(String q, int page, int perPage) throws Exception {
        return obj("/search/issues?q=" + qe(q) + "&per_page=" + perPage + "&page=" + page);
    }
}

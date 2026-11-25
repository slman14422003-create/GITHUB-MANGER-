package com.ghmanager.app;

import com.ghmanager.app.GitHubApi.ApiException;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;

abstract class ApiPart {
    final ApiCore core;

    ApiPart(ApiCore core) {
        this.core = core;
    }

    final String request(String method, String path, JSONObject body) throws IOException, ApiException {
        return core.request(method, path, body);
    }

    final JSONObject obj(String path) throws Exception {
        return core.obj(path);
    }

    final JSONArray arr(String path) throws Exception {
        return core.arr(path);
    }

    final HttpURLConnection open(String method, String url, String accept, boolean api) throws IOException {
        return core.open(method, url, accept, api);
    }

    final HttpURLConnection openDownload(String path, String accept) throws Exception {
        return core.openDownload(path, accept);
    }
}

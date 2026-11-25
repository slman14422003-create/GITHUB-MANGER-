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

final class NotificationApi extends ApiPart {
    NotificationApi(ApiCore core) {
        super(core);
    }

    public JSONArray listNotifications(boolean all, int page, int perPage) throws Exception {
        return arr("/notifications?all=" + all + "&per_page=" + perPage + "&page=" + page);
    }

    public void markNotificationRead(String id) throws Exception {
        request("PATCH", "/notifications/threads/" + seg(id), null);
    }

    public void markAllNotificationsRead() throws Exception {
        request("PUT", "/notifications", new JSONObject());
    }
}

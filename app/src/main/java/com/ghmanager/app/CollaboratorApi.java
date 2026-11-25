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

final class CollaboratorApi extends ApiPart {
    CollaboratorApi(ApiCore core) {
        super(core);
    }

    public JSONArray collaborators(String o, String r) throws Exception {
        return arr(repo(o, r) + "/collaborators?per_page=100&affiliation=all");
    }

    public JSONArray invitations(String o, String r) throws Exception {
        return arr(repo(o, r) + "/invitations?per_page=100");
    }

    public void addCollaborator(String o, String r, String user, String permission) throws Exception {
        JSONObject b = new JSONObject();
        b.put("permission", permission);
        request("PUT", repo(o, r) + "/collaborators/" + enc(user), b);
    }

    public void removeCollaborator(String o, String r, String user) throws Exception {
        request("DELETE", repo(o, r) + "/collaborators/" + enc(user), null);
    }

    public void deleteInvitation(String o, String r, long id) throws Exception {
        request("DELETE", repo(o, r) + "/invitations/" + id, null);
    }
}

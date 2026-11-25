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

final class SecurityApi extends ApiPart {
    SecurityApi(ApiCore core) {
        super(core);
    }

    /** True when Dependabot alerts are enabled (the API answers 204 when on and 404 when off). */
    public boolean vulnerabilityAlerts(String o, String r) throws Exception {
        try {
            request("GET", repo(o, r) + "/vulnerability-alerts", null);
            return true;
        } catch (ApiException e) {
            if (e.code == 404) return false;
            throw e;
        }
    }

    public void setVulnerabilityAlerts(String o, String r, boolean on) throws Exception {
        request(on ? "PUT" : "DELETE", repo(o, r) + "/vulnerability-alerts", null);
    }

    public boolean automatedSecurityFixes(String o, String r) throws Exception {
        try {
            return obj(repo(o, r) + "/automated-security-fixes").optBoolean("enabled");
        } catch (ApiException e) {
            if (e.code == 404) return false;
            throw e;
        }
    }

    public void setAutomatedSecurityFixes(String o, String r, boolean on) throws Exception {
        request(on ? "PUT" : "DELETE", repo(o, r) + "/automated-security-fixes", null);
    }

    /** Returns null when the branch has no protection rule. */
    public JSONObject getBranchProtection(String o, String r, String branch) throws Exception {
        try {
            return new JSONObject(request("GET", repo(o, r) + "/branches/" + enc(branch) + "/protection", null));
        } catch (ApiException e) {
            if (e.code == 404) return null;
            throw e;
        }
    }

    public void putBranchProtection(String o, String r, String branch, JSONObject body) throws Exception {
        request("PUT", repo(o, r) + "/branches/" + enc(branch) + "/protection", body);
    }

    public void deleteBranchProtection(String o, String r, String branch) throws Exception {
        request("DELETE", repo(o, r) + "/branches/" + enc(branch) + "/protection", null);
    }
}

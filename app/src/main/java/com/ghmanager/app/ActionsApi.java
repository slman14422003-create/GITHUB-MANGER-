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

final class ActionsApi extends ApiPart {
    ActionsApi(ApiCore core) {
        super(core);
    }

    public JSONArray listWorkflows(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/workflows?per_page=100").optJSONArray("workflows");
    }

    public JSONObject listRuns(String o, String r, long workflowId, String status, String branch,
                               int page, int perPage) throws Exception {
        return listRuns(o, r, workflowId, status, branch, null, null, page, perPage);
    }

    /** Same as above with GitHub's own "event" and "actor" filters. */
    public JSONObject listRuns(String o, String r, long workflowId, String status, String branch,
                               String event, String actor, int page, int perPage) throws Exception {
        StringBuilder p = new StringBuilder(repo(o, r));
        if (workflowId > 0) p.append("/actions/workflows/").append(workflowId).append("/runs");
        else p.append("/actions/runs");
        p.append("?per_page=").append(perPage).append("&page=").append(page);
        if (status != null && !status.isEmpty()) p.append("&status=").append(qe(status));
        if (branch != null && !branch.isEmpty()) p.append("&branch=").append(qe(branch));
        if (event != null && !event.isEmpty()) p.append("&event=").append(qe(event));
        if (actor != null && !actor.isEmpty()) p.append("&actor=").append(qe(actor));
        return obj(p.toString());
    }

    public JSONObject getRun(String o, String r, long runId) throws Exception {
        return obj(repo(o, r) + "/actions/runs/" + runId);
    }

    public JSONObject getJob(String o, String r, long jobId) throws Exception {
        return obj(repo(o, r) + "/actions/jobs/" + jobId);
    }

    public JSONArray listJobs(String o, String r, long runId) throws Exception {
        return obj(repo(o, r) + "/actions/runs/" + runId + "/jobs?per_page=100&filter=latest").optJSONArray("jobs");
    }

    public JSONArray listRunArtifacts(String o, String r, long runId) throws Exception {
        return obj(repo(o, r) + "/actions/runs/" + runId + "/artifacts?per_page=100").optJSONArray("artifacts");
    }

    public void rerunRun(String o, String r, long runId) throws Exception {
        request("POST", repo(o, r) + "/actions/runs/" + runId + "/rerun", null);
    }

    public void rerunFailed(String o, String r, long runId) throws Exception {
        request("POST", repo(o, r) + "/actions/runs/" + runId + "/rerun-failed-jobs", null);
    }

    public void rerunJob(String o, String r, long jobId) throws Exception {
        request("POST", repo(o, r) + "/actions/jobs/" + jobId + "/rerun", null);
    }

    public void cancelRun(String o, String r, long runId, boolean force) throws Exception {
        request("POST", repo(o, r) + "/actions/runs/" + runId + (force ? "/force-cancel" : "/cancel"), null);
    }

    public void deleteRun(String o, String r, long runId) throws Exception {
        request("DELETE", repo(o, r) + "/actions/runs/" + runId, null);
    }

    public String jobLogsPath(String o, String r, long jobId) {
        return repo(o, r) + "/actions/jobs/" + jobId + "/logs";
    }

    public String runLogsPath(String o, String r, long runId) {
        return repo(o, r) + "/actions/runs/" + runId + "/logs";
    }

    public void dispatchWorkflow(String o, String r, long workflowId, String ref, JSONObject inputs) throws Exception {
        JSONObject b = new JSONObject();
        b.put("ref", ref);
        if (inputs != null && inputs.length() > 0) b.put("inputs", inputs);
        request("POST", repo(o, r) + "/actions/workflows/" + workflowId + "/dispatches", b);
    }

    public void setWorkflowEnabled(String o, String r, long workflowId, boolean enabled) throws Exception {
        request("PUT", repo(o, r) + "/actions/workflows/" + workflowId + (enabled ? "/enable" : "/disable"), null);
    }

    public JSONArray listArtifacts(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/artifacts?per_page=100").optJSONArray("artifacts");
    }

    public void deleteArtifact(String o, String r, long id) throws Exception {
        request("DELETE", repo(o, r) + "/actions/artifacts/" + id, null);
    }

    public String artifactZipPath(String o, String r, long id) {
        return repo(o, r) + "/actions/artifacts/" + id + "/zip";
    }

    public JSONArray listCaches(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/caches?per_page=100&sort=last_accessed_at&direction=desc")
                .optJSONArray("actions_caches");
    }

    public JSONObject cacheUsage(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/cache/usage");
    }

    public void deleteCache(String o, String r, long id) throws Exception {
        request("DELETE", repo(o, r) + "/actions/caches/" + id, null);
    }

    public JSONArray listVariables(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/variables?per_page=100").optJSONArray("variables");
    }

    public void createVariable(String o, String r, String name, String value) throws Exception {
        JSONObject b = new JSONObject();
        b.put("name", name);
        b.put("value", value);
        request("POST", repo(o, r) + "/actions/variables", b);
    }

    public void updateVariable(String o, String r, String name, String value) throws Exception {
        JSONObject b = new JSONObject();
        b.put("name", name);
        b.put("value", value);
        request("PATCH", repo(o, r) + "/actions/variables/" + enc(name), b);
    }

    public void deleteVariable(String o, String r, String name) throws Exception {
        request("DELETE", repo(o, r) + "/actions/variables/" + enc(name), null);
    }

    public JSONArray listSecrets(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/secrets?per_page=100").optJSONArray("secrets");
    }

    public void deleteSecret(String o, String r, String name) throws Exception {
        request("DELETE", repo(o, r) + "/actions/secrets/" + enc(name), null);
    }

    public JSONObject secretsPublicKey(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/secrets/public-key");
    }

    /** Creates or updates an Actions secret: the value is sealed with the repository public key first. */
    public void putSecret(String o, String r, String name, String value) throws Exception {
        JSONObject pk = secretsPublicKey(o, r);
        byte[] key = Base64.decode(pk.getString("key"), Base64.DEFAULT);
        byte[] sealed = SealedBox.seal(value.getBytes(StandardCharsets.UTF_8), key);
        JSONObject b = new JSONObject();
        b.put("encrypted_value", Base64.encodeToString(sealed, Base64.NO_WRAP));
        b.put("key_id", pk.getString("key_id"));
        request("PUT", repo(o, r) + "/actions/secrets/" + enc(name), b);
    }

    public JSONObject actionsPermissions(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/permissions");
    }

    public void setActionsPermissions(String o, String r, boolean enabled, String allowedActions) throws Exception {
        JSONObject b = new JSONObject();
        b.put("enabled", enabled);
        if (enabled && allowedActions != null && !allowedActions.isEmpty()) b.put("allowed_actions", allowedActions);
        request("PUT", repo(o, r) + "/actions/permissions", b);
    }

    public JSONObject workflowPermissions(String o, String r) throws Exception {
        return obj(repo(o, r) + "/actions/permissions/workflow");
    }

    public void setWorkflowPermissions(String o, String r, String defaultPerm, boolean canApprove) throws Exception {
        JSONObject b = new JSONObject();
        b.put("default_workflow_permissions", defaultPerm);
        b.put("can_approve_pull_request_reviews", canApprove);
        request("PUT", repo(o, r) + "/actions/permissions/workflow", b);
    }

    public JSONObject getWorkflow(String o, String r, long workflowId) throws Exception {
        return obj(repo(o, r) + "/actions/workflows/" + workflowId);
    }

    public JSONObject lastSuccessfulRun(String o, String r, JSONObject run) throws Exception {
        if (run == null) return null;
        long wf = run.optLong("workflow_id");
        if (wf <= 0) return null;
        String br = run.isNull("head_branch") ? "" : run.optString("head_branch");
        long id = run.optLong("id");
        long created = Fmt.parse(run.optString("created_at"));
        for (int page = 1; page <= 3; page++) {
            JSONArray a = listRuns(o, r, wf, "success", br, page, 50).optJSONArray("workflow_runs");
            if (a == null || a.length() == 0) return null;
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                if (x == null || x.optLong("id") == id) continue;
                long t = Fmt.parse(x.optString("created_at"));
                if (created > 0 && t >= created) continue;
                return x;
            }
            if (a.length() < 50) return null;
        }
        return null;
    }

    public JSONObject compare(String o, String r, String base, String head) throws Exception {
        return obj(repo(o, r) + "/compare/" + enc(base) + "..." + enc(head) + "?per_page=100");
    }
}

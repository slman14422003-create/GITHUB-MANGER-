package com.ghmanager.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.List;

public class GitHubApi {

    public static final class HeadMoved extends ApiException {
        public HeadMoved(String msg) {
            super(409, msg);
        }
    }

    public static final class Addition {
        public final String path;
        public final byte[] data;

        public Addition(String path, byte[] data) {
            this.path = path;
            this.data = data;
        }
    }

    public static class ApiException extends Exception {
        public final int code;
        /** Milliseconds GitHub asked us to wait (rate limit), 0 when not applicable. */
        public long waitMs = 0;

        public ApiException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    public static class TreeEntry {
        public final String path;
        public final String sha;
        public final String mode;
        public final String content;

        public TreeEntry(String path, String sha) {
            this(path, sha, "100644");
        }

        public TreeEntry(String path, String sha, String mode) {
            this.path = path;
            this.sha = sha;
            this.mode = mode == null ? "100644" : mode;
            this.content = null;
        }

        private TreeEntry(String path, String content, boolean inline) {
            this.path = path;
            this.sha = null;
            this.mode = "100644";
            this.content = content;
        }

        public static TreeEntry inline(String path, String content) {
            return new TreeEntry(path, content, true);
        }
    }

    public interface Progress {
        void on(long done, long total);
    }

    public static class TextResult {
        public final String text;
        public final boolean truncated;

        TextResult(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }

    public static final class Piece {
        public final byte[] data;
        public final boolean ranged;

        Piece(byte[] data, boolean ranged) {
            this.data = data;
            this.ranged = ranged;
        }
    }

    /** path + size, every blob in the whole repository tree (used by SEMO AI to pick which files to read). */
    public static final class BlobInfo {
        public final String path;
        public final long size;
        public final String sha;

        BlobInfo(String path, long size) {
            this(path, size, "");
        }

        BlobInfo(String path, long size, String sha) {
            this.path = path;
            this.size = size;
            this.sha = sha == null ? "" : sha;
        }
    }

    private final ApiCore core;
    private final UserApi userApi;
    private final RepoApi repoApi;
    private final BranchApi branchApi;
    private final ContentApi contentApi;
    private final GitDataApi gitDataApi;
    private final CommitApi commitApi;
    private final ActionsApi actionsApi;
    private final ReleaseApi releaseApi;
    private final IssueApi issueApi;
    private final SearchApi searchApi;
    private final NotificationApi notificationApi;
    private final SecurityApi securityApi;
    private final CollaboratorApi collaboratorApi;
    private final DownloadApi downloadApi;

    public GitHubApi(String token) {
        this(new ApiCore(token), true);
    }

    public GitHubApi(Context c) {
        this(new ApiCore(c), true);
    }

    private GitHubApi(ApiCore core, boolean wired) {
        this.core = core;
        this.userApi = new UserApi(core);
        this.repoApi = new RepoApi(core);
        this.branchApi = new BranchApi(core);
        this.contentApi = new ContentApi(core);
        this.commitApi = new CommitApi(core);
        this.actionsApi = new ActionsApi(core);
        this.releaseApi = new ReleaseApi(core);
        this.issueApi = new IssueApi(core);
        this.searchApi = new SearchApi(core);
        this.notificationApi = new NotificationApi(core);
        this.securityApi = new SecurityApi(core);
        this.collaboratorApi = new CollaboratorApi(core);
        this.downloadApi = new DownloadApi(core);
        this.gitDataApi = new GitDataApi(core, this.branchApi);
    }

    public static String enc(String path) throws IOException {
        return ApiCore.enc(path);
    }

    public static String qe(String s) {
        return ApiCore.qe(s);
    }

    public static String repo(String o, String r) {
        return ApiCore.repo(o, r);
    }

    public static void directDownload(boolean on) {
        ApiCore.directDownload(on);
    }

    public static void rangeFrom(long from) {
        ApiCore.rangeFrom(from);
    }

    public static long copy(InputStream in, OutputStream out, long total, Progress p) throws IOException {
        return ApiCore.copy(in, out, total, p);
    }

    public static String postForm(String url, String form) throws Exception {
        return ApiCore.postForm(url, form);
    }

    public static byte[] fetchBytes(String url, int maxBytes) {
        return ApiCore.fetchBytes(url, maxBytes);
    }

    public static byte[] fetchBytes(String url, int maxBytes, boolean forceMirror, int connectMs) {
        return ApiCore.fetchBytes(url, maxBytes, forceMirror, connectMs);
    }

    public String call(String method, String path, JSONObject body) throws Exception {
        return core.request(method, path, body);
    }

    public JSONObject obj(String path) throws Exception {
        return core.obj(path);
    }

    public JSONArray arr(String path) throws Exception {
        return core.arr(path);
    }

    public HttpURLConnection openDownload(String path, String accept) throws Exception {
        return core.openDownload(path, accept);
    }

    public JSONObject getUser() throws Exception  {
        return userApi.getUser();
    }

    public JSONObject getUserMeta() throws Exception  {
        return userApi.getUserMeta();
    }

    public JSONObject updateProfile(JSONObject patch) throws Exception  {
        return userApi.updateProfile(patch);
    }

    public JSONArray listEmails() throws Exception  {
        return userApi.listEmails();
    }

    public JSONArray listSshKeys() throws Exception  {
        return userApi.listSshKeys();
    }

    public void addSshKey(String title, String key) throws Exception  {
        userApi.addSshKey(title, key);
    }

    public void deleteSshKey(long id) throws Exception  {
        userApi.deleteSshKey(id);
    }

    public JSONArray listOrgs() throws Exception  {
        return userApi.listOrgs();
    }

    public JSONObject rateLimit() throws Exception  {
        return userApi.rateLimit();
    }

    public JSONArray listRepos() throws Exception  {
        return repoApi.listRepos();
    }

    public JSONObject createRepo(String name, boolean isPrivate) throws Exception  {
        return repoApi.createRepo(name, isPrivate);
    }

    public JSONObject getRepo(String o, String r) throws Exception  {
        return repoApi.getRepo(o, r);
    }

    public JSONObject updateRepo(String o, String r, JSONObject patch) throws Exception  {
        return repoApi.updateRepo(o, r, patch);
    }

    public void deleteRepo(String o, String r) throws Exception  {
        repoApi.deleteRepo(o, r);
    }

    public JSONObject languages(String o, String r) throws Exception  {
        return repoApi.languages(o, r);
    }

    public JSONArray contributors(String o, String r) throws Exception  {
        return repoApi.contributors(o, r);
    }

    public JSONObject trafficViews(String o, String r) throws Exception  {
        return repoApi.trafficViews(o, r);
    }

    public JSONObject trafficClones(String o, String r) throws Exception  {
        return repoApi.trafficClones(o, r);
    }

    public JSONArray topics(String o, String r) throws Exception  {
        return repoApi.topics(o, r);
    }

    public void setTopics(String o, String r, List<String> names) throws Exception  {
        repoApi.setTopics(o, r, names);
    }

    public boolean isStarred(String o, String r) throws Exception  {
        return repoApi.isStarred(o, r);
    }

    public void star(String o, String r, boolean on) throws Exception  {
        repoApi.star(o, r, on);
    }

    public JSONArray listBranches(String o, String r) throws Exception  {
        return branchApi.listBranches(o, r);
    }

    public String getBranchSha(String o, String r, String branch) throws Exception  {
        return branchApi.getBranchSha(o, r, branch);
    }

    public void createBranch(String o, String r, String name, String fromSha) throws Exception  {
        branchApi.createBranch(o, r, name, fromSha);
    }

    public void deleteBranch(String o, String r, String name) throws Exception  {
        branchApi.deleteBranch(o, r, name);
    }

    public boolean mergeBranches(String o, String r, String base, String head, String message) throws Exception  {
        return branchApi.mergeBranches(o, r, base, head, message);
    }

    public JSONArray listContents(String o, String r, String path, String branch) throws Exception  {
        return contentApi.listContents(o, r, path, branch);
    }

    public JSONObject getContent(String o, String r, String path, String branch) throws Exception  {
        return contentApi.getContent(o, r, path, branch);
    }

    public String contentRawPath(String o, String r, String path, String branch) throws IOException  {
        return contentApi.contentRawPath(o, r, path, branch);
    }

    public byte[] getFileBytes(String o, String r, String path, String branch, int maxBytes) throws Exception  {
        return contentApi.getFileBytes(o, r, path, branch, maxBytes);
    }

    public JSONObject putFileContent(String o, String r, String path, byte[] data, String message, String branch, String sha) throws Exception  {
        return contentApi.putFileContent(o, r, path, data, message, branch, sha);
    }

    public HttpURLConnection openZipball(String o, String r, String ref) throws Exception  {
        return contentApi.openZipball(o, r, ref);
    }

    public HttpURLConnection openTarball(String o, String r, String ref) throws Exception  {
        return contentApi.openTarball(o, r, ref);
    }

    public void putFile(String o, String r, String path, byte[] data, String message) throws Exception  {
        contentApi.putFile(o, r, path, data, message);
    }

    public JSONObject treeRecursive(String o, String r, String branch, String etag, String[] etagOut) throws Exception  {
        return gitDataApi.treeRecursive(o, r, branch, etag, etagOut);
    }

    public String createBlob(String o, String r, byte[] data) throws Exception  {
        return gitDataApi.createBlob(o, r, data);
    }

    public String createCommitOnBranch(String o, String r, String branch, String expectedHead, String message, List<Addition> adds) throws Exception  {
        return gitDataApi.createCommitOnBranch(o, r, branch, expectedHead, message, adds);
    }

    public String commitEntries(String o, String r, String branch, List<TreeEntry> entries, String message) throws Exception  {
        return gitDataApi.commitEntries(o, r, branch, entries, message);
    }

    public String commitTree(String o, String r, String parentSha, List<TreeEntry> entries, String message) throws Exception  {
        return gitDataApi.commitTree(o, r, parentSha, entries, message);
    }

    public void moveBranch(String o, String r, String branch, String sha, boolean force) throws Exception  {
        gitDataApi.moveBranch(o, r, branch, sha, force);
    }

    public List<TreeEntry> listBlobsUnder(String o, String r, String branch, String folder) throws Exception  {
        return gitDataApi.listBlobsUnder(o, r, branch, folder);
    }

    public java.util.Map<String, String> blobShaMap(String o, String r, String branch) throws Exception  {
        return gitDataApi.blobShaMap(o, r, branch);
    }

    public List<String> listFilesUnder(String o, String r, String branch, String folder) throws Exception  {
        return gitDataApi.listFilesUnder(o, r, branch, folder);
    }

    public List<BlobInfo> listAllBlobs(String o, String r, String branch) throws Exception  {
        return gitDataApi.listAllBlobs(o, r, branch);
    }

    public List<BlobInfo> listBlobsForPaths(String o, String r, String branch, List<String> sel) throws Exception  {
        return gitDataApi.listBlobsForPaths(o, r, branch, sel);
    }

    public String restoreToCommit(String o, String r, String branch, String targetSha, String message) throws Exception  {
        return gitDataApi.restoreToCommit(o, r, branch, targetSha, message);
    }

    public JSONArray listCommits(String o, String r, String branch, int page, int perPage) throws Exception  {
        return commitApi.listCommits(o, r, branch, page, perPage);
    }

    public JSONObject getCommit(String o, String r, String sha) throws Exception  {
        return commitApi.getCommit(o, r, sha);
    }

    public String resolveCommitSha(String o, String r, String ref) throws Exception  {
        return commitApi.resolveCommitSha(o, r, ref);
    }

    public String getParentSha(String o, String r, String commitSha) throws Exception  {
        return commitApi.getParentSha(o, r, commitSha);
    }

    public JSONObject compare(String o, String r, String base, String head) throws Exception  {
        return commitApi.compare(o, r, base, head);
    }

    public JSONArray listCommitsPath(String o, String r, String branch, String path, int page, int perPage) throws Exception  {
        return commitApi.listCommitsPath(o, r, branch, path, page, perPage);
    }

    public JSONArray listWorkflows(String o, String r) throws Exception  {
        return actionsApi.listWorkflows(o, r);
    }

    public JSONObject listRuns(String o, String r, long workflowId, String status, String branch, int page, int perPage) throws Exception  {
        return actionsApi.listRuns(o, r, workflowId, status, branch, page, perPage);
    }

    public JSONObject listRuns(String o, String r, long workflowId, String status, String branch, String event, String actor, int page, int perPage) throws Exception  {
        return actionsApi.listRuns(o, r, workflowId, status, branch, event, actor, page, perPage);
    }

    public JSONObject getRun(String o, String r, long runId) throws Exception  {
        return actionsApi.getRun(o, r, runId);
    }

    public JSONObject getJob(String o, String r, long jobId) throws Exception  {
        return actionsApi.getJob(o, r, jobId);
    }

    public JSONArray listJobs(String o, String r, long runId) throws Exception  {
        return actionsApi.listJobs(o, r, runId);
    }

    public JSONArray listRunArtifacts(String o, String r, long runId) throws Exception  {
        return actionsApi.listRunArtifacts(o, r, runId);
    }

    public void rerunRun(String o, String r, long runId) throws Exception  {
        actionsApi.rerunRun(o, r, runId);
    }

    public void rerunFailed(String o, String r, long runId) throws Exception  {
        actionsApi.rerunFailed(o, r, runId);
    }

    public void rerunJob(String o, String r, long jobId) throws Exception  {
        actionsApi.rerunJob(o, r, jobId);
    }

    public void cancelRun(String o, String r, long runId, boolean force) throws Exception  {
        actionsApi.cancelRun(o, r, runId, force);
    }

    public void deleteRun(String o, String r, long runId) throws Exception  {
        actionsApi.deleteRun(o, r, runId);
    }

    public String jobLogsPath(String o, String r, long jobId) {
        return actionsApi.jobLogsPath(o, r, jobId);
    }

    public String runLogsPath(String o, String r, long runId) {
        return actionsApi.runLogsPath(o, r, runId);
    }

    public void dispatchWorkflow(String o, String r, long workflowId, String ref, JSONObject inputs) throws Exception  {
        actionsApi.dispatchWorkflow(o, r, workflowId, ref, inputs);
    }

    public void setWorkflowEnabled(String o, String r, long workflowId, boolean enabled) throws Exception  {
        actionsApi.setWorkflowEnabled(o, r, workflowId, enabled);
    }

    public JSONArray listArtifacts(String o, String r) throws Exception  {
        return actionsApi.listArtifacts(o, r);
    }

    public void deleteArtifact(String o, String r, long id) throws Exception  {
        actionsApi.deleteArtifact(o, r, id);
    }

    public String artifactZipPath(String o, String r, long id) {
        return actionsApi.artifactZipPath(o, r, id);
    }

    public JSONArray listCaches(String o, String r) throws Exception  {
        return actionsApi.listCaches(o, r);
    }

    public JSONObject cacheUsage(String o, String r) throws Exception  {
        return actionsApi.cacheUsage(o, r);
    }

    public void deleteCache(String o, String r, long id) throws Exception  {
        actionsApi.deleteCache(o, r, id);
    }

    public JSONArray listVariables(String o, String r) throws Exception  {
        return actionsApi.listVariables(o, r);
    }

    public void createVariable(String o, String r, String name, String value) throws Exception  {
        actionsApi.createVariable(o, r, name, value);
    }

    public void updateVariable(String o, String r, String name, String value) throws Exception  {
        actionsApi.updateVariable(o, r, name, value);
    }

    public void deleteVariable(String o, String r, String name) throws Exception  {
        actionsApi.deleteVariable(o, r, name);
    }

    public JSONArray listSecrets(String o, String r) throws Exception  {
        return actionsApi.listSecrets(o, r);
    }

    public void deleteSecret(String o, String r, String name) throws Exception  {
        actionsApi.deleteSecret(o, r, name);
    }

    public JSONObject secretsPublicKey(String o, String r) throws Exception  {
        return actionsApi.secretsPublicKey(o, r);
    }

    public void putSecret(String o, String r, String name, String value) throws Exception  {
        actionsApi.putSecret(o, r, name, value);
    }

    public JSONObject actionsPermissions(String o, String r) throws Exception  {
        return actionsApi.actionsPermissions(o, r);
    }

    public void setActionsPermissions(String o, String r, boolean enabled, String allowedActions) throws Exception  {
        actionsApi.setActionsPermissions(o, r, enabled, allowedActions);
    }

    public JSONObject workflowPermissions(String o, String r) throws Exception  {
        return actionsApi.workflowPermissions(o, r);
    }

    public void setWorkflowPermissions(String o, String r, String defaultPerm, boolean canApprove) throws Exception  {
        actionsApi.setWorkflowPermissions(o, r, defaultPerm, canApprove);
    }

    public JSONObject lastSuccessfulRun(String o, String r, JSONObject run) throws Exception  {
        return actionsApi.lastSuccessfulRun(o, r, run);
    }

    public JSONObject compareRefs(String o, String r, String base, String head) throws Exception  {
        return actionsApi.compare(o, r, base, head);
    }

    public JSONObject getWorkflow(String o, String r, long workflowId) throws Exception  {
        return actionsApi.getWorkflow(o, r, workflowId);
    }

    public JSONArray listReleases(String o, String r, int page, int perPage) throws Exception  {
        return releaseApi.listReleases(o, r, page, perPage);
    }

    public JSONObject getRelease(String o, String r, long id) throws Exception  {
        return releaseApi.getRelease(o, r, id);
    }

    public JSONObject createRelease(String o, String r, JSONObject body) throws Exception  {
        return releaseApi.createRelease(o, r, body);
    }

    public JSONObject updateRelease(String o, String r, long id, JSONObject body) throws Exception  {
        return releaseApi.updateRelease(o, r, id, body);
    }

    public void deleteRelease(String o, String r, long id) throws Exception  {
        releaseApi.deleteRelease(o, r, id);
    }

    public void deleteTag(String o, String r, String tag) throws Exception  {
        releaseApi.deleteTag(o, r, tag);
    }

    public JSONObject generateNotes(String o, String r, String tag, String target) throws Exception  {
        return releaseApi.generateNotes(o, r, tag, target);
    }

    public void deleteAsset(String o, String r, long id) throws Exception  {
        releaseApi.deleteAsset(o, r, id);
    }

    public String assetPath(String o, String r, long id) {
        return releaseApi.assetPath(o, r, id);
    }

    public JSONObject uploadAsset(String o, String r, long releaseId, String name, String contentType, InputStream in, long size, Progress p) throws Exception  {
        return releaseApi.uploadAsset(o, r, releaseId, name, contentType, in, size, p);
    }

    public JSONObject generateNotes(String o, String r, String tag, String target, String previousTag) throws Exception  {
        return releaseApi.generateNotes(o, r, tag, target, previousTag);
    }

    public JSONArray listIssues(String o, String r, String state, int page, int perPage) throws Exception  {
        return issueApi.listIssues(o, r, state, page, perPage);
    }

    public JSONObject getIssue(String o, String r, long number) throws Exception  {
        return issueApi.getIssue(o, r, number);
    }

    public JSONArray listComments(String o, String r, long number) throws Exception  {
        return issueApi.listComments(o, r, number);
    }

    public JSONObject createIssue(String o, String r, String title, String body) throws Exception  {
        return issueApi.createIssue(o, r, title, body);
    }

    public void setIssueState(String o, String r, long number, boolean open) throws Exception  {
        issueApi.setIssueState(o, r, number, open);
    }

    public void addComment(String o, String r, long number, String body) throws Exception  {
        issueApi.addComment(o, r, number, body);
    }

    public JSONObject getPull(String o, String r, long number) throws Exception  {
        return issueApi.getPull(o, r, number);
    }

    public void mergePull(String o, String r, long number, String method) throws Exception  {
        issueApi.mergePull(o, r, number, method);
    }

    public void mergePull(String o, String r, long number, String method, String title, String message) throws Exception  {
        issueApi.mergePull(o, r, number, method, title, message);
    }

    public JSONArray listPullFiles(String o, String r, long number, int page) throws Exception  {
        return issueApi.listPullFiles(o, r, number, page);
    }

    public JSONArray listPullCommits(String o, String r, long number) throws Exception  {
        return issueApi.listPullCommits(o, r, number);
    }

    public JSONArray listReviews(String o, String r, long number) throws Exception  {
        return issueApi.listReviews(o, r, number);
    }

    public JSONArray listPullComments(String o, String r, long number, int page) throws Exception  {
        return issueApi.listPullComments(o, r, number, page);
    }

    public JSONObject createReview(String o, String r, long number, String commitId, String event, String body,
                                   JSONArray comments) throws Exception  {
        return issueApi.createReview(o, r, number, commitId, event, body, comments);
    }

    public void submitReview(String o, String r, long number, long reviewId, String event, String body) throws Exception  {
        issueApi.submitReview(o, r, number, reviewId, event, body);
    }

    public void deleteReview(String o, String r, long number, long reviewId) throws Exception  {
        issueApi.deleteReview(o, r, number, reviewId);
    }

    public void createReview(String o, String r, long number, String event, String body) throws Exception  {
        issueApi.createReview(o, r, number, event, body);
    }

    public JSONObject createPull(String o, String r, String title, String body, String head, String base, boolean draft) throws Exception  {
        return issueApi.createPull(o, r, title, body, head, base, draft);
    }

    public JSONObject searchCode(String o, String r, String q, int page, int perPage) throws Exception  {
        return searchApi.searchCode(o, r, q, page, perPage);
    }

    public JSONObject searchRepos(String q, int page, int perPage) throws Exception  {
        return searchApi.searchRepos(q, page, perPage);
    }

    public JSONObject searchCodeQuery(String q, int page, int perPage) throws Exception  {
        return searchApi.searchCodeQuery(q, page, perPage);
    }

    public JSONObject searchIssues(String q, int page, int perPage) throws Exception  {
        return searchApi.searchIssues(q, page, perPage);
    }

    public JSONArray listNotifications(boolean all, int page, int perPage) throws Exception  {
        return notificationApi.listNotifications(all, page, perPage);
    }

    public void markNotificationRead(String id) throws Exception  {
        notificationApi.markNotificationRead(id);
    }

    public void markAllNotificationsRead() throws Exception  {
        notificationApi.markAllNotificationsRead();
    }

    public boolean vulnerabilityAlerts(String o, String r) throws Exception  {
        return securityApi.vulnerabilityAlerts(o, r);
    }

    public void setVulnerabilityAlerts(String o, String r, boolean on) throws Exception  {
        securityApi.setVulnerabilityAlerts(o, r, on);
    }

    public boolean automatedSecurityFixes(String o, String r) throws Exception  {
        return securityApi.automatedSecurityFixes(o, r);
    }

    public void setAutomatedSecurityFixes(String o, String r, boolean on) throws Exception  {
        securityApi.setAutomatedSecurityFixes(o, r, on);
    }

    public JSONObject getBranchProtection(String o, String r, String branch) throws Exception  {
        return securityApi.getBranchProtection(o, r, branch);
    }

    public void putBranchProtection(String o, String r, String branch, JSONObject body) throws Exception  {
        securityApi.putBranchProtection(o, r, branch, body);
    }

    public void deleteBranchProtection(String o, String r, String branch) throws Exception  {
        securityApi.deleteBranchProtection(o, r, branch);
    }

    public JSONArray collaborators(String o, String r) throws Exception  {
        return collaboratorApi.collaborators(o, r);
    }

    public JSONArray invitations(String o, String r) throws Exception  {
        return collaboratorApi.invitations(o, r);
    }

    public void addCollaborator(String o, String r, String user, String permission) throws Exception  {
        collaboratorApi.addCollaborator(o, r, user, permission);
    }

    public void removeCollaborator(String o, String r, String user) throws Exception  {
        collaboratorApi.removeCollaborator(o, r, user);
    }

    public void deleteInvitation(String o, String r, long id) throws Exception  {
        collaboratorApi.deleteInvitation(o, r, id);
    }

    public TextResult readTail(String path, String accept, int maxBytes) throws Exception  {
        return downloadApi.readTail(path, accept, maxBytes);
    }

    public Piece readFrom(String path, String accept, long from) throws Exception  {
        return downloadApi.readFrom(path, accept, from);
    }
}

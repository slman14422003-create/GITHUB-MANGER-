package com.ghmanager.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs {@link SemoAi}'s checks over every eligible file of one branch, commits whatever it could
 * safely fix in a single commit, and reports what happened. Call only from a background thread
 * (it makes network calls through {@link GitHubApi} to read and, if needed, write files) — it does
 * not touch the UI itself, that is the caller's job via the progress/done callbacks.
 */
final class SemoAiRunner {
    private SemoAiRunner() {
    }

    /** A cap so one run never reads an unreasonable number of files on a phone connection. */
    private static final int MAX_FILES = 400;

    static final class Summary {
        int scanned;
        int fixed;
        final List<String> fixedPaths = new ArrayList<>();
        final List<String> unfixedIssues = new ArrayList<>();   // "path: issue"
        String error;   // set only when the scan itself could not run at all
    }

    interface Progress {
        void onStep(int cur, int total, String path);
    }

    static Summary run(GitHubApi api, String owner, String repo, String branch, Progress progress) {
        Summary sum = new Summary();
        List<GitHubApi.BlobInfo> blobs;
        try {
            blobs = api.listAllBlobs(owner, repo, branch);
        } catch (Exception e) {
            sum.error = e.getMessage() == null ? e.toString() : e.getMessage();
            return sum;
        }

        List<GitHubApi.BlobInfo> targets = new ArrayList<>();
        for (GitHubApi.BlobInfo b : blobs) {
            if (SemoAi.eligible(b.path, b.size)) targets.add(b);
            if (targets.size() >= MAX_FILES) break;
        }

        List<GitHubApi.TreeEntry> toCommit = new ArrayList<>();
        int total = targets.size();
        for (int i = 0; i < total; i++) {
            GitHubApi.BlobInfo b = targets.get(i);
            if (progress != null) progress.onStep(i, total, b.path);
            byte[] raw;
            try {
                raw = api.getFileBytes(owner, repo, b.path, branch, 300 * 1024);
            } catch (Exception ignored) {
                continue;   // one unreadable file never stops the whole scan
            }
            if (raw == null) continue;
            String text;
            try {
                text = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                continue;
            }
            sum.scanned++;
            SemoAi.Result r = SemoAi.check(b.path, text);
            for (String issue : r.issues) sum.unfixedIssues.add(b.path + ": " + issue);
            if (r.changed) {
                try {
                    String sha = api.createBlob(owner, repo, r.fixed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    toCommit.add(new GitHubApi.TreeEntry(b.path, sha));
                    sum.fixed++;
                    sum.fixedPaths.add(b.path);
                } catch (Exception ignored) {
                    // could not upload the fix for this one file — leave it as-is, keep going
                }
            }
        }

        if (!toCommit.isEmpty()) {
            try {
                api.commitEntries(owner, repo, branch, toCommit,
                        "SEMO AI: إصلاحات تلقائية بسيطة قبل تشغيل الـ Action");
            } catch (Exception e) {
                // the fixes could not be saved — report as if nothing was fixed, the action still runs
                sum.error = e.getMessage() == null ? e.toString() : e.getMessage();
                sum.fixed = 0;
                sum.fixedPaths.clear();
            }
        }
        return sum;
    }
}

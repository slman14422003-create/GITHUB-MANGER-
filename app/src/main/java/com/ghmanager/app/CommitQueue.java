package com.ghmanager.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class CommitQueue {
    private CommitQueue() {
    }

    public static final String PENDING = "pending";
    public static final String CONFLICT = "conflict";
    public static final String FAILED = "failed";

    private static final String CHANNEL = "commit_queue";
    private static final int NOTE = 7422;
    private static final Object LOCK = new Object();

    public static final class Item {
        public String id = "";
        public String acc = "";
        public String owner = "";
        public String repo = "";
        public String branch = "";
        public String path = "";
        public String message = "";
        public String baseSha;
        public String content = "";
        public String state = PENDING;
        public String error = "";
        public long at;

        public String full() {
            return owner + "/" + repo + "@" + branch + ":" + path;
        }
    }

    public static final class Outcome {
        public int applied;
        public int conflicts;
        public int failed;
        public int retry;
        public boolean unauthorized;
    }

    static String keyOf(Item it) {
        return GitSha.sha1(it.acc + "\n" + it.owner + "\n" + it.repo + "\n" + it.branch + "\n" + it.path);
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "commitq");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static File fileOf(Context c, String id) {
        return new File(dir(c), id + ".json");
    }

    private static void write(Context c, Item it) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", it.id);
        o.put("acc", it.acc);
        o.put("owner", it.owner);
        o.put("repo", it.repo);
        o.put("branch", it.branch);
        o.put("path", it.path);
        o.put("message", it.message);
        o.put("baseSha", it.baseSha == null ? JSONObject.NULL : it.baseSha);
        o.put("content", it.content);
        o.put("state", it.state);
        o.put("error", it.error);
        o.put("at", it.at);
        Drafts.writeAtomic(fileOf(c, it.id), o.toString());
    }

    private static Item read(File f) {
        try {
            JSONObject o = new JSONObject(Drafts.readAll(f));
            Item it = new Item();
            it.id = o.optString("id");
            it.acc = o.optString("acc");
            it.owner = o.optString("owner");
            it.repo = o.optString("repo");
            it.branch = o.optString("branch");
            it.path = o.optString("path");
            it.message = o.optString("message");
            it.baseSha = o.isNull("baseSha") ? null : o.optString("baseSha");
            it.content = o.optString("content");
            it.state = o.optString("state", PENDING);
            it.error = o.optString("error");
            it.at = o.optLong("at");
            return it.id.isEmpty() ? null : it;
        } catch (Exception e) {
            f.delete();
            return null;
        }
    }

    public static Item add(Context c, String owner, String repo, String branch, String path, String message,
                           String baseSha, String content, String state) {
        synchronized (LOCK) {
            Item it = new Item();
            it.acc = Accounts.activeId(c);
            it.owner = owner;
            it.repo = repo;
            it.branch = branch;
            it.path = path;
            it.message = message;
            it.baseSha = baseSha;
            it.content = content;
            it.state = state;
            it.at = System.currentTimeMillis();
            it.id = keyOf(it);
            try {
                write(c, it);
            } catch (Exception e) {
                return null;
            }
            return it;
        }
    }

    public static List<Item> list(Context c) {
        synchronized (LOCK) {
            List<Item> out = new ArrayList<>();
            File[] fs = dir(c).listFiles();
            if (fs == null) return out;
            String acc = Accounts.activeId(c);
            for (File f : fs) {
                if (!f.getName().endsWith(".json")) continue;
                Item it = read(f);
                if (it != null && it.acc.equals(acc)) out.add(it);
            }
            Collections.sort(out, new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    return Long.compare(a.at, b.at);
                }
            });
            return out;
        }
    }

    public static Item get(Context c, String id) {
        synchronized (LOCK) {
            File f = fileOf(c, id);
            return f.isFile() ? read(f) : null;
        }
    }

    public static void setState(Context c, String id, String state, String error) {
        synchronized (LOCK) {
            Item it = get(c, id);
            if (it == null) return;
            it.state = state;
            it.error = error == null ? "" : error;
            try {
                write(c, it);
            } catch (Exception ignored) {
            }
        }
    }

    public static void remove(Context c, String id) {
        synchronized (LOCK) {
            fileOf(c, id).delete();
        }
    }

    public static int pendingCount(Context c) {
        int n = 0;
        for (Item it : list(c)) if (PENDING.equals(it.state)) n++;
        return n;
    }

    public static int count(Context c) {
        return list(c).size();
    }

    public static void supersede(Context c, String owner, String repo, String branch, String path) {
        synchronized (LOCK) {
            Item probe = new Item();
            probe.acc = Accounts.activeId(c);
            probe.owner = owner;
            probe.repo = repo;
            probe.branch = branch;
            probe.path = path;
            Item old = get(c, keyOf(probe));
            if (old != null && PENDING.equals(old.state)) remove(c, old.id);
        }
    }

    public static void clearAll(Context c) {
        synchronized (LOCK) {
            File[] fs = dir(c).listFiles();
            if (fs == null) return;
            for (File f : fs) f.delete();
        }
    }

    public static Outcome run(Context c) {
        Outcome out = new Outcome();
        if (Accounts.active(c) == null) return out;
        GitHubApi api = new GitHubApi(c);
        for (Item it : list(c)) {
            if (!PENDING.equals(it.state)) continue;
            if (Thread.currentThread().isInterrupted()) {
                out.retry++;
                continue;
            }
            try {
                String r = apply(c, api, it);
                if ("applied".equals(r)) {
                    out.applied++;
                    remove(c, it.id);
                } else {
                    out.conflicts++;
                    setState(c, it.id, CONFLICT, "");
                }
            } catch (GitHubApi.ApiException e) {
                if (e.code == 401) {
                    out.unauthorized = true;
                    out.retry++;
                } else if (e.code == 429 || e.code >= 500 || e.waitMs > 0) {
                    out.retry++;
                } else {
                    out.failed++;
                    setState(c, it.id, FAILED, e.getMessage() == null ? String.valueOf(e.code) : e.getMessage());
                }
            } catch (Exception e) {
                out.retry++;
            }
        }
        notifyDone(c, out);
        return out;
    }

    public static String apply(Context c, GitHubApi api, Item it) throws Exception {
        String remoteSha = remoteSha(api, it.owner, it.repo, it.path, it.branch);
        byte[] data = it.content.getBytes(StandardCharsets.UTF_8);
        if (remoteSha != null && remoteSha.equals(GitSha.blob(data))) return "applied";
        if (!same(remoteSha, it.baseSha)) return "conflict";
        try {
            api.putFileContent(it.owner, it.repo, it.path, data, it.message, it.branch, it.baseSha);
        } catch (GitHubApi.ApiException e) {
            if (e.code == 409) return "conflict";
            throw e;
        }
        RepoTree.invalidate(c, it.owner, it.repo, it.branch);
        FileDates.invalidate();
        return "applied";
    }

    static boolean same(String a, String b) {
        if (a == null || a.isEmpty()) return b == null || b.isEmpty();
        return a.equals(b);
    }

    public static String remoteSha(GitHubApi api, String owner, String repo, String path, String branch)
            throws Exception {
        try {
            JSONObject m = api.getContent(owner, repo, path, branch);
            String s = m.optString("sha");
            return s.isEmpty() ? null : s;
        } catch (GitHubApi.ApiException e) {
            if (e.code == 404) return null;
            throw e;
        }
    }

    private static void notifyDone(Context c, Outcome o) {
        if (o.applied + o.conflicts + o.failed == 0) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || !nm.areNotificationsEnabled()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.cq_channel),
                        NotificationManager.IMPORTANCE_DEFAULT));
            }
            String text = c.getString(R.string.cq_note_text, o.applied, o.conflicts, o.failed);
            Intent open = new Intent(c, QueueActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            nm.notify(NOTE, new NotificationCompat.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_upload)
                    .setContentTitle(c.getString(R.string.cq_title))
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, NOTE, open,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                    .build());
        } catch (Exception ignored) {
        }
    }
}

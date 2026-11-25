package com.ghmanager.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Drafts {
    private Drafts() {
    }

    public static final class Draft {
        public String acc = "";
        public String owner = "";
        public String repo = "";
        public String branch = "";
        public String path = "";
        public String text = "";
        public String baseSha;
        public String baseText = "";
        public boolean auto;
        public long at;
    }

    static String key(String acc, String owner, String repo, String branch, String path) {
        return GitSha.sha1(acc + "\n" + owner + "\n" + repo + "\n" + branch + "\n" + path);
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "drafts");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static File file(Context c, String acc, String owner, String repo, String branch, String path) {
        return new File(dir(c), key(acc, owner, repo, branch, path) + ".json");
    }

    static void writeAtomic(File f, String s) throws Exception {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        FileOutputStream os = new FileOutputStream(tmp);
        try {
            os.write(s.getBytes(StandardCharsets.UTF_8));
            os.getFD().sync();
        } finally {
            os.close();
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new java.io.IOException("rename");
        }
    }

    static String readAll(File f) throws Exception {
        java.io.FileInputStream is = new java.io.FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            is.close();
        }
    }

    public static synchronized void save(Context c, Draft d) {
        try {
            JSONObject o = new JSONObject();
            o.put("acc", d.acc);
            o.put("owner", d.owner);
            o.put("repo", d.repo);
            o.put("branch", d.branch);
            o.put("path", d.path);
            o.put("text", d.text);
            o.put("baseSha", d.baseSha == null ? JSONObject.NULL : d.baseSha);
            o.put("baseText", d.baseText);
            o.put("auto", d.auto);
            o.put("at", d.at == 0 ? System.currentTimeMillis() : d.at);
            writeAtomic(file(c, d.acc, d.owner, d.repo, d.branch, d.path), o.toString());
        } catch (Exception ignored) {
        }
    }

    public static synchronized Draft load(Context c, String owner, String repo, String branch, String path) {
        File f = file(c, Accounts.activeId(c), owner, repo, branch, path);
        if (!f.isFile()) return null;
        return parse(f);
    }

    private static Draft parse(File f) {
        try {
            JSONObject o = new JSONObject(readAll(f));
            Draft d = new Draft();
            d.acc = o.optString("acc");
            d.owner = o.optString("owner");
            d.repo = o.optString("repo");
            d.branch = o.optString("branch");
            d.path = o.optString("path");
            d.text = o.optString("text");
            d.baseSha = o.isNull("baseSha") ? null : o.optString("baseSha");
            d.baseText = o.optString("baseText");
            d.auto = o.optBoolean("auto");
            d.at = o.optLong("at");
            return d;
        } catch (Exception e) {
            f.delete();
            return null;
        }
    }

    public static synchronized void delete(Context c, String owner, String repo, String branch, String path) {
        file(c, Accounts.activeId(c), owner, repo, branch, path).delete();
    }

    public static synchronized List<Draft> all(Context c) {
        List<Draft> out = new ArrayList<>();
        File[] fs = dir(c).listFiles();
        if (fs == null) return out;
        String acc = Accounts.activeId(c);
        for (File f : fs) {
            if (!f.getName().endsWith(".json")) continue;
            Draft d = parse(f);
            if (d != null && d.acc.equals(acc)) out.add(d);
        }
        return out;
    }

    public static synchronized void clearAll(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs == null) return;
        for (File f : fs) f.delete();
    }
}

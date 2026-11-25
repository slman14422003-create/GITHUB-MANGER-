package com.ghmanager.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AuditLog {
    private AuditLog() {
    }

    static final class Entry {
        long time;
        String account = "";
        String kind = "";
        String category = "";
        String method = "";
        String repo = "";
        String target = "";
        String outcome = "";
        int code;
    }

    private static final String FILE = "audit.jsonl";
    private static final int KEEP = 500;
    private static final int TRIM_AT = 600;
    private static final int MAX_TARGET = 140;
    private static final Object LOCK = new Object();
    private static int lines = -1;

    static String repoOf(String path) {
        if (path == null) return "";
        String[] p = clean(path).split("/");
        if (p.length >= 4 && p[1].equals("repos")) return p[2] + "/" + p[3];
        return "";
    }

    static String targetOf(String path) {
        if (path == null) return "";
        String c = clean(path);
        String[] p = c.split("/");
        String out = c;
        if (p.length >= 4 && p[1].equals("repos")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 4; i < p.length; i++) sb.append('/').append(p[i]);
            out = sb.toString();
        }
        out = Redact.text(out);
        if (out.length() > MAX_TARGET) out = out.substring(0, MAX_TARGET);
        return out;
    }

    private static String clean(String path) {
        String c = path;
        int q = c.indexOf('?');
        if (q >= 0) c = c.substring(0, q);
        int h = c.indexOf('#');
        if (h >= 0) c = c.substring(0, h);
        return c;
    }

    static void api(Context c, String account, String category, String method, String path, String outcome, int code) {
        Entry e = new Entry();
        e.time = System.currentTimeMillis();
        e.account = account == null ? "" : account;
        e.kind = "api";
        e.category = category == null ? "" : category;
        e.method = method == null ? "" : method;
        e.repo = repoOf(path);
        e.target = targetOf(path);
        e.outcome = outcome;
        e.code = code;
        append(c, e);
    }

    static void event(Context c, String name) {
        Entry e = new Entry();
        e.time = System.currentTimeMillis();
        Accounts.Acc a = c == null ? null : Accounts.active(c);
        e.account = a == null ? "" : a.id;
        e.kind = "event";
        e.category = name;
        e.outcome = name.endsWith("failed") ? "denied" : "ok";
        append(c, e);
    }

    private static String encode(Entry e) throws Exception {
        JSONObject o = new JSONObject();
        o.put("t", e.time);
        o.put("a", e.account);
        o.put("k", e.kind);
        o.put("c", e.category);
        o.put("m", e.method);
        o.put("r", e.repo);
        o.put("p", e.target);
        o.put("o", e.outcome);
        o.put("h", e.code);
        return o.toString();
    }

    private static Entry decode(String line) {
        try {
            JSONObject o = new JSONObject(line);
            Entry e = new Entry();
            e.time = o.optLong("t");
            e.account = o.optString("a");
            e.kind = o.optString("k");
            e.category = o.optString("c");
            e.method = o.optString("m");
            e.repo = o.optString("r");
            e.target = o.optString("p");
            e.outcome = o.optString("o");
            e.code = o.optInt("h");
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), FILE);
    }

    private static void append(Context c, Entry e) {
        if (c == null) return;
        try {
            String line = encode(e) + "\n";
            synchronized (LOCK) {
                File f = file(c);
                try (FileOutputStream os = new FileOutputStream(f, true)) {
                    os.write(line.getBytes(StandardCharsets.UTF_8));
                }
                if (lines < 0) lines = readLines(c).size();
                else lines++;
                if (lines > TRIM_AT) trim(c);
            }
        } catch (Exception ignored) {
        }
    }

    private static List<String> readLines(Context c) {
        List<String> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String s;
            while ((s = r.readLine()) != null) if (!s.isEmpty()) out.add(s);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void trim(Context c) throws Exception {
        List<String> all = readLines(c);
        int from = Math.max(0, all.size() - KEEP);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < all.size(); i++) sb.append(all.get(i)).append('\n');
        try (FileOutputStream os = new FileOutputStream(file(c), false)) {
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        lines = all.size() - from;
    }

    static List<Entry> read(Context c) {
        List<Entry> out = new ArrayList<>();
        synchronized (LOCK) {
            for (String s : readLines(c)) {
                Entry e = decode(s);
                if (e != null) out.add(e);
            }
        }
        Collections.reverse(out);
        return out;
    }

    static void clear(Context c) {
        synchronized (LOCK) {
            File f = file(c);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            lines = 0;
        }
    }
}

package com.ghmanager.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FmIndex {
    private FmIndex() {
    }

    private static final int MAX_ENTRIES = 600000;

    public interface Progress {
        void count(int n);
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "fm_index.txt");
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("fm", Context.MODE_PRIVATE);
    }

    public static String root(Context c) {
        return sp(c).getString("idx_root", "");
    }

    public static long time(Context c) {
        return sp(c).getLong("idx_time", 0);
    }

    public static int count(Context c) {
        return sp(c).getInt("idx_count", 0);
    }

    public static boolean covers(Context c, File dir) {
        String r = root(c);
        if (r.isEmpty() || !file(c).isFile()) return false;
        String d = dir.getAbsolutePath();
        return d.equals(r) || d.startsWith(r.endsWith("/") ? r : r + "/");
    }

    public static BufferedReader open(Context c) throws IOException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(file(c)), StandardCharsets.UTF_8), 65536);
    }

    private static boolean isLink(File f) {
        try {
            return !f.getCanonicalPath().equals(f.getAbsolutePath());
        } catch (IOException e) {
            return true;
        }
    }

    public static int build(Context c, File root, AtomicBoolean stop, Progress cb) throws IOException {
        File tmp = new File(c.getFilesDir(), "fm_index.tmp");
        int n = 0;
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp),
                StandardCharsets.UTF_8), 65536)) {
            ArrayDeque<File> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty() && n < MAX_ENTRIES) {
                if (stop != null && stop.get()) throw new IOException("cancelled");
                File d = stack.pop();
                File[] arr = d.listFiles();
                if (arr == null) continue;
                for (File f : arr) {
                    String p = f.getAbsolutePath();
                    if (p.indexOf('\n') >= 0) continue;
                    w.write(p);
                    w.write('\n');
                    n++;
                    if (n % 2000 == 0 && cb != null) cb.count(n);
                    if (f.isDirectory() && !isLink(f)) stack.push(f);
                }
            }
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        File out = file(c);
        if (out.exists() && !out.delete()) throw new IOException(out.getName());
        if (!tmp.renameTo(out)) throw new IOException(out.getName());
        sp(c).edit().putString("idx_root", root.getAbsolutePath()).putLong("idx_time", System.currentTimeMillis())
                .putInt("idx_count", n).apply();
        return n;
    }

    public static void clear(Context c) {
        file(c).delete();
        sp(c).edit().remove("idx_root").remove("idx_time").remove("idx_count").apply();
    }
}

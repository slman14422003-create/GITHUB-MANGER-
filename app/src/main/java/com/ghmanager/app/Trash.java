package com.ghmanager.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class Trash {
    private Trash() {
    }

    public static final long KEEP_MS = 30L * 24 * 3600 * 1000;

    public interface Tick {
        void bytes(long n, String name);
    }

    public static final class Item {
        public final String id;
        public final String name;
        public final String origin;
        public final long deletedAt;
        public final long size;
        public final boolean dir;
        public final File stored;

        Item(String id, String name, String origin, long deletedAt, long size, boolean dir, File stored) {
            this.id = id;
            this.name = name;
            this.origin = origin;
            this.deletedAt = deletedAt;
            this.size = size;
            this.dir = dir;
            this.stored = stored;
        }

        public int daysLeft() {
            long left = deletedAt + KEEP_MS - System.currentTimeMillis();
            return (int) Math.max(0, (left + 86399999L) / 86400000L);
        }
    }

    public static File dir(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File d = new File(base, ".trash");
        if (!d.exists())
            d.mkdirs();
        return d;
    }

    private static File data(Context c, String id) {
        return new File(dir(c), id + ".d");
    }

    private static File meta(Context c, String id) {
        return new File(dir(c), id + ".json");
    }

    public static long sizeOf(File f) {
        if (f.isDirectory()) {
            long t = 0;
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) t += sizeOf(k);
            return t;
        }
        return f.length();
    }

    private static void copyTree(File src, File dst, Tick t, java.util.concurrent.atomic.AtomicBoolean stop)
            throws IOException {
        if (stop != null && stop.get()) throw new IOException("cancelled");
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new IOException(dst.getName());
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dst, k.getName()), t, stop);
        } else {
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (stop != null && stop.get()) throw new IOException("cancelled");
                    out.write(buf, 0, n);
                    if (t != null) t.bytes(n, src.getName());
                }
            }
            dst.setLastModified(src.lastModified());
        }
    }

    public static void deleteTree(File f) {
        if (f.isDirectory()) {
            try {
                if (!f.getCanonicalPath().equals(f.getAbsolutePath())) {
                    f.delete();
                    return;
                }
            } catch (IOException e) {
                f.delete();
                return;
            }
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        f.delete();
    }

    public static String put(Context c, File f, Tick t, java.util.concurrent.atomic.AtomicBoolean stop)
            throws IOException {
        String id = UUID.randomUUID().toString().substring(0, 12);
        File to = data(c, id);
        long size = sizeOf(f);
        boolean dir = f.isDirectory();
        if (!f.renameTo(to)) {
            try {
                copyTree(f, to, t, stop);
            } catch (IOException e) {
                deleteTree(to);
                throw e;
            }
            deleteTree(f);
            if (f.exists()) {
                deleteTree(to);
                throw new IOException(f.getName());
            }
        } else if (t != null) {
            t.bytes(size, f.getName());
        }
        try (OutputStream o = new FileOutputStream(meta(c, id))) {
            JSONObject j = new JSONObject();
            j.put("name", f.getName());
            j.put("origin", f.getAbsolutePath());
            j.put("at", System.currentTimeMillis());
            j.put("size", size);
            j.put("dir", dir);
            o.write(j.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException(String.valueOf(e.getMessage()));
        }
        return id;
    }

    public static List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        for (File m : files) {
            String n = m.getName();
            if (!n.endsWith(".json")) continue;
            String id = n.substring(0, n.length() - 5);
            File d = data(c, id);
            if (!d.exists()) {
                m.delete();
                continue;
            }
            try (InputStream in = new FileInputStream(m)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int r;
                while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
                JSONObject j = new JSONObject(new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                out.add(new Item(id, j.optString("name", id), j.optString("origin", ""), j.optLong("at", 0),
                        j.optLong("size", 0), j.optBoolean("dir", d.isDirectory()), d));
            } catch (Exception ignored) {
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.deletedAt, a.deletedAt));
        return out;
    }

    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name;
        String ext = "";
        int i = name.lastIndexOf('.');
        if (i > 0) {
            base = name.substring(0, i);
            ext = name.substring(i);
        }
        for (int n = 1; ; n++) {
            File c = new File(dir, base + " (" + n + ")" + ext);
            if (!c.exists()) return c;
        }
    }

    public static File restore(Context c, Item it, Tick t, java.util.concurrent.atomic.AtomicBoolean stop)
            throws IOException {
        File origin = new File(it.origin);
        File parent = origin.getParentFile();
        if (parent == null) throw new IOException(it.origin);
        if (!parent.exists() && !parent.mkdirs()) throw new IOException(parent.getAbsolutePath());
        File to = unique(parent, origin.getName());
        if (!it.stored.renameTo(to)) {
            try {
                copyTree(it.stored, to, t, stop);
            } catch (IOException e) {
                deleteTree(to);
                throw e;
            }
            deleteTree(it.stored);
        }
        meta(c, it.id).delete();
        return to;
    }

    public static void remove(Context c, Item it) {
        deleteTree(it.stored);
        meta(c, it.id).delete();
    }

    public static Item find(Context c, String id) {
        for (Item i : list(c)) if (i.id.equals(id)) return i;
        return null;
    }

    public static int purgeOld(Context c) {
        int n = 0;
        long limit = System.currentTimeMillis() - KEEP_MS;
        for (Item i : list(c)) {
            if (i.deletedAt > 0 && i.deletedAt < limit) {
                remove(c, i);
                n++;
            }
        }
        return n;
    }

    public static int empty(Context c) {
        int n = 0;
        for (Item i : list(c)) {
            remove(c, i);
            n++;
        }
        return n;
    }
}

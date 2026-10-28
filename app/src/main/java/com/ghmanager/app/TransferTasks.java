package com.ghmanager.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.nio.channels.Channels;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** The actual work of the background transfers (see {@link Transfers}). Nothing here touches an Activity. */
public final class TransferTasks {
    private TransferTasks() {
    }

    private static final int MAX_FILE_BYTES = 25 * 1024 * 1024;
    private static final int PARALLEL = 4;
    private static final int BATCH_FILES = 300;
    private static final long BATCH_BYTES = 100L * 1024 * 1024;
    private static final int GQL_FILES = 40;
    private static final long GQL_BYTES = 6L * 1024 * 1024;
    private static final long GQL_FILE_MAX = 2L * 1024 * 1024;
    private static final int RETRIES = 5;
    private static final long BIG = 1024 * 1024;

    // ------------------------------------------------------------------ where downloads go

    /** Download/GitHubManager - the one place every download of this app ends up. */
    public static File rootDir() {
        return new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "GitHubManager");
    }

    /** Download/GitHubManager/<repo> (or the root when no repository is known). */
    public static File saveDir(String repo) {
        File root = rootDir();
        if (repo == null || repo.isEmpty()) return root;
        return new File(root, repo.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_"));
    }

    // ------------------------------------------------------------------ download

    /**
     * Reserves the whole file on disk BEFORE the first byte arrives: the storage is checked up front
     * (a clear "not enough space" instead of a failure at 90%), and writing into an already sized
     * file is faster because the file system does not have to grow it chunk by chunk.
     */
    private static void reserve(Transfers.Job job, File dest, RandomAccessFile raf, long size) throws IOException {
        if (size <= 0) return;
        job.progress(job.app.getString(R.string.tr_reserving, Fmt.size(size)), 0, 0);
        File dir = dest.getParentFile();
        if (dir != null) {
            long free = dir.getUsableSpace();
            if (free > 0 && free < size + 8L * 1024 * 1024) {
                throw new IOException(job.app.getString(R.string.tr_no_space, Fmt.size(size)));
            }
        }
        try {
            android.system.Os.posix_fallocate(raf.getFD(), 0, size);
        } catch (Exception unsupported) {
            // some file systems cannot pre-allocate: a sized (sparse) file is still reserved
            raf.setLength(size);
        }
        job.check();
        job.progressBytes(dest.getName(), 0, size);
    }

    private static Transfers.Task downloadExtract(final Transfers.Src src, final File dest, final boolean extractApk) {
        return job -> {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists())
                parent.mkdirs();
            job.progress(job.app.getString(R.string.tr_connecting), 0, 0);
            boolean ok = false;
            try {
                HttpURLConnection c = src.open();
                try {
                    long total = c.getContentLengthLong();
                    InputStream in = c.getInputStream();
                    RandomAccessFile raf;
                    try {
                        raf = new RandomAccessFile(dest, "rw");
                    } catch (IOException openFail) {
                        in.close();
                        throw new IOException(job.app.getString(R.string.save_failed, dest.getParent()), openFail);
                    }
                    try {
                        OutputStream out = Channels.newOutputStream(raf.getChannel());
                        GitHubApi.Progress prog = (done, tot) -> {
                            job.progressBytes(dest.getName(), done, tot);
                            job.check();
                        };
                        long written = 0;
                        if (extractApk) {
                            ZipInputStream zin = new ZipInputStream(in);
                            ZipEntry e;
                            boolean found = false;
                            while ((e = zin.getNextEntry()) != null) {
                                if (!e.isDirectory() && e.getName().toLowerCase(Locale.US).endsWith(".apk")) {
                                    reserve(job, dest, raf, e.getSize());
                                    written = GitHubApi.copy(zin, out, e.getSize(), prog);
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) throw new IOException(job.app.getString(R.string.no_apk_in_artifact));
                        } else {
                            reserve(job, dest, raf, total);
                            written = GitHubApi.copy(in, out, total, prog);
                        }
                        // drop whatever is left of the reserved size (compressed or unknown-length sources)
                        raf.setLength(written);
                        ok = true;
                    } finally {
                        try {
                            raf.close();
                        } catch (Exception ignored) {
                        }
                        try {
                            in.close();
                        } catch (Exception ignored) {
                        }
                    }
                } finally {
                    c.disconnect();
                }
            } finally {
                // never leave a half-written or empty file behind
                if (!ok)
                    dest.delete();
            }
            job.message = job.app.getString(R.string.tr_saved_to, dest.getAbsolutePath());
            if (extractApk) {
                job.openIntent = installIntent(job.app, dest);
                job.message = job.message + "\n" + job.app.getString(R.string.tr_tap_install);
            } else {
                job.openIntent = new Intent(job.app, FileManagerActivity.class)
                        .putExtra("path", dest.getParent());
            }
        };
    }

    private static final class NoSpace extends IOException {
        NoSpace(String m) {
            super(m);
        }
    }

    private static final class Restart extends IOException {
        Restart() {
            super("restart");
        }
    }

    private static long[] contentRange(String h) {
        if (h == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)").matcher(h.trim());
        if (!m.find()) return null;
        try {
            long total = "*".equals(m.group(3)) ? -1 : Long.parseLong(m.group(3));
            return new long[]{Long.parseLong(m.group(1)), total};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long readInfo(File info) {
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(info))) {
            String l = br.readLine();
            return l == null ? -1 : Long.parseLong(l.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static void writeInfo(File info, long total) {
        try (FileOutputStream o = new FileOutputStream(info)) {
            o.write(String.valueOf(total).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static void moveInto(File from, File to) throws IOException {
        if (to.exists() && !to.delete()) throw new IOException(to.getName());
        if (from.renameTo(to)) return;
        try (InputStream in = new java.io.FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        from.delete();
    }

    public static Transfers.Task download(final Transfers.Src src, final File dest, final boolean extractApk) {
        if (extractApk) return downloadExtract(src, dest, true);
        return job -> {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists())
                parent.mkdirs();
            final File part = new File(dest.getPath() + ".part");
            final File info = new File(dest.getPath() + ".part.info");
            boolean finished = false;
            int attempt = 0;
            boolean noResume = false;
            try {
                for (; ; ) {
                    job.check();
                    long have = part.isFile() ? part.length() : 0;
                    if (have > 0 && !info.isFile()) {
                        part.delete();
                        have = 0;
                    }
                    job.progress(job.app.getString(R.string.tr_connecting), 0, 0);
                    try {
                        HttpURLConnection c;
                        try {
                            GitHubApi.rangeFrom(have);
                            try {
                                c = src.open();
                            } finally {
                                GitHubApi.rangeFrom(0);
                            }
                        } catch (GitHubApi.ApiException e) {
                            if (e.code == 416 && have > 0) {
                                part.delete();
                                info.delete();
                                continue;
                            }
                            throw e;
                        }
                        try {
                            int code = c.getResponseCode();
                            long start;
                            long total;
                            if (have > 0 && code == 206) {
                                long[] cr = contentRange(c.getHeaderField("Content-Range"));
                                long saved = readInfo(info);
                                if (cr == null || cr[0] != have || (cr[1] > 0 && saved > 0 && cr[1] != saved)) {
                                    part.delete();
                                    info.delete();
                                    throw new Restart();
                                }
                                start = have;
                                total = cr[1];
                            } else {
                                if (have > 0) {
                                    noResume = true;
                                    job.progress(job.app.getString(R.string.tr_no_resume), 0, 0);
                                }
                                start = 0;
                                total = c.getContentLengthLong();
                                writeInfo(info, total);
                            }
                            long need = total > 0 ? total - start : 0;
                            if (need > 0 && parent != null) {
                                long free = parent.getUsableSpace();
                                if (free > 0 && free < need + 8L * 1024 * 1024) {
                                    throw new NoSpace(job.app.getString(R.string.tr_no_space, Fmt.size(need)));
                                }
                            }
                            final long base = start;
                            final long tot = total;
                            try (InputStream in = c.getInputStream();
                                 RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
                                raf.setLength(start);
                                raf.seek(start);
                                OutputStream out = Channels.newOutputStream(raf.getChannel());
                                long written = GitHubApi.copy(in, out, tot, (d, t) -> {
                                    job.progressBytes(dest.getName(), base + d, tot > 0 ? tot : 0);
                                    job.check();
                                });
                                if (tot > 0 && start + written != tot) throw new IOException("Incomplete download");
                            }
                        } finally {
                            c.disconnect();
                        }
                        moveInto(part, dest);
                        info.delete();
                        finished = true;
                        break;
                    } catch (Restart r) {
                        continue;
                    } catch (Transfers.Cancelled | NoSpace e) {
                        throw e;
                    } catch (GitHubApi.ApiException e) {
                        boolean transientErr = e.code >= 500 || e.code == 429;
                        if (!transientErr || ++attempt > RETRIES) throw e;
                        job.progress(job.app.getString(noResume ? R.string.tr_retry_start : R.string.tr_retry_resume,
                                attempt, RETRIES), 0, 0);
                        Thread.sleep(Math.min(20000L, 1500L << attempt));
                    } catch (IOException e) {
                        if (++attempt > (noResume ? 2 : RETRIES)) throw e;
                        job.progress(job.app.getString(noResume ? R.string.tr_retry_start : R.string.tr_retry_resume,
                                attempt, noResume ? 2 : RETRIES), 0, 0);
                        Thread.sleep(Math.min(20000L, 1500L << attempt));
                    }
                }
            } catch (Transfers.Cancelled c) {
                part.delete();
                info.delete();
                throw c;
            } finally {
                if (!finished && dest.isFile() && dest.length() == 0)
                    dest.delete();
            }
            job.message = job.app.getString(R.string.tr_saved_to, dest.getAbsolutePath());
            job.openIntent = new Intent(job.app, FileManagerActivity.class).putExtra("path", dest.getParent());
        };
    }

    public static void cleanParts() {
        try {
            long limit = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
            cleanDir(rootDir(), 0, limit);
        } catch (Throwable ignored) {
        }
    }

    private static void cleanDir(File d, int depth, long limit) {
        File[] kids = d.listFiles();
        if (kids == null || depth > 3) return;
        for (File k : kids) {
            if (k.isDirectory()) {
                cleanDir(k, depth + 1, limit);
            } else {
                String n = k.getName();
                if ((n.endsWith(".part") || n.endsWith(".part.info")) && k.lastModified() < limit)
                    k.delete();
            }
        }
    }

    public static Transfers.Task downloadRepoPaths(final GitHubApi api, final String owner, final String repo,
                                                   final String branch, final List<String> paths,
                                                   final File destDir) {
        return job -> {
            job.progress(job.app.getString(R.string.tr_connecting), 0, 0);
            List<GitHubApi.BlobInfo> all = api.listAllBlobs(owner, repo, branch);
            final List<GitHubApi.BlobInfo> pick = new ArrayList<>();
            final List<String> rels = new ArrayList<>();
            for (GitHubApi.BlobInfo b : all) {
                for (String p : paths) {
                    if (b.path.equals(p) || b.path.startsWith(p + "/")) {
                        int cut = p.lastIndexOf('/') + 1;
                        pick.add(b);
                        rels.add(b.path.substring(cut));
                        break;
                    }
                }
            }
            if (pick.isEmpty()) {
                job.message = job.app.getString(R.string.no_files);
                return;
            }
            long totalBytes = 0;
            for (GitHubApi.BlobInfo b : pick) totalBytes += b.size;
            if (!destDir.exists() && !destDir.mkdirs()) throw new IOException(destDir.getAbsolutePath());
            long free = destDir.getUsableSpace();
            if (free > 0 && free < totalBytes + 8L * 1024 * 1024) {
                throw new NoSpace(job.app.getString(R.string.tr_no_space, Fmt.size(totalBytes)));
            }
            String root = destDir.getCanonicalPath() + File.separator;
            long doneBytes = 0;
            int ok = 0;
            int skipped = 0;
            List<String> failed = new ArrayList<>();
            for (int i = 0; i < pick.size(); i++) {
                job.check();
                final GitHubApi.BlobInfo b = pick.get(i);
                File out = new File(destDir, rels.get(i));
                if (!out.getCanonicalPath().startsWith(root)) {
                    failed.add(b.path);
                    continue;
                }
                if (out.isFile() && !b.sha.isEmpty() && b.sha.equals(gitSha(out))) {
                    doneBytes += b.size;
                    skipped++;
                    job.progressBytes(b.path, doneBytes, totalBytes);
                    continue;
                }
                File dir = out.getParentFile();
                if (dir != null && !dir.exists())
                    dir.mkdirs();
                File tmp = new File(out.getPath() + ".part");
                final long before = doneBytes;
                final long all2 = totalBytes;
                boolean got = false;
                for (int attempt = 0; attempt < 3 && !got; attempt++) {
                    job.check();
                    try {
                        HttpURLConnection c = api.openDownload(api.contentRawPath(owner, repo, b.path, branch),
                                "application/vnd.github.raw");
                        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(tmp)) {
                            GitHubApi.copy(in, os, b.size, (d, t) -> {
                                job.progressBytes(b.path, before + d, all2);
                                job.check();
                            });
                        } finally {
                            c.disconnect();
                        }
                        moveInto(tmp, out);
                        got = true;
                    } catch (Transfers.Cancelled e) {
                        tmp.delete();
                        throw e;
                    } catch (GitHubApi.ApiException e) {
                        tmp.delete();
                        if (e.code < 500 && e.code != 429 && e.code != 403) break;
                        Thread.sleep(Math.max(1500L << attempt, Math.min(e.waitMs, 60000L)));
                    } catch (IOException e) {
                        tmp.delete();
                        Thread.sleep(1500L << attempt);
                    }
                }
                if (got) ok++;
                else failed.add(b.path);
                doneBytes += b.size;
                job.progressBytes(b.path, doneBytes, totalBytes);
            }
            if (!failed.isEmpty()) {
                StringBuilder sb = new StringBuilder(job.app.getString(R.string.tr_partial_failed, failed.size()));
                for (int i = 0; i < Math.min(5, failed.size()); i++) sb.append('\n').append(failed.get(i));
                throw new IOException(sb.toString());
            }
            job.message = job.app.getString(R.string.tr_saved_to, destDir.getAbsolutePath())
                    + "\n" + job.app.getString(R.string.tr_files_skipped_n, ok, skipped);
            job.openIntent = new Intent(job.app, FileManagerActivity.class).putExtra("path", destDir.getAbsolutePath());
        };
    }

    // ------------------------------------------------------------------ app update

    public static File updateApkFile(Context c) {
        return new File(new File(c.getCacheDir(), "apk"), "update.apk");
    }

    public static Transfers.Task updateDownload(final GitHubApi api, final String owner, final String repo,
                                                final Updater.Info in) {
        return job -> {
            File apk = updateApkFile(job.app);
            File dir = apk.getParentFile();
            if (dir != null && !dir.exists())
                dir.mkdirs();
            File[] old = dir == null ? null : dir.listFiles();
            if (old != null) for (File o : old)
                o.delete();
            job.progress(job.app.getString(R.string.tr_connecting), 0, 0);
            HttpURLConnection c = api.openDownload(api.assetPath(owner, repo, in.assetId), "application/octet-stream");
            try {
                long total = c.getContentLengthLong();
                if (total <= 0) total = in.assetSize;
                InputStream is = c.getInputStream();
                RandomAccessFile raf = new RandomAccessFile(apk, "rw");
                try {
                    reserve(job, apk, raf, total);
                    long written = GitHubApi.copy(is, Channels.newOutputStream(raf.getChannel()), total,
                            (done, tot) -> {
                                job.progressBytes(in.assetName, done, tot);
                                job.check();
                            });
                    raf.setLength(written);
                } catch (RuntimeException | IOException e) {
                    try {
                        raf.close();
                    } catch (Exception ignored) {
                    }
                    apk.delete();
                    throw e;
                } finally {
                    try {
                        raf.close();
                    } catch (Exception ignored) {
                    }
                    try {
                        is.close();
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                c.disconnect();
            }
            if (in.assetSize > 0 && apk.length() != in.assetSize) {
                apk.delete();
                throw new IOException(job.app.getString(R.string.upd_size_bad));
            }
            if (!in.sha256.isEmpty()) {
                job.progress(job.app.getString(R.string.upd_verifying), 0, 0);
                String got = sha256Hex(apk);
                if (!got.equalsIgnoreCase(in.sha256)) {
                    apk.delete();
                    throw new IOException(job.app.getString(R.string.upd_hash_bad));
                }
            }
            // the update must be signed by the same key as the installed app
            if (!Integrity.sameSigner(job.app, apk)) {
                apk.delete();
                throw new IOException(job.app.getString(R.string.upd_sig_bad));
            }
            job.openIntent = installIntent(job.app, apk);
            job.message = job.app.getString(R.string.tr_tap_install);
        };
    }

    private static String sha256Hex(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    /** Intent that opens the system installer for an APK (usable from a notification). */
    public static Intent installIntent(Context c, File apk) {
        Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".files", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    // ------------------------------------------------------------------ upload (batched commits, skips what is already there)

    public static Transfers.Task upload(final GitHubApi api, final String owner, final String repo,
                                        final String branch, final String base, final String message,
                                        final List<File> roots, final boolean includeRoot,
                                        final boolean skipJunk) {
        return job -> {
            final Context app = job.app;
            final ContentResolver cr = app.getContentResolver();
            job.progress(app.getString(R.string.tr_scanning), 0, 0);
            List<FileScanner.Item> all = new ArrayList<>();
            FileScanner.scanFiles(roots, includeRoot, skipJunk, all);
            if (all.isEmpty()) {
                job.message = app.getString(R.string.no_files);
                return;
            }
            job.check();

            boolean empty = false;
            try {
                api.getBranchSha(owner, repo, branch);
            } catch (GitHubApi.ApiException e) {
                if (e.code == 404 || e.code == 409) empty = true;
                else throw e;
            }

            final int total = all.size();
            final String[] shas = new String[total];
            final boolean[] skipped = new boolean[total];
            final boolean[] same = new boolean[total];
            final long[] sizes = new long[total];
            final List<String> skippedNames = new ArrayList<>();
            int first = 0;
            if (empty) {
                for (; first < total; first++) {
                    FileScanner.Item it = all.get(first);
                    byte[] data = readBytes(cr, it.uri, -1);
                    if (data == null) {
                        skipped[first] = true;
                        skippedNames.add(it.path);
                        continue;
                    }
                    api.putFile(owner, repo, base.isEmpty() ? it.path : base + "/" + it.path, data, message);
                    shas[first] = "";
                    first++;
                    break;
                }
            }

            final Map<String, String> existing = new HashMap<>();
            if (!empty || first > 0) {
                try {
                    existing.putAll(api.blobShaMap(owner, repo, branch));
                } catch (Exception ignored) {
                }
            }

            final boolean[] gql = {true};
            final byte[][] datas = new byte[total][];
            final AtomicInteger doneCount = new AtomicInteger(first);
            final AtomicLong coolUntil = new AtomicLong(0);
            final Semaphore bigGate = new Semaphore(1);
            final ExecutorService ex = Executors.newFixedThreadPool(PARALLEL);
            int batchNo = 0;
            try {
                int idx = first;
                while (idx < total) {
                    job.check();
                    final int from = idx;
                    final boolean useGql = gql[0];
                    final int maxFiles = useGql ? GQL_FILES : BATCH_FILES;
                    final long maxBytes = useGql ? GQL_BYTES : BATCH_BYTES;
                    int to = from;
                    long acc = 0;
                    while (to < total && to - from < maxFiles && (acc < maxBytes || to == from)) {
                        long sz = FileScanner.size(cr, all.get(to).uri);
                        sizes[to] = sz;
                        if (sz > 0) acc += Math.min(sz, MAX_FILE_BYTES);
                        to++;
                    }
                    final int end = to;
                    final AtomicReference<Throwable> error = new AtomicReference<>();
                    List<Future<?>> futures = new ArrayList<>();
                    for (int i = from; i < end; i++) {
                        final int k = i;
                        final FileScanner.Item it = all.get(i);
                        final long size = sizes[i];
                        futures.add(ex.submit(() -> {
                            if (error.get() != null || job.cancelled()) return;
                            try {
                                String dest = base.isEmpty() ? it.path : base + "/" + it.path;
                                if (size > MAX_FILE_BYTES) {
                                    skipped[k] = true;
                                } else {
                                    boolean big = size < 0 || size > BIG;
                                    if (big) bigGate.acquire();
                                    try {
                                        String local = size >= 0 ? gitSha(cr, it.uri, size) : null;
                                        if (local != null && local.equals(existing.get(dest))) {
                                            same[k] = true;
                                        } else {
                                            byte[] data = readBytes(cr, it.uri, size);
                                            if (data == null) skipped[k] = true;
                                            else if (useGql && size >= 0 && size <= GQL_FILE_MAX) datas[k] = data;
                                            else shas[k] = blob(api, owner, repo, data, coolUntil, job);
                                        }
                                    } finally {
                                        if (big) bigGate.release();
                                    }
                                }
                                int n = doneCount.incrementAndGet();
                                job.progress(n + "/" + total + "  " + it.path, n, total);
                            } catch (Throwable t) {
                                error.compareAndSet(null, t);
                            }
                        }));
                    }
                    for (Future<?> f : futures) f.get();
                    Throwable err = error.get();
                    if (err instanceof Exception) throw (Exception) err;
                    if (err != null) throw new IOException(String.valueOf(err));
                    job.check();

                    List<GitHubApi.TreeEntry> entries = new ArrayList<>();
                    List<GitHubApi.Addition> adds = new ArrayList<>();
                    for (int i = from; i < end; i++) {
                        FileScanner.Item it = all.get(i);
                        String dest = base.isEmpty() ? it.path : base + "/" + it.path;
                        if (skipped[i]) {
                            skippedNames.add(it.path);
                        } else if (same[i]) {
                            continue;
                        } else if (datas[i] != null) {
                            adds.add(new GitHubApi.Addition(dest, datas[i]));
                        } else if (shas[i] != null && !shas[i].isEmpty()) {
                            entries.add(new GitHubApi.TreeEntry(dest, shas[i]));
                        }
                    }
                    if (!adds.isEmpty()) {
                        batchNo++;
                        job.progress(app.getString(R.string.committing), doneCount.get(), total);
                        String msgN = total > maxFiles ? message + " (" + batchNo + ")" : message;
                        boolean sent = gqlCommit(api, owner, repo, branch, msgN, adds, job, coolUntil);
                        if (!sent) {
                            gql[0] = false;
                            for (GitHubApi.Addition a : adds) {
                                entries.add(new GitHubApi.TreeEntry(a.path,
                                        blob(api, owner, repo, a.data, coolUntil, job)));
                            }
                        }
                        for (int i = from; i < end; i++) datas[i] = null;
                    }
                    if (!entries.isEmpty()) {
                        batchNo++;
                        job.progress(app.getString(R.string.committing), doneCount.get(), total);
                        api.commitEntries(owner, repo, branch, entries,
                                total > maxFiles ? message + " (" + batchNo + ")" : message);
                    }
                    idx = end;
                }
            } finally {
                ex.shutdownNow();
                RepoTree.invalidate(app, owner, repo, branch);
            }

            StringBuilder sb = new StringBuilder(app.getString(R.string.upload_summary, total - skippedNames.size()));
            if (!skippedNames.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (int i = 0; i < Math.min(skippedNames.size(), 10); i++) names.append(skippedNames.get(i)).append('\n');
                sb.append("\n\n").append(app.getString(R.string.skipped_summary, skippedNames.size(), names.toString()));
            }
            job.message = sb.toString();
        };
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(40);
        for (byte b : d) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return sb.toString();
    }

    private static String gitShaOf(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(("blob " + data.length + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.update(data);
            return hex(md.digest());
        } catch (Exception e) {
            return "";
        }
    }

    public static String gitSha(File f) {
        if (f == null || !f.isFile()) return null;
        return gitSha(null, Uri.fromFile(f), f.length());
    }

    private static boolean gqlCommit(GitHubApi api, String owner, String repo, String branch, String message,
                                     List<GitHubApi.Addition> adds, Transfers.Job job, AtomicLong coolUntil)
            throws Exception {
        List<GitHubApi.Addition> pending = new ArrayList<>(adds);
        for (int attempt = 0; attempt < 5; attempt++) {
            job.check();
            long wait = coolUntil.get() - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(Math.min(wait, 90000L));
            try {
                String head = api.getBranchSha(owner, repo, branch);
                if (attempt > 0) {
                    Map<String, String> now = api.blobShaMap(owner, repo, branch);
                    List<GitHubApi.Addition> left = new ArrayList<>();
                    for (GitHubApi.Addition a : pending) {
                        if (!gitShaOf(a.data).equals(now.get(a.path))) left.add(a);
                    }
                    pending = left;
                    if (pending.isEmpty()) return true;
                }
                api.createCommitOnBranch(owner, repo, branch, head, message, pending);
                return true;
            } catch (GitHubApi.HeadMoved e) {
                Thread.sleep(400L * (attempt + 1));
            } catch (GitHubApi.ApiException e) {
                boolean rate = e.code == 429 || (e.code == 403 && e.waitMs > 0);
                if (rate && attempt < 4) {
                    final long until = System.currentTimeMillis() + Math.min(Math.max(e.waitMs, 3000L), 90000L);
                    coolUntil.updateAndGet(v -> Math.max(v, until));
                    continue;
                }
                if (e.code >= 500 && attempt < 3) {
                    Thread.sleep(1500L << attempt);
                    continue;
                }
                return false;
            } catch (IOException e) {
                if (attempt >= 2) return false;
                Thread.sleep(1500L);
            }
        }
        return false;
    }

    public interface Stop {
        boolean now();
    }

    public static final class Preview {
        public int files;
        public int ignored;
        public int tooBig;
        public int fresh;
        public int changed;
        public int same;
        public long bytes;
        public long uploadBytes;
    }

    public static Preview preview(GitHubApi api, ContentResolver cr, String owner, String repo, String branch,
                                  String base, List<File> roots, boolean includeRoot, boolean skipJunk,
                                  Stop stop) throws Exception {
        Preview p = new Preview();
        List<FileScanner.Item> all = new ArrayList<>();
        FileScanner.scanFiles(roots, includeRoot, skipJunk, all);
        if (skipJunk) {
            List<FileScanner.Item> raw = new ArrayList<>();
            FileScanner.scanFiles(roots, includeRoot, false, raw);
            p.ignored = Math.max(0, raw.size() - all.size());
        }
        boolean empty = false;
        Map<String, String> existing = new HashMap<>();
        try {
            api.getBranchSha(owner, repo, branch);
            existing.putAll(api.blobShaMap(owner, repo, branch));
        } catch (GitHubApi.ApiException e) {
            if (e.code == 404 || e.code == 409) empty = true;
            else throw e;
        }
        for (FileScanner.Item it : all) {
            if (stop.now()) throw new Transfers.Cancelled();
            long size = FileScanner.size(cr, it.uri);
            if (size > MAX_FILE_BYTES) {
                p.tooBig++;
                continue;
            }
            p.files++;
            if (size > 0) p.bytes += size;
            String dest = base.isEmpty() ? it.path : base + "/" + it.path;
            String remote = empty ? null : existing.get(dest);
            if (remote == null) {
                p.fresh++;
                if (size > 0) p.uploadBytes += size;
            } else {
                String local = size >= 0 ? gitSha(cr, it.uri, size) : null;
                if (remote.equals(local)) {
                    p.same++;
                } else {
                    p.changed++;
                    if (size > 0) p.uploadBytes += size;
                }
            }
        }
        return p;
    }

    /** Same sha git itself would give the file, computed while streaming (no file is held in memory). */
    private static String gitSha(ContentResolver cr, Uri u, long size) {
        InputStream is = null;
        try {
            is = cr == null ? new java.io.FileInputStream(new File(u.getPath())) : cr.openInputStream(u);
            if (is == null) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(("blob " + size + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] buf = new byte[65536];
            int n;
            long read = 0;
            while ((n = is.read(buf)) != -1) {
                md.update(buf, 0, n);
                read += n;
            }
            if (read != size) return null;
            StringBuilder sb = new StringBuilder(40);
            for (byte b : md.digest()) {
                sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** createBlob that backs off (shared by every worker) when GitHub rate limits or hiccups. */
    private static String blob(GitHubApi api, String owner, String repo, byte[] data, AtomicLong coolUntil,
                               Transfers.Job job) throws Exception {
        for (int attempt = 0; ; attempt++) {
            long wait = coolUntil.get() - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(Math.min(wait, 90000L));
            job.check();
            long ms = Math.min(30000L, 1500L << attempt);
            try {
                return api.createBlob(owner, repo, data);
            } catch (GitHubApi.ApiException e) {
                boolean rate = e.code == 403 || e.code == 429;
                if (!(rate || e.code >= 500) || attempt >= 6) throw e;
                if (rate) {
                    final long until = System.currentTimeMillis() + Math.max(ms, Math.min(e.waitMs, 90000L));
                    coolUntil.updateAndGet(v -> Math.max(v, until));
                }
            } catch (IOException e) {
                if (attempt >= 6) throw e;
            }
            Thread.sleep(ms);
        }
    }

    private static byte[] readBytes(ContentResolver cr, Uri u, long hint) throws IOException {
        InputStream is;
        try {
            is = cr.openInputStream(u);
        } catch (java.io.FileNotFoundException e) {
            return null;
        }
        if (is == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(hint > 0 && hint <= MAX_FILE_BYTES ? (int) hint + 16 : 16384);
            byte[] buf = new byte[32768];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_FILE_BYTES) return null;
            }
            return bos.toByteArray();
        } finally {
            is.close();
        }
    }
}

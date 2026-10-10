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
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** The actual work of the background transfers (see {@link Transfers}). Nothing here touches an Activity. */
public final class TransferTasks {
    private TransferTasks() {
    }

    private static final int MAX_FILE_BYTES = 25 * 1024 * 1024;
    private static final int PARALLEL = 3;
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

    /** Downloads {@code src} into {@code dest}; with {@code extractApk} only the .apk inside the ZIP is kept. */
    public static Transfers.Task download(final Transfers.Src src, final File dest, final boolean extractApk) {
        return job -> {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            boolean ok = false;
            try {
                HttpURLConnection c = src.open();
                try {
                    long total = c.getContentLengthLong();
                    InputStream in = c.getInputStream();
                    OutputStream out;
                    try {
                        out = new FileOutputStream(dest);
                    } catch (IOException openFail) {
                        in.close();
                        throw new IOException(job.app.getString(R.string.save_failed, dest.getParent()), openFail);
                    }
                    try {
                        GitHubApi.Progress prog = (done, tot) -> {
                            job.progress(dest.getName(), done, tot);
                            job.check();
                        };
                        if (extractApk) {
                            ZipInputStream zin = new ZipInputStream(in);
                            ZipEntry e;
                            boolean found = false;
                            while ((e = zin.getNextEntry()) != null) {
                                if (!e.isDirectory() && e.getName().toLowerCase(Locale.US).endsWith(".apk")) {
                                    GitHubApi.copy(zin, out, e.getSize(), prog);
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) throw new IOException(job.app.getString(R.string.no_apk_in_artifact));
                        } else {
                            GitHubApi.copy(in, out, total, prog);
                        }
                        ok = true;
                    } finally {
                        try {
                            out.close();
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
                if (!ok) //noinspection ResultOfMethodCallIgnored
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

    // ------------------------------------------------------------------ app update

    public static File updateApkFile(Context c) {
        return new File(new File(c.getCacheDir(), "apk"), "update.apk");
    }

    public static Transfers.Task updateDownload(final GitHubApi api, final String owner, final String repo,
                                                final Updater.Info in) {
        return job -> {
            File apk = updateApkFile(job.app);
            File dir = apk.getParentFile();
            if (dir != null && !dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            File[] old = dir == null ? null : dir.listFiles();
            if (old != null) for (File o : old) //noinspection ResultOfMethodCallIgnored
                o.delete();
            HttpURLConnection c = api.openDownload(api.assetPath(owner, repo, in.assetId), "application/octet-stream");
            try {
                long total = c.getContentLengthLong();
                if (total <= 0) total = in.assetSize;
                InputStream is = c.getInputStream();
                OutputStream os = new FileOutputStream(apk);
                try {
                    GitHubApi.copy(is, os, total, (done, tot) -> {
                        job.progress(in.assetName, done, tot);
                        job.check();
                    });
                } catch (RuntimeException | IOException e) {
                    try {
                        os.close();
                    } catch (Exception ignored) {
                    }
                    //noinspection ResultOfMethodCallIgnored
                    apk.delete();
                    throw e;
                } finally {
                    try {
                        os.close();
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
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                throw new IOException(job.app.getString(R.string.upd_size_bad));
            }
            if (!in.sha256.isEmpty()) {
                job.progress(job.app.getString(R.string.upd_verifying), 0, 0);
                String got = sha256Hex(apk);
                if (!got.equalsIgnoreCase(in.sha256)) {
                    //noinspection ResultOfMethodCallIgnored
                    apk.delete();
                    throw new IOException(job.app.getString(R.string.upd_hash_bad));
                }
            }
            // the update must be signed by the same key as the installed app
            if (!Integrity.sameSigner(job.app, apk)) {
                //noinspection ResultOfMethodCallIgnored
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

    // ------------------------------------------------------------------ upload (several files at once, one commit)

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
            int first = 0;
            if (empty) {
                // an empty repository has no branch yet: the first file creates it through the contents API
                for (; first < total; first++) {
                    FileScanner.Item it = all.get(first);
                    byte[] data = readBytes(cr, it.uri);
                    if (data == null) {
                        skipped[first] = true;
                        continue;
                    }
                    api.putFile(owner, repo, base.isEmpty() ? it.path : base + "/" + it.path, data, message);
                    shas[first] = "";   // already committed
                    first++;
                    break;
                }
            }

            final AtomicInteger doneCount = new AtomicInteger(first);
            final AtomicReference<Throwable> error = new AtomicReference<>();
            final Semaphore bigGate = new Semaphore(1);
            ExecutorService ex = Executors.newFixedThreadPool(PARALLEL);
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (int i = first; i < total; i++) {
                    final int idx = i;
                    final FileScanner.Item it = all.get(i);
                    futures.add(ex.submit(() -> {
                        if (error.get() != null || job.cancelled()) return;
                        try {
                            long size = FileScanner.size(cr, it.uri);
                            if (size > MAX_FILE_BYTES) {
                                skipped[idx] = true;
                            } else {
                                // big files are handled one at a time so base64 + JSON never exhaust memory
                                boolean big = size < 0 || size > BIG;
                                if (big) bigGate.acquire();
                                try {
                                    byte[] data = readBytes(cr, it.uri);
                                    if (data == null) skipped[idx] = true;
                                    else shas[idx] = blob(api, owner, repo, data);
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
            } finally {
                ex.shutdownNow();
            }
            Throwable err = error.get();
            if (err instanceof Exception) throw (Exception) err;
            if (err != null) throw new IOException(String.valueOf(err));
            job.check();

            List<GitHubApi.TreeEntry> entries = new ArrayList<>();
            List<String> skippedNames = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                FileScanner.Item it = all.get(i);
                if (skipped[i]) {
                    skippedNames.add(it.path);
                } else if (shas[i] != null && !shas[i].isEmpty()) {
                    entries.add(new GitHubApi.TreeEntry(base.isEmpty() ? it.path : base + "/" + it.path, shas[i]));
                }
            }
            if (!entries.isEmpty()) {
                job.progress(app.getString(R.string.committing), total, total);
                api.commitEntries(owner, repo, branch, entries, message);
            }
            RepoTree.invalidate(app, owner, repo, branch);

            StringBuilder sb = new StringBuilder(app.getString(R.string.upload_summary, total - skippedNames.size()));
            if (!skippedNames.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (int i = 0; i < Math.min(skippedNames.size(), 10); i++) names.append(skippedNames.get(i)).append('\n');
                sb.append("\n\n").append(app.getString(R.string.skipped_summary, skippedNames.size(), names.toString()));
            }
            job.message = sb.toString();
        };
    }

    /** createBlob with a few retries for rate limits and flaky connections. */
    private static String blob(GitHubApi api, String owner, String repo, byte[] data) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                return api.createBlob(owner, repo, data);
            } catch (GitHubApi.ApiException e) {
                boolean retry = e.code == 403 || e.code == 429 || e.code >= 500;
                if (!retry || attempt >= 3) throw e;
            } catch (IOException e) {
                if (attempt >= 3) throw e;
            }
            Thread.sleep(1500L * (attempt + 1));
        }
    }

    private static byte[] readBytes(ContentResolver cr, Uri u) throws IOException {
        InputStream is;
        try {
            is = cr.openInputStream(u);
        } catch (java.io.FileNotFoundException e) {
            return null;
        }
        if (is == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
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

package com.ghmanager.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.ghmanager.app.FmConst.*;
import static com.ghmanager.app.FmFiles.*;

final class FmOperations {
    FileManagerActivity fm;

    FmOperations(FileManagerActivity fm) {
        this.fm = fm;
    }

    volatile boolean cancelled = false;

    final List<String> lastTrash = new ArrayList<>();

    AlertDialog busy;

    TextView busyText;

    Set<String> favs() {
        return new LinkedHashSet<>(fm.prefs.getStringSet("fav", new LinkedHashSet<String>()));
    }

    final Runnable undoHide = () -> {
        if (fm.undoBar != null) Fx.show(fm.undoBar, false);
    };

    void openFile(FmEntry e) {
        int t = typeOf(e);
        if (t == T_APK) {
            Perms.installApk(fm, e.f);
            return;
        }
        if (e.ext.equals("zip") || e.ext.equals("jar")) {
            fm.dialogs.zipMenu(e.f);
            return;
        }
        if (t == T_TXT) {
            Intent i = new Intent(fm, FileEditActivity.class);
            i.putExtra("path", e.f.getAbsolutePath());
            fm.startActivity(i);
            return;
        }
        openExternal(e.f, false);
    }

    void openExternal(File f, boolean chooser) {
        try {
            Uri u = FileProvider.getUriForFile(fm, fm.getPackageName() + ".files", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, mimeOf(f));
            i.setClipData(ClipData.newRawUri("", u));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            fm.startActivity(chooser ? Intent.createChooser(i, f.getName()) : i);
        } catch (Exception ex) {
            fm.toast(R.string.fm_no_app);
        }
    }

    void listZip(final File zip) {
        fm.loading(true);
        fm.io.execute(() -> {
            final StringBuilder sb = new StringBuilder();
            String err = null;
            try (ZipFile zf = new ZipFile(zip)) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                int n = 0;
                int total = zf.size();
                while (en.hasMoreElements() && n < 300) {
                    ZipEntry ze = en.nextElement();
                    sb.append(ze.isDirectory() ? "▸ " : "• ").append(ze.getName());
                    if (!ze.isDirectory() && ze.getSize() >= 0) sb.append("  (").append(Fmt.size(ze.getSize())).append(")");
                    sb.append('\n');
                    n++;
                }
                if (total > n) sb.append("…  +").append(total - n);
            } catch (Exception ex) {
                err = String.valueOf(ex.getMessage());
            }
            final String e2 = err;
            fm.post(() -> {
                fm.loading(false);
                fm.dialogs.info(zip.getName(), e2 != null ? e2 : sb.toString());
            });
        });
    }

    void previewThenUpload(final GitHubApi api, final String owner, final String name, final String b,
                                   final String base, final String m, final List<File> files,
                                   final boolean incRoot, final boolean skip) {
        showBusy(fm.getString(R.string.fm_preview_scanning), false);
        fm.io.execute(() -> {
            try {
                final TransferTasks.Preview p = TransferTasks.preview(api, fm.getContentResolver(), owner, name, b, base,
                        files, incRoot, skip, () -> cancelled);
                fm.post(() -> {
                    hideBusy();
                    fm.dialogs.showPreview(p, () -> {
                        Transfers.start(fm.getApplicationContext(), fm.getString(R.string.tr_upload_title, name),
                                "upload:" + owner + "/" + name,
                                TransferTasks.upload(api, owner, name, b, base, m, files, incRoot, skip),
                                (job, ok, text) -> {
                                    String first = text == null ? "" : text;
                                    int nl = first.indexOf('\n');
                                    if (nl > 0) first = first.substring(0, nl);
                                    Toast.makeText(job.app, ok ? first
                                            : job.app.getString(R.string.tr_failed) + ": " + first,
                                            Toast.LENGTH_LONG).show();
                                });
                        Toast.makeText(fm, R.string.tr_started_bg, Toast.LENGTH_LONG).show();
                        fm.selection.clearSelection();
                    });
                });
            } catch (final Exception e) {
                fm.post(() -> {
                    hideBusy();
                    if (e instanceof Transfers.Cancelled) {
                        fm.toast(R.string.fm_cancelled);
                        return;
                    }
                    String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    Dlg.error(fm, fm.getString(R.string.error), msg, e,
                            () -> previewThenUpload(api, owner, name, b, base, m, files, incRoot, skip));
                });
            }
        });
    }

    void toggleFav(List<File> files, boolean remove) {
        Set<String> s = favs();
        if (remove) s.removeAll(paths(files));
        else s.addAll(paths(files));
        fm.prefs.edit().putStringSet("fav", s).apply();
        fm.selection.clearSelection();
        if (fm.mode == M_FAV) fm.listing.refresh();
        fm.toast(remove ? R.string.fm_unfavorited : R.string.fm_favorited);
    }

    void copyText(String text) {
        ClipboardManager cm = (ClipboardManager) fm.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("path", text));
            fm.toast(R.string.copied);
        }
    }

    int opCount;

    static final class Op {
        volatile long total;
        volatile long done;
        long lastUi;
        long lastT;
        long lastD;
        float speed;
    }

    Op op;

    ProgressBar opBar;

    TextView opDetail;

    void showBusy(String text) {
        showBusy(text, false);
    }

    void showBusy(String text, boolean progress) {
        hideBusy();
        cancelled = false;
        opCount = 0;
        busyText = new TextView(fm);
        busyText.setText(text);
        busyText.setSingleLine(true);
        busyText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout box;
        if (progress) {
            op = new Op();
            op.lastT = SystemClock.elapsedRealtime();
            box = Ui.box(fm);
            busyText.setTextColor(Ui.color(fm, R.color.text_primary));
            busyText.setTextSize(14);
            opBar = new ProgressBar(fm, null, android.R.attr.progressBarStyleHorizontal);
            opBar.setMax(1000);
            opBar.setIndeterminate(true);
            Ui.tint(fm, opBar);
            opDetail = new TextView(fm);
            opDetail.setTextColor(Ui.color(fm, R.color.text_secondary));
            opDetail.setTextSize(12);
            opDetail.setText(R.string.fm_op_preparing);
            box.addView(busyText);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Ui.dp(fm, 8));
            lp.topMargin = Ui.dp(fm, 14);
            box.addView(opBar, lp);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            dl.topMargin = Ui.dp(fm, 10);
            box.addView(opDetail, dl);
        } else {
            op = null;
            opBar = null;
            opDetail = null;
            IosSpinner wheel = new IosSpinner(fm);
            box = Ui.busyBox(fm, wheel, busyText);
        }
        busy = new Dlg(fm).centered().setTitle(R.string.working).setView(box)
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, (d, w) -> cancelled = true)
                .create();
        busy.show();
    }

    void setBusy(final String t) {
        fm.post(() -> {
            if (busyText != null) busyText.setText(t);
        });
    }

    void setOpTotal(long t) {
        Op o = op;
        if (o != null) o.total = t;
    }

    void tick(long n, String name) {
        final Op o = op;
        if (o == null) return;
        o.done += n;
        long now = SystemClock.elapsedRealtime();
        if (now - o.lastUi < 200) return;
        o.lastUi = now;
        if (now - o.lastT >= 500) {
            float inst = (o.done - o.lastD) * 1000f / (now - o.lastT);
            o.speed = o.speed <= 0 ? inst : o.speed * 0.6f + inst * 0.4f;
            o.lastT = now;
            o.lastD = o.done;
        }
        final long d = o.done;
        final long t = o.total;
        final float sp = o.speed;
        final String nm = name;
        fm.post(() -> showOp(o, d, t, sp, nm));
    }

    void showOp(Op o, long d, long t, float speed, String name) {
        if (op != o || opBar == null || opDetail == null) return;
        if (name != null && busyText != null) busyText.setText(name);
        StringBuilder sb = new StringBuilder();
        if (t > 0) {
            int pm = (int) Math.min(1000, d * 1000 / t);
            if (opBar.isIndeterminate()) opBar.setIndeterminate(false);
            Ui.progress(opBar, pm);
            sb.append(pm / 10).append('%').append("  ·  ").append(Fmt.size(d)).append(" / ").append(Fmt.size(t));
        } else {
            sb.append(Fmt.size(d));
        }
        if (speed > 0) {
            sb.append("  ·  ").append(Fmt.size((long) speed)).append("/s");
            if (t > d) sb.append("  ·  ").append(fm.getString(R.string.fm_op_eta, clock((long) ((t - d) / speed))));
        }
        opDetail.setText(sb.toString());
    }

    void hideBusy() {
        if (busy != null) {
            try {
                busy.dismiss();
            } catch (Exception ignored) {
            }
            busy = null;
        }
        busyText = null;
        op = null;
        opBar = null;
        opDetail = null;
    }

    void runTask(String text, final FmWork w) {
        runTask(text, false, w);
    }

    void runTask(final String text, final boolean progress, final FmWork w) {
        showBusy(text, progress);
        fm.io.execute(() -> {
            String msg = null;
            Exception err = null;
            try {
                msg = w.run();
            } catch (Exception e) {
                err = e;
            }
            final String m = msg;
            final Exception er = err;
            fm.post(() -> {
                hideBusy();
                if (er != null) fm.dialogs.error(er, () -> runTask(text, progress, w));
                else if (m != null) fm.toast(m);
                fm.listing.refresh();
            });
        });
    }

    void copyTree(File src, File dst) throws IOException {
        if (cancelled) throw new FmCancel();
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new IOException(fm.getString(R.string.fm_err_mkdir, dst.getName()));
            }
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dst, k.getName()));
        } else {
            if (op == null) setBusy(src.getName() + "  ·  " + (++opCount));
            else tick(0, src.getName());
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled) throw new FmCancel();
                    out.write(buf, 0, n);
                    tick(n, src.getName());
                }
            }
            dst.setLastModified(src.lastModified());
        }
    }

    void deleteTree(File f) throws IOException {
        if (cancelled) throw new FmCancel();
        if (f.isDirectory() && !isLink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        setBusy(f.getName() + "  ·  " + (++opCount));
        if (!f.delete() && f.exists()) throw new IOException(fm.getString(R.string.fm_err_delete, f.getName()));
    }

    void paste() {
        if (fm.selection.clip.isEmpty() || fm.mode != M_DIR) return;
        final File dest = fm.cur;
        final boolean cut = fm.selection.clipCut;
        final List<File> src = new ArrayList<>(fm.selection.clip);
        final Set<File> doneSrc = new java.util.HashSet<>();
        fm.selection.clip.clear();
        fm.chrome.updatePasteBar();
        runTask(fm.getString(cut ? R.string.fm_moving : R.string.fm_copying), true, () -> {
            final long[] sizes = new long[src.size()];
            long total = 0;
            for (int i = 0; i < src.size(); i++) {
                long[] t = new long[3];
                if (src.get(i).exists()) sizeOf(src.get(i), t);
                sizes[i] = t[0];
                total += t[0];
            }
            setOpTotal(total);
            if (!cut) {
                long free = dest.getUsableSpace();
                if (free > 0 && free < total + 1024L * 1024) {
                    throw new IOException(fm.getString(R.string.fm_err_no_space, Fmt.size(total)));
                }
            }
            int ok = 0;
            for (int i = 0; i < src.size(); i++) {
                File f = src.get(i);
                if (cancelled) throw new FmCancel();
                if (doneSrc.contains(f)) continue;
                if (!f.exists()) continue;
                if (f.isDirectory() && isInside(dest, f)) throw new IOException(fm.getString(R.string.fm_err_into_self));
                if (cut && dest.equals(f.getParentFile())) continue;
                File target = unique(dest, f.getName());
                if (cut) {
                    if (f.renameTo(target)) {
                        tick(sizes[i], f.getName());
                    } else {
                        long free = dest.getUsableSpace();
                        if (free > 0 && free < sizes[i] + 1024L * 1024) {
                            throw new IOException(fm.getString(R.string.fm_err_no_space, Fmt.size(sizes[i])));
                        }
                        try {
                            copyTree(f, target);
                        } catch (IOException ex) {
                            Trash.deleteTree(target);
                            throw ex;
                        }
                        deleteTree(f);
                    }
                } else {
                    try {
                        copyTree(f, target);
                    } catch (IOException ex) {
                        Trash.deleteTree(target);
                        throw ex;
                    }
                }
                doneSrc.add(f);
                ok++;
            }
            return fm.getString(R.string.fm_done_n, ok);
        });
    }

    void deleteSelected() {
        if (fm.mode == M_TRASH) {
            fm.dialogs.foreverEntries(fm.selection.selectedEntries());
            return;
        }
        fm.dialogs.confirmDelete(fm.selection.selectedFiles());
    }

    Trash.Tick trashTick(final AtomicBoolean stop) {
        return (n, name) -> {
            tick(n, name);
            if (cancelled) stop.set(true);
        };
    }

    void trashFiles(final List<File> files) {
        final List<String> ids = new ArrayList<>();
        runTask(fm.getString(R.string.fm_trashing), true, () -> {
            long total = 0;
            for (File f : files) {
                long[] t = new long[3];
                if (f.exists()) sizeOf(f, t);
                total += t[0];
            }
            setOpTotal(total);
            final AtomicBoolean stop = new AtomicBoolean(false);
            Trash.Tick tk = trashTick(stop);
            for (File f : files) {
                if (cancelled) throw new FmCancel();
                if (!f.exists()) continue;
                try {
                    ids.add(Trash.put(fm.getApplicationContext(), f, tk, stop));
                } catch (IOException ex) {
                    if (cancelled) throw new FmCancel();
                    throw ex;
                }
            }
            Set<String> fv = favs();
            if (fv.removeAll(paths(files))) fm.prefs.edit().putStringSet("fav", fv).apply();
            final List<String> got = new ArrayList<>(ids);
            fm.post(() -> showUndo(got));
            return null;
        });
    }

    void showUndo(List<String> ids) {
        lastTrash.clear();
        lastTrash.addAll(ids);
        if (ids.isEmpty() || fm.undoBar == null) return;
        fm.undoText.setText(fm.getString(R.string.fm_trashed_n, ids.size()));
        Fx.show(fm.undoBar, true);
        fm.ui.removeCallbacks(undoHide);
        fm.ui.postDelayed(undoHide, 10000);
    }

    void undoTrash() {
        final List<String> ids = new ArrayList<>(lastTrash);
        lastTrash.clear();
        fm.ui.removeCallbacks(undoHide);
        Fx.show(fm.undoBar, false);
        if (ids.isEmpty()) return;
        runTask(fm.getString(R.string.fm_restoring), true, () -> restoreIds(ids));
    }

    String restoreIds(List<String> ids) throws IOException {
        long total = 0;
        List<Trash.Item> items = new ArrayList<>();
        for (String id : ids) {
            Trash.Item it = Trash.find(fm.getApplicationContext(), id);
            if (it == null) continue;
            items.add(it);
            total += it.size;
        }
        setOpTotal(total);
        final AtomicBoolean stop = new AtomicBoolean(false);
        Trash.Tick tk = trashTick(stop);
        int n = 0;
        for (Trash.Item it : items) {
            if (cancelled) throw new FmCancel();
            try {
                Trash.restore(fm.getApplicationContext(), it, tk, stop);
            } catch (IOException ex) {
                if (cancelled) throw new FmCancel();
                throw ex;
            }
            n++;
        }
        return fm.getString(R.string.fm_restored_n, n);
    }

    void restoreEntries(List<FmEntry> es) {
        final List<String> ids = new ArrayList<>();
        for (FmEntry e : es) if (e.trashId != null) ids.add(e.trashId);
        fm.selection.selected.clear();
        if (ids.isEmpty()) return;
        runTask(fm.getString(R.string.fm_restoring), true, () -> restoreIds(ids));
    }

    void doCompress(final List<File> files, final File out) {
        fm.selection.selected.clear();
        runTask(fm.getString(R.string.fm_compressing), true, () -> {
            long total = 0;
            for (File f : files) {
                long[] t = new long[3];
                sizeOf(f, t);
                total += t[0];
            }
            setOpTotal(total);
            boolean ok = false;
            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
                for (File f : files) addToZip(zos, f, f.getName(), out);
                ok = true;
            } finally {
                if (!ok) out.delete();
            }
            return fm.getString(R.string.fm_created_name, out.getName());
        });
    }

    void addToZip(ZipOutputStream zos, File f, String entry, File outFile) throws IOException {
        if (cancelled) throw new FmCancel();
        if (f.equals(outFile)) return;
        if (f.isDirectory()) {
            zos.putNextEntry(new ZipEntry(entry + "/"));
            zos.closeEntry();
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) addToZip(zos, k, entry + "/" + k.getName(), outFile);
        } else {
            if (op == null) setBusy(f.getName());
            else tick(0, f.getName());
            zos.putNextEntry(new ZipEntry(entry));
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled) throw new FmCancel();
                    zos.write(buf, 0, n);
                    tick(n, f.getName());
                }
            }
            zos.closeEntry();
        }
    }

    void extract(final File zip, final boolean intoFolder) {
        fm.selection.selected.clear();
        File parent = zip.getParentFile();
        if (parent == null) parent = fm.cur;
        final File dest = intoFolder ? unique(parent, stripExt(zip.getName())) : parent;
        runTask(fm.getString(R.string.fm_extracting), true, () -> {
            if (!dest.exists() && !dest.mkdirs()) throw new IOException(fm.getString(R.string.fm_err_mkdir, dest.getName()));
            long unpacked = 0;
            try (ZipFile zf = new ZipFile(zip)) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    long sz = ze.getSize();
                    unpacked += sz >= 0 ? sz : Math.max(0, ze.getCompressedSize());
                }
            } catch (IOException ignored) {
                unpacked = 0;
            }
            setOpTotal(unpacked);
            long free = dest.getUsableSpace();
            if (unpacked > 0 && free > 0 && free < unpacked + 1024L * 1024) {
                throw new IOException(fm.getString(R.string.fm_err_no_space, Fmt.size(unpacked)));
            }
            String root = dest.getCanonicalPath() + File.separator;
            int count = 0;
            int entries = 0;
            long written = 0;
            try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
                ZipEntry e;
                byte[] buf = new byte[64 * 1024];
                while ((e = zin.getNextEntry()) != null) {
                    if (cancelled) throw new FmCancel();
                    if (++entries > 100000) throw new IOException(fm.getString(R.string.fm_err_zip_unsafe));
                    File out = new File(dest, e.getName());
                    if (!out.getCanonicalPath().startsWith(root)) {
                        throw new IOException(fm.getString(R.string.fm_err_zip_unsafe));
                    }
                    if (e.isDirectory()) {
                        out.mkdirs();
                        continue;
                    }
                    File p = out.getParentFile();
                    if (p != null) p.mkdirs();
                    if (op == null) setBusy(e.getName());
                    else tick(0, e.getName());
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        int r;
                        while ((r = zin.read(buf)) != -1) {
                            if (cancelled) throw new FmCancel();
                            os.write(buf, 0, r);
                            written += r;
                            if (free > 0 && written > free) throw new IOException(fm.getString(R.string.fm_err_no_space, Fmt.size(written)));
                            tick(r, e.getName());
                        }
                    }
                    count++;
                }
            }
            return fm.getString(R.string.fm_extracted_n, count);
        });
    }

    void importUris(final List<Uri> uris) {
        final File dest = fm.cur;
        final Set<Uri> doneUri = new java.util.HashSet<>();
        runTask(fm.getString(R.string.fm_importing), true, () -> {
            long total = 0;
            for (Uri u : uris) {
                long sz = FileScanner.size(fm.getContentResolver(), u);
                if (sz > 0) total += sz;
            }
            setOpTotal(total);
            int ok = 0;
            for (Uri u : uris) {
                if (cancelled) throw new FmCancel();
                if (doneUri.contains(u)) continue;
                String n = FileScanner.displayName(fm.getContentResolver(), u);
                if (n == null || !validName(n)) n = "file-" + System.currentTimeMillis();
                File t = unique(dest, n);
                if (op == null) setBusy(t.getName());
                else tick(0, t.getName());
                try (InputStream in = fm.getContentResolver().openInputStream(u);
                     OutputStream out = new FileOutputStream(t)) {
                    if (in == null) throw new IOException(n);
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        if (cancelled) {
                            out.close();
                            t.delete();
                            throw new FmCancel();
                        }
                        out.write(buf, 0, r);
                        tick(r, t.getName());
                    }
                }
                doneUri.add(u);
                ok++;
            }
            return fm.getString(R.string.fm_done_n, ok);
        });
    }

    void share(List<File> files) {
        try {
            ArrayList<Uri> uris = new ArrayList<>();
            for (File f : files) {
                if (f.isFile()) uris.add(FileProvider.getUriForFile(fm, fm.getPackageName() + ".files", f));
            }
            if (uris.isEmpty()) return;
            Intent i;
            if (uris.size() == 1) {
                i = new Intent(Intent.ACTION_SEND);
                i.setType(mimeOf(files.get(0)));
                i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                i.setClipData(ClipData.newRawUri("", uris.get(0)));
            } else {
                i = new Intent(Intent.ACTION_SEND_MULTIPLE);
                i.setType("*/*");
                i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                ClipData cd = ClipData.newRawUri("", uris.get(0));
                for (int k = 1; k < uris.size(); k++) cd.addItem(new ClipData.Item(uris.get(k)));
                i.setClipData(cd);
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            fm.startActivity(Intent.createChooser(i, fm.getString(R.string.share)));
            fm.selection.clearSelection();
        } catch (Exception e) {
            fm.toast(R.string.fm_no_app);
        }
    }

    void sizeOf(File f, long[] tot) {
        if (cancelled) return;
        if (f.isDirectory()) {
            tot[2]++;
            if (isLink(f)) return;
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) sizeOf(k, tot);
        } else {
            tot[0] += f.length();
            tot[1]++;
        }
    }

    String sha256(File f) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}

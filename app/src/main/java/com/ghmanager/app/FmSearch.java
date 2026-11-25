package com.ghmanager.app;

import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.ghmanager.app.FmConst.*;
import static com.ghmanager.app.FmFiles.*;

final class FmSearch {
    FileManagerActivity fm;

    FmSearch(FileManagerActivity fm) {
        this.fm = fm;
    }

    String query = "";

    boolean suppressSearch = false;

    SearchSession session;

    boolean pageLoading;

    final Runnable searchRun = () -> {
        if (query.isEmpty()) return;
        fm.mode = M_SEARCH;
        fm.listing.refresh();
    };

    boolean useIndex() {
        return fm.prefs.getBoolean("use_index", false);
    }

    final class SearchSession {
        final String q;
        final boolean hidden;
        final String prefix;
        final ArrayDeque<File> stack = new ArrayDeque<>();
        final File root;
        java.io.BufferedReader idx;
        boolean started;
        volatile boolean done;
        int total;

        SearchSession(File root, String q, boolean hidden, boolean useIdx) {
            this.root = root;
            this.q = q;
            this.hidden = hidden;
            String rp = root.getAbsolutePath();
            this.prefix = rp.endsWith("/") ? rp : rp + "/";
            if (useIdx) {
                try {
                    idx = FmIndex.open(fm);
                } catch (IOException e) {
                    idx = null;
                }
            }
        }

        void close() {
            try {
                if (idx != null) idx.close();
            } catch (IOException ignored) {
            }
        }

        void fill(List<FmEntry> out, int want, long my) {
            int found = 0;
            if (idx != null) {
                try {
                    boolean eof = false;
                    while (found < want) {
                        String line = idx.readLine();
                        if (line == null) {
                            eof = true;
                            break;
                        }
                        if (my != fm.gen) return;
                        if (!line.startsWith(prefix)) continue;
                        String rel = line.substring(prefix.length());
                        if (!hidden && (rel.startsWith(".") || rel.contains("/."))) continue;
                        int sl = line.lastIndexOf('/');
                        String name = sl >= 0 ? line.substring(sl + 1) : line;
                        if (!name.toLowerCase(Locale.ROOT).contains(q)) continue;
                        File f = new File(line);
                        if (!f.exists()) continue;
                        out.add(new FmEntry(f));
                        found++;
                    }
                    if (eof) done = true;
                } catch (IOException e) {
                    done = true;
                }
                total += found;
                return;
            }
            if (!started) {
                started = true;
                stack.push(root);
            }
            while (!stack.isEmpty() && found < want) {
                if (my != fm.gen) return;
                File d = stack.pop();
                File[] arr = d.listFiles();
                if (arr == null) continue;
                for (File f : arr) {
                    String n = f.getName();
                    if (!hidden && n.startsWith(".")) continue;
                    if (n.toLowerCase(Locale.ROOT).contains(q)) {
                        out.add(new FmEntry(f));
                        found++;
                    }
                    if (f.isDirectory() && !isLink(f)) stack.push(f);
                }
            }
            if (stack.isEmpty()) done = true;
            total += found;
        }
    }

    void runSearch() {
        final long my = ++fm.gen;
        if (session != null) session.close();
        final SearchSession ss = new SearchSession(fm.cur, query, fm.showHidden, useIndex() && FmIndex.covers(fm, fm.cur));
        session = ss;
        pageLoading = false;
        fm.loading(true);
        fm.io.execute(() -> {
            List<FmEntry> out = new ArrayList<>();
            ss.fill(out, PAGE, my);
            Collections.sort(out, fm.listing.comparator());
            final List<FmEntry> res = out;
            fm.post(() -> {
                if (my != fm.gen) return;
                fm.listDenied = false;
                fm.listing.setShown(res);
                fm.loading(false);
            });
        });
    }

    void maybeLoadMore() {
        final SearchSession ss = session;
        if (fm.mode != M_SEARCH || ss == null || ss.done || pageLoading || ss.total >= MAX_SEARCH_ALL) return;
        if (fm.gridLm.findLastVisibleItemPosition() < fm.shown.size() - 15) return;
        pageLoading = true;
        final long my = fm.gen;
        fm.loading(true);
        fm.io.execute(() -> {
            final List<FmEntry> more = new ArrayList<>();
            ss.fill(more, PAGE, my);
            Collections.sort(more, fm.listing.comparator());
            fm.post(() -> {
                pageLoading = false;
                fm.loading(false);
                if (my != fm.gen || ss != session || fm.mode != M_SEARCH) return;
                if (!more.isEmpty()) {
                    int start = fm.shown.size();
                    fm.shown.addAll(more);
                    fm.adapter.notifyItemRangeInserted(start, more.size());
                }
                fm.chrome.updateChrome();
            });
        });
    }

    void buildIndex() {
        final File root = fm.lastGranted ? Environment.getExternalStorageDirectory() : fm.cur;
        fm.ops.runTask(fm.getString(R.string.fm_index_building), () -> {
            final AtomicBoolean stop = new AtomicBoolean(false);
            int n;
            try {
                n = FmIndex.build(fm.getApplicationContext(), root, stop, c -> {
                    if (fm.ops.cancelled) stop.set(true);
                    fm.ops.setBusy(fm.getString(R.string.fm_index_count, c));
                });
            } catch (IOException e) {
                if (fm.ops.cancelled) throw new FmCancel();
                throw e;
            }
            fm.prefs.edit().putBoolean("use_index", true).apply();
            return fm.getString(R.string.fm_index_done, n);
        });
    }

    void attach(EditText input) {
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (suppressSearch) return;
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                fm.ui.removeCallbacks(searchRun);
                if (query.isEmpty()) {
                    if (fm.mode == M_SEARCH) {
                        fm.mode = M_DIR;
                        fm.listing.refresh();
                    }
                } else {
                    fm.ui.postDelayed(searchRun, 350);
                }
            }
        });
    }
}

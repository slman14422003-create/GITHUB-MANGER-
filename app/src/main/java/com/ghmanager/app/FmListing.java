package com.ghmanager.app;

import android.os.Environment;
import android.view.View;
import androidx.recyclerview.widget.DiffUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

import static com.ghmanager.app.FmConst.*;
import static com.ghmanager.app.FmFiles.*;

final class FmListing {
    FileManagerActivity fm;

    FmListing(FileManagerActivity fm) {
        this.fm = fm;
    }

    String listKey = "";

    /** First visible row of folders we left, so going back lands where the user was. */
    final java.util.Map<String, Integer> scrollPos = new java.util.HashMap<>();

    boolean resetScroll = false;

    File appDir() {
        File d = fm.getExternalFilesDir(null);
        if (d == null) d = fm.getFilesDir();
        return d;
    }

    File defaultRoot() {
        return Perms.hasAllFiles(fm) ? Environment.getExternalStorageDirectory() : appDir();
    }

    List<File> sdRoots() {
        List<File> out = new ArrayList<>();
        File[] dirs = fm.getExternalFilesDirs(null);
        if (dirs == null) return out;
        for (int i = 1; i < dirs.length; i++) {
            if (dirs[i] == null) continue;
            String p = dirs[i].getAbsolutePath();
            int k = p.indexOf("/Android/data");
            if (k > 0) {
                File r = new File(p.substring(0, k));
                if (r.exists()) out.add(r);
            }
        }
        return out;
    }

    boolean isVolumeRoot(File f) {
        if (f.equals(Environment.getExternalStorageDirectory()) || f.equals(appDir())) return true;
        for (File r : sdRoots()) if (r.equals(f)) return true;
        return f.getParentFile() == null;
    }

    void navigate(File dir) {
        if (fm.mode == M_DIR && fm.cur != null && fm.listView != null && !fm.cur.equals(dir)) {
            scrollPos.put(fm.cur.getAbsolutePath(), fm.gridLm.findFirstVisibleItemPosition());
        }
        resetScroll = !dir.equals(fm.cur);
        // choosing files to upload keeps the ticks while moving between folders
        if (!"files".equals(fm.pickMode)) fm.selection.clearSelectionQuiet();
        fm.ui.removeCallbacks(fm.search.searchRun);
        fm.search.suppressSearch = true;
        fm.searchView.setText("");
        fm.search.suppressSearch = false;
        fm.search.query = "";
        fm.mode = M_DIR;
        fm.cur = dir;
        fm.prefs.edit().putString("last", dir.getAbsolutePath()).apply();
        refresh();
    }

    void onBack() {
        if (!fm.selection.selected.isEmpty() && fm.pickMode == null) {
            fm.selection.clearSelection();
            return;
        }
        if (fm.mode != M_DIR || !fm.search.query.isEmpty()) {
            navigate(fm.cur);
            return;
        }
        if (!isVolumeRoot(fm.cur)) {
            File p = fm.cur.getParentFile();
            if (p != null && p.canRead()) {
                navigate(p);
                return;
            }
        }
        fm.finish();
    }

    void refresh() {
        switch (fm.mode) {
            case M_SEARCH:
                fm.search.runSearch();
                break;
            case M_FAV:
                showFavorites();
                break;
            case M_LARGEST:
                showLargest();
                break;
            case M_RECENT:
                showRecent();
                break;
            case M_TRASH:
                showTrash();
                break;
            default:
                load();
                break;
        }
        fm.chrome.buildPlaces();
        fm.chrome.updateChrome();
    }

    Comparator<FmEntry> comparator() {
        final int m = fm.sortMode;
        final boolean d = fm.sortDesc;
        return (a, b) -> {
            if (a.dir != b.dir) return a.dir ? -1 : 1;
            int r;
            switch (m) {
                case SORT_DATE:
                    r = Long.compare(a.mod, b.mod);
                    break;
                case SORT_SIZE:
                    r = Long.compare(a.size, b.size);
                    break;
                case SORT_TYPE:
                    r = a.ext.compareTo(b.ext);
                    if (r == 0) r = a.name.compareToIgnoreCase(b.name);
                    break;
                default:
                    r = a.name.compareToIgnoreCase(b.name);
                    break;
            }
            return d ? -r : r;
        };
    }

    void load() {
        final long my = ++fm.gen;
        final File dir = fm.cur;
        fm.loading(true);
        fm.io.execute(() -> {
            File[] arr = dir.listFiles();
            final boolean denied = arr == null;
            final List<FmEntry> out = new ArrayList<>();
            if (arr != null) {
                for (File f : arr) {
                    if (!fm.showHidden && f.getName().startsWith(".")) continue;
                    out.add(new FmEntry(f));
                }
            }
            Collections.sort(out, comparator());
            fm.post(() -> {
                if (my != fm.gen) return;
                fm.listDenied = denied;
                setShown(out);
                fm.loading(false);
            });
        });
    }

    void showTrash() {
        final long my = ++fm.gen;
        fm.loading(true);
        fm.io.execute(() -> {
            List<Trash.Item> items = Trash.list(fm.getApplicationContext());
            final List<FmEntry> out = new ArrayList<>();
            for (Trash.Item t : items) out.add(new FmEntry(t));
            fm.post(() -> {
                if (my != fm.gen) return;
                fm.listDenied = false;
                setShown(out);
                fm.loading(false);
            });
        });
    }

    void showFavorites() {
        final long my = ++fm.gen;
        fm.loading(true);
        final Set<String> favs = new LinkedHashSet<>(fm.prefs.getStringSet("fav", new LinkedHashSet<String>()));
        fm.io.execute(() -> {
            List<FmEntry> out = new ArrayList<>();
            for (String p : favs) {
                File f = new File(p);
                if (f.exists()) out.add(new FmEntry(f));
            }
            Collections.sort(out, comparator());
            final List<FmEntry> res = out;
            fm.post(() -> {
                if (my != fm.gen) return;
                fm.listDenied = false;
                setShown(res);
                fm.loading(false);
            });
        });
    }

    void showLargest() {
        final long my = ++fm.gen;
        final File dir = fm.cur;
        fm.loading(true);
        fm.io.execute(() -> {
            PriorityQueue<FmEntry> heap = new PriorityQueue<>(MAX_LARGEST + 1, (a, b) -> Long.compare(a.size, b.size));
            int[] budget = {250000};
            walkLargest(dir, heap, budget, my, 0);
            final List<FmEntry> res = new ArrayList<>(heap);
            Collections.sort(res, (a, b) -> Long.compare(b.size, a.size));
            fm.post(() -> {
                if (my != fm.gen) return;
                fm.listDenied = false;
                setShown(res);
                fm.loading(false);
            });
        });
    }

    void walkLargest(File dir, PriorityQueue<FmEntry> heap, int[] budget, long my, int depth) {
        if (my != fm.gen || budget[0] <= 0 || depth > 20) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (my != fm.gen || budget[0] <= 0) return;
            if (!fm.showHidden && f.getName().startsWith(".")) continue;
            budget[0]--;
            if (f.isDirectory()) {
                if (!isLink(f)) walkLargest(f, heap, budget, my, depth + 1);
            } else {
                long len = f.length();
                if (heap.size() < MAX_LARGEST || len > heap.peek().size) {
                    heap.add(new FmEntry(f));
                    if (heap.size() > MAX_LARGEST) heap.poll();
                }
            }
        }
    }

    void showRecent() {
        final long my = ++fm.gen;
        final File dir = fm.cur;
        fm.loading(true);
        fm.io.execute(() -> {
            PriorityQueue<FmEntry> heap = new PriorityQueue<>(MAX_LARGEST + 1, (a, b) -> Long.compare(a.mod, b.mod));
            int[] budget = {250000};
            walkRecent(dir, heap, budget, my, 0);
            final List<FmEntry> res = new ArrayList<>(heap);
            Collections.sort(res, (a, b) -> Long.compare(b.mod, a.mod));
            fm.post(() -> {
                if (my != fm.gen) return;
                fm.listDenied = false;
                setShown(res);
                fm.loading(false);
            });
        });
    }

    void walkRecent(File dir, PriorityQueue<FmEntry> heap, int[] budget, long my, int depth) {
        if (my != fm.gen || budget[0] <= 0 || depth > 20) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (my != fm.gen || budget[0] <= 0) return;
            if (!fm.showHidden && f.getName().startsWith(".")) continue;
            budget[0]--;
            if (f.isDirectory()) {
                if (!isLink(f)) walkRecent(f, heap, budget, my, depth + 1);
            } else {
                long m = f.lastModified();
                if (heap.size() < MAX_LARGEST || m > heap.peek().mod) {
                    heap.add(new FmEntry(f));
                    if (heap.size() > MAX_LARGEST) heap.poll();
                }
            }
        }
    }

    void scrollTo(int pos) {
        fm.gridLm.scrollToPositionWithOffset(Math.max(0, pos), 0);
    }

    void setShown(List<FmEntry> list) {
        final List<FmEntry> old = new ArrayList<>(fm.shown);
        final List<FmEntry> now = new ArrayList<>(list);
        fm.shown.clear();
        fm.shown.addAll(now);
        if (!"files".equals(fm.pickMode)) {
            Set<String> alive = new LinkedHashSet<>();
            for (FmEntry e : fm.shown) alive.add(e.f.getAbsolutePath());
            fm.selection.selected.retainAll(alive);
        }
        String key = fm.mode + "|" + (fm.cur == null ? "" : fm.cur.getAbsolutePath()) + "|" + fm.search.query;
        boolean sameList = key.equals(listKey) && !resetScroll && !old.isEmpty() && !now.isEmpty();
        listKey = key;
        fm.favSnap = null;
        if (sameList) {
            DiffUtil.DiffResult dr = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return old.size();
                }

                @Override
                public int getNewListSize() {
                    return now.size();
                }

                @Override
                public boolean areItemsTheSame(int o, int n) {
                    return old.get(o).f.getAbsolutePath().equals(now.get(n).f.getAbsolutePath());
                }

                @Override
                public boolean areContentsTheSame(int o, int n) {
                    FmEntry a = old.get(o);
                    FmEntry b = now.get(n);
                    return a.name.equals(b.name) && a.size == b.size && a.mod == b.mod && a.kids == b.kids
                            && a.dir == b.dir && a.left == b.left;
                }
            }, false);
            dr.dispatchUpdatesTo(fm.adapter);
        } else {
            fm.adapter.notifyDataSetChanged();
        }
        if (resetScroll) {
            scrollTo(0);
            resetScroll = false;
            if (Motion.enabled()) {
                fm.listView.setLayoutAnimation(android.view.animation.AnimationUtils.loadLayoutAnimation(fm,
                        R.anim.layout_fall_in));
                fm.listView.scheduleLayoutAnimation();
            }
        }
        if (fm.mode == M_DIR && fm.cur != null) {
            Integer sp = scrollPos.remove(fm.cur.getAbsolutePath());
            if (sp != null && !fm.shown.isEmpty()) scrollTo(Math.min(sp, fm.shown.size() - 1));
        }
        fm.chrome.updateChrome();
        boolean empty = fm.shown.isEmpty();
        boolean emptyWas = fm.emptyView.getVisibility() == View.VISIBLE;
        if (empty && !emptyWas) Fx.reveal(fm.emptyView);
        else fm.emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            int msg;
            if (fm.mode == M_SEARCH) msg = R.string.fm_no_results;
            else if (fm.mode == M_FAV) msg = R.string.fm_no_favs;
            else if (fm.mode == M_TRASH) msg = R.string.fm_trash_empty;
            else if (fm.listDenied) msg = R.string.fm_denied;
            else msg = R.string.fm_empty;
            fm.emptyView.setText(msg);
            Fx.emptyState(fm.emptyView, fm.mode == M_SEARCH ? R.drawable.ic_search : fm.mode == M_FAV ? R.drawable.ic_star
                    : fm.mode == M_TRASH ? R.drawable.ic_trash_sweep : R.drawable.ic_folder);
        }
    }

    void applyLayout() {
        int cols = 1;
        int pad = 0;
        if (fm.gridMode) {
            int w = fm.getResources().getDisplayMetrics().widthPixels;
            cols = Math.max(3, w / Ui.dp(fm, 118));
            pad = Ui.dp(fm, 10);
        }
        fm.gridLm.setSpanCount(cols);
        fm.listView.setPadding(pad, fm.listView.getPaddingTop(), pad, fm.listView.getPaddingBottom());
    }

    void setGridMode(boolean on) {
        fm.gridMode = on;
        fm.prefs.edit().putBoolean("grid", on).apply();
        applyLayout();
        fm.adapter.notifyDataSetChanged();
    }

    void openTrash() {
        fm.selection.clearSelectionQuiet();
        fm.mode = M_TRASH;
        refresh();
    }
}

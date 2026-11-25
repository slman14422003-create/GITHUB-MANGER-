package com.ghmanager.app;

import android.content.Intent;
import android.view.MotionEvent;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.ghmanager.app.FmConst.*;
import static com.ghmanager.app.FmFiles.*;

final class FmSelection {
    FileManagerActivity fm;

    FmSelection(FileManagerActivity fm) {
        this.fm = fm;
    }

    final Set<String> selected = new LinkedHashSet<>();

    final List<File> clip = new ArrayList<>();

    boolean clipCut = false;

    boolean dragging;

    int dragAnchor = -1;

    float dragX, dragY;

    int dragScroll;

    final Set<String> dragAdded = new LinkedHashSet<>();

    final Runnable dragRun = new Runnable() {
        @Override
        public void run() {
            if (!dragging || dragScroll == 0) return;
            fm.listView.scrollBy(0, dragScroll);
            View v = fm.listView.findChildViewUnder(dragX, dragY);
            if (v != null) {
                int pos = fm.listView.getChildAdapterPosition(v);
                if (pos != RecyclerView.NO_POSITION) applyDragRange(pos);
            }
            fm.ui.postDelayed(this, 16);
        }
    };

    final RecyclerView.OnItemTouchListener dragTouch = new RecyclerView.OnItemTouchListener() {
        @Override
        public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                endDrag();
                return false;
            }
            if (!dragging) return false;
            if (a == MotionEvent.ACTION_MOVE) {
                dragMove(rv, e);
                return true;
            }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) endDrag();
            return false;
        }

        @Override
        public void onTouchEvent(RecyclerView rv, MotionEvent e) {
            if (!dragging) return;
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_MOVE) dragMove(rv, e);
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) endDrag();
        }

        @Override
        public void onRequestDisallowInterceptTouchEvent(boolean disallow) {
        }
    };

    void startDrag(int pos) {
        if (pos < 0 || pos >= fm.shown.size()) return;
        dragging = true;
        dragAnchor = pos;
        dragAdded.clear();
        dragAdded.add(fm.shown.get(pos).f.getAbsolutePath());
    }

    void endDrag() {
        dragging = false;
        dragScroll = 0;
        dragAnchor = -1;
        dragAdded.clear();
        fm.ui.removeCallbacks(dragRun);
    }

    void dragMove(RecyclerView rv, MotionEvent e) {
        dragX = e.getX();
        dragY = e.getY();
        View v = rv.findChildViewUnder(dragX, dragY);
        if (v != null) {
            int pos = rv.getChildAdapterPosition(v);
            if (pos != RecyclerView.NO_POSITION) applyDragRange(pos);
        }
        int edge = Ui.dp(fm, 72);
        int step = Ui.dp(fm, 14);
        int before = dragScroll;
        if (dragY < edge) dragScroll = -step;
        else if (dragY > rv.getHeight() - edge) dragScroll = step;
        else dragScroll = 0;
        if (dragScroll != 0 && before == 0) fm.ui.post(dragRun);
    }

    void applyDragRange(int pos) {
        if (dragAnchor < 0) return;
        int lo = Math.min(dragAnchor, pos);
        int hi = Math.min(Math.max(dragAnchor, pos), fm.shown.size() - 1);
        boolean filesOnly = "files".equals(fm.pickMode);
        Set<String> now = new LinkedHashSet<>();
        for (int i = lo; i <= hi; i++) {
            FmEntry e = fm.shown.get(i);
            if (filesOnly && e.dir) continue;
            now.add(e.f.getAbsolutePath());
        }
        boolean changedSel = false;
        for (String p : dragAdded) {
            if (!now.contains(p) && selected.remove(p)) changedSel = true;
        }
        for (String p : now) {
            if (selected.add(p)) changedSel = true;
        }
        dragAdded.clear();
        dragAdded.addAll(now);
        if (changedSel) updateSelectionChrome();
    }

    void updateSelectionChrome() {
        if (fm.pickMode != null) updatePickBar();
        boolean sel = !selected.isEmpty() && fm.pickMode == null;
        Fx.show(fm.selBar, sel);
        fm.searchView.setVisibility(sel ? View.GONE : View.VISIBLE);
        if (sel) {
            long total = 0;
            for (FmEntry e : fm.shown) if (!e.dir && selected.contains(e.f.getAbsolutePath())) total += e.size;
            String t = fm.getString(R.string.fm_selected_n, selected.size());
            fm.selCount.setText(total > 0 ? t + " · " + Fmt.size(total) : t);
        }
        fm.changed();
    }

    boolean onItemLong(int pos) {
        if ("folder".equals(fm.pickMode) || "save".equals(fm.pickMode)) return true;
        if (pos < 0 || pos >= fm.shown.size()) return true;
        if ("files".equals(fm.pickMode) && fm.shown.get(pos).dir) return true;
        String path = fm.shown.get(pos).f.getAbsolutePath();
        if (!selected.contains(path)) {
            selected.add(path);
            fm.chrome.updateChrome();
        }
        startDrag(pos);
        return true;
    }

    void onItemClick(int pos) {
        if (pos < 0 || pos >= fm.shown.size()) return;
        if (!selected.isEmpty()) {
            toggleSelect(pos);
            return;
        }
        FmEntry e = fm.shown.get(pos);
        if (e.trashId != null) {
            fm.dialogs.trashItemMenu(e);
            return;
        }
        if (e.dir) {
            fm.listing.navigate(e.f);
        } else if ("files".equals(fm.pickMode)) {
            toggleSelect(pos);
        } else if (fm.pickMode == null) {
            fm.ops.openFile(e);
        }
    }

    void updatePickBar() {
        if (fm.pickMode == null || fm.pickBar == null) return;
        fm.pickBar.setVisibility(View.VISIBLE);
        fm.pickAll.setVisibility("files".equals(fm.pickMode) ? View.VISIBLE : View.GONE);
        if ("files".equals(fm.pickMode)) {
            int n = selected.size();
            fm.pickGo.setEnabled(n > 0);
            fm.pickGo.setAlpha(n > 0 ? 1f : 0.4f);
            fm.pickGo.setText(R.string.pk_upload);
            fm.pickText.setText(n > 0 ? fm.getString(R.string.pk_selected_n, n) : fm.getString(R.string.pk_hint_files));
        } else {
            boolean ok = fm.mode == M_DIR;
            fm.pickGo.setEnabled(ok);
            fm.pickGo.setAlpha(ok ? 1f : 0.4f);
            if ("save".equals(fm.pickMode)) {
                fm.pickGo.setText(R.string.pk_save_here);
                fm.pickText.setText(fm.getString(R.string.pk_save_name,
                        fm.getIntent().getStringExtra("fileName"), fm.cur.getAbsolutePath()));
            } else {
                fm.pickGo.setText(R.string.pk_upload_folder);
                fm.pickText.setText(fm.cur.getAbsolutePath());
            }
        }
    }

    void finishPick() {
        ArrayList<String> paths = new ArrayList<>();
        if ("files".equals(fm.pickMode)) {
            for (File f : selectedFiles()) paths.add(f.getAbsolutePath());
        } else {
            paths.add(fm.cur.getAbsolutePath());
        }
        if (paths.isEmpty()) {
            fm.toast(R.string.pk_nothing);
            return;
        }
        Intent r = new Intent();
        r.putStringArrayListExtra("paths", paths);
        r.putExtra("folder", !"files".equals(fm.pickMode));
        fm.setResult(android.app.Activity.RESULT_OK, r);
        fm.finish();
    }

    void toggleSelect(int pos) {
        if (pos < 0 || pos >= fm.shown.size()) return;
        String p = fm.shown.get(pos).f.getAbsolutePath();
        if (!selected.remove(p)) selected.add(p);
        fm.chrome.updateChrome();
    }

    void selectAll() {
        boolean filesOnly = "files".equals(fm.pickMode);
        int eligible = 0;
        int chosen = 0;
        for (FmEntry e : fm.shown) {
            if (filesOnly && e.dir) continue;
            eligible++;
            if (selected.contains(e.f.getAbsolutePath())) chosen++;
        }
        if (eligible > 0 && chosen == eligible) {
            for (FmEntry e : fm.shown) selected.remove(e.f.getAbsolutePath());
        } else {
            for (FmEntry e : fm.shown) {
                if (filesOnly && e.dir) continue;
                selected.add(e.f.getAbsolutePath());
            }
        }
        fm.chrome.updateChrome();
    }

    void clearSelection() {
        selected.clear();
        fm.chrome.updateChrome();
    }

    void clearSelectionQuiet() {
        selected.clear();
    }

    List<File> selectedFiles() {
        List<File> out = new ArrayList<>();
        if ("files".equals(fm.pickMode)) {
            for (String p : selected) {
                File f = new File(p);
                if (f.isFile()) out.add(f);
            }
            return out;
        }
        for (FmEntry e : fm.shown) if (selected.contains(e.f.getAbsolutePath())) out.add(e.f);
        return out;
    }

    void toClipboard(boolean cut) {
        if (fm.mode == M_TRASH) return;
        List<File> files = selectedFiles();
        if (files.isEmpty()) return;
        clip.clear();
        clip.addAll(files);
        clipCut = cut;
        selected.clear();
        fm.chrome.updateChrome();
        fm.toast(R.string.fm_clip_hint);
    }

    List<FmEntry> selectedEntries() {
        List<FmEntry> out = new ArrayList<>();
        for (FmEntry e : fm.shown) if (selected.contains(e.f.getAbsolutePath())) out.add(e);
        return out;
    }
}

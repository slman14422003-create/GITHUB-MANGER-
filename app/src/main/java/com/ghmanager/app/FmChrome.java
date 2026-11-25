package com.ghmanager.app;

import android.os.Environment;
import android.os.StatFs;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.ghmanager.app.FmConst.*;
import static com.ghmanager.app.FmFiles.*;

final class FmChrome {
    final FileManagerActivity fm;

    FmChrome(FileManagerActivity fm) {
        this.fm = fm;
    }

    String folderLabel(File f) {
        if (f.equals(Environment.getExternalStorageDirectory())) return fm.getString(R.string.fm_internal);
        String n = f.getName();
        return n.isEmpty() ? "/" : n;
    }

    void updateChrome() {
        switch (fm.mode) {
            case M_SEARCH:
                fm.titleView.setText(fm.getString(R.string.fm_search_results, fm.shown.size())
                        + (fm.search.session != null && !fm.search.session.done ? "+" : ""));
                setSubtitle(fm.cur.getAbsolutePath());
                break;
            case M_TRASH:
                fm.titleView.setText(R.string.fm_trash);
                setSubtitle(fm.getString(R.string.fm_trash_hint));
                break;
            case M_FAV:
                fm.titleView.setText(R.string.fm_favorites);
                setSubtitle(null);
                break;
            case M_LARGEST:
                fm.titleView.setText(R.string.fm_largest);
                setSubtitle(fm.cur.getAbsolutePath());
                break;
            case M_RECENT:
                fm.titleView.setText(R.string.fm_recent);
                setSubtitle(fm.cur.getAbsolutePath());
                break;
            default:
                fm.titleView.setText(folderLabel(fm.cur));
                setSubtitle(fm.cur.getAbsolutePath());
                break;
        }
        boolean dirMode = fm.mode == M_DIR;
        fm.crumbScroll.setVisibility(dirMode ? View.VISIBLE : View.GONE);
        fm.storageCard.setVisibility(dirMode ? View.VISIBLE : View.GONE);
        if (dirMode) {
            buildCrumbs();
            updateStorage();
        }
        if (fm.pickMode != null) {
            fm.titleView.setText("files".equals(fm.pickMode) ? fm.getString(R.string.pk_title_files)
                    : "save".equals(fm.pickMode) ? fm.getString(R.string.pk_title_save)
                    : fm.getString(R.string.pk_title_folder));
            fm.selection.updatePickBar();
        }
        boolean sel = !fm.selection.selected.isEmpty() && fm.pickMode == null;
        Fx.show(fm.selBar, sel);
        fm.searchView.setVisibility(sel ? View.GONE : View.VISIBLE);
        if (sel) {
            long total = 0;
            for (FmEntry e : fm.shown) if (!e.dir && fm.selection.selected.contains(e.f.getAbsolutePath())) total += e.size;
            String t = fm.getString(R.string.fm_selected_n, fm.selection.selected.size());
            fm.selCount.setText(total > 0 ? t + " · " + Fmt.size(total) : t);
        }
        updatePermBanner();
        updatePasteBar();
        fm.changed();
    }

    void setSubtitle(String s) {
        if (s == null || s.isEmpty()) {
            fm.subtitleView.setVisibility(View.GONE);
        } else {
            fm.subtitleView.setText(s);
            fm.subtitleView.setVisibility(View.VISIBLE);
        }
    }

    void updatePermBanner() {
        boolean need = !Perms.hasAllFiles(fm) && !isInside(fm.cur, fm.listing.appDir());
        fm.permBanner.setVisibility(need ? View.VISIBLE : View.GONE);
    }

    void updatePasteBar() {
        boolean show = !fm.selection.clip.isEmpty() && fm.selection.selected.isEmpty();
        Fx.show(fm.pasteBar, show);
        if (show) {
            fm.pasteText.setText(fm.getString(fm.selection.clipCut ? R.string.fm_clip_cut_n : R.string.fm_clip_copy_n, fm.selection.clip.size()));
            fm.findViewById(R.id.pasteGo).setEnabled(fm.mode == M_DIR);
        }
    }

    void buildCrumbs() {
        fm.crumbRow.removeAllViews();
        File internal = Environment.getExternalStorageDirectory();
        List<File> chain = new ArrayList<>();
        File f = fm.cur;
        while (f != null) {
            chain.add(0, f);
            if (f.equals(internal)) break;
            f = f.getParentFile();
        }
        for (int i = 0; i < chain.size(); i++) {
            final File target = chain.get(i);
            if (i > 0) {
                TextView sep = new TextView(fm);
                sep.setText("›");
                sep.setTextColor(Ui.color(fm, R.color.text_hint));
                sep.setTextSize(16);
                sep.setPadding(Ui.dp(fm, 4), 0, Ui.dp(fm, 4), 0);
                fm.crumbRow.addView(sep);
            }
            TextView c = new TextView(fm);
            String label = target.equals(internal) ? fm.getString(R.string.fm_internal)
                    : (target.getName().isEmpty() ? "/" : target.getName());
            c.setText(label);
            c.setSingleLine(true);
            c.setTextSize(13);
            boolean last = i == chain.size() - 1;
            c.setTextColor(Ui.color(fm, last ? R.color.text_primary : R.color.accent_text));
            c.setPadding(Ui.dp(fm, 8), Ui.dp(fm, 6), Ui.dp(fm, 8), Ui.dp(fm, 6));
            if (!last) c.setOnClickListener(v -> fm.listing.navigate(target));
            fm.crumbRow.addView(c);
        }
        fm.crumbScroll.post(() -> ((HorizontalScrollView) fm.crumbScroll).fullScroll(View.FOCUS_RIGHT));
    }

    void updateStorage() {
        fm.storageBarHolder.removeAllViews();
        try {
            File probe = fm.cur.exists() ? fm.cur : Environment.getExternalStorageDirectory();
            StatFs st = new StatFs(probe.getAbsolutePath());
            long total = st.getTotalBytes();
            long free = st.getAvailableBytes();
            long used = Math.max(0, total - free);
            if (total <= 0) throw new IllegalStateException();
            double pct = used * 100.0 / total;
            fm.storageText.setText(fm.getString(R.string.fm_storage_line, Fmt.size(used), Fmt.size(total), Fmt.size(free)));
            fm.storageBarHolder.addView(Ui.bar(fm, pct, pct > 90 ? R.color.bad : R.color.accent));
            fm.storageCard.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            fm.storageCard.setVisibility(View.GONE);
        }
    }

    void buildPlaces() {
        fm.placesRow.removeAllViews();
        // first chip: the one folder where every download of the app lands (Download/GitHubManager/<repo>)
        File gh = TransferTasks.rootDir();
        if (!gh.exists() && Perms.hasAllFiles(fm)) //noinspection ResultOfMethodCallIgnored
            gh.mkdirs();
        addPlace(fm.getString(R.string.fm_github), gh);
        addPlace(fm.getString(R.string.fm_internal), Environment.getExternalStorageDirectory());
        addPlace(fm.getString(R.string.fm_downloads), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        addPlace(fm.getString(R.string.fm_dcim), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM));
        addPlace(fm.getString(R.string.fm_pictures), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES));
        addPlace(fm.getString(R.string.fm_documents), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS));
        addPlace(fm.getString(R.string.fm_music), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC));
        addPlace(fm.getString(R.string.fm_movies), Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES));
        int n = 1;
        for (File sd : fm.listing.sdRoots()) {
            addPlace(fm.getString(R.string.fm_sdcard) + (n > 1 ? " " + n : ""), sd);
            n++;
        }
        addPlace(fm.getString(R.string.fm_app_folder), fm.listing.appDir());

        TextView fav = Ui.chip(fm, fm.getString(R.string.fm_favorites), fm.mode == M_FAV);
        fav.setOnClickListener(v -> {
            fm.selection.clearSelectionQuiet();
            fm.mode = M_FAV;
            fm.listing.refresh();
        });
        fm.placesRow.addView(fav);

        TextView big = Ui.chip(fm, fm.getString(R.string.fm_largest), fm.mode == M_LARGEST);
        big.setOnClickListener(v -> {
            fm.selection.clearSelectionQuiet();
            fm.mode = M_LARGEST;
            fm.listing.refresh();
        });
        fm.placesRow.addView(big);

        TextView recent = Ui.chip(fm, fm.getString(R.string.fm_recent), fm.mode == M_RECENT);
        recent.setOnClickListener(v -> {
            fm.selection.clearSelectionQuiet();
            fm.mode = M_RECENT;
            fm.listing.refresh();
        });
        fm.placesRow.addView(recent);

        TextView trash = Ui.chip(fm, fm.getString(R.string.fm_trash), fm.mode == M_TRASH);
        trash.setOnClickListener(v -> fm.listing.openTrash());
        fm.placesRow.addView(trash);
    }

    void addPlace(String label, final File dir) {
        if (dir == null || !dir.exists()) return;
        TextView c = Ui.chip(fm, label, fm.mode == M_DIR && fm.cur.equals(dir));
        c.setOnClickListener(v -> fm.listing.navigate(dir));
        fm.placesRow.addView(c);
    }
}

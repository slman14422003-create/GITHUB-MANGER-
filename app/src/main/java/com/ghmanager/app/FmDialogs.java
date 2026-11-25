package com.ghmanager.app;

import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.appcompat.app.AlertDialog;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.ghmanager.app.FmConst.*;
import static com.ghmanager.app.FmFiles.*;

final class FmDialogs {
    FileManagerActivity fm;

    FmDialogs(FileManagerActivity fm) {
        this.fm = fm;
    }

    void badgesMenu() {
        if (fm.badges.badgesOn()) {
            String[] items = {fm.getString(R.string.fm_badges_refresh), fm.getString(R.string.fm_badges_change),
                    fm.getString(R.string.fm_badges_off)};
            new Dlg(fm).setTitle(fm.getString(R.string.fm_badges_title) + " · " + fm.badges.badgeRepo)
                    .setItems(items, (d, which) -> {
                        if (which == 0) {
                            fm.badges.reloadRemote();
                        } else if (which == 1) {
                            pickBadgeRepo();
                        } else {
                            fm.prefs.edit().remove("bd_repo").remove("bd_branch").remove("bd_root").apply();
                            fm.badges.badgeRepo = "";
                            fm.badges.badgeBranch = "";
                            fm.badges.badgeRoot = "";
                            fm.badges.remoteShas = null;
                            fm.badges.badgeCache.clear();
                            fm.changed();
                        }
                    }).show();
        } else {
            pickBadgeRepo();
        }
    }

    void pickBadgeRepo() {
        JSONArray repos = RepoCache.load(fm);
        if (repos == null || repos.length() == 0 || Store.getToken(fm) == null) {
            fm.toast(R.string.fm_gh_no_repos);
            return;
        }
        final List<JSONObject> list = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < repos.length(); i++) {
            JSONObject r = repos.optJSONObject(i);
            if (r == null || r.optString("full_name").indexOf('/') <= 0) continue;
            list.add(r);
            names.add(r.optString("full_name"));
        }
        if (list.isEmpty()) {
            fm.toast(R.string.fm_gh_no_repos);
            return;
        }
        new Dlg(fm).setTitle(R.string.fm_gh_pick_repo)
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    final JSONObject r = list.get(which);
                    final String full = r.optString("full_name");
                    String def = r.optString("default_branch", "main");
                    if (def.isEmpty() || "null".equals(def)) def = "main";
                    final String saved = fm.prefs.getString("up_branch:" + full, "");
                    final String branch = saved.isEmpty() ? def : saved;
                    final File root = fm.cur;
                    new Dlg(fm).setTitle(R.string.fm_badges_title)
                            .setMessage(fm.getString(R.string.fm_badges_confirm, root.getAbsolutePath(), full, branch))
                            .setPositiveButton(R.string.ok, (d2, w) -> {
                                fm.prefs.edit().putString("bd_repo", full).putString("bd_branch", branch)
                                        .putString("bd_root", root.getAbsolutePath()).apply();
                                fm.badges.loadBadgePrefs();
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                }).show();
    }

    void zipMenu(final File zip) {
        String[] items = {fm.getString(R.string.fm_zip_view), fm.getString(R.string.fm_extract_here),
                fm.getString(R.string.fm_extract_folder), fm.getString(R.string.fm_open_with)};
        new Dlg(fm).setTitle(zip.getName()).setItems(items, (d, which) -> {
            if (which == 0) fm.ops.listZip(zip);
            else if (which == 1) fm.ops.extract(zip, false);
            else if (which == 2) fm.ops.extract(zip, true);
            else fm.ops.openExternal(zip, true);
        }).show();
    }

    void info(String title, String msg) {
        new Dlg(fm).setTitle(title).setMessage(msg)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    void sortMenu() {
        String[] names = {fm.getString(R.string.fm_sort_name), fm.getString(R.string.fm_sort_date),
                fm.getString(R.string.fm_sort_size), fm.getString(R.string.fm_sort_type)};
        List<String> items = new ArrayList<>();
        for (int i = 0; i < names.length; i++) items.add((i == fm.sortMode ? "● " : "○ ") + names[i]);
        items.add(fm.getString(fm.sortDesc ? R.string.fm_order_desc : R.string.fm_order_asc));
        items.add(fm.getString(fm.showHidden ? R.string.fm_hide_hidden : R.string.fm_show_hidden));
        items.add(fm.getString(fm.gridMode ? R.string.fm_view_list : R.string.fm_view_grid));
        items.add(fm.getString(R.string.fm_trash));
        items.add(fm.getString(fm.badges.badgesOn() ? R.string.fm_badges_on : R.string.fm_badges_menu));
        items.add(fm.getString(fm.search.useIndex() ? R.string.fm_index_off : R.string.fm_index_on));
        long it = FmIndex.time(fm);
        items.add(fm.getString(R.string.fm_index_build) + (it > 0 ? " · " + fm.dateFmt.format(new Date(it)) : ""));
        new Dlg(fm).setTitle(R.string.fm_sort)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which == 6) {
                        fm.listing.setGridMode(!fm.gridMode);
                        return;
                    }
                    if (which == 7) {
                        fm.listing.openTrash();
                        return;
                    }
                    if (which == 8) {
                        badgesMenu();
                        return;
                    }
                    if (which == 9) {
                        if (fm.search.useIndex()) {
                            fm.prefs.edit().putBoolean("use_index", false).apply();
                            fm.toast(R.string.fm_index_disabled);
                        } else if (FmIndex.root(fm).isEmpty()) {
                            fm.search.buildIndex();
                        } else {
                            fm.prefs.edit().putBoolean("use_index", true).apply();
                            fm.toast(R.string.fm_index_enabled);
                        }
                        return;
                    }
                    if (which == 10) {
                        fm.search.buildIndex();
                        return;
                    }
                    if (which < 4) {
                        fm.sortMode = which;
                    } else if (which == 4) {
                        fm.sortDesc = !fm.sortDesc;
                    } else {
                        fm.showHidden = !fm.showHidden;
                    }
                    fm.prefs.edit().putInt("sort", fm.sortMode).putBoolean("desc", fm.sortDesc)
                            .putBoolean("hidden", fm.showHidden).apply();
                    fm.listing.refresh();
                }).show();
    }

    void newMenu() {
        if (fm.mode == M_TRASH) {
            emptyTrashDialog();
            return;
        }
        String[] items = {fm.getString(R.string.fm_new_folder), fm.getString(R.string.fm_new_file),
                fm.getString(R.string.fm_import_files)};
        new Dlg(fm).setTitle(R.string.fm_new).setItems(items, (d, which) -> {
            if (fm.mode != M_DIR) {
                fm.toast(R.string.fm_open_folder_first);
                return;
            }
            if (which == 0) newItemDialog(true);
            else if (which == 1) newItemDialog(false);
            else fm.importLauncher.launch("*/*");
        }).show();
    }

    void newItemDialog(final boolean folder) {
        LinearLayout box = Ui.box(fm);
        final EditText name = Ui.edit(fm, fm.getString(R.string.fm_name_hint), null);
        box.addView(name);
        new Dlg(fm)
                .setTitle(folder ? R.string.fm_new_folder : R.string.fm_new_file)
                .setView(box)
                .setPositiveButton(R.string.create, (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (!validName(n)) {
                        fm.toast(R.string.fm_bad_name);
                        Dlg.stay(d);
                        return;
                    }
                    File t = new File(fm.cur, n);
                    if (t.exists()) {
                        fm.toast(R.string.fm_exists);
                        Dlg.stay(d);
                        return;
                    }
                    boolean ok;
                    try {
                        ok = folder ? t.mkdirs() : t.createNewFile();
                    } catch (IOException ex) {
                        ok = false;
                    }
                    if (!ok) fm.toast(R.string.fm_create_failed);
                    fm.listing.refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Actions for one row, opened from its "more" button (no need to know about long-press). */
    void itemMenu(final FmEntry e) {
        if (fm.pickMode != null) return;
        if (e.trashId != null) {
            trashItemMenu(e);
            return;
        }
        final File f = e.f;
        final List<File> one = new ArrayList<>();
        one.add(f);
        final boolean isZip = !e.dir && e.ext.equals("zip");
        final boolean fav = fm.ops.favs().contains(f.getAbsolutePath());
        final List<String> labels = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        if (!e.dir) add(labels, ids, fm.getString(R.string.fm_open), 0);
        add(labels, ids, fm.getString(R.string.fm_select), 1);
        add(labels, ids, fm.getString(R.string.fm_copy), 2);
        add(labels, ids, fm.getString(R.string.fm_move), 3);
        add(labels, ids, fm.getString(R.string.fm_rename), 4);
        add(labels, ids, fm.getString(R.string.fm_details), 5);
        if (!e.dir) add(labels, ids, fm.getString(R.string.share), 6);
        add(labels, ids, fm.getString(R.string.fm_compress), 7);
        if (isZip) {
            add(labels, ids, fm.getString(R.string.fm_extract_here), 8);
            add(labels, ids, fm.getString(R.string.fm_extract_folder), 9);
        }
        add(labels, ids, fm.getString(fav ? R.string.fm_unfavorite : R.string.fm_favorite), 10);
        add(labels, ids, fm.getString(R.string.fm_copy_path), 11);
        add(labels, ids, fm.getString(R.string.fm_gh_upload), 13);
        add(labels, ids, fm.getString(R.string.delete), 12);
        new Dlg(fm).setTitle(e.name)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    switch (ids.get(which)) {
                        case 0:
                            fm.ops.openFile(e);
                            break;
                        case 1:
                            fm.selection.selected.add(f.getAbsolutePath());
                            fm.chrome.updateChrome();
                            break;
                        case 2:
                        case 3:
                            fm.selection.selected.clear();
                            fm.selection.selected.add(f.getAbsolutePath());
                            fm.selection.toClipboard(ids.get(which) == 3);
                            break;
                        case 4:
                            renameDialog(f);
                            break;
                        case 5:
                            showDetails(one);
                            break;
                        case 6:
                            fm.ops.share(one);
                            break;
                        case 7:
                            compress(one);
                            break;
                        case 8:
                            fm.ops.extract(f, false);
                            break;
                        case 9:
                            fm.ops.extract(f, true);
                            break;
                        case 10:
                            fm.ops.toggleFav(one, fav);
                            break;
                        case 11:
                            fm.ops.copyText(f.getAbsolutePath());
                            break;
                        case 13:
                            uploadToGithub(one);
                            break;
                        default:
                            confirmDelete(one);
                            break;
                    }
                }).show();
    }

    void moreMenu() {
        if (fm.mode == M_TRASH) {
            trashSelMenu();
            return;
        }
        final List<File> files = fm.selection.selectedFiles();
        if (files.isEmpty()) return;
        final boolean single = files.size() == 1;
        final File one = files.get(0);
        final boolean isZip = single && !one.isDirectory() && (extOf(one.getName()).equals("zip"));
        final boolean anyFile = hasFile(files);
        final boolean allFav = fm.ops.favs().containsAll(paths(files));

        final List<String> labels = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        if (single && !one.isDirectory()) add(labels, ids, fm.getString(R.string.fm_open_with), 0);
        if (single) add(labels, ids, fm.getString(R.string.fm_rename), 1);
        add(labels, ids, fm.getString(R.string.fm_details), 2);
        if (anyFile) add(labels, ids, fm.getString(R.string.share), 3);
        add(labels, ids, fm.getString(R.string.fm_compress), 4);
        if (isZip) {
            add(labels, ids, fm.getString(R.string.fm_extract_here), 5);
            add(labels, ids, fm.getString(R.string.fm_extract_folder), 6);
        }
        add(labels, ids, fm.getString(allFav ? R.string.fm_unfavorite : R.string.fm_favorite), 7);
        if (single) add(labels, ids, fm.getString(R.string.fm_copy_path), 8);
        add(labels, ids, fm.getString(R.string.fm_gh_upload), 13);

        new Dlg(fm).setTitle(fm.getString(R.string.fm_selected_n, files.size()))
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    switch (ids.get(which)) {
                        case 0:
                            fm.ops.openExternal(one, true);
                            break;
                        case 1:
                            renameDialog(one);
                            break;
                        case 2:
                            showDetails(files);
                            break;
                        case 3:
                            fm.ops.share(files);
                            break;
                        case 4:
                            compress(files);
                            break;
                        case 5:
                            fm.ops.extract(one, false);
                            break;
                        case 6:
                            fm.ops.extract(one, true);
                            break;
                        case 7:
                            fm.ops.toggleFav(files, allFav);
                            break;
                        case 13:
                            uploadToGithub(files);
                            break;
                        default:
                            fm.ops.copyText(one.getAbsolutePath());
                            break;
                    }
                }).show();
    }

    void uploadToGithub(final List<File> files) {
        if (files.isEmpty() || fm.mode == M_TRASH) return;
        JSONArray repos = RepoCache.load(fm);
        if (repos == null || repos.length() == 0 || Store.getToken(fm) == null) {
            fm.toast(R.string.fm_gh_no_repos);
            return;
        }
        final List<JSONObject> list = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < repos.length(); i++) {
            JSONObject r = repos.optJSONObject(i);
            if (r == null || r.optString("full_name").indexOf('/') <= 0) continue;
            list.add(r);
            names.add(r.optString("full_name"));
        }
        if (list.isEmpty()) {
            fm.toast(R.string.fm_gh_no_repos);
            return;
        }
        final String lastRepo = fm.prefs.getString("up_repo", "");
        int lastAt = names.indexOf(lastRepo);
        if (lastAt > 0) {
            list.add(0, list.remove(lastAt));
            names.add(0, names.remove(lastAt));
        }
        new Dlg(fm).setTitle(R.string.fm_gh_pick_repo)
                .setItems(names.toArray(new String[0]), (d, which) -> uploadDialog(files, list.get(which)))
                .show();
    }

    void uploadDialog(final List<File> files, JSONObject r) {
        final String full = r.optString("full_name");
        final int slash = full.indexOf('/');
        final String owner = full.substring(0, slash);
        final String name = full.substring(slash + 1);
        String def = r.optString("default_branch", "main");
        if (def.isEmpty() || "null".equals(def)) def = "main";
        String savedBranch = fm.prefs.getString("up_branch:" + full, "");
        if (!savedBranch.isEmpty()) def = savedBranch;
        String savedTarget = fm.prefs.getString("up_target:" + full, "");

        boolean anyDir = false;
        for (File f : files) if (f.isDirectory()) anyDir = true;

        LinearLayout box = Ui.box(fm);
        final EditText branch = Ui.edit(fm, fm.getString(R.string.branch_label), def);
        branch.setTextDirection(View.TEXT_DIRECTION_LTR);
        final EditText target = Ui.edit(fm, fm.getString(R.string.target_folder), savedTarget);
        target.setTextDirection(View.TEXT_DIRECTION_LTR);
        final EditText msg = Ui.edit(fm, fm.getString(R.string.default_commit), fm.getString(R.string.default_commit));
        final CheckBox includeRoot = Ui.check(fm, R.string.include_root_folder, true);
        final CheckBox skipJunk = Ui.check(fm, R.string.upload_skip_junk, false);
        box.addView(Ui.field(fm, fm.getString(R.string.branch_label), branch));
        box.addView(Ui.field(fm, fm.getString(R.string.target_folder), target));
        box.addView(Ui.field(fm, fm.getString(R.string.commit_message), msg));
        if (anyDir) {
            box.addView(includeRoot);
            box.addView(skipJunk);
        }
        final boolean hasDir = anyDir;

        new Dlg(fm)
                .setTitle(full)
                .setView(box)
                .setPositiveButton(R.string.upload, (d, w) -> {
                    String b = branch.getText().toString().trim();
                    if (b.isEmpty()) b = "main";
                    String base = target.getText().toString().trim().replaceAll("^/+|/+$", "");
                    String m = msg.getText().toString().trim();
                    if (m.isEmpty()) m = fm.getString(R.string.default_commit);
                    fm.prefs.edit().putString("up_repo", full).putString("up_branch:" + full, b)
                            .putString("up_target:" + full, base).apply();
                    fm.ops.previewThenUpload(new GitHubApi(fm), owner, name, b, base, m, new ArrayList<>(files),
                            !hasDir || includeRoot.isChecked(), hasDir && skipJunk.isChecked());
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    void showPreview(TransferTasks.Preview p, final Runnable go) {
        StringBuilder sb = new StringBuilder();
        sb.append(fm.getString(R.string.fm_pv_files, p.files, Fmt.size(p.bytes))).append('\n');
        sb.append(fm.getString(R.string.fm_pv_states, p.fresh, p.changed, p.same)).append('\n');
        sb.append(fm.getString(R.string.fm_pv_upload, p.fresh + p.changed, Fmt.size(p.uploadBytes)));
        if (p.ignored > 0 || p.tooBig > 0) {
            sb.append('\n').append(fm.getString(R.string.fm_pv_skipped, p.ignored, p.tooBig));
        }
        Dlg d = new Dlg(fm);
        d.setTitle(R.string.fm_pv_title);
        d.setMessage(sb.toString());
        if (p.fresh + p.changed > 0) {
            d.setPositiveButton(R.string.upload, (x, w) -> go.run());
            d.setNegativeButton(R.string.cancel, null);
        } else {
            d.setPositiveButton(R.string.close, null);
        }
        d.show();
    }

    void error(Exception e) {
        error(e, null);
    }

    void error(Exception e, Runnable retry) {
        if (e instanceof FmCancel) {
            fm.toast(R.string.fm_cancelled);
            return;
        }
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        Dlg.error(fm, fm.getString(R.string.error), m, e, retry);
    }

    void confirmDelete(final List<File> files) {
        if (files.isEmpty() || fm.mode == M_TRASH) return;
        String msg = files.size() == 1
                ? fm.getString(R.string.fm_trash_msg, files.get(0).getName())
                : fm.getString(R.string.fm_trash_msg_n, files.size());
        LinearLayout box = Ui.box(fm);
        box.addView(Ui.body(fm, msg, 15, R.color.text_secondary));
        final CheckBox perm = Ui.check(fm, R.string.fm_delete_forever, false);
        box.addView(perm);
        new Dlg(fm).setTitle(R.string.delete_title).setView(box)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    final boolean forever = perm.isChecked();
                    fm.selection.selected.clear();
                    if (forever) {
                        fm.ops.runTask(fm.getString(R.string.fm_deleting), () -> {
                            for (File f : files) fm.ops.deleteTree(f);
                            Set<String> fv = fm.ops.favs();
                            if (fv.removeAll(paths(files))) fm.prefs.edit().putStringSet("fav", fv).apply();
                            return fm.getString(R.string.fm_done_n, files.size());
                        });
                    } else {
                        fm.ops.trashFiles(files);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    void trashItemMenu(final FmEntry e) {
        String[] items = {fm.getString(R.string.fm_restore), fm.getString(R.string.fm_delete_forever),
                fm.getString(R.string.fm_select)};
        new Dlg(fm).setTitle(e.name).setItems(items, (d, which) -> {
            final List<FmEntry> one = new ArrayList<>();
            one.add(e);
            if (which == 0) {
                fm.ops.restoreEntries(one);
            } else if (which == 1) {
                foreverEntries(one);
            } else {
                fm.selection.selected.add(e.f.getAbsolutePath());
                fm.chrome.updateChrome();
            }
        }).show();
    }

    void trashSelMenu() {
        final List<FmEntry> es = fm.selection.selectedEntries();
        if (es.isEmpty()) return;
        String[] items = {fm.getString(R.string.fm_restore), fm.getString(R.string.fm_delete_forever)};
        new Dlg(fm).setTitle(fm.getString(R.string.fm_selected_n, es.size())).setItems(items, (d, which) -> {
            if (which == 0) fm.ops.restoreEntries(es);
            else foreverEntries(es);
        }).show();
    }

    void foreverEntries(List<FmEntry> es) {
        final List<String> ids = new ArrayList<>();
        for (FmEntry e : es) if (e.trashId != null) ids.add(e.trashId);
        if (ids.isEmpty()) return;
        new Dlg(fm).setTitle(R.string.delete_title)
                .setMessage(fm.getString(R.string.fm_forever_msg, ids.size()))
                .setPositiveButton(R.string.delete, (d, w) -> {
                    fm.selection.selected.clear();
                    fm.ops.runTask(fm.getString(R.string.fm_deleting), () -> {
                        int n = 0;
                        for (String id : ids) {
                            Trash.Item it = Trash.find(fm.getApplicationContext(), id);
                            if (it != null) {
                                Trash.remove(fm.getApplicationContext(), it);
                                n++;
                            }
                        }
                        return fm.getString(R.string.fm_done_n, n);
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    void emptyTrashDialog() {
        new Dlg(fm).setTitle(R.string.fm_trash)
                .setMessage(fm.getString(R.string.fm_empty_trash_msg))
                .setPositiveButton(R.string.fm_empty_trash, (d, w) -> fm.ops.runTask(fm.getString(R.string.fm_deleting), () -> {
                    int n = Trash.empty(fm.getApplicationContext());
                    return fm.getString(R.string.fm_done_n, n);
                }))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    void renameDialog(final File f) {
        LinearLayout box = Ui.box(fm);
        final EditText name = Ui.edit(fm, fm.getString(R.string.fm_name_hint), f.getName());
        box.addView(name);
        new Dlg(fm).setTitle(R.string.fm_rename).setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (!validName(n)) {
                        fm.toast(R.string.fm_bad_name);
                        Dlg.stay(d);
                        return;
                    }
                    if (n.equals(f.getName())) return;
                    File t = new File(f.getParentFile(), n);
                    if (t.exists()) {
                        fm.toast(R.string.fm_exists);
                        Dlg.stay(d);
                        return;
                    }
                    if (!f.renameTo(t)) fm.toast(R.string.fm_rename_failed);
                    fm.selection.clearSelection();
                    fm.listing.refresh();
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    void compress(final List<File> files) {
        String def = files.size() == 1 ? stripExt(files.get(0).getName()) : fm.getString(R.string.fm_archive_name);
        LinearLayout box = Ui.box(fm);
        final EditText name = Ui.edit(fm, fm.getString(R.string.fm_name_hint), def + ".zip");
        box.addView(name);
        new Dlg(fm).setTitle(R.string.fm_compress).setView(box)
                .setPositiveButton(R.string.create, (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (!validName(n)) {
                        fm.toast(R.string.fm_bad_name);
                        Dlg.stay(d);
                        return;
                    }
                    if (!n.toLowerCase(Locale.ROOT).endsWith(".zip")) n = n + ".zip";
                    File parent = files.get(0).getParentFile();
                    if (parent == null) parent = fm.cur;
                    fm.ops.doCompress(files, unique(parent, n));
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    void showDetails(final List<File> files) {
        fm.ops.showBusy(fm.getString(R.string.fm_calculating));
        fm.io.execute(() -> {
            long[] tot = new long[3]; // size, files, folders
            for (File f : files) fm.ops.sizeOf(f, tot);
            String hash = null;
            if (files.size() == 1 && files.get(0).isFile() && files.get(0).length() <= 200L * 1024 * 1024) {
                hash = fm.ops.sha256(files.get(0));
            }
            final StringBuilder sb = new StringBuilder();
            if (files.size() == 1) {
                File f = files.get(0);
                sb.append(fm.getString(R.string.fm_d_path)).append(":\n").append(f.getAbsolutePath()).append("\n\n");
                sb.append(fm.getString(R.string.fm_d_type)).append(": ")
                        .append(f.isDirectory() ? fm.getString(R.string.fm_folder) : mimeOf(f)).append('\n');
                sb.append(fm.getString(R.string.fm_d_modified)).append(": ")
                        .append(fm.dateFmt.format(new Date(f.lastModified()))).append('\n');
                sb.append(fm.getString(R.string.fm_d_access)).append(": ")
                        .append(f.canRead() ? "r" : "-").append(f.canWrite() ? "w" : "-")
                        .append(f.canExecute() ? "x" : "-").append('\n');
            } else {
                sb.append(fm.getString(R.string.fm_selected_n, files.size())).append('\n');
            }
            sb.append(fm.getString(R.string.fm_d_size)).append(": ").append(Fmt.size(tot[0]))
                    .append(" (").append(String.format(Locale.US, "%,d", tot[0])).append(" B)\n");
            if (files.size() > 1 || files.get(0).isDirectory()) {
                sb.append(fm.getString(R.string.fm_d_contents)).append(": ")
                        .append(fm.getString(R.string.fm_d_contents_v, tot[1], tot[2])).append('\n');
            }
            if (hash != null) sb.append("\nSHA-256:\n").append(hash);
            final String title = files.size() == 1 ? files.get(0).getName() : fm.getString(R.string.fm_details);
            final String copy = files.size() == 1 ? files.get(0).getAbsolutePath() : null;
            fm.post(() -> {
                fm.ops.hideBusy();
                AlertDialog.Builder b = new Dlg(fm).setTitle(title)
                        .setMessage(sb.toString()).setPositiveButton(android.R.string.ok, null);
                if (copy != null) b.setNeutralButton(R.string.fm_copy_path, (d, w) -> fm.ops.copyText(copy));
                b.show();
            });
        });
    }
}

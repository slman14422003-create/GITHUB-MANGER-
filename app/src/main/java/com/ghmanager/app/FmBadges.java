package com.ghmanager.app;

import android.graphics.drawable.GradientDrawable;
import android.view.View;
import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.ghmanager.app.FmFiles.*;

final class FmBadges {
    FileManagerActivity fm;

    FmBadges(FileManagerActivity fm) {
        this.fm = fm;
    }

    String badgeRepo = "";

    String badgeBranch = "";

    String badgeRoot = "";

    volatile java.util.Map<String, String> remoteShas;

    volatile int badgeVer;

    final java.util.concurrent.ConcurrentHashMap<String, Integer> badgeCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    final Set<String> badgePending = Collections.synchronizedSet(new java.util.HashSet<String>());

    final ExecutorService badgeIo = Executors.newSingleThreadExecutor();

    final Runnable badgeRefresh = () -> fm.changed();

    final Transfers.Listener finishListener = job -> {
        if (badgesOn() && job.tag != null && job.tag.equals("upload:" + badgeRepo)) reloadRemote();
    };

    boolean badgesOn() {
        return !badgeRepo.isEmpty() && !badgeRoot.isEmpty();
    }

    String relPath(File f) {
        String ap = f.getAbsolutePath();
        String root = badgeRoot;
        if (root.isEmpty() || !ap.startsWith(root + "/")) return null;
        return ap.substring(root.length() + 1);
    }

    int badgeStatus(FmEntry e) {
        final java.util.Map<String, String> remote = remoteShas;
        if (remote == null) return -1;
        final String rel = relPath(e.f);
        if (rel == null) return -1;
        final String key = e.f.getAbsolutePath() + "|" + e.mod + "|" + e.size;
        Integer c = badgeCache.get(key);
        if (c != null) return c;
        if (e.size > 25L * 1024 * 1024) return -1;
        if (badgePending.add(key)) {
            final int ver = badgeVer;
            final File f = e.f;
            badgeIo.execute(() -> {
                String local = TransferTasks.gitSha(f);
                String r = remote.get(rel);
                int st = r == null ? 0 : (r.equals(local) ? 2 : 1);
                if (local != null && ver == badgeVer) badgeCache.put(key, st);
                badgePending.remove(key);
                fm.ui.removeCallbacks(badgeRefresh);
                fm.ui.postDelayed(badgeRefresh, 250);
            });
        }
        return -1;
    }

    void bindBadge(FmAdapter.VH h, FmEntry e) {
        if (h.badge == null) return;
        if (!badgesOn() || e.dir || e.trashId != null) {
            h.badge.setVisibility(View.GONE);
            return;
        }
        int st = badgeStatus(e);
        if (st < 0) {
            h.badge.setVisibility(View.GONE);
            return;
        }
        int colorRes = st == 2 ? R.color.ok : st == 1 ? R.color.warn : R.color.info;
        int c = Ui.color(fm, colorRes);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(fm, 10));
        g.setColor((c & 0x00FFFFFF) | 0x26000000);
        h.badge.setBackground(g);
        h.badge.setTextColor(c);
        h.badge.setText(st == 2 ? R.string.fm_b_up : st == 1 ? R.string.fm_b_changed : R.string.fm_b_new);
        h.badge.setVisibility(View.VISIBLE);
    }

    void loadBadgePrefs() {
        badgeRepo = fm.prefs.getString("bd_repo", "");
        badgeBranch = fm.prefs.getString("bd_branch", "");
        badgeRoot = fm.prefs.getString("bd_root", "");
        if (badgesOn()) reloadRemote();
    }

    void reloadRemote() {
        final String repo = badgeRepo;
        final String br = badgeBranch;
        final int slash = repo.indexOf('/');
        if (slash <= 0 || Store.getToken(fm) == null) return;
        badgeVer++;
        badgeCache.clear();
        remoteShas = null;
        fm.io.execute(() -> {
            try {
                java.util.Map<String, String> m = new GitHubApi(fm).blobShaMap(repo.substring(0, slash),
                        repo.substring(slash + 1), br);
                fm.post(() -> {
                    if (!repo.equals(badgeRepo)) return;
                    remoteShas = m;
                    fm.changed();
                });
            } catch (final Exception e) {
                if (e instanceof GitHubApi.ApiException
                        && (((GitHubApi.ApiException) e).code == 404 || ((GitHubApi.ApiException) e).code == 409)) {
                    fm.post(() -> {
                        remoteShas = new java.util.HashMap<>();
                        fm.changed();
                    });
                    return;
                }
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                fm.post(() -> Dlg.error(fm, fm.getString(R.string.error), msg, e, this::reloadRemote));
            }
        });
    }
}

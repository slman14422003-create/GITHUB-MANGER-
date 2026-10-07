package com.ghmanager.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Looks for a newer APK in the GitHub Releases of the app's own repository. */
public final class Updater {
    private Updater() {
    }

    /** Repository the app updates itself from unless the user chooses another one. */
    public static final String DEFAULT_REPO = "slman14422003-create/GITHUB-MANGER-";

    /** Wrong slug shipped in earlier builds; it made the update check answer "Not Found". */
    public static final String OLD_BROKEN_REPO = "slman14422003/create-GITHUB-MANGER-";

    /** Every wrong spelling that earlier builds shipped or saved; all are replaced by DEFAULT_REPO. */
    public static boolean isBrokenSlug(String slug) {
        return OLD_BROKEN_REPO.equals(slug) || "slman14422003-create/GITHUB-MANGER".equals(slug);
    }

    private static final long AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    public static final class Info {
        public String tag = "";
        public String name = "";
        public String body = "";
        public String htmlUrl = "";
        public String publishedAt = "";
        public boolean prerelease;
        public long assetId;
        public String assetName = "";
        public long assetSize;
        /** versionCode written by the build workflow in the notes (0 when absent). */
        public long versionCode;
        public String sha256 = "";
        public boolean newer;
    }

    // ------------------------------------------------------------------ installed version

    public static String installedName(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return p.versionName == null ? "" : p.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("deprecation")
    public static long installedCode(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ version comparison

    /** Extracts the numeric parts of a version such as "v2.3.15-beta" -> [2, 3, 15]. */
    public static long[] parseVersion(String v) {
        if (v == null) return new long[0];
        Matcher m = Pattern.compile("\\d+").matcher(v);
        long[] tmp = new long[16];
        int n = 0;
        while (m.find() && n < tmp.length) {
            try {
                tmp[n++] = Long.parseLong(m.group());
            } catch (NumberFormatException e) {
                tmp[n++] = 0;
            }
        }
        long[] out = new long[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    public static int compareVersions(String a, String b) {
        long[] x = parseVersion(a);
        long[] y = parseVersion(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            long p = i < x.length ? x[i] : 0;
            long q = i < y.length ? y[i] : 0;
            if (p != q) return p > q ? 1 : -1;
        }
        return 0;
    }

    // ------------------------------------------------------------------ release lookup

    private static boolean isApk(JSONObject asset) {
        return asset.optString("name").toLowerCase(Locale.US).endsWith(".apk")
                && "uploaded".equals(asset.optString("state", "uploaded"));
    }

    private static JSONObject pickApk(JSONArray assets) {
        if (assets == null) return null;
        JSONObject fallback = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null || !isApk(a)) continue;
            String n = a.optString("name").toLowerCase(Locale.US);
            if (n.contains("debug")) {
                if (fallback == null) fallback = a;
                continue;
            }
            return a;
        }
        return fallback;
    }

    private static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Returns the newest release that carries an APK, with {@link Info#newer} telling whether it is
     * newer than the installed app, or null when the repository has no installable release.
     */
    public static Info check(Context c, GitHubApi api, String owner, String repo, boolean includePre) throws Exception {
        JSONArray rels = api.listReleases(owner, repo, 1, 20);
        for (int i = 0; i < rels.length(); i++) {
            JSONObject r = rels.getJSONObject(i);
            if (r.optBoolean("draft")) continue;
            if (r.optBoolean("prerelease") && !includePre) continue;
            JSONObject apk = pickApk(r.optJSONArray("assets"));
            if (apk == null) continue;
            Info in = new Info();
            in.tag = r.optString("tag_name");
            in.name = r.optString("name");
            in.body = r.isNull("body") ? "" : r.optString("body");
            in.htmlUrl = r.optString("html_url");
            in.publishedAt = r.isNull("published_at") ? r.optString("created_at") : r.optString("published_at");
            in.prerelease = r.optBoolean("prerelease");
            in.assetId = apk.optLong("id");
            in.assetName = apk.optString("name");
            in.assetSize = apk.optLong("size");
            String code = find(in.body, "versionCode\\s*[:=]\\s*(\\d+)");
            if (!code.isEmpty()) {
                try {
                    in.versionCode = Long.parseLong(code);
                } catch (NumberFormatException ignored) {
                }
            }
            in.sha256 = find(in.body, "SHA-?256[^0-9a-fA-F]{0,12}([0-9a-fA-F]{64})").toLowerCase(Locale.US);
            if (in.versionCode > 0) in.newer = in.versionCode > installedCode(c);
            else in.newer = compareVersions(in.tag, installedName(c)) > 0;
            return in;
        }
        return null;
    }

    /** Release notes without the machine-readable footer lines added by the build workflow. */
    public static String cleanNotes(String body) {
        if (body == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\r?\n")) {
            String t = line.trim().toLowerCase(Locale.US);
            if (t.contains("versioncode:") || t.contains("sha-256") || t.contains("sha256")) continue;
            sb.append(line).append('\n');
        }
        String s = sb.toString().trim();
        return s.length() > 1200 ? s.substring(0, 1200) + "…" : s;
    }

    // ------------------------------------------------------------------ background check on launch

    /** Silent check (at most every few hours) that offers the update in a dialog when one exists. */
    public static void autoCheck(final Activity a, final ExecutorService io, final Handler ui) {
        if (!Store.autoUpdate(a)) return;
        final long now = System.currentTimeMillis();
        if (now - Store.lastUpdateCheck(a) < AUTO_CHECK_INTERVAL_MS) return;
        final String slug = Store.getUpdateRepo(a);
        final int slash = slug.indexOf('/');
        if (slash <= 0 || slash >= slug.length() - 1) return;
        final String owner = slug.substring(0, slash);
        final String repo = slug.substring(slash + 1);
        final boolean pre = Store.updatePre(a);
        io.execute(() -> {
            try {
                Info in = check(a, new GitHubApi(Store.getToken(a)), owner, repo, pre);
                Store.setLastUpdateCheck(a, now);
                if (in != null && in.newer && !in.tag.equals(Store.skippedVersion(a))) {
                    ui.post(() -> {
                        if (!a.isFinishing() && !a.isDestroyed()) prompt(a, in);
                    });
                }
            } catch (Exception ignored) {
                // offline or no access: stay silent, the manual check reports errors
            }
        });
    }

    private static void prompt(final Activity a, final Info in) {
        String msg = a.getString(R.string.upd_prompt_msg, in.tag, installedName(a));
        String notes = cleanNotes(in.body);
        if (!notes.isEmpty()) msg = msg + "\n\n" + notes;
        new Dlg(a)
                .setTitle(R.string.upd_available)
                .setMessage(msg)
                .setPositiveButton(R.string.upd_update_now, (d, w) -> {
                    Intent i = new Intent(a, UpdateActivity.class);
                    splitInto(i, Store.getUpdateRepo(a));
                    i.putExtra("autostart", true);
                    a.startActivity(i);
                })
                .setNeutralButton(R.string.upd_skip_version, (d, w) -> Store.setSkippedVersion(a, in.tag))
                .setNegativeButton(R.string.install_later, null)
                .show();
    }

    /** Puts owner / repo extras for an "owner/repo" slug. */
    public static void splitInto(Intent i, String slug) {
        int slash = slug.indexOf('/');
        if (slash > 0 && slash < slug.length() - 1) {
            i.putExtra("owner", slug.substring(0, slash));
            i.putExtra("repo", slug.substring(slash + 1));
        }
    }
}

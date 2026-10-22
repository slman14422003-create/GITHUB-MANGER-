package com.ghmanager.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Several GitHub accounts on one device. Each token is encrypted with the same Android Keystore key
 * as before (see Store); this class only keeps the list, the active account and basic profile info.
 */
public final class Accounts {
    private Accounts() {
    }

    public static final class Acc {
        public String id = "";
        public String login = "";
        public String name = "";
        public String avatar = "";
        /** "oauth" (signed in through GitHub) or "token" (personal access token). */
        public String type = "token";
        /** Encrypted token (Store.encrypt). */
        String tok = "";
        /** Encrypted refresh token (empty for tokens that never expire). */
        String ref = "";
        /** Wall-clock ms at which the access token expires (0 = never). */
        long exp = 0;
        /** Client ID the tokens were issued to. */
        String cid = "";

        public String title() {
            if (!name.isEmpty()) return name;
            return login.isEmpty() ? "GitHub" : login;
        }
    }

    private static final String K_LIST = "acc_list";
    private static final String K_ACTIVE = "acc_active";

    private static List<Acc> read(Context c) {
        List<Acc> out = new ArrayList<>();
        String raw = Store.rawPref(c, K_LIST);
        if (raw.isEmpty()) {
            // one-time migration of the single token an older version stored
            String enc = Store.legacyEncrypted(c);
            if (enc.isEmpty()) return out;
            Acc a = new Acc();
            a.id = "legacy";
            a.tok = enc;
            out.add(a);
            write(c, out, a.id);
            Store.dropLegacy(c);
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Acc a = new Acc();
                a.id = o.optString("id");
                a.login = o.optString("login");
                a.name = o.optString("name");
                a.avatar = o.optString("avatar");
                a.type = o.optString("type", "token");
                a.tok = o.optString("tok");
                a.ref = o.optString("ref");
                a.exp = o.optLong("exp", 0);
                a.cid = o.optString("cid");
                if (!a.id.isEmpty() && !a.tok.isEmpty()) out.add(a);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void write(Context c, List<Acc> list, String active) {
        JSONArray arr = new JSONArray();
        try {
            for (Acc a : list) {
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("login", a.login);
                o.put("name", a.name);
                o.put("avatar", a.avatar);
                o.put("type", a.type);
                o.put("tok", a.tok);
                o.put("ref", a.ref);
                o.put("exp", a.exp);
                o.put("cid", a.cid);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        Store.putPrefs(c, K_LIST, arr.toString(), K_ACTIVE, active);
    }

    public static List<Acc> all(Context c) {
        return read(c);
    }

    public static Acc active(Context c) {
        List<Acc> list = read(c);
        if (list.isEmpty()) return null;
        String id = Store.rawPref(c, K_ACTIVE);
        for (Acc a : list) if (a.id.equals(id)) return a;
        return list.get(0);
    }

    public static String activeToken(Context c) {
        Acc a = active(c);
        return a == null ? "" : Store.decrypt(a.tok);
    }

    public static String tokenOf(Acc a) {
        return a == null ? "" : Store.decrypt(a.tok);
    }

    /** Adds (or replaces, when the same login exists) an account and makes it the active one. */
    public static void add(Context c, String token, JSONObject user, String type) {
        add(c, token, user, type, "", 0);
    }

    /** Same as above, also keeping the refresh token so the session never has to end. */
    public static void add(Context c, String token, JSONObject user, String type, String refresh, long expiresInSec) {
        List<Acc> list = read(c);
        String login = user == null ? "" : user.optString("login");
        String id = login.isEmpty() ? "t" + System.currentTimeMillis() : login.toLowerCase(Locale.ROOT);
        Acc target = null;
        for (Acc a : list) {
            if (a.id.equals(id) || (a.id.equals("legacy") && !login.isEmpty() && a.login.isEmpty()
                    && Store.decrypt(a.tok).equals(token))) {
                target = a;
                break;
            }
        }
        if (target == null) {
            target = new Acc();
            list.add(target);
        }
        target.id = id;
        target.login = login;
        target.name = user == null || user.isNull("name") ? "" : user.optString("name");
        target.avatar = user == null ? "" : user.optString("avatar_url");
        target.type = type == null ? "token" : type;
        target.tok = Store.encrypt(token);
        String encRef = refresh == null || refresh.isEmpty() ? "" : Store.encrypt(refresh);
        target.ref = encRef;
        target.exp = !encRef.isEmpty() && expiresInSec > 0 ? System.currentTimeMillis() + expiresInSec * 1000L : 0;
        target.cid = encRef.isEmpty() ? "" : Store.oauthClientId(c);
        write(c, list, id);
    }

    private static final Object REFRESH_LOCK = new Object();

    /** Id of the active account ("" when nobody is signed in). */
    public static String activeId(Context c) {
        Acc a = active(c);
        return a == null ? "" : a.id;
    }

    /** True when the account's access token is expired or about to expire and a refresh token exists. */
    static boolean needsRefresh(Context c, String id) {
        for (Acc a : read(c)) {
            if (a.id.equals(id)) {
                return !a.ref.isEmpty() && a.exp > 0 && a.exp - System.currentTimeMillis() < 300000L;
            }
        }
        return false;
    }

    /**
     * Gets a new access token with the stored refresh token. {@code stale} is the token the caller was
     * using: when another thread already replaced it, that newer token is returned instead (refresh
     * tokens are single-use, so two refreshes at once would burn the session). "" when it failed.
     */
    static String refresh(Context c, String id, String stale) {
        synchronized (REFRESH_LOCK) {
            List<Acc> list = read(c);
            for (Acc a : list) {
                if (!a.id.equals(id)) continue;
                String cur = Store.decrypt(a.tok);
                if (!cur.isEmpty() && !cur.equals(stale)) return cur;
                String rt = Store.decrypt(a.ref);
                String cid = a.cid.isEmpty() ? Store.oauthClientId(c) : a.cid;
                if (rt.isEmpty() || cid.isEmpty()) return "";
                try {
                    DeviceFlow.Result r = DeviceFlow.refresh(cid, rt);
                    if (r.token == null) return "";
                    String encTok = Store.encrypt(r.token);
                    if (encTok.isEmpty()) return "";
                    a.tok = encTok;
                    if (r.refresh != null && !r.refresh.isEmpty()) a.ref = Store.encrypt(r.refresh);
                    a.exp = r.expiresIn > 0 ? System.currentTimeMillis() + r.expiresIn * 1000L : 0;
                    a.cid = cid;
                    write(c, list, Store.rawPref(c, K_ACTIVE));
                    return r.token;
                } catch (Exception e) {
                    return "";
                }
            }
            return "";
        }
    }

    /** Refreshes the stored profile (name, avatar, login) of an account. */
    public static void updateProfile(Context c, String id, JSONObject user) {
        if (user == null) return;
        List<Acc> list = read(c);
        Acc active = active(c);
        String activeId = active == null ? "" : active.id;
        for (Acc a : list) {
            if (!a.id.equals(id)) continue;
            String login = user.optString("login");
            if (!login.isEmpty()) a.login = login;
            a.name = user.isNull("name") ? "" : user.optString("name");
            a.avatar = user.optString("avatar_url");
            if (a.id.equals("legacy") && !login.isEmpty()) {
                a.id = login.toLowerCase(Locale.ROOT);
                if (activeId.equals("legacy")) activeId = a.id;
            }
            break;
        }
        write(c, list, activeId);
    }

    public static void setActive(Context c, String id) {
        write(c, read(c), id);
    }

    /** Removes one account. Returns how many accounts are left. */
    public static int remove(Context c, String id) {
        List<Acc> list = read(c);
        Acc active = active(c);
        String activeId = active == null ? "" : active.id;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                break;
            }
        }
        if (list.isEmpty()) {
            Store.clear(c);
            return 0;
        }
        if (activeId.equals(id)) activeId = list.get(0).id;
        write(c, list, activeId);
        return list.size();
    }

    /** Signs one account out and opens the right screen afterwards. */
    public static void signOut(Activity a, String id) {
        int left = remove(a, id);
        Intent i = new Intent(a, left == 0 ? LoginActivity.class : ReposActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        a.startActivity(i);
        a.finish();
    }

    /** Signs every account out. */
    public static void signOutAll(Activity a) {
        Store.clear(a);
        Intent i = new Intent(a, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        a.startActivity(i);
        a.finish();
    }

    /** Switches account and reopens the repositories screen with a clean back stack. */
    public static void switchTo(Activity a, String id) {
        setActive(a, id);
        Intent i = new Intent(a, ReposActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        a.startActivity(i);
        a.finish();
    }
}

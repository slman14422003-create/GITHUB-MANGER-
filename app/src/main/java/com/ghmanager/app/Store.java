package com.ghmanager.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class Store {
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("gh", Context.MODE_PRIVATE);
    }

    // The access token is encrypted with an AES-256-GCM key that lives in the Android Keystore
    // (hardware-backed where available) and can never be exported, so the stored value is useless
    // without this device and this app.
    private static final String KEY_ALIAS = "gh_token_key";
    private static final String TOKEN_ENC = "token_enc";

    private static SecretKey tokenKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return (SecretKey) ks.getKey(KEY_ALIAS, null);
        }
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    /** Encrypts a secret with the Keystore key (Base64 of IV + ciphertext); "" on failure. */
    static String encrypt(String t) {
        try {
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.ENCRYPT_MODE, tokenKey());
            byte[] iv = ci.getIV();
            byte[] ct = ci.doFinal(t.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return Base64.encodeToString(all, Base64.NO_WRAP);
        } catch (Exception ex) {
            // never fall back to plain text: without the keystore the secret is simply not kept
            return "";
        }
    }

    /** Reverse of encrypt(); "" when the value is empty, damaged, or the key is gone. */
    static String decrypt(String enc) {
        if (enc == null || enc.isEmpty()) return "";
        try {
            byte[] all = Base64.decode(enc, Base64.NO_WRAP);
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, tokenKey(), new GCMParameterSpec(128, all, 0, 12));
            return new String(ci.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static String rawPref(Context c, String key) {
        String v = sp(c).getString(key, "");
        return v == null ? "" : v;
    }

    static void putPrefs(Context c, String k1, String v1, String k2, String v2) {
        sp(c).edit().putString(k1, v1).putString(k2, v2).apply();
    }

    /** The encrypted token the single-account versions kept (migrates a plain-text one first). */
    static String legacyEncrypted(Context c) {
        SharedPreferences p = sp(c);
        String enc = p.getString(TOKEN_ENC, "");
        if (enc != null && !enc.isEmpty()) return enc;
        String old = p.getString("token", "");
        if (old != null && !old.isEmpty()) return encrypt(old);
        return "";
    }

    static void dropLegacy(Context c) {
        sp(c).edit().remove("token").remove(TOKEN_ENC).apply();
    }

    /** Token of the active account ("" when nobody is signed in). */
    public static String getToken(Context c) {
        return Accounts.activeToken(c);
    }

    /** Saves a token as the (only) account when the user has no profile yet. Prefer Accounts.add. */
    public static void setToken(Context c, String t) {
        Accounts.add(c, t, null, "token");
    }

    /**
     * Signs out. Called ONLY from the explicit logout buttons: the app never signs the user out on
     * its own (not on network errors, not on a 401, not on a timeout).
     * Removes every account and everything else except the in-app preferences (keys starting with upd_,
     * which include the mirror settings so the user can sign in again where GitHub is blocked).
     */
    public static void clear(Context c) {
        SharedPreferences p = sp(c);
        SharedPreferences.Editor e = p.edit();
        for (String k : p.getAll().keySet()) {
            if (!k.startsWith("upd_")) e.remove(k);
        }
        e.apply();
        RepoCache.clear(c);
        RepoTree.clearAll(c);
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ in-app updates

    /** "owner/repo" the app downloads its own new versions from. */
    public static String getUpdateRepo(Context c) {
        String v = sp(c).getString("upd_repo", "");
        if (v == null || v.trim().isEmpty() || Updater.isBrokenSlug(v.trim())) {
            return Updater.DEFAULT_REPO;
        }
        return v.trim();
    }

    public static void setUpdateRepo(Context c, String v) {
        sp(c).edit().putString("upd_repo", v == null ? "" : v.trim()).apply();
    }

    public static boolean autoUpdate(Context c) {
        return sp(c).getBoolean("upd_auto", true);
    }

    public static void setAutoUpdate(Context c, boolean on) {
        sp(c).edit().putBoolean("upd_auto", on).apply();
    }

    public static boolean updatePre(Context c) {
        return sp(c).getBoolean("upd_pre", false);
    }

    public static void setUpdatePre(Context c, boolean on) {
        sp(c).edit().putBoolean("upd_pre", on).apply();
    }

    /** "Run with SEMO AI" checkbox state, remembered across repos (applies to every project). */
    public static boolean semoAiOn(Context c) {
        return sp(c).getBoolean("semo_ai_on", false);
    }

    public static void setSemoAiOn(Context c, boolean on) {
        sp(c).edit().putBoolean("semo_ai_on", on).apply();
    }

    public static long lastUpdateCheck(Context c) {
        return sp(c).getLong("upd_last", 0L);
    }

    public static void setLastUpdateCheck(Context c, long t) {
        sp(c).edit().putLong("upd_last", t).apply();
    }

    public static String skippedVersion(Context c) {
        return sp(c).getString("upd_skip", "");
    }

    public static void setSkippedVersion(Context c, String tag) {
        sp(c).edit().putString("upd_skip", tag == null ? "" : tag).apply();
    }

    // ------------------------------------------------------------------ app settings

    /** "system", "ar" or "en". */
    public static String language(Context c) {
        return sp(c).getString("upd_lang", "system");
    }

    public static void setLanguage(Context c, String v) {
        sp(c).edit().putString("upd_lang", v == null ? "system" : v).apply();
    }

    /** true = every download asks where to save; false (default) = straight into Download/GitHubManager/<repo>. */
    public static boolean askSaveLocation(Context c) {
        return sp(c).getBoolean("upd_ask_save", false);
    }

    public static void setAskSaveLocation(Context c, boolean on) {
        sp(c).edit().putBoolean("upd_ask_save", on).apply();
    }

    // ------------------------------------------------------------------ proxy mirror / OAuth

    public static boolean mirrorOn(Context c) {
        return sp(c).getBoolean("upd_mirror_on", false);
    }

    public static String mirrorUrl(Context c) {
        return rawPref(c, "upd_mirror_url");
    }

    /** Optional access key of the Worker, encrypted like the tokens. */
    public static String mirrorKey(Context c) {
        return decrypt(rawPref(c, "upd_mirror_key"));
    }

    public static void setMirror(Context c, boolean on, String url, String key) {
        sp(c).edit().putBoolean("upd_mirror_on", on)
                .putString("upd_mirror_url", url == null ? "" : url.trim())
                .putString("upd_mirror_key", key == null || key.isEmpty() ? "" : encrypt(key))
                .apply();
    }

    /** Client ID of the user's own GitHub OAuth App (Device Flow sign-in). */
    public static String oauthClientId(Context c) {
        return rawPref(c, "upd_oauth_client");
    }

    public static void setOauthClientId(Context c, String v) {
        sp(c).edit().putString("upd_oauth_client", v == null ? "" : v.trim()).apply();
    }
}

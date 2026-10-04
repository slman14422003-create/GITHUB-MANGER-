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

    public static String getToken(Context c) {
        SharedPreferences p = sp(c);
        String enc = p.getString(TOKEN_ENC, "");
        if (enc != null && !enc.isEmpty()) {
            try {
                byte[] all = Base64.decode(enc, Base64.NO_WRAP);
                Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
                ci.init(Cipher.DECRYPT_MODE, tokenKey(), new GCMParameterSpec(128, all, 0, 12));
                return new String(ci.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        }
        // one-time migration of a token saved in plain text by an older version
        String old = p.getString("token", "");
        if (old != null && !old.isEmpty()) {
            setToken(c, old);
            return old;
        }
        return "";
    }

    public static void setToken(Context c, String t) {
        SharedPreferences.Editor e = sp(c).edit().remove("token");
        try {
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.ENCRYPT_MODE, tokenKey());
            byte[] iv = ci.getIV();
            byte[] ct = ci.doFinal(t.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            e.putString(TOKEN_ENC, Base64.encodeToString(all, Base64.NO_WRAP));
        } catch (Exception ex) {
            // never fall back to plain text: without the keystore the token is simply not kept
            e.remove(TOKEN_ENC);
        }
        e.apply();
    }

    /**
     * Signs out. Called ONLY from the explicit logout buttons: the app never signs the user out on
     * its own (not on network errors, not on a 401, not on a timeout).
     * Removes everything except the in-app update preferences (keys starting with upd_).
     */
    public static void clear(Context c) {
        SharedPreferences p = sp(c);
        SharedPreferences.Editor e = p.edit();
        for (String k : p.getAll().keySet()) {
            if (!k.startsWith("upd_")) e.remove(k);
        }
        e.apply();
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
}

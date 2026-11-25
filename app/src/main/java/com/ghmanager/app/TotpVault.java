package com.ghmanager.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class TotpVault {
    private TotpVault() {
    }

    private static final String HMAC_ALIAS = "gh_totp_hmac";
    private static final String AES_ALIAS = "gh_totp_aes";
    private static final String P_MODE = "ver_totp_mode";
    private static final String P_BLOB = "ver_totp_blob";
    private static final String P_LAST = "ver_totp_last";
    private static final String MODE_KS = "ks";
    private static final String MODE_AES = "aes";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("gh", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        String m = sp(c).getString(P_MODE, "");
        return MODE_KS.equals(m) || MODE_AES.equals(m);
    }

    static synchronized boolean save(Context c, byte[] secret) {
        dropKeys();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.setEntry(HMAC_ALIAS, new KeyStore.SecretKeyEntry(new SecretKeySpec(secret, "HmacSHA1")),
                    new KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).build());
            Mac m = Mac.getInstance("HmacSHA1");
            m.init((SecretKey) ks.getKey(HMAC_ALIAS, null));
            m.doFinal(new byte[8]);
            sp(c).edit().putString(P_MODE, MODE_KS).putString(P_BLOB, "").putLong(P_LAST, -1L).apply();
            return true;
        } catch (Exception e) {
            dropKeys();
        }
        try {
            String blob = wrap(secret);
            if (blob.isEmpty()) return false;
            sp(c).edit().putString(P_MODE, MODE_AES).putString(P_BLOB, blob).putLong(P_LAST, -1L).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static synchronized void wipe(Context c) {
        dropKeys();
        sp(c).edit().remove(P_MODE).remove(P_BLOB).remove(P_LAST).apply();
    }

    static Totp.Signer signer(Context c) {
        try {
            String mode = sp(c).getString(P_MODE, "");
            if (MODE_KS.equals(mode)) {
                KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
                ks.load(null);
                final SecretKey key = (SecretKey) ks.getKey(HMAC_ALIAS, null);
                if (key == null) return null;
                return msg -> {
                    Mac m = Mac.getInstance("HmacSHA1");
                    m.init(key);
                    return m.doFinal(msg);
                };
            }
            if (MODE_AES.equals(mode)) {
                final byte[] secret = unwrap(sp(c).getString(P_BLOB, ""));
                if (secret == null) return null;
                return msg -> {
                    Mac m = Mac.getInstance("HmacSHA1");
                    m.init(new SecretKeySpec(secret, "HmacSHA1"));
                    return m.doFinal(msg);
                };
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static synchronized boolean verify(Context c, String input) {
        Totp.Signer s = signer(c);
        if (s == null) return false;
        try {
            long last = sp(c).getLong(P_LAST, -1L);
            long hit = Totp.match(s, input == null ? "" : input.trim(), System.currentTimeMillis(), last);
            if (hit < 0) return false;
            sp(c).edit().putLong(P_LAST, hit).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void dropKeys() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(HMAC_ALIAS)) ks.deleteEntry(HMAC_ALIAS);
            if (ks.containsAlias(AES_ALIAS)) ks.deleteEntry(AES_ALIAS);
        } catch (Exception ignored) {
        }
    }

    private static SecretKey aesKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(AES_ALIAS)) return (SecretKey) ks.getKey(AES_ALIAS, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(AES_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    private static String wrap(byte[] secret) throws Exception {
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, aesKey());
        byte[] iv = ci.getIV();
        byte[] ct = ci.doFinal(secret);
        byte[] all = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, all, 0, iv.length);
        System.arraycopy(ct, 0, all, iv.length, ct.length);
        return Base64.encodeToString(all, Base64.NO_WRAP);
    }

    private static byte[] unwrap(String blob) {
        try {
            if (blob == null || blob.isEmpty()) return null;
            byte[] all = Base64.decode(blob, Base64.NO_WRAP);
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, aesKey(), new GCMParameterSpec(128, all, 0, 12));
            return ci.doFinal(all, 12, all.length - 12);
        } catch (Exception e) {
            return null;
        }
    }
}

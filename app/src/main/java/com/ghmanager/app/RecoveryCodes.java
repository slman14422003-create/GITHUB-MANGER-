package com.ghmanager.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class RecoveryCodes {
    private RecoveryCodes() {
    }

    private static final String KEY = "ver_recovery";
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int COUNT = 8;
    static final int LENGTH = 10;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("gh", Context.MODE_PRIVATE);
    }

    static String normalize(String s) {
        if (s == null) return "";
        return s.replace("-", "").replace(" ", "").trim().toUpperCase(Locale.ROOT);
    }

    static String pretty(String code) {
        String n = normalize(code);
        if (n.length() <= LENGTH / 2) return n;
        return n.substring(0, LENGTH / 2) + "-" + n.substring(LENGTH / 2);
    }

    static String hash(String salt, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((salt + ":" + normalize(code)).getBytes(StandardCharsets.UTF_8));
            return toHex(md.digest());
        } catch (Exception e) {
            return "";
        }
    }

    static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xFF));
        return sb.toString();
    }

    static List<String> generate(Context c) {
        SecureRandom rnd = new SecureRandom();
        List<String> plain = new ArrayList<>();
        JSONArray stored = new JSONArray();
        for (int i = 0; i < COUNT; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < LENGTH; j++) sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
            byte[] saltBytes = new byte[8];
            rnd.nextBytes(saltBytes);
            String salt = toHex(saltBytes);
            stored.put(salt + ":" + hash(salt, sb.toString()));
            plain.add(pretty(sb.toString()));
        }
        sp(c).edit().putString(KEY, stored.toString()).apply();
        return plain;
    }

    static int remaining(Context c) {
        try {
            return new JSONArray(sp(c).getString(KEY, "[]")).length();
        } catch (Exception e) {
            return 0;
        }
    }

    static synchronized boolean consume(Context c, String input) {
        String n = normalize(input);
        if (n.length() != LENGTH) return false;
        try {
            JSONArray arr = new JSONArray(sp(c).getString(KEY, "[]"));
            int hit = -1;
            for (int i = 0; i < arr.length(); i++) {
                String[] p = arr.getString(i).split(":", 2);
                if (p.length != 2) continue;
                boolean eq = MessageDigest.isEqual(hash(p[0], n).getBytes(StandardCharsets.UTF_8),
                        p[1].getBytes(StandardCharsets.UTF_8));
                if (eq && hit < 0) hit = i;
            }
            if (hit < 0) return false;
            JSONArray left = new JSONArray();
            for (int i = 0; i < arr.length(); i++) if (i != hit) left.put(arr.getString(i));
            sp(c).edit().putString(KEY, left.toString()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void clear(Context c) {
        sp(c).edit().remove(KEY).apply();
    }
}

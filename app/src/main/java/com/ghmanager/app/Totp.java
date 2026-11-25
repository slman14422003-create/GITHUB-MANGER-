package com.ghmanager.app;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

final class Totp {
    private Totp() {
    }

    interface Signer {
        byte[] sign(byte[] message) throws Exception;
    }

    static final long PERIOD = 30L;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    static byte[] newSecret() {
        byte[] b = new byte[20];
        new SecureRandom().nextBytes(b);
        return b;
    }

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte x : data) {
            buffer = (buffer << 8) | (x & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] fromBase32(String s) {
        if (s == null) return new byte[0];
        String clean = s.replace(" ", "").replace("-", "").replace("=", "").toUpperCase(Locale.ROOT);
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int idx = 0;
        for (int i = 0; i < clean.length(); i++) {
            int v = ALPHABET.indexOf(clean.charAt(i));
            if (v < 0) return new byte[0];
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out[idx++] = (byte) ((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out;
    }

    static String group(String b32) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b32.length(); i++) {
            if (i > 0 && i % 4 == 0) sb.append(' ');
            sb.append(b32.charAt(i));
        }
        return sb.toString();
    }

    static long step(long millis) {
        return millis / 1000L / PERIOD;
    }

    static String code(Signer signer, long step) throws Exception {
        byte[] h = signer.sign(ByteBuffer.allocate(8).putLong(step).array());
        int off = h[h.length - 1] & 0x0F;
        int bin = ((h[off] & 0x7F) << 24) | ((h[off + 1] & 0xFF) << 16)
                | ((h[off + 2] & 0xFF) << 8) | (h[off + 3] & 0xFF);
        return String.format(Locale.US, "%06d", bin % 1_000_000);
    }

    static long match(Signer signer, String input, long nowMs, long lastStep) throws Exception {
        if (input == null || !input.matches("\\d{6}")) return -1;
        byte[] given = input.getBytes("UTF-8");
        long base = step(nowMs);
        long found = -1;
        for (long d = -1; d <= 1; d++) {
            long st = base + d;
            byte[] want = code(signer, st).getBytes("UTF-8");
            if (MessageDigest.isEqual(want, given) && st > lastStep && found < 0) found = st;
        }
        return found;
    }

    static String uri(String account, String issuer, byte[] secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + base32(secret) + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=6&period=30";
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }
}

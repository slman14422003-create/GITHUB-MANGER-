package com.ghmanager.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class GitSha {
    private GitSha() {
    }

    public static String blob(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(("blob " + data.length + "\0").getBytes(StandardCharsets.US_ASCII));
            md.update(data);
            return hex(md.digest());
        } catch (Exception e) {
            return "";
        }
    }

    public static String blob(String text) {
        return blob(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return hex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return sb.toString();
    }
}

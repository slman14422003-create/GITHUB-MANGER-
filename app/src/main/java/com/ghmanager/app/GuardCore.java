package com.ghmanager.app;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Checks that the code and resources inside the installed APK are exactly the ones the build sealed.
 *
 * <p>The build (tools/seal_apk.py) hashes every classes*.dex, resources.arsc and AndroidManifest.xml and
 * stores the list in assets/guard.bin together with an HMAC. The HMAC key is derived from the SHA-256 of
 * the signing certificate, so a copy that was edited and signed with anybody else's key cannot match, and
 * adding or removing a dex file changes the list. Pure Java on purpose (no Android classes): the same
 * code is tested against the Python sealer on a build machine.
 */
final class GuardCore {
    private GuardCore() {
    }

    static final String ENTRY = "assets/guard.bin";

    static boolean protectedName(String n) {
        return n.equals("resources.arsc") || n.equals("AndroidManifest.xml")
                || (n.startsWith("classes") && n.endsWith(".dex") && n.indexOf('/') < 0);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] read(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    /** True only when guard.bin is present, authentic for this certificate, and matches every protected file. */
    static boolean verify(File apk, String certHexLower) {
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry g = zf.getEntry(ENTRY);
            if (g == null) return false;
            String text = new String(read(zf.getInputStream(g)), StandardCharsets.UTF_8);
            int at = text.lastIndexOf("mac=");
            if (at < 0) return false;
            String body = text.substring(0, at);
            String mac = text.substring(at + 4).trim();

            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(("gh-guard-v1|" + certHexLower).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expect = hex(m.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            if (!MessageDigest.isEqual(expect.getBytes(StandardCharsets.UTF_8), mac.getBytes(StandardCharsets.UTF_8))) {
                return false;
            }

            Map<String, String> sealed = new TreeMap<>();
            for (String line : body.split("\n")) {
                if (line.isEmpty()) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) return false;
                sealed.put(line.substring(0, eq), line.substring(eq + 1));
            }

            Map<String, String> actual = new TreeMap<>();
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !protectedName(n)) continue;
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (InputStream in = zf.getInputStream(e)) {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = in.read(buf)) != -1) md.update(buf, 0, r);
                }
                actual.put(n, hex(md.digest()));
            }
            return !sealed.isEmpty() && sealed.equals(actual);
        } catch (Exception e) {
            return false;
        }
    }
}

package com.ghmanager.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;

import java.security.MessageDigest;

/**
 * Runtime self-checks for release builds. They make repackaged or tampered copies refuse to run
 * (and therefore refuse to read the stored token). No client-side check is unbreakable, but together
 * with the Keystore-encrypted token they raise the bar a lot.
 */
final class Integrity {
    private Integrity() {
    }

    /** True when the app may run. Debug builds are never blocked. */
    static boolean verify(Context c) {
        if (BuildConfig.DEBUG) return true;
        try {
            // a release build must not be debuggable or have a debugger attached
            if ((c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) return false;
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return false;

            // the signing certificate must be the one the build was made with
            String expected = BuildConfig.CERT_SHA256;
            if (expected != null && !expected.isEmpty()) {
                String actual = signerSha256(c);
                if (actual == null || !actual.equalsIgnoreCase(expected)) return false;
                // the code and resources inside the installed APK must be exactly what the build sealed
                if (BuildConfig.SEALED && !sealIntact(c, expected)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static volatile int sealState = 0; // 0 = not checked, 1 = intact, 2 = broken

    private static boolean sealIntact(Context c, String certHex) {
        if (sealState == 0) {
            boolean ok = GuardCore.verify(new java.io.File(c.getApplicationInfo().sourceDir),
                    certHex.toLowerCase(java.util.Locale.ROOT));
            sealState = ok ? 1 : 2;
        }
        return sealState == 1;
    }

    /**
     * True when the downloaded APK is signed with the same certificate as the installed app. A
     * tampered or foreign update is rejected before the installer is even opened.
     */
    @SuppressWarnings("deprecation")
    static boolean sameSigner(Context c, java.io.File apk) {
        try {
            PackageManager pm = c.getPackageManager();
            String path = apk.getAbsolutePath();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo pi = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi == null || pi.signingInfo == null) return false;
                sigs = pi.signingInfo.getApkContentsSigners();
            } else {
                PackageInfo pi = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES);
                if (pi == null) return false;
                sigs = pi.signatures;
            }
            if (sigs == null || sigs.length != 1) return false;
            String mine = signerSha256(c);
            return mine != null && mine.equals(hex(sigs[0]));
        } catch (Exception e) {
            return false;
        }
    }

    private static String hex(Signature sig) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray());
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @SuppressWarnings("deprecation")
    private static String signerSha256(Context c) throws Exception {
        PackageManager pm = c.getPackageManager();
        Signature[] sigs;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageInfo pi = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (pi.signingInfo == null) return null;
            // more than one signer in the history means the key was rotated: use the current one
            sigs = pi.signingInfo.getApkContentsSigners();
        } else {
            PackageInfo pi = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES);
            sigs = pi.signatures;
        }
        if (sigs == null || sigs.length != 1) return null;
        return hex(sigs[0]);
    }
}

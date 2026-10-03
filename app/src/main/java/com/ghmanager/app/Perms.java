package com.ghmanager.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;

/** One place for every permission the app needs: all-files access and installing APKs. */
public final class Perms {
    public static final int REQ_STORAGE = 4021;

    private Perms() {
    }

    // ------------------------------------------------------------------ storage

    public static boolean hasAllFiles(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(c, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Opens the right system screen (or dialog) to grant file access. */
    public static void requestAllFiles(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + a.getPackageName()));
                a.startActivity(i);
                return;
            } catch (Exception ignored) {
            }
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                return;
            } catch (Exception ignored) {
            }
            openAppSettings(a);
        } else {
            ActivityCompat.requestPermissions(a, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    // ------------------------------------------------------------------ install unknown apps

    public static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return c.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    public static void requestInstall(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + a.getPackageName())));
                return;
            } catch (Exception ignored) {
            }
        }
        openAppSettings(a);
    }

    public static void openAppSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception ignored) {
        }
    }

    /**
     * Installs an APK file. When the "install unknown apps" permission is missing, explains why and
     * sends the user to the exact system screen instead of silently failing.
     */
    public static void installApk(final Activity a, final File apk) {
        if (!canInstall(a)) {
            new Dlg(a)
                    .setTitle(R.string.perm_install_title)
                    .setMessage(R.string.perm_install_dialog)
                    .setPositiveButton(R.string.perm_open_settings, (d, w) -> requestInstall(a))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(a, a.getPackageName() + ".files", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(i);
        } catch (Exception e) {
            android.widget.Toast.makeText(a, R.string.install_failed, android.widget.Toast.LENGTH_LONG).show();
        }
    }
}

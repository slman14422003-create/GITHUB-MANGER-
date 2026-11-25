package com.ghmanager.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

final class TokenExpiry {
    private TokenExpiry() {
    }

    static final long WARN_MS = 7L * 24L * 3600L * 1000L;
    private static final long NOTIFY_EVERY_MS = 24L * 3600L * 1000L;
    private static final String CHANNEL = "token_expiry";
    private static final int NOTE = 7810;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("gh", Context.MODE_PRIVATE);
    }

    static long parse(String header) {
        if (header == null || header.trim().isEmpty()) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            f.setLenient(false);
            String h = header.trim().replace(" UTC", "");
            return f.parse(h).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    static void seen(Context c, String accId, String header) {
        if (c == null || accId == null || accId.isEmpty() || header == null) return;
        long exp = parse(header);
        if (exp <= 0) return;
        SharedPreferences p = sp(c);
        if (p.getLong("tkx_" + accId, 0) != exp) p.edit().putLong("tkx_" + accId, exp).apply();
        maybeNotify(c, accId, exp);
    }

    static long expiresAt(Context c, String accId) {
        return sp(c).getLong("tkx_" + accId, 0);
    }

    static void forget(Context c, String accId) {
        sp(c).edit().remove("tkx_" + accId).remove("tkxn_" + accId).apply();
    }

    static boolean autoRenews(Context c, String accId) {
        for (Accounts.Acc a : Accounts.all(c)) if (a.id.equals(accId)) return !a.ref.isEmpty();
        return false;
    }

    static long daysLeft(long exp, long now) {
        long left = exp - now;
        if (left <= 0) return 0;
        return (left + 24L * 3600L * 1000L - 1) / (24L * 3600L * 1000L);
    }

    static boolean warn(long exp, long now) {
        return exp > 0 && exp - now <= WARN_MS;
    }

    static String banner(Context c) {
        Accounts.Acc a = Accounts.active(c);
        if (a == null || !a.ref.isEmpty()) return null;
        long exp = expiresAt(c, a.id);
        long now = System.currentTimeMillis();
        if (!warn(exp, now)) return null;
        if (exp <= now) return c.getString(R.string.tkx_expired);
        return c.getResources().getQuantityString(R.plurals.tkx_days, (int) daysLeft(exp, now),
                (int) daysLeft(exp, now));
    }

    private static void maybeNotify(Context c, String accId, long exp) {
        long now = System.currentTimeMillis();
        if (!warn(exp, now) || autoRenews(c, accId)) return;
        SharedPreferences p = sp(c);
        if (now - p.getLong("tkxn_" + accId, 0) < NOTIFY_EVERY_MS) return;
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || !nm.areNotificationsEnabled()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.tkx_channel),
                        NotificationManager.IMPORTANCE_DEFAULT));
            }
            String text = exp <= now ? c.getString(R.string.tkx_expired)
                    : c.getResources().getQuantityString(R.plurals.tkx_days, (int) daysLeft(exp, now),
                    (int) daysLeft(exp, now));
            Intent open = new Intent(c, AccountActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            nm.notify(NOTE, new NotificationCompat.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_lock)
                    .setContentTitle(c.getString(R.string.tkx_title))
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, NOTE, open,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                    .build());
            p.edit().putLong("tkxn_" + accId, now).apply();
        } catch (Exception ignored) {
        }
    }
}

package com.ghmanager.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * Foreground service that keeps uploads, downloads and update downloads alive while the app is in
 * the background. It only holds the notification and the wake lock; the work itself runs in
 * {@link Transfers}.
 */
public class TransferService extends Service {
    private static final String CH_RUN = "transfers_run";
    private static final String CH_DONE = "transfers_done";
    private static final int ID_RUN = 7101;
    private static final String ACT_CANCEL = "com.ghmanager.app.CANCEL_TRANSFERS";

    private static volatile TransferService inst;
    private long lastNotify = 0;
    private PowerManager.WakeLock wake;

    static void begin(Context app) {
        try {
            ContextCompat.startForegroundService(app, new Intent(app, TransferService.class));
        } catch (Exception ignored) {
            // the transfer still runs; it just has no foreground protection
        }
    }

    static void refresh() {
        TransferService s = inst;
        if (s != null) s.update(false);
    }

    static void finished(Context app, Transfers.Job job, boolean ok, boolean cancelled) {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && !cancelled) {
                ensureChannels(app, nm);
                NotificationCompat.Builder b = new NotificationCompat.Builder(app, CH_DONE)
                        .setSmallIcon(ok ? R.drawable.ic_check : R.drawable.ic_cancel)
                        .setContentTitle(job.title)
                        .setContentText(job.message.isEmpty()
                                ? app.getString(ok ? R.string.tr_done : R.string.tr_failed) : job.message)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(job.message))
                        .setAutoCancel(true);
                Intent open = job.openIntent;
                if (open == null) open = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
                if (open != null) {
                    open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    b.setContentIntent(PendingIntent.getActivity(app, 7200 + job.id, open,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
                }
                nm.notify(7200 + job.id, b.build());
            }
        } catch (Exception ignored) {
        }
        TransferService s = inst;
        if (s != null) {
            if (Transfers.activeCount() == 0) s.shutdown();
            else s.update(true);
        }
    }

    private static void ensureChannels(Context c, NotificationManager nm) {
        if (Build.VERSION.SDK_INT < 26) return;
        nm.createNotificationChannel(new NotificationChannel(CH_RUN, c.getString(R.string.tr_channel_run),
                NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_DONE, c.getString(R.string.tr_channel_done),
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACT_CANCEL.equals(intent.getAction())) Transfers.cancelAll();
        inst = this;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) ensureChannels(this, nm);
        try {
            ServiceCompat.startForeground(this, ID_RUN, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        acquireWake();
        if (Transfers.activeCount() == 0) shutdown();
        return START_NOT_STICKY;
    }

    /** Android 15 stops a data-sync service after its time budget; it must end itself. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        shutdown();
    }

    private void shutdown() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        stopSelf();
    }

    private void acquireWake() {
        try {
            if (wake == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm == null) return;
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ghmanager:transfer");
                wake.setReferenceCounted(false);
            }
            wake.acquire(30L * 60L * 1000L);
        } catch (Exception ignored) {
        }
    }

    private void update(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotify < 600) return;
        lastNotify = now;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(ID_RUN, build());
        } catch (Exception ignored) {
        }
    }

    private Notification build() {
        List<Transfers.Job> js = Transfers.snapshot();
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_RUN)
                .setSmallIcon(R.drawable.ic_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS);
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            b.setContentIntent(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE));
        }
        Intent cancel = new Intent(this, TransferService.class).setAction(ACT_CANCEL);
        b.addAction(0, getString(R.string.tr_cancel),
                PendingIntent.getService(this, 1, cancel, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        if (js.isEmpty()) {
            b.setContentTitle(getString(R.string.app_name));
            return b.build();
        }
        Transfers.Job first = js.get(0);
        b.setContentTitle(js.size() > 1 ? getString(R.string.tr_n_running, js.size()) : first.title);
        b.setContentText(first.text);
        long total = first.total;
        if (total > 0) b.setProgress(100, (int) Math.min(100, first.done * 100 / total), false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    @Override
    public void onDestroy() {
        if (inst == this) inst = null;
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

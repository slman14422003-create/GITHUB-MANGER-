package com.ghmanager.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Round GitHub avatars, loaded through the same (mirror-aware) network layer and cached in memory. */
public final class Avatar {
    private Avatar() {
    }

    private static final LruCache<String, Bitmap> CACHE = new LruCache<>(48);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    public static void load(final ImageView v, final String url, int padDp) {
        final Context c = v.getContext();
        v.setBackgroundResource(R.drawable.bg_circle_accent);
        v.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        v.setClipToOutline(true);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setTag(url == null ? "" : url);
        Bitmap hit = url == null || url.isEmpty() ? null : CACHE.get(url);
        if (hit != null) {
            show(v, hit);
            return;
        }
        int p = Ui.dp(c, padDp);
        v.setPadding(p, p, p, p);
        v.setImageResource(R.drawable.ic_user);
        v.setColorFilter(Ui.color(c, R.color.accent_text));
        if (url == null || url.isEmpty()) return;
        POOL.execute(() -> {
            byte[] b = GitHubApi.fetchBytes(url + (url.contains("?") ? "&" : "?") + "s=160", 600 * 1024);
            if (b == null) return;
            final Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length);
            if (bmp == null) return;
            CACHE.put(url, bmp);
            v.post(() -> {
                if (url.equals(v.getTag())) show(v, bmp);
            });
        });
    }

    private static void show(ImageView v, Bitmap b) {
        v.clearColorFilter();
        v.setPadding(0, 0, 0, 0);
        v.setImageBitmap(b);
    }
}

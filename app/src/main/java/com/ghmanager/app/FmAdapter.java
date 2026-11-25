package com.ghmanager.app;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.ghmanager.app.FmConst.*;
import static com.ghmanager.app.FmFiles.*;

final class FmAdapter extends RecyclerView.Adapter<FmAdapter.VH> {
    FileManagerActivity fm;

    FmAdapter(FileManagerActivity fm) {
        this.fm = fm;
    }

    class VH extends RecyclerView.ViewHolder {
        final ImageView icon, thumb, check, fav;
        final TextView title, sub, badge;
        final View more, chevron;

        VH(View v) {
            super(v);
            icon = v.findViewById(R.id.icon);
            thumb = v.findViewById(R.id.thumb);
            check = v.findViewById(R.id.check);
            fav = v.findViewById(R.id.fav);
            title = v.findViewById(R.id.title);
            sub = v.findViewById(R.id.sub);
            badge = v.findViewById(R.id.badge);
            more = v.findViewById(R.id.more);
            chevron = v.findViewById(R.id.chevron);
        }
    }

    @Override
    public int getItemCount() {
        return fm.shown.size();
    }

    @Override
    public int getItemViewType(int position) {
        return fm.gridMode ? 1 : 0;
    }

    @Override
    public VH onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = fm.getLayoutInflater().inflate(viewType == 1 ? R.layout.item_file_grid : R.layout.item_file,
                parent, false);
        final VH h = new VH(v);
        v.setOnClickListener(x -> {
            int p = h.getBindingAdapterPosition();
            if (p != RecyclerView.NO_POSITION) fm.selection.onItemClick(p);
        });
        v.setOnLongClickListener(x -> {
            int p = h.getBindingAdapterPosition();
            return p != RecyclerView.NO_POSITION && fm.selection.onItemLong(p);
        });
        if (h.more != null) {
            h.more.setOnClickListener(x -> {
                int p = h.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION && p < fm.shown.size()) fm.dialogs.itemMenu(fm.shown.get(p));
            });
        }
        return h;
    }

    @Override
    public void onBindViewHolder(VH h, int pos) {
        if (pos < 0 || pos >= fm.shown.size()) return;
        final FmEntry e = fm.shown.get(pos);
        final String path = e.f.getAbsolutePath();
        boolean sel = fm.selection.selected.contains(path);
        int type = typeOf(e);
        int color = Ui.color(fm, colorFor(type));

        h.icon.setImageResource(iconFor(type));
        h.icon.setImageTintList(ColorStateList.valueOf(color));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.radius(fm, R.dimen.radius_s));
        g.setColor((color & 0x00FFFFFF) | 0x26000000);
        h.icon.setBackground(g);
        h.thumb.setBackground(g);
        h.thumb.setClipToOutline(true);
        h.thumb.setTag(path);
        Bitmap cached = (type == T_IMG || type == T_APK) ? thumbs.get(path + "|" + e.mod) : null;
        if (cached != null) {
            h.thumb.setImageBitmap(cached);
            h.thumb.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.INVISIBLE);
        } else {
            h.thumb.setVisibility(View.GONE);
            h.icon.setVisibility(View.VISIBLE);
            if (type == T_IMG || type == T_APK) loadThumb(e, type, h.thumb, h.icon);
        }

        h.title.setText(e.name);
        StringBuilder sub = new StringBuilder();
        if (e.dir) {
            if (e.trashId != null) sub.append(Fmt.size(e.size));
            else sub.append(e.kids < 0 ? fm.getString(R.string.fm_unreadable) : fm.getString(R.string.fm_items_n, e.kids));
        } else {
            sub.append(Fmt.size(e.size));
        }
        if (e.trashId != null) {
            sub.append(" · ").append(fm.getString(R.string.fm_trash_left, e.left));
        } else if (fm.mode == M_DIR) {
            sub.append(" · ").append(fm.dateFmt.format(new Date(e.mod)));
        } else {
            File p = e.f.getParentFile();
            if (p != null) sub.append(" · ").append(p.getAbsolutePath());
        }
        h.sub.setText(sub.toString());
        fm.badges.bindBadge(h, e);

        if (fm.favSnap == null) fm.favSnap = fm.ops.favs();
        h.fav.setVisibility(fm.favSnap.contains(path) ? View.VISIBLE : View.GONE);
        boolean pickFiles = "files".equals(fm.pickMode);
        boolean selecting = (!fm.selection.selected.isEmpty() && fm.pickMode == null) || pickFiles;
        boolean showCheck = selecting && !(pickFiles && e.dir);
        h.check.setVisibility(showCheck ? View.VISIBLE : View.GONE);
        Object lastSel = h.check.getTag(R.id.chip_state);
        h.check.setTag(R.id.chip_state, sel);
        if (showCheck && sel && lastSel instanceof Boolean && !((Boolean) lastSel)) Fx.pop(h.check);
        h.check.setImageResource(sel ? R.drawable.ic_check_circle : R.drawable.ic_circle_outline);
        h.check.setImageTintList(ColorStateList.valueOf(Ui.color(fm,
                sel ? R.color.accent_text : R.color.text_hint)));
        if (h.chevron != null) h.chevron.setVisibility(!showCheck && e.dir ? View.VISIBLE : View.GONE);
        if (h.more != null) {
            h.more.setVisibility(fm.pickMode == null && fm.selection.selected.isEmpty() ? View.VISIBLE : View.GONE);
        }
        Ui.shapeRow(fm, h.itemView, fm.gridMode || pos == 0,
                fm.gridMode || pos == fm.shown.size() - 1, sel ? R.color.accent_soft : R.color.surface);
    }

    final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };

    final ExecutorService thumbIo = Executors.newFixedThreadPool(2);

    void loadThumb(final FmEntry e, final int type, final ImageView thumb, final ImageView icon) {
        final String path = e.f.getAbsolutePath();
        final String key = path + "|" + e.mod;
        thumbIo.execute(() -> {
            Bitmap b = null;
            try {
                if (type == T_IMG) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(path, o);
                    int s = 1;
                    int max = Math.max(o.outWidth, o.outHeight);
                    while (max / s > 192) s *= 2;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = Math.max(1, s);
                    b = BitmapFactory.decodeFile(path, o2);
                } else {
                    PackageManager pm = fm.getPackageManager();
                    PackageInfo pi = pm.getPackageArchiveInfo(path, 0);
                    if (pi != null && pi.applicationInfo != null) {
                        ApplicationInfo ai = pi.applicationInfo;
                        ai.sourceDir = path;
                        ai.publicSourceDir = path;
                        Drawable d = ai.loadIcon(pm);
                        int w = Math.max(1, d.getIntrinsicWidth());
                        int h = Math.max(1, d.getIntrinsicHeight());
                        b = Bitmap.createBitmap(Math.min(w, 192), Math.min(h, 192), Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(b);
                        d.setBounds(0, 0, c.getWidth(), c.getHeight());
                        d.draw(c);
                    }
                }
            } catch (Throwable ignored) {
            }
            if (b == null) return;
            final Bitmap fb = b;
            thumbs.put(key, fb);
            fm.post(() -> {
                if (path.equals(thumb.getTag())) {
                    thumb.setImageBitmap(fb);
                    thumb.setVisibility(View.VISIBLE);
                    icon.setVisibility(View.INVISIBLE);
                }
            });
        });
    }
}

package com.ghmanager.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class RowAdapter extends BaseAdapter {
    private final Context ctx;
    private final LayoutInflater inflater;
    private final List<Row> rows = new ArrayList<>();

    public RowAdapter(Context ctx) {
        this.ctx = ctx;
        this.inflater = LayoutInflater.from(ctx);
    }

    public void setRows(List<Row> newRows) {
        rows.clear();
        rows.addAll(newRows);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public Object getItem(int position) {
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View v = convertView != null ? convertView : inflater.inflate(R.layout.item_row, parent, false);
        bind(ctx, v, rows.get(position));
        Ui.shapeRow(ctx, v, position == 0, position == rows.size() - 1);
        return v;
    }

    /** Fills an inflated item_row view. Shared by lists and by programmatic screens. */
    public static void bind(Context ctx, View v, Row r) {
        ImageView icon = v.findViewById(R.id.icon);
        icon.setImageResource(r.icon);
        boolean colored = r.iconColor != 0;
        icon.setBackground(null);
        int tint = colored ? r.iconColor : ContextCompat.getColor(ctx, R.color.text_primary);
        icon.setImageTintList(ColorStateList.valueOf(tint));

        ((TextView) v.findViewById(R.id.title)).setText(r.title);

        TextView sub = v.findViewById(R.id.sub);
        if (r.sub == null || r.sub.isEmpty()) {
            sub.setVisibility(View.GONE);
        } else {
            sub.setText(r.sub);
            sub.setVisibility(View.VISIBLE);
        }

        TextView badge = v.findViewById(R.id.badge);
        if (r.badge == null || r.badge.isEmpty()) {
            badge.setVisibility(View.GONE);
        } else {
            badge.setText(r.badge);
            badge.setTextColor(r.badgeColor);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Ui.dp(ctx, 100));
            g.setColor((r.badgeColor & 0x00FFFFFF) | 0x26000000);
            badge.setBackground(g);
            badge.setVisibility(View.VISIBLE);
        }

        v.findViewById(R.id.lock).setVisibility(r.lock ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.chevron).setVisibility(r.chevron ? View.VISIBLE : View.INVISIBLE);
    }
}

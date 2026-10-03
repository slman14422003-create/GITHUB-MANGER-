package com.ghmanager.app;

import android.content.Context;
import android.content.res.ColorStateList;
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
        Row r = rows.get(position);

        ImageView icon = v.findViewById(R.id.icon);
        icon.setImageResource(r.icon);
        icon.setBackgroundResource(r.accent ? R.drawable.bg_circle_accent : R.drawable.bg_circle_neutral);
        icon.setImageTintList(ColorStateList.valueOf(
                ContextCompat.getColor(ctx, r.accent ? R.color.accent : R.color.text_secondary)));

        ((TextView) v.findViewById(R.id.title)).setText(r.title);

        TextView sub = v.findViewById(R.id.sub);
        if (r.sub == null || r.sub.isEmpty()) {
            sub.setVisibility(View.GONE);
        } else {
            sub.setText(r.sub);
            sub.setVisibility(View.VISIBLE);
        }
        v.findViewById(R.id.lock).setVisibility(r.lock ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.chevron).setVisibility(r.chevron ? View.VISIBLE : View.INVISIBLE);
        return v;
    }
}

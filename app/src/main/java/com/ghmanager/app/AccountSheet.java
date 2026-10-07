package com.ghmanager.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.List;

/** The quick account switcher opened from the avatar icon at the top of the app. */
public final class AccountSheet {
    private AccountSheet() {
    }

    /** One account line: avatar, name, @login and an "active" pill. */
    public static View row(Activity a, Accounts.Acc acc, boolean active, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackgroundResource(R.drawable.bg_card_ripple);
        r.setClickable(true);
        r.setFocusable(true);
        int p = Ui.dp(a, 12);
        r.setPaddingRelative(p, p, Ui.dp(a, 16), p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, Ui.dp(a, 8));
        r.setLayoutParams(lp);

        ImageView img = new ImageView(a);
        r.addView(img, new LinearLayout.LayoutParams(Ui.dp(a, 44), Ui.dp(a, 44)));
        Avatar.load(img, acc.avatar, 10);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cl.setMarginStart(Ui.dp(a, 12));
        r.addView(col, cl);
        TextView name = new TextView(a);
        name.setText(acc.title());
        name.setTextColor(Ui.color(a, R.color.text_primary));
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        col.addView(name);
        TextView sub = new TextView(a);
        sub.setText(acc.login.isEmpty() ? "GitHub" : "@" + acc.login);
        sub.setTextColor(Ui.color(a, R.color.text_secondary));
        sub.setTextSize(13);
        sub.setSingleLine(true);
        sub.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        col.addView(sub);

        if (active) {
            TextView pill = new TextView(a);
            pill.setText(R.string.acc_active);
            pill.setTextSize(12);
            pill.setTextColor(Ui.color(a, R.color.accent_text));
            pill.setBackgroundResource(R.drawable.bg_pill_accent);
            pill.setPadding(Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 10), Ui.dp(a, 4));
            r.addView(pill);
        }
        if (click != null) r.setOnClickListener(click);
        Ui.press(a, r);
        return r;
    }

    /** A plain action line (icon + text) in the same style. */
    public static View action(Activity a, int icon, int textRes, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackgroundResource(R.drawable.bg_card_ripple);
        r.setClickable(true);
        r.setFocusable(true);
        int p = Ui.dp(a, 14);
        r.setPaddingRelative(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, Ui.dp(a, 8));
        r.setLayoutParams(lp);
        ImageView ic = new ImageView(a);
        ic.setImageResource(icon);
        ic.setColorFilter(Ui.color(a, R.color.accent_text));
        r.addView(ic, new LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)));
        TextView t = new TextView(a);
        t.setText(textRes);
        t.setTextColor(Ui.color(a, R.color.text_primary));
        t.setTextSize(15);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(Ui.dp(a, 14));
        r.addView(t, tl);
        r.setOnClickListener(click);
        Ui.press(a, r);
        return r;
    }

    public static void show(final Activity a) {
        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout box = Ui.box(a);
        Accounts.Acc cur = Accounts.active(a);
        List<Accounts.Acc> list = Accounts.all(a);
        for (final Accounts.Acc acc : list) {
            final boolean isActive = cur != null && acc.id.equals(cur.id);
            box.addView(row(a, acc, isActive, v -> {
                if (holder[0] != null) holder[0].dismiss();
                if (!isActive) Accounts.switchTo(a, acc.id);
            }));
        }
        box.addView(action(a, R.drawable.ic_add, R.string.acc_add, v -> {
            if (holder[0] != null) holder[0].dismiss();
            Intent i = new Intent(a, LoginActivity.class);
            i.putExtra("add", true);
            a.startActivity(i);
        }));
        box.addView(action(a, R.drawable.ic_settings, R.string.acc_manage, v -> {
            if (holder[0] != null) holder[0].dismiss();
            a.startActivity(new Intent(a, AccountActivity.class));
        }));
        holder[0] = new Dlg(a)
                .setTitle(R.string.acc_title)
                .setView(box)
                .setNegativeButton(R.string.close, null)
                .create();
        holder[0].show();
    }
}

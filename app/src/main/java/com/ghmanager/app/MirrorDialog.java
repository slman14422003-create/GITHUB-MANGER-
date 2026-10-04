package com.ghmanager.app;

import android.app.Activity;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The dialog where the user pastes the address of their Cloudflare Worker mirror and tests it. */
public final class MirrorDialog {
    private MirrorDialog() {
    }

    /** Short text for a settings row: "On · host" or "Off". */
    public static String summary(Activity a) {
        String url = Mirror.clean(Store.mirrorUrl(a));
        if (Store.mirrorOn(a) && !url.isEmpty()) {
            return a.getString(R.string.mir_row_sub_on, url.replace("https://", ""));
        }
        return a.getString(R.string.mir_row_sub_off);
    }

    public static void show(final Activity a, final Runnable onSaved) {
        final LinearLayout box = Ui.box(a);
        final TextView help = Ui.body(a, a.getString(R.string.mir_help), 13, R.color.text_secondary);
        help.setPaddingRelative(Ui.dp(a, 4), 0, Ui.dp(a, 4), Ui.dp(a, 8));
        box.addView(help);

        final EditText url = Ui.edit(a, a.getString(R.string.mir_url_hint), Store.mirrorUrl(a));
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setTextDirection(View.TEXT_DIRECTION_LTR);
        final EditText key = Ui.edit(a, a.getString(R.string.mir_key), Store.mirrorKey(a));
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setTextDirection(View.TEXT_DIRECTION_LTR);
        final CheckBox on = Ui.check(a, R.string.mir_enable, Store.mirrorOn(a));
        final TextView result = Ui.body(a, "", 13, R.color.text_secondary);
        result.setPaddingRelative(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
        final Button test = Ui.button(a, R.string.mir_test, false);

        box.addView(url);
        box.addView(key);
        box.addView(on);
        box.addView(test);
        box.addView(result);

        final ExecutorService io = Executors.newSingleThreadExecutor();
        test.setOnClickListener(v -> {
            final String u = url.getText().toString();
            final String k = key.getText().toString().trim();
            if (Mirror.clean(u).isEmpty()) {
                result.setTextColor(Ui.color(a, R.color.bad));
                result.setText(R.string.mir_bad_url);
                return;
            }
            result.setTextColor(Ui.color(a, R.color.text_secondary));
            result.setText(R.string.mir_testing);
            test.setEnabled(false);
            io.execute(() -> {
                String err = null;
                try {
                    Mirror.test(u, k);
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String fe = err;
                a.runOnUiThread(() -> {
                    test.setEnabled(true);
                    if (fe == null) {
                        result.setTextColor(Ui.color(a, R.color.ok));
                        result.setText(R.string.mir_ok);
                    } else {
                        result.setTextColor(Ui.color(a, R.color.bad));
                        result.setText(a.getString(R.string.mir_fail, fe));
                    }
                });
            });
        });

        AlertDialog d = new Dlg(a)
                .setTitle(R.string.mir_title)
                .setView(box)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.setOnDismissListener(x -> io.shutdown());
        d.show();
        // validate before closing: the dialog stays open when the address is not usable
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String u = url.getText().toString().trim();
            boolean enable = on.isChecked();
            if (!u.isEmpty() && Mirror.clean(u).isEmpty()) {
                result.setTextColor(Ui.color(a, R.color.bad));
                result.setText(R.string.mir_bad_url);
                return;
            }
            if (enable && u.isEmpty()) {
                result.setTextColor(Ui.color(a, R.color.bad));
                result.setText(R.string.mir_bad_url);
                return;
            }
            Store.setMirror(a, enable, Mirror.clean(u), key.getText().toString().trim());
            Mirror.load(a);
            Toast.makeText(a, R.string.acc_saved, Toast.LENGTH_SHORT).show();
            d.dismiss();
            if (onSaved != null) onSaved.run();
        });
    }
}

package com.ghmanager.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import java.security.SecureRandom;
import java.util.Locale;

public class VerifyActivity extends Activity {
    static final String EXTRA_TOKEN = "tok";
    private static final int REQ = 78;
    private static final int NOTE = 7801;
    private static final String CHANNEL = "verify_code";
    private String code;
    private long expires;
    private int tries = 3;
    private long tok = -1L;
    private boolean totp;
    private boolean done;
    private String method = "notify";
    private EditText input;

    @Override
    protected void onCreate(Bundle b) {
        Themes.apply(this);
        super.onCreate(b);
        tok = getIntent().getLongExtra(EXTRA_TOKEN, -1L);
        totp = TotpVault.enabled(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Ui.color(this, R.color.bg));
        int p = Ui.dp(this, 32);
        root.setPadding(p, p, p, p);
        TextView title = new TextView(this);
        title.setText(totp ? R.string.verify_title_totp : R.string.verify_title);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Ui.color(this, R.color.text_primary));
        root.addView(title);
        input = new EditText(this);
        if (totp) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            input.setTextSize(24);
            input.setHint(R.string.verify_totp_hint);
        } else {
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setTextSize(28);
        }
        input.setGravity(Gravity.CENTER);
        input.setTextColor(Ui.color(this, R.color.text_primary));
        input.setHintTextColor(Ui.color(this, R.color.text_hint));
        input.setEnabled(false);
        root.addView(input, new LinearLayout.LayoutParams(Ui.dp(this, totp ? 280 : 220), -2));
        Button ok = Ui.button(this, R.string.verify_confirm, true);
        ok.setOnClickListener(v -> check());
        root.addView(ok, new LinearLayout.LayoutParams(Ui.dp(this, totp ? 280 : 220), -2));
        setContentView(root);
        Fx.slideIn(root);

        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        Intent i = km == null ? null : km.createConfirmDeviceCredentialIntent(
                getString(R.string.app_name), getString(R.string.verify_title));
        if (i == null) {
            finish();
            return;
        }
        if (b == null) startActivityForResult(i, REQ);
        else finish();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ) return;
        if (res != RESULT_OK || (!totp && !send())) {
            finish();
            return;
        }
        if (totp) expires = SystemClock.elapsedRealtime() + 110_000L;
        input.setEnabled(true);
        input.requestFocus();
    }

    private boolean send() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.verify_enable), NotificationManager.IMPORTANCE_HIGH));
        }
        if (!nm.areNotificationsEnabled()) {
            Toast.makeText(this, R.string.verify_need_notifications, Toast.LENGTH_LONG).show();
            return false;
        }
        code = String.format(Locale.US, "%06d", new SecureRandom().nextInt(1_000_000));
        expires = SystemClock.elapsedRealtime() + 90_000L;
        nm.notify(NOTE, new NotificationCompat.Builder(this, CHANNEL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSmallIcon(R.drawable.ic_lock)
                .setContentTitle(getString(R.string.verify_code_title))
                .setContentText(code)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setTimeoutAfter(90_000L)
                .build());
        return true;
    }

    private boolean accepted(String given) {
        if (totp) {
            String digits = given.replace(" ", "");
            if (digits.matches("\\d{6}")) {
                method = "totp";
                return TotpVault.verify(this, digits);
            }
            method = "recovery";
            if (RecoveryCodes.consume(this, given)) {
                int left = RecoveryCodes.remaining(this);
                Toast.makeText(this, getString(R.string.verify_recovery_left, left), Toast.LENGTH_LONG).show();
                return true;
            }
            return false;
        }
        return code != null && code.equals(given);
    }

    private void check() {
        if (!totp && code == null) return;
        if (!input.isEnabled()) return;
        if (SystemClock.elapsedRealtime() > expires) {
            finish();
        } else if (accepted(input.getText().toString().trim())) {
            done = true;
            AuditLog.event(this, "verify_ok_" + method);
            Verify.finish(tok, true);
            finish();
        } else if (--tries <= 0) {
            finish();
        } else {
            input.setText("");
            Fx.shake(input);
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            getSystemService(NotificationManager.class).cancel(NOTE);
            if (!done) AuditLog.event(this, "verify_failed");
            Verify.finish(tok, false);
        }
        super.onDestroy();
    }
}

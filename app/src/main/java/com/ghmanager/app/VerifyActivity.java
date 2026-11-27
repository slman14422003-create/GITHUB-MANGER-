package com.ghmanager.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.graphics.Typeface;
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
    private LinearLayout codeCard;
    private TextView codeView;
    private TextView timerView;
    private String plain = "";
    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshCode();
            tickHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        Themes.apply(this);
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
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
        codeCard = new LinearLayout(this);
        codeCard.setOrientation(LinearLayout.VERTICAL);
        codeCard.setGravity(Gravity.CENTER);
        codeCard.setBackgroundResource(R.drawable.bg_card);
        int cp = Ui.dp(this, 16);
        codeCard.setPadding(cp, cp, cp, cp);
        codeCard.setVisibility(View.GONE);
        TextView codeLabel = new TextView(this);
        codeLabel.setText(R.string.verify_code_title);
        codeLabel.setTextSize(13);
        codeLabel.setGravity(Gravity.CENTER);
        codeLabel.setTextColor(Ui.color(this, R.color.text_secondary));
        codeCard.addView(codeLabel);
        codeView = new TextView(this);
        codeView.setTextSize(38);
        codeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeView.setLetterSpacing(0.08f);
        codeView.setGravity(Gravity.CENTER);
        codeView.setTextDirection(View.TEXT_DIRECTION_LTR);
        codeView.setTextColor(Ui.color(this, R.color.accent_text));
        codeCard.addView(codeView);
        timerView = new TextView(this);
        timerView.setTextSize(13);
        timerView.setGravity(Gravity.CENTER);
        timerView.setTextColor(Ui.color(this, R.color.text_hint));
        codeCard.addView(timerView);
        TextView fillHint = new TextView(this);
        fillHint.setText(R.string.verify_show_hint);
        fillHint.setTextSize(12);
        fillHint.setGravity(Gravity.CENTER);
        fillHint.setTextColor(Ui.color(this, R.color.text_hint));
        fillHint.setPadding(0, Ui.dp(this, 6), 0, 0);
        codeCard.addView(fillHint);
        codeCard.setOnClickListener(v -> {
            if (plain.isEmpty()) return;
            input.setText(plain);
            input.setSelection(input.getText().length());
        });
        Ui.press(this, codeCard);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(Ui.dp(this, totp ? 280 : 220), -2);
        cardLp.topMargin = Ui.dp(this, 16);
        cardLp.bottomMargin = Ui.dp(this, 8);
        root.addView(codeCard, cardLp);
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
        if (res != RESULT_OK) {
            finish();
            return;
        }
        if (totp) {
            expires = SystemClock.elapsedRealtime() + 110_000L;
            tickHandler.removeCallbacks(ticker);
            tickHandler.post(ticker);
        } else {
            send();
        }
        codeCard.setVisibility(View.VISIBLE);
        input.setEnabled(true);
        input.requestFocus();
    }

    private void refreshCode() {
        if (!totp) return;
        try {
            Totp.Signer s = TotpVault.signer(this);
            if (s == null) return;
            long now = System.currentTimeMillis();
            plain = Totp.code(s, Totp.step(now));
            codeView.setText(plain.substring(0, 3) + " " + plain.substring(3));
            long left = Totp.PERIOD - (now / 1000L) % Totp.PERIOD;
            timerView.setText(getString(R.string.verify_totp_left, (int) left));
        } catch (Exception ignored) {
        }
    }

    private void send() {
        code = String.format(Locale.US, "%06d", new SecureRandom().nextInt(1_000_000));
        expires = SystemClock.elapsedRealtime() + 90_000L;
        plain = code;
        codeView.setText(code.substring(0, 3) + " " + code.substring(3));
        timerView.setText(R.string.verify_code_valid);
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                        getString(R.string.verify_enable), NotificationManager.IMPORTANCE_HIGH));
            }
            if (nm.areNotificationsEnabled()) {
                nm.notify(NOTE, new NotificationCompat.Builder(this, CHANNEL)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setSmallIcon(R.drawable.ic_lock)
                        .setContentTitle(getString(R.string.verify_code_title))
                        .setContentText(code)
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setTimeoutAfter(90_000L)
                        .build());
            }
        } catch (Exception ignored) {
        }
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
        tickHandler.removeCallbacks(ticker);
        if (isFinishing()) {
            getSystemService(NotificationManager.class).cancel(NOTE);
            if (!done) AuditLog.event(this, "verify_failed");
            Verify.finish(tok, false);
        }
        super.onDestroy();
    }
}

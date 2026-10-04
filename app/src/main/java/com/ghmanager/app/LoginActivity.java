package com.ghmanager.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sign in / add an account. Two ways: "Continue with GitHub" (device flow: GitHub's own page offers
 * Google, e-mail + password or a passkey) and a personal access token.
 */
public class LoginActivity extends AppCompatActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final long SPLASH_MS = 900;

    private boolean destroyed = false;
    private DeviceFlow.Code dev;
    private long deadline;
    private int interval = 5;

    private Button btnOauth;
    private Button loginBtn;
    private View codePanel;
    private TextView codeText;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            pollOnce();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        if (!Integrity.verify(this)) {
            new Dlg(this)
                    .setTitle(R.string.integrity_title)
                    .setMessage(R.string.integrity_msg)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok, (d, w) -> finishAffinity())
                    .show();
            return;
        }
        final long start = SystemClock.uptimeMillis();
        splash.setKeepOnScreenCondition(() -> SystemClock.uptimeMillis() - start < SPLASH_MS);
        splash.setOnExitAnimationListener(provider -> provider.getView().animate()
                .alpha(0f)
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(220)
                .withEndAction(provider::remove)
                .start());
        final boolean add = getIntent().getBooleanExtra("add", false);
        final boolean reauth = getIntent().getBooleanExtra("reauth", false);
        if (!add && !reauth && !Store.getToken(this).isEmpty()) {
            ui.postDelayed(() -> {
                if (isFinishing()) return;
                startActivity(new Intent(LoginActivity.this, ReposActivity.class));
                finish();
            }, SPLASH_MS);
            return;
        }
        setContentView(R.layout.activity_login);
        setTitle(R.string.app_name);

        View close = findViewById(R.id.btnClose);
        if (add || reauth) {
            close.setVisibility(View.VISIBLE);
            close.setOnClickListener(v -> finish());
            Ui.press(this, close);
            if (add) ((TextView) findViewById(R.id.loginTitle)).setText(R.string.login_add_title);
        }

        btnOauth = findViewById(R.id.btnOauth);
        codePanel = findViewById(R.id.codePanel);
        codeText = findViewById(R.id.codeText);
        loginBtn = findViewById(R.id.login);
        final EditText tokenView = findViewById(R.id.token);

        btnOauth.setOnClickListener(v -> {
            if (Store.oauthClientId(this).isEmpty()) {
                clientDialog(this::startFlow);
            } else {
                startFlow();
            }
        });
        findViewById(R.id.btnClient).setOnClickListener(v -> clientDialog(null));
        findViewById(R.id.btnMirror).setOnClickListener(v -> MirrorDialog.show(this, null));
        findViewById(R.id.btnCancelFlow).setOnClickListener(v -> stopFlow());
        findViewById(R.id.btnCopyCode).setOnClickListener(v -> {
            if (dev != null) copyCode(dev.userCode);
        });
        findViewById(R.id.btnOpenGh).setOnClickListener(v -> {
            if (dev != null) openGithub(dev.uri);
        });

        loginBtn.setOnClickListener(v -> {
            final String t = tokenView.getText().toString().trim();
            if (t.isEmpty()) return;
            loginBtn.setEnabled(false);
            complete(t, "token");
        });
    }

    // ------------------------------------------------------------------ device flow

    private void clientDialog(final Runnable then) {
        LinearLayout box = Ui.box(this);
        TextView msg = Ui.body(this, getString(R.string.login_client_msg), 13, R.color.text_secondary);
        msg.setPaddingRelative(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 8));
        final EditText id = Ui.edit(this, getString(R.string.login_client_hint), Store.oauthClientId(this));
        id.setTextDirection(View.TEXT_DIRECTION_LTR);
        box.addView(msg);
        box.addView(id);
        new Dlg(this)
                .setTitle(R.string.login_client_title)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String v = id.getText().toString().trim();
                    Store.setOauthClientId(this, v);
                    if (!v.isEmpty() && then != null) then.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void startFlow() {
        final String cid = Store.oauthClientId(this);
        if (cid.isEmpty()) return;
        btnOauth.setEnabled(false);
        io.execute(() -> {
            try {
                final DeviceFlow.Code c = DeviceFlow.start(cid);
                ui.post(() -> {
                    if (destroyed) return;
                    dev = c;
                    interval = c.interval;
                    deadline = System.currentTimeMillis() + c.expiresIn * 1000L;
                    codeText.setText(c.userCode);
                    codePanel.setVisibility(View.VISIBLE);
                    copyCode(c.userCode);
                    openGithub(c.uri);
                    ui.removeCallbacks(pollTask);
                    ui.postDelayed(pollTask, interval * 1000L);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    btnOauth.setEnabled(true);
                    failed(e);
                });
            }
        });
    }

    private void pollOnce() {
        if (dev == null || destroyed) return;
        if (System.currentTimeMillis() > deadline) {
            stopFlow();
            Toast.makeText(this, R.string.login_expired, Toast.LENGTH_LONG).show();
            return;
        }
        final String cid = Store.oauthClientId(this);
        final String code = dev.deviceCode;
        io.execute(() -> {
            try {
                DeviceFlow.Result r = DeviceFlow.poll(cid, code);
                if (r.token != null) {
                    complete(r.token, "oauth");
                    return;
                }
                if (r.slowDown) interval += 5;
                ui.post(() -> {
                    if (dev != null && !destroyed) ui.postDelayed(pollTask, interval * 1000L);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    stopFlow();
                    String m = e.getMessage() == null ? "" : e.getMessage();
                    if ("expired_token".equals(m)) {
                        Toast.makeText(this, R.string.login_expired, Toast.LENGTH_LONG).show();
                    } else if ("access_denied".equals(m)) {
                        Toast.makeText(this, R.string.login_denied, Toast.LENGTH_LONG).show();
                    } else {
                        failed(e);
                    }
                });
            }
        });
    }

    private void stopFlow() {
        dev = null;
        ui.removeCallbacks(pollTask);
        if (codePanel != null) codePanel.setVisibility(View.GONE);
        if (btnOauth != null) btnOauth.setEnabled(true);
    }

    private void copyCode(String code) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            ClipData clip = ClipData.newPlainText("code", code);
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
            cm.setPrimaryClip(clip);
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void openGithub(String uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    private void failed(Exception e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        Toast.makeText(this, getString(R.string.login_failed, m), Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ finish

    /** Checks the token against GitHub, saves the account and opens the repositories. */
    private void complete(final String token, final String type) {
        io.execute(() -> {
            try {
                final JSONObject u = new GitHubApi(token).getUser();
                ui.post(() -> {
                    if (destroyed) return;
                    Accounts.add(this, token, u, type);
                    if (Store.getToken(this).isEmpty()) {
                        // the Android Keystore refused to keep the token
                        stopFlow();
                        if (loginBtn != null) loginBtn.setEnabled(true);
                        Toast.makeText(this, getString(R.string.invalid_token) + ": Keystore", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Intent i = new Intent(this, ReposActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    stopFlow();
                    if (loginBtn != null) loginBtn.setEnabled(true);
                    Toast.makeText(this, getString(R.string.invalid_token) + ": " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        ui.removeCallbacks(pollTask);
        io.shutdown();
    }
}

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
    private boolean resumed = false;
    /** The account is saved but the app was in the background: Android blocks opening a screen from there. */
    private boolean ready = false;
    private DeviceFlow.Code dev;
    private long deadline;
    private int interval = 5;
    private boolean polling = false;
    private int renewals = 0;
    private static final int MAX_RENEWALS = 4;

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
                .setDuration(Motion.base(this))
                .setInterpolator(Motion.standard())
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
        restoreFlow();
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
        startFlow(true);
    }

    /** @param open true when the user asked for it: copy the code and open GitHub; false = silent renewal. */
    private void startFlow(final boolean open) {
        final String cid = Store.oauthClientId(this);
        if (cid.isEmpty()) return;
        btnOauth.setEnabled(false);
        io.execute(() -> {
            try {
                final DeviceFlow.Code c = DeviceFlow.start(cid);
                ui.post(() -> {
                    if (destroyed) return;
                    applyCode(c, open);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    btnOauth.setEnabled(true);
                    failed(e);
                });
            }
        });
    }

    private void showCode(DeviceFlow.Code c) {
        dev = c;
        interval = c.interval;
        deadline = c.until - 10000L;
        polling = false;
        codeText.setText(c.userCode);
        codePanel.setVisibility(View.VISIBLE);
        btnOauth.setEnabled(false);
    }

    private void applyCode(DeviceFlow.Code c, boolean open) {
        showCode(c);
        DeviceFlow.save(this, c);
        copyCode(c.userCode);
        if (open) {
            renewals = 0;
            openGithub(c.uri);
        } else {
            Toast.makeText(this, R.string.login_renewed, Toast.LENGTH_LONG).show();
        }
        ui.removeCallbacks(pollTask);
        ui.postDelayed(pollTask, interval * 1000L);
    }

    /** The screen was recreated (low memory, rotation, app switch): carry on with the code still valid. */
    private void restoreFlow() {
        if (Store.oauthClientId(this).isEmpty()) return;
        DeviceFlow.Code c = DeviceFlow.load(this);
        if (c == null) return;
        showCode(c);
        ui.removeCallbacks(pollTask);
        ui.post(pollTask);
    }

    /** Asks GitHub for a brand new code without the user doing anything. */
    private void renew() {
        if (renewals >= MAX_RENEWALS) {
            stopFlow();
            Toast.makeText(this, R.string.login_expired, Toast.LENGTH_LONG).show();
            return;
        }
        renewals++;
        dev = null;
        ui.removeCallbacks(pollTask);
        startFlow(false);
    }

    private void pollOnce() {
        if (dev == null || destroyed || polling) return;
        if (System.currentTimeMillis() > deadline) {
            renew();
            return;
        }
        final String cid = Store.oauthClientId(this);
        final String code = dev.deviceCode;
        polling = true;
        io.execute(() -> {
            try {
                DeviceFlow.Result r = DeviceFlow.poll(cid, code);
                if (r.token != null) {
                    ui.post(() -> {
                        polling = false;
                        DeviceFlow.clear(this);
                    });
                    complete(r.token, "oauth");
                    return;
                }
                if (r.slowDown) interval += 5;
                ui.post(() -> {
                    polling = false;
                    if (dev != null && !destroyed && dev.deviceCode.equals(code)) {
                        ui.removeCallbacks(pollTask);
                        ui.postDelayed(pollTask, interval * 1000L);
                    }
                });
            } catch (DeviceFlow.Fatal e) {
                ui.post(() -> {
                    polling = false;
                    String m = e.getMessage() == null ? "" : e.getMessage();
                    if ("expired_token".equals(m)) {
                        renew();
                    } else {
                        stopFlow();
                        if ("access_denied".equals(m)) {
                            Toast.makeText(this, R.string.login_denied, Toast.LENGTH_LONG).show();
                        } else {
                            failed(e);
                        }
                    }
                });
            } catch (Exception e) {
                // network hiccup (browser switch, mobile data): keep the code alive and try again
                ui.post(() -> {
                    polling = false;
                    if (dev != null && !destroyed) {
                        ui.removeCallbacks(pollTask);
                        ui.postDelayed(pollTask, interval * 1000L);
                    }
                });
            }
        });
    }

    private void stopFlow() {
        dev = null;
        polling = false;
        DeviceFlow.clear(this);
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
        if (!SafeUrl.github(uri)) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
            return;
        }
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
        final android.content.Context app = getApplicationContext();
        io.execute(() -> {
            Exception last = null;
            JSONObject user = null;
            // the token is precious: a short network hiccup must not throw it away
            for (int attempt = 0; attempt < 3 && user == null; attempt++) {
                try {
                    user = new GitHubApi(token).getUser();
                } catch (Exception e) {
                    last = e;
                    if (e instanceof GitHubApi.ApiException && ((GitHubApi.ApiException) e).code == 401) break;
                    try {
                        Thread.sleep(1200);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
            final JSONObject u = user;
            final Exception err = last;
            ui.post(() -> {
                if (u == null) {
                    if (destroyed) return;
                    stopFlow();
                    if (loginBtn != null) loginBtn.setEnabled(true);
                    Toast.makeText(this, getString(R.string.invalid_token) + ": "
                            + (err == null ? "" : err.getMessage()), Toast.LENGTH_LONG).show();
                    return;
                }
                Accounts.add(app, token, u, type);
                DeviceFlow.clear(this);
                if (destroyed) return;
                if (Store.getToken(this).isEmpty()) {
                    // the Android Keystore refused to keep the token
                    stopFlow();
                    if (loginBtn != null) loginBtn.setEnabled(true);
                    Toast.makeText(this, getString(R.string.invalid_token) + ": Keystore", Toast.LENGTH_LONG).show();
                    return;
                }
                ready = true;
                if (resumed) goRepos();   // otherwise onResume continues as soon as the user is back
            });
        });
    }

    private void goRepos() {
        if (!ready || isFinishing()) return;
        ready = false;
        Intent i = new Intent(this, ReposActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (ready) {
            goRepos();
        } else if (dev != null) {
            // back from the browser: ask GitHub right now instead of waiting for the next timer tick
            ui.removeCallbacks(pollTask);
            ui.post(pollTask);
        } else if (dev == null && codePanel != null && codePanel.getVisibility() == View.VISIBLE) {
            restoreFlow();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        ui.removeCallbacks(pollTask);
        io.shutdown();
    }
}

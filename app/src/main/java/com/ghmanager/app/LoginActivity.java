package com.ghmanager.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginActivity extends AppCompatActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final long SPLASH_MS = 900;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        final long start = SystemClock.uptimeMillis();
        splash.setKeepOnScreenCondition(() -> SystemClock.uptimeMillis() - start < SPLASH_MS);
        if (!Store.getToken(this).isEmpty()) {
            ui.postDelayed(() -> {
                if (isFinishing()) return;
                startActivity(new Intent(LoginActivity.this, ReposActivity.class));
                finish();
            }, SPLASH_MS);
            return;
        }
        setContentView(R.layout.activity_login);
        setTitle(R.string.app_name);

        final EditText tokenView = findViewById(R.id.token);
        final Button login = findViewById(R.id.login);
        login.setOnClickListener(v -> {
            final String t = tokenView.getText().toString().trim();
            if (t.isEmpty()) return;
            login.setEnabled(false);
            io.execute(() -> {
                try {
                    new GitHubApi(t).getUser();
                    ui.post(() -> {
                        Store.setToken(LoginActivity.this, t);
                        startActivity(new Intent(LoginActivity.this, ReposActivity.class));
                        finish();
                    });
                } catch (Exception e) {
                    ui.post(() -> {
                        login.setEnabled(true);
                        Toast.makeText(LoginActivity.this,
                                getString(R.string.invalid_token) + ": " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}

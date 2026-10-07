package com.ghmanager.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Simple text viewer / editor for local files opened from the in-app file manager. */
public class FileEditActivity extends AppCompatActivity {
    // ---- one shared guard: stops a fast double tap from pushing the same screen twice
    @Override
    public void startActivity(android.content.Intent intent) {
        if (Tap.ok("nav")) super.startActivity(intent);
    }

    @Override
    public void startActivityForResult(android.content.Intent intent, int requestCode) {
        if (Tap.ok("nav")) super.startActivityForResult(intent, requestCode);
    }

    private static final long MAX_BYTES = 2L * 1024 * 1024;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private File file;
    private EditText editor;
    private View loadingBar;
    private boolean dirty = false;
    private boolean suppress = false;
    private boolean editable = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);
        String path = getIntent().getStringExtra("path");
        file = path == null ? null : new File(path);
        if (file == null || !file.isFile()) {
            Toast.makeText(this, R.string.fm_cannot_open, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        ((TextView) findViewById(R.id.title)).setText(file.getName());
        TextView sub = findViewById(R.id.subtitle);
        sub.setText(file.getAbsolutePath());
        findViewById(R.id.btnBack).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        loadingBar = findViewById(R.id.loading);
        if (loadingBar instanceof ProgressBar) Ui.tint(this, (ProgressBar) loadingBar);
        editor = findViewById(R.id.editor);

        ImageButton save = findViewById(R.id.btnA1);
        save.setImageResource(R.drawable.ic_check);
        save.setContentDescription(getString(R.string.save));
        save.setVisibility(View.VISIBLE);
        save.setOnClickListener(v -> save());

        ImageButton reload = findViewById(R.id.btnRefresh);
        reload.setOnClickListener(v -> {
            if (dirty) confirmDiscard(this::load);
            else load();
        });

        editor.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!suppress) dirty = true;
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (dirty) {
                    confirmDiscard(() -> {
                        dirty = false;
                        finish();
                    });
                } else {
                    finish();
                }
            }
        });
        load();
    }

    private void confirmDiscard(final Runnable onYes) {
        new Dlg(this)
                .setTitle(R.string.discard_title)
                .setMessage(R.string.discard_msg)
                .setPositiveButton(R.string.discard, (d, w) -> onYes.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void load() {
        loadingBar.setVisibility(View.VISIBLE);
        io.execute(() -> {
            String text = null;
            int error = 0;
            try {
                if (file.length() > MAX_BYTES) {
                    error = R.string.fm_too_large;
                } else {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    try (InputStream in = new FileInputStream(file)) {
                        byte[] buf = new byte[32 * 1024];
                        int n;
                        while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
                    }
                    byte[] data = bo.toByteArray();
                    for (int i = 0; i < Math.min(data.length, 8192); i++) {
                        if (data[i] == 0) {
                            error = R.string.fm_not_text;
                            break;
                        }
                    }
                    if (error == 0) text = new String(data, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                error = R.string.fm_cannot_open;
            }
            final String t = text;
            final int err = error;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                loadingBar.setVisibility(View.INVISIBLE);
                if (err != 0) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                suppress = true;
                editor.setText(t);
                suppress = false;
                dirty = false;
                editable = file.canWrite();
                editor.setEnabled(true);
                if (!editable) {
                    editor.setKeyListener(null);
                    Toast.makeText(this, R.string.fm_read_only, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void save() {
        if (!editable) {
            Toast.makeText(this, R.string.fm_read_only, Toast.LENGTH_SHORT).show();
            return;
        }
        final byte[] data = editor.getText().toString().getBytes(StandardCharsets.UTF_8);
        loadingBar.setVisibility(View.VISIBLE);
        io.execute(() -> {
            boolean ok = true;
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(data);
            } catch (Exception e) {
                ok = false;
            }
            final boolean fOk = ok;
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                loadingBar.setVisibility(View.INVISIBLE);
                if (fOk) dirty = false;
                Toast.makeText(this, fOk ? R.string.saved : R.string.fm_save_failed, Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }
}

package com.ghmanager.app;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** View / edit / create a text file and commit it straight to the branch. */
public class EditorActivity extends BaseRepoActivity {
    private static final int MAX_BYTES = 1536 * 1024;

    private String path;
    private boolean isNew;
    private String sha = null;
    private boolean dirty = false;
    private boolean suppress = false;
    private boolean editable = true;
    private EditText editor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);
        path = getIntent().getStringExtra("path");
        if (path == null) path = "";
        isNew = getIntent().getBooleanExtra("new", false);
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        bindHeader(name, path);
        statusView = findViewById(R.id.status);
        editor = findViewById(R.id.editor);

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

        btnRefresh.setOnClickListener(v -> {
            if (isNew) return;
            if (dirty) confirm(getString(R.string.discard_title), getString(R.string.discard_msg),
                    R.string.discard, this::load);
            else load();
        });
        action(btnA1, R.drawable.ic_check, R.string.save, v -> saveDialog());
        action(btnA2, R.drawable.ic_more, R.string.more, v -> moreMenu());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (dirty) {
                    confirm(getString(R.string.discard_title), getString(R.string.discard_msg),
                            R.string.discard, () -> {
                                dirty = false;
                                getOnBackPressedDispatcher().onBackPressed();
                            });
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (isNew) {
            editor.requestFocus();
        } else {
            load();
        }
    }

    private void setText(String t) {
        suppress = true;
        editor.setText(t);
        suppress = false;
        dirty = false;
    }

    private void lockEditor(int msgRes) {
        editable = false;
        editor.setEnabled(false);
        btnA1.setVisibility(View.GONE);
        showStatus(getString(msgRes));
    }

    private static boolean isText(byte[] b) {
        for (int i = 0; i < Math.min(b.length, 8000); i++) {
            if (b[i] == 0) return false;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private void load() {
        loading(true);
        final String p = path;
        final String b = branch;
        io.execute(() -> {
            try {
                JSONObject meta = api.getContent(owner, repo, p, b);
                final String newSha = meta.optString("sha");
                long size = meta.optLong("size");
                byte[] bytes = null;
                if (size <= MAX_BYTES) {
                    String enc = meta.optString("encoding");
                    String c = meta.optString("content");
                    if ("base64".equals(enc) && !c.isEmpty()) bytes = Base64.decode(c, Base64.DEFAULT);
                    else bytes = api.getFileBytes(owner, repo, p, b, MAX_BYTES);
                }
                final byte[] fb = bytes;
                post(() -> {
                    loading(false);
                    sha = newSha;
                    if (fb == null) {
                        lockEditor(R.string.file_too_large);
                    } else if (!isText(fb)) {
                        lockEditor(R.string.file_binary);
                    } else {
                        showStatus(null);
                        setText(new String(fb, StandardCharsets.UTF_8));
                    }
                });
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void saveDialog() {
        if (!editable) return;
        final String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        LinearLayout box = Ui.box(this);
        final EditText msg = Ui.edit(this, getString(R.string.commit_message),
                getString(isNew ? R.string.create_file_commit : R.string.update_file_commit, name));
        box.addView(msg);
        new AlertDialog.Builder(this)
                .setTitle(R.string.commit_changes)
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String m = msg.getText().toString().trim();
                    final String message = m.isEmpty() ? getString(R.string.default_commit) : m;
                    final byte[] data = editor.getText().toString().getBytes(StandardCharsets.UTF_8);
                    final String currentSha = sha;
                    showProgress(getString(R.string.committing));
                    bg(() -> {
                        JSONObject res = api.putFileContent(owner, repo, path, data, message, branch, currentSha);
                        JSONObject content = res.optJSONObject("content");
                        final String newSha = content == null ? currentSha : content.optString("sha");
                        post(() -> {
                            hideProgress();
                            sha = newSha;
                            isNew = false;
                            dirty = false;
                            toast(R.string.saved);
                        });
                    });
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void moreMenu() {
        String[] items = {getString(R.string.copy_content), getString(R.string.download),
                getString(R.string.open_in_github)};
        choose(getString(R.string.more), items, (d, which) -> {
            if (which == 0) {
                copy("file", editor.getText().toString());
            } else if (which == 1) {
                if (isNew) {
                    toast(R.string.save_first);
                    return;
                }
                final String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                saveAs(name, () -> api.openDownload(api.contentRawPath(owner, repo, path, branch),
                        "application/vnd.github.raw"));
            } else {
                openUrl(webUrl("/blob/" + branch + "/" + path));
            }
        });
    }
}

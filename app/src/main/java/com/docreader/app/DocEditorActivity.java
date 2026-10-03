package com.docreader.app;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.docreader.app.pdf.DocSpeaker;
import com.docreader.app.util.FileTypeUtils;
import com.docreader.app.viewer.DocxHtmlRenderer;
import com.docreader.app.viewer.DocxWriter;
import com.docreader.app.viewer.XlsxModel;
import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONArray;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * محرّر Word (.docx) وExcel (.xlsx): واجهة شريط أدوات على نمط Office داخل WebView
 * (assets/editor/word.html و excel.html) + حفظ إلى الملف الأصلي أو نسخة جديدة + قارئ صوتي.
 */
public class DocEditorActivity extends AppCompatActivity {

    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_NAME = "extra_name";

    private static final int MENU_SAVE = 1, MENU_SAVE_AS = 2, MENU_SHARE = 3, MENU_READ = 4;
    private static final float[] SPEEDS = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private WebView web;
    private View progress;
    private TextView errorView;
    private MaterialToolbar toolbar;
    private View ttsBar;
    private TextView ttsStatus, ttsSpeed;
    private ImageButton ttsPlay;

    private Uri uri;
    private String name;
    private boolean isExcel;
    private boolean dirty;
    private boolean overwriteConfirmed;
    private String initialContent = "";

    private DocSpeaker speaker;
    private int speedIdx = 1;
    private boolean speechPrepared;

    // حفظ لاحق عبر منتقي الملفات
    private File pendingFile;
    private Runnable afterSave;
    private final ActivityResultLauncher<String> createDocLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("*/*"), target -> {
                if (target == null || pendingFile == null) {
                    pendingFile = null;
                    afterSave = null;
                    return;
                }
                final File f = pendingFile;
                final Runnable then = afterSave;
                pendingFile = null;
                afterSave = null;
                io.execute(() -> {
                    try {
                        copyFileToUri(f, target, false);
                        main.post(() -> {
                            toast(getString(R.string.editor_saved));
                            markClean();
                            if (then != null) then.run();
                        });
                    } catch (Exception e) {
                        main.post(() -> toast(getString(R.string.editor_save_failed)));
                    }
                });
            });

    private final ActivityResultLauncher<String> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onImageChosen);

    public static void open(Context ctx, Uri uri, String name) {
        Intent i = new Intent(ctx, DocEditorActivity.class);
        i.putExtra(EXTRA_URI, uri.toString());
        i.putExtra(EXTRA_NAME, name);
        ctx.startActivity(i);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_doc_editor);

        toolbar = findViewById(R.id.editorToolbar);
        web = findViewById(R.id.editorWeb);
        progress = findViewById(R.id.editorProgress);
        errorView = findViewById(R.id.editorError);
        ttsBar = findViewById(R.id.edTtsBar);
        ttsStatus = findViewById(R.id.edTtsStatus);
        ttsSpeed = findViewById(R.id.edTtsSpeed);
        ttsPlay = findViewById(R.id.edTtsPlay);

        applyInsets();

        String u = getIntent().getStringExtra(EXTRA_URI);
        name = getIntent().getStringExtra(EXTRA_NAME);
        toolbar.setTitle(name != null ? name : "");
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        buildMenu();
        if (u == null) {
            showError(getString(R.string.error_loading));
            return;
        }
        uri = Uri.parse(u);
        isExcel = FileTypeUtils.detect(name) == FileTypeUtils.DocType.XLSX;

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!dirty) {
                    finish();
                    return;
                }
                new AlertDialog.Builder(DocEditorActivity.this)
                        .setTitle(R.string.editor_unsaved_title)
                        .setMessage(R.string.editor_unsaved_msg)
                        .setPositiveButton(R.string.editor_save, (d, w) -> requestSave("save", DocEditorActivity.this::finish))
                        .setNegativeButton(R.string.editor_discard, (d, w) -> {
                            dirty = false;
                            finish();
                        })
                        .setNeutralButton(R.string.cancel, null)
                        .show();
            }
        });

        setupWeb();
        setupTts();
        loadContent();
    }

    private void applyInsets() {
        View root = findViewById(R.id.editorRoot);
        final int top = toolbar.getPaddingTop();
        final int bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(toolbar.getPaddingLeft(), top + bars.top, toolbar.getPaddingRight(), toolbar.getPaddingBottom());
            root.setPadding(root.getPaddingLeft(), root.getPaddingTop(), root.getPaddingRight(), bottom + bars.bottom);
            return insets;
        });
    }

    private void buildMenu() {
        Menu m = toolbar.getMenu();
        m.add(0, MENU_READ, 0, R.string.editor_read_aloud).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        m.add(0, MENU_SAVE, 1, R.string.editor_save).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        m.add(0, MENU_SAVE_AS, 2, R.string.editor_save_as).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        m.add(0, MENU_SHARE, 3, R.string.editor_share).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        toolbar.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_SAVE:
                    requestSave("save", null);
                    return true;
                case MENU_SAVE_AS:
                    requestSave("saveas", null);
                    return true;
                case MENU_SHARE:
                    requestSave("share", null);
                    return true;
                case MENU_READ:
                    toggleSpeech();
                    return true;
                default:
                    return false;
            }
        });
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
    }

    private void loadContent() {
        io.execute(() -> {
            File temp = new File(getCacheDir(), "edit_src_" + System.nanoTime());
            try {
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(temp)) {
                    if (in == null) throw new IllegalStateException("no stream");
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                String content = isExcel ? XlsxModel.read(temp) : DocxHtmlRenderer.render(temp);
                main.post(() -> {
                    initialContent = content;
                    progress.setVisibility(View.GONE);
                    web.loadUrl("file:///android_asset/editor/" + (isExcel ? "excel.html" : "word.html"));
                });
            } catch (Throwable t) {
                main.post(() -> showError(getString(R.string.error_loading)));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        });
    }

    private void showError(String msg) {
        progress.setVisibility(View.GONE);
        web.setVisibility(View.GONE);
        errorView.setText(msg);
        errorView.setVisibility(View.VISIBLE);
    }

    private boolean isNight() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void js(String script) {
        web.evaluateJavascript(script, null);
    }

    /** الجسر بين JavaScript وجافا. تُستدعى دوالّه من خيط الجسر لا من الخيط الرئيسي. */
    private final class Bridge {
        @JavascriptInterface
        public String getInitialContent() {
            return initialContent;
        }

        @JavascriptInterface
        public boolean isNight() {
            return DocEditorActivity.this.isNight();
        }

        @JavascriptInterface
        public void onDirty(boolean d) {
            main.post(() -> {
                dirty = d;
                toolbar.setTitle((d ? "• " : "") + (name == null ? "" : name));
            });
        }

        @JavascriptInterface
        public void onExported(String mode, String json) {
            handleExported(mode, json);
        }

        @JavascriptInterface
        public void copyText(String text) {
            main.post(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("text", text));
            });
        }

        @JavascriptInterface
        public String pasteText() {
            final String[] out = {""};
            final Object lock = new Object();
            final boolean[] done = {false};
            main.post(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(DocEditorActivity.this);
                        if (t != null) out[0] = t.toString();
                    }
                } catch (Throwable ignored) {
                }
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            });
            synchronized (lock) {
                long end = System.currentTimeMillis() + 1500;
                while (!done[0] && System.currentTimeMillis() < end) {
                    try {
                        lock.wait(100);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            return out[0];
        }

        @JavascriptInterface
        public void toast(String s) {
            main.post(() -> DocEditorActivity.this.toast(s));
        }

        @JavascriptInterface
        public void pickImage() {
            main.post(() -> pickImageLauncher.launch("image/*"));
        }

        @JavascriptInterface
        public void toggleSpeech() {
            main.post(DocEditorActivity.this::toggleSpeech);
        }
    }

    // ------------------------------------------------------------------ الصور

    private void onImageChosen(Uri img) {
        if (img == null) return;
        io.execute(() -> {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(img)) {
                    BitmapFactory.decodeStream(in, null, o);
                }
                int sample = 1;
                while (o.outWidth / sample > 2000 || o.outHeight / sample > 2000) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap bmp;
                try (InputStream in = getContentResolver().openInputStream(img)) {
                    bmp = BitmapFactory.decodeStream(in, null, o2);
                }
                if (bmp == null) throw new IllegalStateException("decode");
                boolean png = bmp.hasAlpha();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 88, bos);
                String data = "data:" + (png ? "image/png" : "image/jpeg") + ";base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                main.post(() -> js("window.onImagePicked && window.onImagePicked('" + data + "')"));
            } catch (Throwable t) {
                main.post(() -> toast(getString(R.string.error_loading)));
            }
        });
    }

    // ------------------------------------------------------------------ الحفظ

    private void requestSave(String mode, Runnable then) {
        afterSave = then;
        js("window.exportAndSave && window.exportAndSave('" + mode + "')");
    }

    private String ext() {
        return isExcel ? ".xlsx" : ".docx";
    }

    private void markClean() {
        dirty = false;
        toolbar.setTitle(name == null ? "" : name);
        js("window.markSaved && window.markSaved()");
    }

    /** يُستدعى من الجسر بعد أن يصدّر JS نموذج المستند. */
    private void handleExported(String mode, String json) {
        final Runnable then = afterSave;
        io.execute(() -> {
            File out = new File(getCacheDir(), "edit_out_" + System.nanoTime() + ext());
            try {
                try (OutputStream os = new FileOutputStream(out)) {
                    if (isExcel) XlsxModel.write(json, os);
                    else DocxWriter.write(json, os);
                }
            } catch (Throwable t) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                main.post(() -> toast(getString(R.string.editor_save_failed)));
                return;
            }
            main.post(() -> dispatchSave(mode, out, then));
        });
    }

    private void dispatchSave(String mode, File out, Runnable then) {
        if ("share".equals(mode)) {
            try {
                Uri shareUri = FileProvider.getUriForFile(this, getPackageName() + ".pdfshare", renamedForShare(out));
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(isExcel ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        : "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
                send.putExtra(Intent.EXTRA_STREAM, shareUri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(send, getString(R.string.editor_share)));
            } catch (Throwable t) {
                toast(getString(R.string.editor_save_failed));
            }
            return;
        }
        if ("saveas".equals(mode)) {
            askCopyTarget(out, then);
            return;
        }
        // حفظ فوق الأصل (بعد تأكيد أول مرة)
        if (!overwriteConfirmed) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.editor_overwrite_title)
                    .setMessage(R.string.editor_overwrite_msg)
                    .setPositiveButton(R.string.editor_overwrite, (d, w) -> {
                        overwriteConfirmed = true;
                        overwriteOriginal(out, then);
                    })
                    .setNegativeButton(R.string.editor_copy, (d, w) -> askCopyTarget(out, then))
                    .setNeutralButton(R.string.cancel, null)
                    .show();
        } else {
            overwriteOriginal(out, then);
        }
    }

    private File renamedForShare(File out) {
        String base = name == null ? "document" : name.replaceAll("\\.[^.]*$", "");
        File f = new File(getCacheDir(), base + ext());
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        if (!out.renameTo(f)) return out;
        return f;
    }

    private void askCopyTarget(File out, Runnable then) {
        pendingFile = out;
        afterSave = then;
        String base = name == null ? "document" : name.replaceAll("\\.[^.]*$", "");
        createDocLauncher.launch(base + " (edited)" + ext());
    }

    private void overwriteOriginal(File out, Runnable then) {
        io.execute(() -> {
            try {
                copyFileToUri(out, uri, true);
                main.post(() -> {
                    toast(getString(R.string.editor_saved));
                    markClean();
                    if (then != null) then.run();
                });
            } catch (Throwable t) {
                // لا صلاحية كتابة على الملف الأصلي: نعرض حفظ نسخة جديدة
                main.post(() -> askCopyTarget(out, then));
            }
        });
    }

    private void copyFileToUri(File src, Uri target, boolean truncate) throws Exception {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream os = getContentResolver().openOutputStream(target, truncate ? "wt" : "w")) {
            if (os == null) throw new IllegalStateException("no output stream");
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        }
    }

    // ------------------------------------------------------------------ القراءة الصوتية

    private void setupTts() {
        speaker = new DocSpeaker(this, new DocSpeaker.Listener() {
            @Override
            public void onItem(int index, int total) {
                ttsStatus.setText((isExcel ? "الصف " : "الفقرة ") + (index + 1) + " / " + total);
                js("window.highlightItem && window.highlightItem(" + index + ")");
            }

            @Override
            public void onPlaying(boolean playing) {
                ttsPlay.setImageResource(playing ? R.drawable.ic_tts_pause : R.drawable.ic_tts_play);
            }

            @Override
            public void onFinished() {
                hideTts();
            }

            @Override
            public void onStatus(String message) {
                toast(message);
            }
        });
        speedIdx = 1;
        float r = speaker.getRate();
        for (int i = 0; i < SPEEDS.length; i++) if (Math.abs(SPEEDS[i] - r) < 0.01f) speedIdx = i;
        updateSpeedLabel();
        ttsPlay.setOnClickListener(v -> speaker.toggle());
        findViewById(R.id.edTtsNext).setOnClickListener(v -> speaker.next());
        findViewById(R.id.edTtsPrev).setOnClickListener(v -> speaker.previous());
        findViewById(R.id.edTtsClose).setOnClickListener(v -> hideTts());
        ttsSpeed.setOnClickListener(v -> {
            speedIdx = (speedIdx + 1) % SPEEDS.length;
            speaker.setRate(SPEEDS[speedIdx]);
            updateSpeedLabel();
        });
    }

    private void updateSpeedLabel() {
        ttsSpeed.setText(SPEEDS[speedIdx] + "x");
    }

    private void toggleSpeech() {
        if (speaker == null) return;
        if (ttsBar.getVisibility() == View.VISIBLE && speechPrepared) {
            speaker.toggle();
            return;
        }
        web.evaluateJavascript("(function(){try{return JSON.stringify({items:window.collectSpeechItems(),start:(window.getSpeechStart?window.getSpeechStart():0)});}catch(e){return '';}})()", value -> {
            try {
                if (value == null || value.equals("null") || value.equals("\"\"")) {
                    toast("لا يوجد نص للقراءة");
                    return;
                }
                String inner = (String) new JSONTokener(value).nextValue();
                org.json.JSONObject o = new org.json.JSONObject(inner);
                JSONArray arr = new JSONArray(o.getString("items"));
                List<String> items = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) items.add(arr.getString(i));
                if (items.isEmpty()) {
                    toast("لا يوجد نص للقراءة");
                    return;
                }
                speaker.setItems(items);
                speaker.setRate(SPEEDS[speedIdx]);
                ttsBar.setVisibility(View.VISIBLE);
                speechPrepared = true;
                speaker.playFrom(o.optInt("start", 0));
            } catch (Throwable t) {
                toast("لا يوجد نص للقراءة");
            }
        });
    }

    private void hideTts() {
        if (speaker != null) speaker.stop();
        ttsBar.setVisibility(View.GONE);
        speechPrepared = false;
        js("window.clearHighlight && window.clearHighlight()");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (speaker != null && speaker.isPlaying()) speaker.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speaker != null) speaker.release();
        io.shutdown();
        if (web != null) {
            web.removeJavascriptInterface("Android");
            web.destroy();
        }
    }
}

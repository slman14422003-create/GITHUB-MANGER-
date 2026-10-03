package com.docreader.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.docreader.app.model.RecentFile;
import com.docreader.app.ui.RecentFilesAdapter;
import com.docreader.app.util.AppDialogs;
import com.docreader.app.pdf.PdfViewerActivity;
import com.docreader.app.util.FileTypeUtils;
import com.docreader.app.util.RecentFilesStore;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private RecyclerView recyclerRecentFiles;
    private TextView textEmptyState;

    private final ActivityResultLauncher<String[]> openDocumentLauncher =
            registerForActivityResult(new OpenDocumentRw(), uri -> {
                if (uri != null) openViewer(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recyclerRecentFiles = findViewById(R.id.recyclerRecentFiles);
        textEmptyState = findViewById(R.id.textEmptyState);
        recyclerRecentFiles.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.cardOpenFile).setOnClickListener(v -> pickDocument());
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnClearRecent).setOnClickListener(v -> confirmClearRecent());

        applyWindowInsets();
    }

    private void applyWindowInsets() {
        View root = findViewById(R.id.mainRoot);
        View header = findViewById(R.id.layoutHeader);
        final int headerPaddingTop = header.getPaddingTop();
        final int rootPaddingBottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            header.setPadding(header.getPaddingLeft(), headerPaddingTop + bars.top,
                    header.getPaddingRight(), header.getPaddingBottom());
            root.setPadding(root.getPaddingLeft(), root.getPaddingTop(),
                    root.getPaddingRight(), rootPaddingBottom + bars.bottom);
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRecentFiles();
    }

    private void pickDocument() {
        openDocumentLauncher.launch(new String[]{
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/rtf",
                "text/rtf",
                "text/html",
                "text/plain",
                "text/csv",
                "text/markdown",
                "image/jpeg",
                "image/png",
                "image/webp",
                "image/bmp",
                "image/gif",
                "*/*"
        });
    }

    /** منتقي ملفات يطلب صلاحية قراءة وكتابة دائمة (لحفظ تعديلات Word/Excel على الملف الأصلي). */
    private static final class OpenDocumentRw extends ActivityResultContracts.OpenDocument {
        @Override
        public Intent createIntent(android.content.Context context, String[] input) {
            Intent i = super.createIntent(context, input);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            return i;
        }
    }

    private void openViewer(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // بعض المزودين لا يدعمون صلاحيات دائمة؛ نتابع بصلاحية مؤقتة
            }
        }

        String displayName = FileTypeUtils.queryDisplayName(this, uri);
        RecentFilesStore.add(this, new RecentFile(
                uri.toString(), displayName, "", System.currentTimeMillis()));

        if (FileTypeUtils.detect(displayName) == FileTypeUtils.DocType.PDF) {
            PdfViewerActivity.open(this, uri);
            return;
        }

        Intent intent = new Intent(this, DocumentViewerActivity.class);
        intent.putExtra(DocumentViewerActivity.EXTRA_URI, uri.toString());
        intent.putExtra(DocumentViewerActivity.EXTRA_NAME, displayName);
        startActivity(intent);
    }

    private void confirmClearRecent() {
        AppDialogs.showConfirm(this,
                getString(R.string.settings_clear_recent_confirm_title),
                getString(R.string.settings_clear_recent_confirm_msg),
                getString(R.string.confirm),
                getString(R.string.cancel),
                true,
                () -> {
                    RecentFilesStore.clear(this);
                    refreshRecentFiles();
                });
    }

    private void refreshRecentFiles() {
        List<RecentFile> files = RecentFilesStore.getAll(this);
        textEmptyState.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerRecentFiles.setAdapter(new RecentFilesAdapter(files, file -> {
            try {
                openViewer(Uri.parse(file.uri));
            } catch (Exception e) {
                // الملف لم يعد متاحًا
            }
        }));
    }
}

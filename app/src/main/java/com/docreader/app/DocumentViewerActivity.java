package com.docreader.app;

import android.content.res.Configuration;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.docreader.app.pdf.PdfViewerActivity;
import com.docreader.app.util.FileTypeUtils;
import com.docreader.app.viewer.DocxHtmlRenderer;
import com.docreader.app.viewer.HtmlPage;
import com.docreader.app.viewer.OoxmlUtil;
import com.docreader.app.viewer.PptxHtmlRenderer;
import com.docreader.app.viewer.RtfHtmlRenderer;
import com.docreader.app.viewer.XlsxHtmlRenderer;
import com.docreader.app.viewer.XlsxParser;
import com.google.android.material.appbar.MaterialToolbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DocumentViewerActivity extends AppCompatActivity {

    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_NAME = "extra_name";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private View progressLoading;
    private View imageScroll;
    private ImageView imageSingle;
    private WebView webViewContent;
    private View layoutError;
    private TextView textError;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        applyWindowInsets(toolbar);

        progressLoading = findViewById(R.id.progressLoading);
        imageScroll = findViewById(R.id.imageScroll);
        imageSingle = findViewById(R.id.imageSingle);
        webViewContent = findViewById(R.id.webViewContent);
        layoutError = findViewById(R.id.layoutError);
        textError = findViewById(R.id.textError);
        webViewContent.getSettings().setLoadWithOverviewMode(true);
        webViewContent.getSettings().setUseWideViewPort(true);

        String uriString = getIntent().getStringExtra(EXTRA_URI);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        toolbar.setTitle(name != null ? name : getString(R.string.viewer_title));

        if (uriString == null) {
            showError(getString(R.string.error_loading));
            return;
        }
        loadDocument(Uri.parse(uriString), name);
    }

    private void applyWindowInsets(MaterialToolbar toolbar) {
        View root = findViewById(R.id.viewerRoot);
        final int toolbarPaddingTop = toolbar.getPaddingTop();
        final int rootPaddingBottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(toolbar.getPaddingLeft(), toolbarPaddingTop + bars.top,
                    toolbar.getPaddingRight(), toolbar.getPaddingBottom());
            root.setPadding(root.getPaddingLeft(), root.getPaddingTop(),
                    root.getPaddingRight(), rootPaddingBottom + bars.bottom);
            return insets;
        });
    }

    private void loadDocument(Uri uri, String name) {
        FileTypeUtils.DocType type = FileTypeUtils.detect(name);

        // ملفات PDF يتولاها قارئ PDF المتقدّم (القراءة الصوتية، التشكيل، الترجمة، البحث، OCR...)
        if (type == FileTypeUtils.DocType.PDF) {
            PdfViewerActivity.open(this, uri);
            finish();
            return;
        }

        // Word / Excel: محرر كامل (تحرير + حفظ + قراءة صوتية)
        if (type == FileTypeUtils.DocType.DOCX || type == FileTypeUtils.DocType.XLSX) {
            DocEditorActivity.open(this, uri, name);
            finish();
            return;
        }

        if (FileTypeUtils.isLegacyBinary(type)) {
            showError(getString(R.string.legacy_format_unsupported));
            return;
        }

        executor.execute(() -> {
            try {
                switch (type) {
                    case DOCX:
                        loadDocx(uri);
                        break;
                    case RTF:
                        loadRtf(uri);
                        break;
                    case XLSX:
                        loadXlsx(uri);
                        break;
                    case CSV:
                        loadCsv(uri);
                        break;
                    case PPTX:
                        loadPptx(uri);
                        break;
                    case TXT:
                        loadTxt(uri);
                        break;
                    case HTML:
                        loadHtml(uri);
                        break;
                    case IMAGE:
                        loadImage(uri);
                        break;
                    default:
                        mainHandler.post(() -> showError(getString(R.string.unsupported_format)));
                }
            } catch (Exception e) {
                mainHandler.post(() -> showError(getString(R.string.error_loading)));
            }
        });
    }

    // ---------- DOCX (قارئ حقيقي كامل التنسيق عبر HTML) ----------
    private void loadDocx(Uri uri) throws Exception {
        File temp = copyToTemp(uri, "temp_view.docx");
        String fragment;
        try {
            fragment = DocxHtmlRenderer.render(temp);
        } finally {
            temp.delete();
        }
        displayHtml(HtmlPage.wrap(fragment, isNightMode(), ""));
    }

    // ---------- RTF (تنسيقات حقيقية: عريض/مائل/تسطير/ألوان/جداول/صور/روابط) ----------
    private void loadRtf(Uri uri) throws Exception {
        File temp = copyToTemp(uri, "temp_view.rtf");
        String fragment;
        try {
            fragment = RtfHtmlRenderer.render(temp);
        } finally {
            temp.delete();
        }
        displayHtml(HtmlPage.wrap(fragment, isNightMode(), ""));
    }

    // ---------- TXT / Markdown كنص ----------
    private void loadTxt(Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in != null) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) > 0) sb.append(new String(buffer, 0, len, "UTF-8"));
            }
        }
        displayHtml(HtmlPage.wrap(txtToHtml(sb.toString()), isNightMode(), ""));
    }

    private static String txtToHtml(String text) {
        StringBuilder out = new StringBuilder();
        out.append("<div class=\"paper doc\" style=\"font-family:'Segoe UI',Roboto,'Noto Sans Arabic','Noto Naskh Arabic',Arial,sans-serif;line-height:1.6\">");
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] paragraphs = normalized.split("\n{2,}");
        boolean any = false;
        for (String p : paragraphs) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            any = true;
            boolean rtl = Boolean.TRUE.equals(OoxmlUtil.firstStrongIsRtl(t));
            out.append("<p").append(rtl ? " dir=\"rtl\"" : "").append(" style=\"white-space:pre-wrap;overflow-wrap:anywhere;margin:0 0 1em\">")
                    .append(HtmlPage.esc(t)).append("</p>");
        }
        if (!any) out.append("<p class=\"empty\">المستند فارغ</p>");
        out.append("</div>");
        return out.toString();
    }

    // ---------- XLSX (قارئ حقيقي: تنسيق أرقام/تواريخ، ألوان، دمج خلايا، أوراق متعددة) ----------
    private void loadXlsx(Uri uri) throws Exception {
        File temp = copyToTemp(uri, "temp_view.xlsx");
        String fragment;
        try {
            fragment = XlsxHtmlRenderer.render(temp);
        } finally {
            temp.delete();
        }
        displayHtml(HtmlPage.wrap(fragment, isNightMode(), ""));
    }

    // ---------- CSV ----------
    private void loadCsv(Uri uri) throws Exception {
        List<List<String>> rows;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            rows = XlsxParser.parseCsv(in);
        }
        displayHtml(HtmlPage.wrap(csvToHtml(rows), isNightMode(), ""));
    }

    private static String csvToHtml(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<style>.doc table{border-collapse:collapse;width:100%;font-size:14px}")
                .append(".doc td{border:1px solid #ddd;padding:6px 10px;text-align:start;vertical-align:top}")
                .append(".doc tr:first-child td{font-weight:bold;background:#F4F3EF}</style>");
        sb.append("<div class=\"paper doc\"><table>");
        boolean any = false;
        for (List<String> row : rows) {
            any = true;
            sb.append("<tr>");
            for (String cell : row) sb.append("<td dir=\"auto\">").append(HtmlPage.esc(cell == null ? "" : cell)).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</table>");
        if (!any) sb.append("<p class=\"empty\">المستند فارغ</p>");
        sb.append("</div>");
        return sb.toString();
    }

    // ---------- PPTX (قارئ حقيقي: مواضع فعلية، وراثة أنماط، جداول، صور، تنقّل بالسحب) ----------
    private void loadPptx(Uri uri) throws Exception {
        File temp = copyToTemp(uri, "temp_view.pptx");
        String fragment;
        try {
            fragment = PptxHtmlRenderer.render(temp);
        } finally {
            temp.delete();
        }
        displayHtml(HtmlPage.wrap(fragment, isNightMode(), ""));
    }

    // ---------- HTML ----------
    private void loadHtml(Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in != null) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) > 0) sb.append(new String(buffer, 0, len, "UTF-8"));
            }
        }
        displayHtml(sb.toString());
    }

    // ---------- صور مستقلة ----------
    private void loadImage(Uri uri) throws Exception {
        mainHandler.post(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                imageSingle.setImageBitmap(BitmapFactory.decodeStream(in));
                showContentView(imageScroll);
            } catch (Exception e) {
                showError(getString(R.string.error_loading));
            }
        });
    }

    // ---------- أدوات مشتركة ----------

    /** ينسخ محتوى Uri إلى ملف مؤقت في ذاكرة التخزين المؤقت؛ مطلوب لأن مُحوّلات
     * HTML الجديدة تفتح المستند كأرشيف ZIP (ZipFile) يحتاج ملفاً حقيقياً قابلاً
     * للوصول العشوائي، وليس مجرد InputStream متدفّق. */
    private File copyToTemp(Uri uri, String fileName) throws Exception {
        File temp = new File(getCacheDir(), fileName);
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[8192];
            int len;
            while (in != null && (len = in.read(buffer)) > 0) out.write(buffer, 0, len);
        }
        return temp;
    }

    private boolean isNightMode() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void displayHtml(String fullHtml) {
        mainHandler.post(() -> {
            webViewContent.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null);
            showContentView(webViewContent);
        });
    }

    private void showContentView(View view) {
        progressLoading.setVisibility(View.GONE);
        imageScroll.setVisibility(view == imageScroll ? View.VISIBLE : View.GONE);
        webViewContent.setVisibility(view == webViewContent ? View.VISIBLE : View.GONE);
        layoutError.setVisibility(View.GONE);
    }

    private void showError(String message) {
        progressLoading.setVisibility(View.GONE);
        layoutError.setVisibility(View.VISIBLE);
        textError.setText(message);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}

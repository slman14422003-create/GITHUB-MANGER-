package com.ghmanager.app;

import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static com.ghmanager.app.FmFiles.*;

final class FmFiles {
    private FmFiles() {
    }

    static final int T_DIR = 0, T_IMG = 1, T_VID = 2, T_AUD = 3, T_ARC = 4, T_APK = 5,
            T_TXT = 6, T_PDF = 7, T_OTHER = 8;

    static boolean isInside(File child, File parent) {
        String c = child.getAbsolutePath();
        String p = parent.getAbsolutePath();
        return c.equals(p) || c.startsWith(p.endsWith("/") ? p : p + "/");
    }

    static boolean isLink(File f) {
        try {
            return !f.getCanonicalPath().equals(f.getAbsolutePath());
        } catch (IOException e) {
            return true;
        }
    }

    static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 || i == name.length() - 1 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    static boolean in(String ext, String... list) {
        return Arrays.asList(list).contains(ext);
    }

    static int typeOf(FmEntry e) {
        if (e.dir) return T_DIR;
        String x = e.ext;
        if (in(x, "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")) return T_IMG;
        if (in(x, "mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v")) return T_VID;
        if (in(x, "mp3", "wav", "ogg", "m4a", "aac", "flac", "opus", "amr")) return T_AUD;
        if (in(x, "zip", "rar", "7z", "tar", "gz", "tgz", "xz", "bz2", "jar")) return T_ARC;
        if (x.equals("apk")) return T_APK;
        if (x.equals("pdf")) return T_PDF;
        if (isTextExt(x)) return T_TXT;
        return T_OTHER;
    }

    static boolean isTextExt(String x) {
        return in(x, "txt", "md", "json", "xml", "yml", "yaml", "java", "kt", "kts", "gradle",
                "properties", "js", "ts", "html", "htm", "css", "py", "sh", "c", "cpp", "h", "hpp", "cs",
                "go", "rs", "php", "rb", "sql", "csv", "log", "ini", "cfg", "conf", "toml", "gitignore",
                "pro", "bat", "svg", "tsx", "jsx", "dart", "swift", "lua", "env", "gitattributes");
    }

    static int iconFor(int t) {
        switch (t) {
            case T_DIR:
                return R.drawable.ic_folder;
            case T_IMG:
                return R.drawable.ic_image;
            case T_VID:
                return R.drawable.ic_video;
            case T_AUD:
                return R.drawable.ic_music;
            case T_ARC:
                return R.drawable.ic_archive;
            case T_APK:
                return R.drawable.ic_package;
            case T_TXT:
                return R.drawable.ic_code;
            default:
                return R.drawable.ic_file;
        }
    }

    static int colorFor(int t) {
        switch (t) {
            case T_DIR:
                return R.color.accent_text;
            case T_IMG:
                return R.color.ok;
            case T_VID:
                return R.color.bad;
            case T_AUD:
                return R.color.info;
            case T_ARC:
                return R.color.warn;
            case T_APK:
                return R.color.ok;
            case T_TXT:
                return R.color.accent_text;
            case T_PDF:
                return R.color.bad;
            default:
                return R.color.text_secondary;
        }
    }

    static String mimeOf(File f) {
        String x = extOf(f.getName());
        if (isTextExt(x) && !x.equals("svg") && !x.equals("html") && !x.equals("htm") && !x.equals("json")
                && !x.equals("xml") && !x.equals("csv")) return "text/plain";
        String m = x.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(x);
        return m == null ? "*/*" : m;
    }

    static boolean validName(String n) {
        return !n.isEmpty() && !n.contains("/") && !n.equals(".") && !n.equals("..") && n.length() <= 200;
    }

    static void add(List<String> labels, List<Integer> ids, String l, int id) {
        labels.add(l);
        ids.add(id);
    }

    static boolean hasFile(List<File> files) {
        for (File f : files) if (f.isFile()) return true;
        return false;
    }

    static List<String> paths(List<File> files) {
        List<String> out = new ArrayList<>();
        for (File f : files) out.add(f.getAbsolutePath());
        return out;
    }

    static String clock(long sec) {
        if (sec < 3600) return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
        return String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);
    }

    static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name;
        String ext = "";
        int i = name.lastIndexOf('.');
        if (i > 0) {
            base = name.substring(0, i);
            ext = name.substring(i);
        }
        int n = 1;
        while (true) {
            File c = new File(dir, base + " (" + n + ")" + ext);
            if (!c.exists()) return c;
            n++;
        }
    }

    static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }
}

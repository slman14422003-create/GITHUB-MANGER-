package com.ghmanager.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.util.ArrayList;
import java.util.List;

public class FileScanner {

    public static class Item {
        public final String path;
        public final Uri uri;

        public Item(String path, Uri uri) {
            this.path = path;
            this.uri = uri;
        }
    }

    /** Files and folders chosen in the in-app file manager (plain java.io paths). */
    public static void scanFiles(List<java.io.File> roots, boolean includeFolderName, List<Item> out) {
        scanFiles(roots, includeFolderName, false, out);
    }

    /**
     * Same, and with {@code skipJunk} the picked folder's own .gitignore is honoured (the way git
     * would) and the usual build / cache folders are left out, so what reaches GitHub is what a
     * normal "git add" would have added.
     */
    public static void scanFiles(List<java.io.File> roots, boolean includeFolderName, boolean skipJunk,
                                 List<Item> out) {
        for (java.io.File r : roots) {
            if (r.isDirectory()) {
                Ignore ig = skipJunk ? Ignore.load(r) : null;
                walkFile(r, includeFolderName ? r.getName() + "/" : "", "", ig, out);
            } else if (r.isFile()) {
                out.add(new Item(r.getName(), Uri.fromFile(r)));
            }
        }
    }

    private static void walkFile(java.io.File dir, String prefix, String rel, Ignore ig, List<Item> out) {
        java.io.File[] kids = dir.listFiles();
        if (kids == null) return;
        java.util.Arrays.sort(kids);
        for (java.io.File k : kids) {
            String relPath = rel + k.getName();
            if (k.isDirectory()) {
                if (".git".equals(k.getName())) continue;
                if (ig != null && ig.skip(relPath, true)) continue;
                walkFile(k, prefix + k.getName() + "/", relPath + "/", ig, out);
            } else if (k.isFile()) {
                if (ig != null && ig.skip(relPath, false)) continue;
                out.add(new Item(prefix + k.getName(), Uri.fromFile(k)));
            }
        }
    }

    /** Minimal .gitignore matcher (names, *.ext, dir/, /anchored, **) plus a built-in junk list. */
    static final class Ignore {
        private static final String[] JUNK_DIRS = {"node_modules", "build", ".gradle", ".idea", ".cxx",
                "__pycache__", ".dart_tool", "Pods", ".externalNativeBuild"};
        private static final String[] JUNK_FILES = {".DS_Store", "Thumbs.db", "local.properties", "keystore.properties"};

        private final List<java.util.regex.Pattern> names = new ArrayList<>();
        private final List<Boolean> namesDirOnly = new ArrayList<>();
        private final List<java.util.regex.Pattern> paths = new ArrayList<>();
        private final List<Boolean> pathsDirOnly = new ArrayList<>();

        static Ignore load(java.io.File root) {
            Ignore ig = new Ignore();
            java.io.File f = new java.io.File(root, ".gitignore");
            if (f.isFile() && f.length() < 256 * 1024) {
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                        new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) ig.add(line.trim());
                } catch (Exception ignored) {
                }
            }
            return ig;
        }

        private void add(String line) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return;
            boolean dirOnly = line.endsWith("/");
            if (dirOnly) line = line.substring(0, line.length() - 1);
            boolean anchored = line.startsWith("/");
            if (anchored) line = line.substring(1);
            if (line.isEmpty()) return;
            boolean hasSlash = line.indexOf('/') >= 0;
            StringBuilder re = new StringBuilder();
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '*') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '*') {
                        re.append(".*");
                        i++;
                    } else {
                        re.append("[^/]*");
                    }
                } else if (c == '?') {
                    re.append("[^/]");
                } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                    re.append('\\').append(c);
                } else {
                    re.append(c);
                }
            }
            try {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(re.toString());
                if (hasSlash || anchored) {
                    paths.add(p);
                    pathsDirOnly.add(dirOnly);
                } else {
                    names.add(p);
                    namesDirOnly.add(dirOnly);
                }
            } catch (Exception ignored) {
            }
        }

        boolean skip(String relPath, boolean dir) {
            int slash = relPath.lastIndexOf('/');
            String name = slash < 0 ? relPath : relPath.substring(slash + 1);
            if (dir) {
                for (String j : JUNK_DIRS) if (j.equals(name)) return true;
            } else {
                for (String j : JUNK_FILES) if (j.equals(name)) return true;
                if (name.endsWith(".iml")) return true;
            }
            for (int i = 0; i < names.size(); i++) {
                if (namesDirOnly.get(i) && !dir) continue;
                if (names.get(i).matcher(name).matches()) return true;
            }
            for (int i = 0; i < paths.size(); i++) {
                if (pathsDirOnly.get(i) && !dir) continue;
                if (paths.get(i).matcher(relPath).matches()) return true;
            }
            return false;
        }
    }

    public static String displayName(ContentResolver cr, Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) return new java.io.File(uri.getPath()).getName();
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /** Size in bytes, or -1 when the provider does not report it. */
    public static long size(ContentResolver cr, Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) return new java.io.File(uri.getPath()).length();
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    /** MIME type for a content:// or file:// uri; never null. */
    public static String mime(ContentResolver cr, Uri uri) {
        String t = null;
        try {
            t = cr.getType(uri);
        } catch (Exception ignored) {
        }
        if (t == null) {
            String n = displayName(cr, uri);
            int dot = n == null ? -1 : n.lastIndexOf('.');
            if (dot >= 0) {
                t = android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(n.substring(dot + 1).toLowerCase(java.util.Locale.US));
            }
        }
        return t == null ? "application/octet-stream" : t;
    }

    private static String docName(ContentResolver cr, Uri docUri) {
        Cursor c = null;
        try {
            c = cr.query(docUri, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    public static void scanTree(ContentResolver cr, Uri tree, boolean includeRoot, List<Item> out) {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        String prefix = "";
        if (includeRoot) {
            String n = docName(cr, DocumentsContract.buildDocumentUriUsingTree(tree, rootId));
            if (n != null && !n.isEmpty()) prefix = n + "/";
        }
        walk(cr, tree, rootId, prefix, out);
    }

    private static void walk(ContentResolver cr, Uri tree, String docId, String prefix, List<Item> out) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        List<String[]> kids = new ArrayList<>();
        Cursor c = null;
        try {
            c = cr.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            while (c != null && c.moveToNext()) {
                kids.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
            }
        } finally {
            if (c != null) c.close();
        }
        for (String[] k : kids) {
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(k[2])) {
                if (".git".equals(k[1])) continue;
                walk(cr, tree, k[0], prefix + k[1] + "/", out);
            } else {
                out.add(new Item(prefix + k[1], DocumentsContract.buildDocumentUriUsingTree(tree, k[0])));
            }
        }
    }
}

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
        for (java.io.File r : roots) {
            if (r.isDirectory()) {
                walkFile(r, includeFolderName ? r.getName() + "/" : "", out);
            } else if (r.isFile()) {
                out.add(new Item(r.getName(), Uri.fromFile(r)));
            }
        }
    }

    private static void walkFile(java.io.File dir, String prefix, List<Item> out) {
        java.io.File[] kids = dir.listFiles();
        if (kids == null) return;
        java.util.Arrays.sort(kids);
        for (java.io.File k : kids) {
            if (k.isDirectory()) {
                if (".git".equals(k.getName())) continue;
                walkFile(k, prefix + k.getName() + "/", out);
            } else if (k.isFile()) {
                out.add(new Item(prefix + k.getName(), Uri.fromFile(k)));
            }
        }
    }

    public static String displayName(ContentResolver cr, Uri uri) {
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

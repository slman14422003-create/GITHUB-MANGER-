package com.ghmanager.app;

import java.io.File;

import static com.ghmanager.app.FmFiles.*;

    final class FmEntry {
        final File f;
        final String name;
        final boolean dir;
        final long size;
        final long mod;
        final int kids;
        final String ext;
        final String trashId;
        final String origin;
        final int left;

        FmEntry(Trash.Item t) {
            this.f = t.stored;
            this.name = t.name;
            this.dir = t.dir;
            this.mod = t.deletedAt;
            this.kids = 0;
            this.size = t.size;
            this.ext = t.dir ? "" : extOf(t.name);
            this.trashId = t.id;
            this.origin = t.origin;
            this.left = t.daysLeft();
        }

        FmEntry(File f) {
            this.trashId = null;
            this.origin = null;
            this.left = 0;
            this.f = f;
            this.name = f.getName();
            this.dir = f.isDirectory();
            this.mod = f.lastModified();
            if (dir) {
                String[] l = f.list();
                kids = l == null ? -1 : l.length;
                size = 0;
                ext = "";
            } else {
                kids = 0;
                size = f.length();
                ext = extOf(name);
            }
        }
    }

package com.ghmanager.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PatchLines {
    private PatchLines() {
    }

    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");

    public static final class Line {
        public final char type;
        public final String text;
        public final int oldNo;
        public final int newNo;

        Line(char type, String text, int oldNo, int newNo) {
            this.type = type;
            this.text = text;
            this.oldNo = oldNo;
            this.newNo = newNo;
        }

        public String side() {
            return type == '-' ? "LEFT" : "RIGHT";
        }

        public int number() {
            return type == '-' ? oldNo : newNo;
        }

        public boolean commentable() {
            return (type == '+' || type == '-' || type == ' ') && number() > 0;
        }
    }

    public static String key(String path, String side, int line) {
        return path + "\n" + side + "\n" + line;
    }

    public static List<Line> parse(String patch) {
        List<Line> out = new ArrayList<>();
        if (patch == null || patch.isEmpty()) return out;
        String[] ls = patch.split("\n", -1);
        int n = ls.length;
        if (n > 0 && ls[n - 1].isEmpty()) n--;
        int oldNo = 0;
        int newNo = 0;
        boolean inHunk = false;
        for (int i = 0; i < n; i++) {
            String l = ls[i];
            if (l.endsWith("\r")) l = l.substring(0, l.length() - 1);
            if (l.startsWith("@@")) {
                Matcher m = HUNK.matcher(l);
                if (m.matches()) {
                    oldNo = Integer.parseInt(m.group(1));
                    newNo = Integer.parseInt(m.group(2));
                    inHunk = true;
                } else {
                    inHunk = false;
                    oldNo = 0;
                    newNo = 0;
                }
                out.add(new Line('@', l, 0, 0));
            } else if (l.startsWith("\\")) {
                out.add(new Line('\\', l, 0, 0));
            } else if (!inHunk) {
                out.add(new Line(' ', l, 0, 0));
            } else if (l.startsWith("+")) {
                out.add(new Line('+', l.substring(1), 0, newNo));
                newNo++;
            } else if (l.startsWith("-")) {
                out.add(new Line('-', l.substring(1), oldNo, 0));
                oldNo++;
            } else {
                out.add(new Line(' ', l.startsWith(" ") ? l.substring(1) : l, oldNo, newNo));
                oldNo++;
                newNo++;
            }
        }
        return out;
    }
}

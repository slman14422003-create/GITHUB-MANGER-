package com.ghmanager.app;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LineDiff {
    private LineDiff() {
    }

    private static final int MAX_D = 1500;
    private static final int CHUNK_LINES = 16;
    private static final int MAX_SHOWN_LINES = 2400;

    public static final class Op {
        public final char type;
        public final String text;

        Op(char type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    public static final class Result {
        public final List<Op> ops = new ArrayList<>();
        public int added;
        public int removed;
        public boolean coarse;

        public boolean changed() {
            return added > 0 || removed > 0;
        }
    }

    private static String[] lines(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        String[] parts = s.split("\n", -1);
        if (parts.length > 0 && parts[parts.length - 1].isEmpty()) {
            String[] cut = new String[parts.length - 1];
            System.arraycopy(parts, 0, cut, 0, cut.length);
            parts = cut;
        }
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].endsWith("\r")) parts[i] = parts[i].substring(0, parts[i].length() - 1);
        }
        return parts;
    }

    public static Result compute(String oldText, String newText) {
        String[] a = lines(oldText);
        String[] b = lines(newText);
        Result r = new Result();
        int pre = 0;
        while (pre < a.length && pre < b.length && a[pre].equals(b[pre])) pre++;
        int suf = 0;
        while (suf < a.length - pre && suf < b.length - pre
                && a[a.length - 1 - suf].equals(b[b.length - 1 - suf])) suf++;
        for (int i = 0; i < pre; i++) r.ops.add(new Op(' ', a[i]));
        String[] ma = new String[a.length - pre - suf];
        String[] mb = new String[b.length - pre - suf];
        System.arraycopy(a, pre, ma, 0, ma.length);
        System.arraycopy(b, pre, mb, 0, mb.length);
        List<Op> mid = myers(ma, mb);
        if (mid == null) {
            r.coarse = true;
            mid = new ArrayList<>();
            for (String s : ma) mid.add(new Op('-', s));
            for (String s : mb) mid.add(new Op('+', s));
        }
        for (Op o : mid) {
            if (o.type == '+') r.added++;
            else if (o.type == '-') r.removed++;
            r.ops.add(o);
        }
        for (int i = a.length - suf; i < a.length; i++) r.ops.add(new Op(' ', a[i]));
        return r;
    }

    private static List<Op> myers(String[] a, String[] b) {
        int n = a.length;
        int m = b.length;
        List<Op> out = new ArrayList<>();
        if (n == 0 && m == 0) return out;
        if (n == 0) {
            for (String s : b) out.add(new Op('+', s));
            return out;
        }
        if (m == 0) {
            for (String s : a) out.add(new Op('-', s));
            return out;
        }
        int max = Math.min(n + m, MAX_D);
        int off = max + 2;
        int[] v = new int[2 * max + 5];
        List<int[]> trace = new ArrayList<>();
        int found = -1;
        for (int d = 0; d <= max && found < 0; d++) {
            int[] slice = new int[2 * d + 1];
            for (int k = -d; k <= d; k++) slice[k + d] = v[off + k];
            trace.add(slice);
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) x = v[off + k + 1];
                else x = v[off + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a[x].equals(b[y])) {
                    x++;
                    y++;
                }
                v[off + k] = x;
                if (x >= n && y >= m) {
                    found = d;
                    break;
                }
            }
        }
        if (found < 0) return null;
        int x = n;
        int y = m;
        List<Op> rev = new ArrayList<>();
        for (int d = found; d > 0; d--) {
            int[] vp = trace.get(d);
            int k = x - y;
            int prevK;
            if (k == -d || (k != d && vp[k - 1 + d] < vp[k + 1 + d])) prevK = k + 1;
            else prevK = k - 1;
            int prevX = vp[prevK + d];
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                x--;
                y--;
                rev.add(new Op(' ', a[x]));
            }
            if (x == prevX) {
                y--;
                rev.add(new Op('+', b[y]));
            } else {
                x--;
                rev.add(new Op('-', a[x]));
            }
            x = prevX;
            y = prevY;
        }
        while (x > 0 && y > 0) {
            x--;
            y--;
            rev.add(new Op(' ', a[x]));
        }
        Collections.reverse(rev);
        return rev;
    }

    private static int tint(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static void addLine(Context c, SpannableStringBuilder sb, char type, String text) {
        int ok = Ui.color(c, R.color.ok);
        int bad = Ui.color(c, R.color.bad);
        int hint = Ui.color(c, R.color.text_hint);
        int primary = Ui.color(c, R.color.text_primary);
        int start = sb.length();
        String shown = text.isEmpty() ? " " : text;
        sb.append(type == '+' ? "+ " : type == '-' ? "\u2212 " : "  ").append(shown);
        int end = sb.length();
        if (type == '+') {
            sb.setSpan(new BackgroundColorSpan(tint(ok, 0x30)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(primary), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (type == '-') {
            sb.setSpan(new BackgroundColorSpan(tint(bad, 0x30)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(primary), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (type == '@') {
            int info = Ui.color(c, R.color.info);
            sb.setSpan(new ForegroundColorSpan(info), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            sb.setSpan(new ForegroundColorSpan(hint), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    public static List<CharSequence> chunks(Context c, Result r, int context) {
        List<CharSequence> out = new ArrayList<>();
        int n = r.ops.size();
        boolean[] keep = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (r.ops.get(i).type != ' ') {
                int from = Math.max(0, i - context);
                int to = Math.min(n - 1, i + context);
                for (int j = from; j <= to; j++) keep[j] = true;
            }
        }
        SpannableStringBuilder cur = new SpannableStringBuilder();
        int inChunk = 0;
        int shown = 0;
        boolean gap = false;
        for (int i = 0; i < n; i++) {
            if (!keep[i]) {
                gap = true;
                continue;
            }
            if (shown >= MAX_SHOWN_LINES) {
                if (cur.length() > 0) out.add(cur);
                SpannableStringBuilder more = new SpannableStringBuilder();
                addLine(c, more, '@', "\u22EF " + c.getString(R.string.df_truncated));
                out.add(more);
                return out;
            }
            if (gap && shown > 0) {
                if (cur.length() > 0) cur.append('\n');
                addLine(c, cur, '@', "\u22EF");
                inChunk++;
            }
            gap = false;
            if (cur.length() > 0) cur.append('\n');
            Op o = r.ops.get(i);
            addLine(c, cur, o.type, o.text);
            inChunk++;
            shown++;
            if (inChunk >= CHUNK_LINES) {
                out.add(cur);
                cur = new SpannableStringBuilder();
                inChunk = 0;
            }
        }
        if (cur.length() > 0) out.add(cur);
        return out;
    }

    public static List<CharSequence> patchChunks(Context c, String patch) {
        List<CharSequence> out = new ArrayList<>();
        if (patch == null || patch.isEmpty()) return out;
        String[] ls = patch.split("\n", -1);
        SpannableStringBuilder cur = new SpannableStringBuilder();
        int inChunk = 0;
        int shown = 0;
        for (String l : ls) {
            if (shown >= MAX_SHOWN_LINES) {
                if (cur.length() > 0) out.add(cur);
                SpannableStringBuilder more = new SpannableStringBuilder();
                addLine(c, more, '@', "\u22EF " + c.getString(R.string.df_truncated));
                out.add(more);
                return out;
            }
            if (cur.length() > 0) cur.append('\n');
            if (l.startsWith("@@")) addLine(c, cur, '@', l);
            else if (l.startsWith("+")) addLine(c, cur, '+', l.substring(1));
            else if (l.startsWith("-")) addLine(c, cur, '-', l.substring(1));
            else if (l.startsWith("\\")) addLine(c, cur, '@', l);
            else addLine(c, cur, ' ', l.startsWith(" ") ? l.substring(1) : l);
            inChunk++;
            shown++;
            if (inChunk >= CHUNK_LINES) {
                out.add(cur);
                cur = new SpannableStringBuilder();
                inChunk = 0;
            }
        }
        if (cur.length() > 0) out.add(cur);
        return out;
    }
}

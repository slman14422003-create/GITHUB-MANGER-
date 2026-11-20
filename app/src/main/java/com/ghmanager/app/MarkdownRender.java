package com.ghmanager.app;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.View;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MarkdownRender {
    private MarkdownRender() {
    }

    public interface Links {
        void open(String url);
    }

    private static final float[] HEADING = {1.7f, 1.45f, 1.25f, 1.1f, 1.0f, 0.95f};
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)[^)]*\\)");
    private static final Pattern CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*|__(?=\\S)(.+?)(?<=\\S)__");
    private static final Pattern ITALIC = Pattern.compile(
            "(?<![*\\w])\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?![*\\w])|(?<![_\\w])_(?![\\s_])(.+?)(?<![\\s_])_(?![_\\w])");
    private static final Pattern STRIKE = Pattern.compile("~~(?=\\S)(.+?)(?<=\\S)~~");
    private static final Pattern ORDERED = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern RULE = Pattern.compile("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");

    public static SpannableStringBuilder render(Context c, String md, Links links) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (md == null) return out;
        String[] lines = md.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int i = 0;
        boolean blank = true;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                String fence = trimmed.substring(0, 3);
                int j = i + 1;
                StringBuilder code = new StringBuilder();
                while (j < lines.length && !lines[j].trim().startsWith(fence)) {
                    if (code.length() > 0) code.append('\n');
                    code.append(lines[j]);
                    j++;
                }
                gap(out, blank);
                int st = out.length();
                out.append(code.length() == 0 ? " " : code.toString());
                codeBlock(c, out, st, out.length());
                out.append('\n');
                blank = false;
                i = j + 1;
                continue;
            }
            if (trimmed.isEmpty()) {
                blank = true;
                i++;
                continue;
            }
            if (i + 1 < lines.length && line.indexOf('|') >= 0 && TABLE_SEP.matcher(lines[i + 1]).matches()) {
                int j = i;
                gap(out, blank);
                int st = out.length();
                boolean first = true;
                while (j < lines.length && lines[j].indexOf('|') >= 0 && !lines[j].trim().isEmpty()) {
                    if (TABLE_SEP.matcher(lines[j]).matches()) {
                        j++;
                        continue;
                    }
                    String row = lines[j].trim();
                    if (row.startsWith("|")) row = row.substring(1);
                    if (row.endsWith("|")) row = row.substring(0, row.length() - 1);
                    String[] cells = row.split("\\|", -1);
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < cells.length; k++) {
                        if (k > 0) sb.append("  \u2502  ");
                        sb.append(cells[k].trim());
                    }
                    int rs = out.length();
                    out.append(sb);
                    if (first) out.setSpan(new StyleSpan(Typeface.BOLD), rs, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.append('\n');
                    first = false;
                    j++;
                }
                codeBlock(c, out, st, out.length() - 1);
                blank = false;
                i = j;
                continue;
            }
            Matcher hm = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$").matcher(line);
            if (hm.matches()) {
                int level = hm.group(1).length();
                gap(out, blank);
                int st = out.length();
                out.append(hm.group(2));
                inline(c, out, st, links);
                out.setSpan(new StyleSpan(Typeface.BOLD), st, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(HEADING[level - 1]), st, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(Ui.color(c, R.color.text_primary)), st, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                blank = false;
                i++;
                continue;
            }
            if (RULE.matcher(line).matches()) {
                gap(out, blank);
                int st = out.length();
                out.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500");
                out.setSpan(new ForegroundColorSpan(Ui.color(c, R.color.stroke)), st, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                blank = false;
                i++;
                continue;
            }
            if (trimmed.startsWith(">")) {
                gap(out, blank);
                int st = out.length();
                boolean firstQ = true;
                while (i < lines.length && lines[i].trim().startsWith(">")) {
                    String q = lines[i].trim().substring(1);
                    if (q.startsWith(" ")) q = q.substring(1);
                    if (!firstQ) out.append('\n');
                    int ls = out.length();
                    out.append(q);
                    inline(c, out, ls, links);
                    firstQ = false;
                    i++;
                }
                out.setSpan(new QuoteSpan(Ui.color(c, R.color.accent)), st, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(Ui.color(c, R.color.text_secondary)), st, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                blank = false;
                continue;
            }
            Matcher om = ORDERED.matcher(line);
            Matcher bm = BULLET.matcher(line);
            if (om.matches() || bm.matches()) {
                boolean ordered = om.matches();
                Matcher m = ordered ? om : bm;
                String indent = m.group(1);
                int level = Math.min(4, indent.replace("\t", "    ").length() / 2);
                String body = ordered ? m.group(3) : m.group(2);
                String mark = ordered ? om.group(2) + ". " : "\u2022 ";
                if (body.startsWith("[ ] ")) {
                    mark = "\u2610 ";
                    body = body.substring(4);
                } else if (body.startsWith("[x] ") || body.startsWith("[X] ")) {
                    mark = "\u2611 ";
                    body = body.substring(4);
                }
                if (blank && out.length() > 0 && out.charAt(out.length() - 1) == '\n'
                        && !(out.length() > 1 && out.charAt(out.length() - 2) == '\n') && !lastWasList(out)) {
                    out.append('\n');
                }
                StringBuilder pad = new StringBuilder();
                for (int k = 0; k < level; k++) pad.append("    ");
                out.append(pad).append(mark);
                int st = out.length();
                out.append(body);
                inline(c, out, st, links);
                out.append('\n');
                blank = false;
                i++;
                continue;
            }
            StringBuilder para = new StringBuilder(trimmed);
            int j = i + 1;
            while (j < lines.length) {
                String nx = lines[j];
                String nt = nx.trim();
                if (nt.isEmpty() || nt.startsWith("#") || nt.startsWith(">") || nt.startsWith("```")
                        || nt.startsWith("~~~") || BULLET.matcher(nx).matches() || ORDERED.matcher(nx).matches()
                        || RULE.matcher(nx).matches()) break;
                para.append(' ').append(nt);
                j++;
            }
            gap(out, blank);
            int st = out.length();
            out.append(para);
            inline(c, out, st, links);
            out.append('\n');
            blank = false;
            i = j;
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') out.delete(out.length() - 1, out.length());
        return out;
    }

    private static boolean lastWasList(SpannableStringBuilder out) {
        int end = out.length() - 1;
        if (end <= 0) return false;
        int start = end;
        while (start > 0 && out.charAt(start - 1) != '\n') start--;
        String last = out.subSequence(start, end).toString().trim();
        return last.startsWith("\u2022") || last.startsWith("\u2610") || last.startsWith("\u2611")
                || last.matches("^\\d+\\.\\s.*");
    }

    private static void gap(SpannableStringBuilder out, boolean blank) {
        if (blank && out.length() > 0) out.append('\n');
    }

    private static void codeBlock(Context c, SpannableStringBuilder out, int st, int end) {
        if (end <= st) return;
        out.setSpan(new TypefaceSpan("monospace"), st, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(Ui.color(c, R.color.field)), st, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(0.92f), st, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void inline(Context c, SpannableStringBuilder sb, int from, final Links links) {
        replaceAll(sb, from, IMAGE, 1, 0, (start, end, g2) -> {
            sb.setSpan(new ForegroundColorSpan(Ui.color(c, R.color.text_secondary)), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }, "\u25A3 ");
        replaceAll(sb, from, LINK, 1, 2, (start, end, url) -> {
            final int accent = Ui.color(c, R.color.accent_text);
            final String target = url;
            sb.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    if (links != null) links.open(target);
                }

                @Override
                public void updateDrawState(TextPaint ds) {
                    ds.setColor(accent);
                    ds.setUnderlineText(true);
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }, "");
        replaceAll(sb, from, CODE, 1, 0, (start, end, g2) -> {
            sb.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new BackgroundColorSpan(Ui.color(c, R.color.field)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(Ui.color(c, R.color.warn)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }, "");
        replaceAll(sb, from, BOLD, 1, 2, (start, end, g2) ->
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE), "");
        replaceAll(sb, from, ITALIC, 1, 2, (start, end, g2) ->
                sb.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE), "");
        replaceAll(sb, from, STRIKE, 1, 0, (start, end, g2) ->
                sb.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE), "");
    }

    private interface Apply {
        void on(int start, int end, String extra);
    }

    private static void replaceAll(SpannableStringBuilder sb, int from, Pattern p, int g1, int g2, Apply apply,
                                   String prefix) {
        int guard = 0;
        int pos = from;
        while (guard++ < 400) {
            Matcher m = p.matcher(sb);
            if (!m.find(pos)) return;
            String inner = m.group(g1);
            String extra = g2 > 0 ? m.group(g2) : null;
            if (inner == null && g2 > 0 && p == BOLD || inner == null && p == ITALIC) {
                inner = m.group(2);
                extra = null;
            }
            if (inner == null) {
                pos = m.end();
                continue;
            }
            int s = m.start();
            int e = m.end();
            String rep = prefix + inner;
            sb.replace(s, e, rep);
            apply.on(s, s + rep.length(), extra);
            pos = s + rep.length();
        }
    }
}

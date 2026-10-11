package com.ghmanager.app;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cleans and colors GitHub Actions log text (timestamps, ANSI codes, ##[group] markers). */
final class LogFmt {
    private static final Pattern TS = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z ?");
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-9;?]*[ -/]*[@-~]");

    private static final Pattern TS_FULL = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?Z ?");

    private LogFmt() {
    }

    /** One cleaned log line with the data needed to file it under its step. */
    static final class Line {
        long ts;
        int no;
        boolean marker;
        boolean err;
        SpannableStringBuilder text;
        String plain = "";
    }

    /**
     * Every line of the log (nothing filtered out but empty ones), with its timestamp, whether it starts
     * a new step ("##[group]Run ...") and whether it looks like an error.
     */
    static List<Line> parse(Context c, String raw) {
        final int bad = Ui.color(c, R.color.bad);
        final int warn = Ui.color(c, R.color.warn);
        final int info = Ui.color(c, R.color.info);
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        List<Line> out = new ArrayList<>();
        for (String l : raw.split("\n", -1)) {
            String line = l;
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            long ts = 0;
            Matcher m = TS_FULL.matcher(line);
            if (m.find()) {
                try {
                    cal.clear();
                    cal.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1,
                            Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                            Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
                    long ms = 0;
                    String fr = m.group(7);
                    if (fr != null && !fr.isEmpty()) {
                        String three = (fr + "000").substring(0, 3);
                        ms = Long.parseLong(three);
                    }
                    ts = cal.getTimeInMillis() + ms;
                } catch (Exception ignored) {
                    ts = 0;
                }
                line = line.substring(m.end());
            }
            line = ANSI.matcher(line).replaceAll("");
            if (line.startsWith("##[endgroup]")) continue;
            if (line.isEmpty() && l.isEmpty()) continue;

            Line ln = new Line();
            ln.ts = ts;
            ln.marker = line.startsWith("##[group]Run ") || line.startsWith("##[group]Post Run ")
                    || line.startsWith("Cleaning up orphan processes");
            int color = 0;
            boolean bold = false;
            boolean isError = false;
            if (line.startsWith("##[group]")) {
                line = "\u25B6 " + line.substring(9);
                bold = true;
            } else if (line.startsWith("##[error]")) {
                line = "\u2716 " + line.substring(9);
                color = bad;
                bold = true;
                isError = true;
            } else if (line.startsWith("##[warning]")) {
                line = "\u26A0 " + line.substring(11);
                color = warn;
            } else if (line.startsWith("##[command]")) {
                line = "$ " + line.substring(11);
                color = info;
            }
            ln.err = isError || looksLikeError(line.toLowerCase(Locale.ROOT));
            ln.plain = line;
            SpannableStringBuilder sb = new SpannableStringBuilder(line);
            if (color != 0) sb.setSpan(new ForegroundColorSpan(color), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bold) sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ln.text = sb;
            out.add(ln);
        }
        return out;
    }

    static boolean looksLikeError(String lower) {
        return lower.contains("error") || lower.contains("fatal") || lower.contains("failed")
                || lower.contains("exception") || lower.contains("traceback");
    }

    /**
     * @param mode      0 = everything, 1 = errors only
     * @param lastLines keep only the last N lines (0 = all)
     */
    static SpannableStringBuilder format(Context c, String raw, int mode, int lastLines) {
        List<SpannableStringBuilder> items = lines(c, raw, mode);
        int from = lastLines > 0 ? Math.max(0, items.size() - lastLines) : 0;
        SpannableStringBuilder out = new SpannableStringBuilder();
        for (int i = from; i < items.size(); i++) {
            out.append(items.get(i));
            if (i < items.size() - 1) out.append('\n');
        }
        return out;
    }

    /** The cleaned, coloured log as one entry per line (what the log screen shows, a chunk of lines per row). */
    static List<SpannableStringBuilder> lines(Context c, String raw, int mode) {
        final int bad = Ui.color(c, R.color.bad);
        final int warn = Ui.color(c, R.color.warn);
        final int info = Ui.color(c, R.color.info);
        List<SpannableStringBuilder> items = new ArrayList<>();
        for (String l : raw.split("\n", -1)) {
            String line = l;
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            Matcher m = TS.matcher(line);
            if (m.find()) line = line.substring(m.end());
            line = ANSI.matcher(line).replaceAll("");

            int color = 0;
            boolean bold = false;
            boolean isError = false;
            if (line.startsWith("##[group]")) {
                line = "▶ " + line.substring(9);
                bold = true;
            } else if (line.startsWith("##[endgroup]")) {
                continue;
            } else if (line.startsWith("##[error]")) {
                line = "✖ " + line.substring(9);
                color = bad;
                bold = true;
                isError = true;
            } else if (line.startsWith("##[warning]")) {
                line = "⚠ " + line.substring(11);
                color = warn;
            } else if (line.startsWith("##[command]")) {
                line = "$ " + line.substring(11);
                color = info;
            }
            if (mode == 1 && !isError && !looksLikeError(line.toLowerCase(Locale.ROOT))) continue;
            if (mode == 0 && line.isEmpty() && l.isEmpty()) continue;

            SpannableStringBuilder sb = new SpannableStringBuilder(line);
            if (color != 0) sb.setSpan(new ForegroundColorSpan(color), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bold) sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            items.add(sb);
        }
        return items;
    }
}

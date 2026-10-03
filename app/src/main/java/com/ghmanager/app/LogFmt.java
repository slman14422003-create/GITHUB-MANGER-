package com.ghmanager.app;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cleans and colors GitHub Actions log text (timestamps, ANSI codes, ##[group] markers). */
final class LogFmt {
    private static final Pattern TS = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z ?");
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-9;?]*[ -/]*[@-~]");

    private LogFmt() {
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
        int from = lastLines > 0 ? Math.max(0, items.size() - lastLines) : 0;
        SpannableStringBuilder out = new SpannableStringBuilder();
        for (int i = from; i < items.size(); i++) {
            out.append(items.get(i));
            if (i < items.size() - 1) out.append('\n');
        }
        return out;
    }
}

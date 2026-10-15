package com.ghmanager.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Developer tools shared by both text editors: a symbol bar above the keyboard, find and replace,
 * go to line and a live line / column readout. The editor's layout provides the containers
 * (findBar, editorInfo, symbolRow, editorScroll); this class fills them.
 */
final class CodeTools {
    private static final String[][] SYMS = {
            {"Tab", "    ", "0"}, {"{}", "{}", "1"}, {"()", "()", "1"}, {"[]", "[]", "1"},
            {"\"\"", "\"\"", "1"}, {"''", "''", "1"}, {"<>", "<>", "1"}, {";", ";", "0"},
            {":", ":", "0"}, {"=", "=", "0"}, {"/", "/", "0"}, {"\\", "\\", "0"},
            {"|", "|", "0"}, {"&", "&", "0"}, {"!", "!", "0"}, {"#", "#", "0"},
            {"$", "$", "0"}, {"_", "_", "0"}, {"-", "-", "0"}, {"+", "+", "0"},
            {"*", "*", "0"}, {"%", "%", "0"}
    };

    private final Activity a;
    private final CodeEditText ed;
    private final ScrollView sv;
    private final TextView info;
    private final LinearLayout findBar;
    private final View symbolScroll;
    private EditText findField;
    private EditText replField;
    private TextView matchInfo;
    private View replaceRow;
    private TextView caseChip;
    private boolean matchCase = false;
    private boolean editable = true;
    private final BackgroundColorSpan matchSpan;
    private final Runnable infoUpdate = this::updateInfo;

    CodeTools(Activity act) {
        a = act;
        ed = a.findViewById(R.id.editor);
        sv = a.findViewById(R.id.editorScroll);
        info = a.findViewById(R.id.editorInfo);
        findBar = a.findViewById(R.id.findBar);
        symbolScroll = a.findViewById(R.id.symbolScroll);
        matchSpan = new BackgroundColorSpan((Ui.color(a, R.color.warn) & 0x00FFFFFF) | 0x66000000);
        buildSymbols((LinearLayout) a.findViewById(R.id.symbolRow));
        buildFindBar();
        ed.setCursorListener((s, e) -> {
            ed.removeCallbacks(infoUpdate);
            ed.postDelayed(infoUpdate, 90);
        });
        updateInfo();
    }

    // ------------------------------------------------------------------ state

    /** Read-only files hide the symbol bar and the replace row. */
    void setEditable(boolean on) {
        editable = on;
        symbolScroll.setVisibility(on ? View.VISIBLE : View.GONE);
        if (replaceRow != null) replaceRow.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    boolean findVisible() {
        return findBar.getVisibility() == View.VISIBLE;
    }

    void onTextLoaded() {
        updateInfo();
    }

    // ------------------------------------------------------------------ cursor readout

    private void updateInfo() {
        CharSequence t = ed.getText();
        if (t == null) return;
        int pos = Math.max(0, Math.min(ed.getSelectionStart(), t.length()));
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < pos; i++) {
            if (t.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        int total = line;
        for (int i = pos; i < t.length(); i++) if (t.charAt(i) == '\n') total++;
        info.setText(a.getString(R.string.ed_info, line, pos - lineStart + 1, total));
    }

    // ------------------------------------------------------------------ symbol bar

    private void buildSymbols(LinearLayout row) {
        for (final String[] s : SYMS) {
            TextView c = Ui.chip(a, s[0], false);
            c.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            c.setTextSize(14);
            c.setGravity(Gravity.CENTER);
            c.setMinWidth(Ui.dp(a, 44));
            c.setTextDirection(View.TEXT_DIRECTION_LTR);
            final String ins = s[1];
            final int back = Integer.parseInt(s[2]);
            c.setOnClickListener(v -> insert(ins, back));
            row.addView(c);
        }
    }

    private void insert(String text, int back) {
        if (!editable || !ed.isEnabled() || ed.getKeyListener() == null) return;
        Editable e = ed.getText();
        int s = Math.max(0, ed.getSelectionStart());
        int t = Math.max(0, ed.getSelectionEnd());
        int lo = Math.min(s, t);
        int hi = Math.max(s, t);
        e.replace(lo, hi, text);
        ed.setSelection(Math.min(e.length(), lo + text.length() - back));
    }

    // ------------------------------------------------------------------ find and replace

    private void buildFindBar() {
        LinearLayout r1 = new LinearLayout(a);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        findField = field(R.string.ed_find_hint);
        findField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        findField.setSingleLine(true);
        r1.addView(findField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView prev = bar("‹", R.string.ed_prev);
        prev.setOnClickListener(v -> findNext(false));
        TextView next = bar("›", R.string.ed_next);
        next.setOnClickListener(v -> findNext(true));
        TextView close = bar("✕", R.string.ed_close);
        close.setOnClickListener(v -> hideFind());
        r1.addView(prev);
        r1.addView(next);
        r1.addView(close);
        findBar.addView(r1);

        LinearLayout r2 = new LinearLayout(a);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        replField = field(R.string.ed_replace_hint);
        replField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        replField.setSingleLine(true);
        r2.addView(replField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView rep = Ui.chip(a, a.getString(R.string.ed_replace), false);
        rep.setOnClickListener(v -> replaceOne());
        TextView all = Ui.chip(a, a.getString(R.string.ed_replace_all), false);
        all.setOnClickListener(v -> replaceAll());
        r2.addView(rep);
        r2.addView(all);
        replaceRow = r2;
        findBar.addView(r2);

        LinearLayout r3 = new LinearLayout(a);
        r3.setOrientation(LinearLayout.HORIZONTAL);
        r3.setGravity(Gravity.CENTER_VERTICAL);
        caseChip = Ui.chip(a, a.getString(R.string.ed_match_case), false);
        caseChip.setTypeface(Typeface.DEFAULT_BOLD);
        caseChip.setOnClickListener(v -> {
            matchCase = !matchCase;
            Ui.setChip(a, caseChip, matchCase);
            findNext(true);
        });
        r3.addView(caseChip);
        matchInfo = new TextView(a);
        matchInfo.setTextSize(12);
        matchInfo.setTextColor(Ui.color(a, R.color.text_secondary));
        matchInfo.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        r3.addView(matchInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        findBar.addView(r3);

        findField.setOnEditorActionListener((v, id, ev) -> {
            findNext(true);
            return true;
        });
    }

    private EditText field(int hint) {
        EditText e = Ui.edit(a, a.getString(hint), null);
        e.setMinHeight(Ui.dp(a, 44));
        e.setTextSize(14);
        e.setPaddingRelative(Ui.dp(a, 14), Ui.dp(a, 8), Ui.dp(a, 14), Ui.dp(a, 8));
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(Ui.dp(a, 8));
        lp.bottomMargin = Ui.dp(a, 8);
        e.setLayoutParams(lp);
        return e;
    }

    private TextView bar(String label, int desc) {
        TextView c = Ui.chip(a, label, false);
        c.setTextSize(16);
        c.setTypeface(Typeface.DEFAULT_BOLD);
        c.setGravity(Gravity.CENTER);
        c.setMinWidth(Ui.dp(a, 40));
        c.setContentDescription(a.getString(desc));
        ((LinearLayout.LayoutParams) c.getLayoutParams()).bottomMargin = Ui.dp(a, 8);
        return c;
    }

    void showFind() {
        findBar.setVisibility(View.VISIBLE);
        int s = Math.min(ed.getSelectionStart(), ed.getSelectionEnd());
        int e = Math.max(ed.getSelectionStart(), ed.getSelectionEnd());
        if (s >= 0 && e > s && e - s <= 80) {
            String sel = ed.getText().subSequence(s, e).toString();
            if (sel.indexOf('\n') < 0) findField.setText(sel);
        }
        findField.requestFocus();
        findField.selectAll();
    }

    void hideFind() {
        findBar.setVisibility(View.GONE);
        clearMatch();
        ed.requestFocus();
    }

    void toggleFind() {
        if (findVisible()) hideFind();
        else showFind();
    }

    private Pattern pattern() {
        String q = findField.getText().toString();
        if (q.isEmpty()) return null;
        int flags = Pattern.LITERAL;
        if (!matchCase) flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return Pattern.compile(q, flags);
    }

    private void clearMatch() {
        Editable e = ed.getText();
        if (e != null) e.removeSpan(matchSpan);
    }

    private void findNext(boolean forward) {
        Pattern p = pattern();
        clearMatch();
        if (p == null) {
            matchInfo.setText("");
            return;
        }
        CharSequence text = ed.getText();
        Matcher m = p.matcher(text);
        int selS = Math.min(ed.getSelectionStart(), ed.getSelectionEnd());
        int selE = Math.max(ed.getSelectionStart(), ed.getSelectionEnd());
        int total = 0;
        int pick = -1;
        int pickS = -1;
        int pickE = -1;
        int first = -1;
        int firstS = -1;
        int firstE = -1;
        int last = -1;
        int lastS = -1;
        int lastE = -1;
        while (m.find() && total < 99999) {
            if (first < 0) {
                first = total;
                firstS = m.start();
                firstE = m.end();
            }
            if (forward) {
                if (pick < 0 && m.start() >= selE) {
                    pick = total;
                    pickS = m.start();
                    pickE = m.end();
                }
            } else if (m.start() < selS) {
                pick = total;
                pickS = m.start();
                pickE = m.end();
            }
            last = total;
            lastS = m.start();
            lastE = m.end();
            total++;
        }
        if (total == 0) {
            matchInfo.setText(R.string.ed_no_match);
            return;
        }
        if (pick < 0) {
            // wrap around
            if (forward) {
                pick = first;
                pickS = firstS;
                pickE = firstE;
            } else {
                pick = last;
                pickS = lastS;
                pickE = lastE;
            }
        }
        ed.getText().setSpan(matchSpan, pickS, pickE, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ed.setSelection(pickS, pickE);
        scrollToOffset(pickS);
        matchInfo.setText(a.getString(R.string.ed_matches, pick + 1, total));
    }

    private void replaceOne() {
        if (!editable || ed.getKeyListener() == null) return;
        Pattern p = pattern();
        if (p == null) return;
        int s = Math.min(ed.getSelectionStart(), ed.getSelectionEnd());
        int e = Math.max(ed.getSelectionStart(), ed.getSelectionEnd());
        if (e > s && p.matcher(ed.getText().subSequence(s, e)).matches()) {
            clearMatch();
            ed.getText().replace(s, e, replField.getText().toString());
            ed.setSelection(Math.min(ed.length(), s + replField.getText().length()));
        }
        findNext(true);
    }

    private void replaceAll() {
        if (!editable || ed.getKeyListener() == null) return;
        Pattern p = pattern();
        if (p == null) return;
        clearMatch();
        String src = ed.getText().toString();
        Matcher m = p.matcher(src);
        int n = 0;
        while (m.find()) n++;
        if (n == 0) {
            matchInfo.setText(R.string.ed_no_match);
            return;
        }
        String out = p.matcher(src).replaceAll(Matcher.quoteReplacement(replField.getText().toString()));
        final int y = sv.getScrollY();
        ed.getText().replace(0, ed.length(), out);
        sv.post(() -> sv.scrollTo(0, y));
        matchInfo.setText("");
        Toast.makeText(a, a.getString(R.string.ed_replaced_n, n), Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ go to line

    void goToDialog() {
        LinearLayout box = Ui.box(a);
        final EditText n = Ui.edit(a, a.getString(R.string.ed_goto_hint), null);
        n.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(n);
        new Dlg(a)
                .setTitle(R.string.ed_goto)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String t = n.getText().toString().trim();
                    if (t.isEmpty()) return;
                    try {
                        goToLine(Integer.parseInt(t));
                    } catch (NumberFormatException ignored) {
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    void goToLine(int line) {
        CharSequence t = ed.getText();
        int target = Math.max(1, line);
        int off = 0;
        int cur = 1;
        while (cur < target && off < t.length()) {
            if (t.charAt(off) == '\n') cur++;
            off++;
        }
        ed.setSelection(off);
        scrollToOffset(off);
        ed.requestFocus();
    }

    private void scrollToOffset(final int off) {
        Runnable r = () -> {
            Layout l = ed.getLayout();
            if (l == null) return;
            int o = Math.max(0, Math.min(off, ed.length()));
            int y = l.getLineTop(l.getLineForOffset(o)) + ed.getPaddingTop();
            sv.smoothScrollTo(0, Math.max(0, y - sv.getHeight() / 3));
        };
        if (ed.getLayout() == null) ed.post(r);
        else r.run();
    }
}

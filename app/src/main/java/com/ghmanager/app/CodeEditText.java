package com.ghmanager.app;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextWatcher;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatEditText;

import java.util.ArrayList;

/**
 * Multi-line code field. It never scrolls by itself: it is always hosted in a ScrollView, so the
 * whole text moves with one native fling instead of two scrollers fighting over the same touch.
 * Reports cursor moves and keeps the indentation of the previous line when Enter is pressed.
 */
public class CodeEditText extends AppCompatEditText {
    public interface CursorListener {
        void onCursor(int start, int end);
    }

    private CursorListener cursorListener;
    private boolean autoIndent = true;

    public static final class History {
        final ArrayList<Edit> undo = new ArrayList<>();
        final ArrayList<Edit> redo = new ArrayList<>();
    }

    static final class Edit {
        int pos;
        String before;
        String after;
        long time;
    }

    private History history = new History();
    private boolean replaying = false;
    private boolean tracking = true;
    private String pendingBefore = "";
    private int pendingStart = 0;
    private Runnable historyListener;

    public CodeEditText(Context c) {
        super(c);
        init();
    }

    public CodeEditText(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public CodeEditText(Context c, AttributeSet a, int d) {
        super(c, a, d);
        init();
    }

    private void init() {
        addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence t, int start, int count, int after) {
                if (replaying || !tracking) return;
                pendingStart = start;
                pendingLen = t.length();
                pendingBefore = count > 0 ? t.subSequence(start, start + count).toString() : "";
            }

            @Override
            public void onTextChanged(CharSequence t, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (replaying || !tracking) return;
                int start = pendingStart;
                String after = grabAfter(e, start);
                if (pendingBefore.equals(after)) return;
                recordEdit(start, pendingBefore, after);
            }
        });
        setFilters(new InputFilter[]{new InputFilter() {
            @Override
            public CharSequence filter(CharSequence src, int s, int e, Spanned dest, int ds, int de) {
                if (!autoIndent || e - s != 1 || src.charAt(s) != '\n') return null;
                int ls = ds;
                while (ls > 0 && dest.charAt(ls - 1) != '\n') ls--;
                int i = ls;
                while (i < ds && (dest.charAt(i) == ' ' || dest.charAt(i) == '\t')) i++;
                StringBuilder sb = new StringBuilder("\n");
                sb.append(dest, ls, i);
                int last = ds - 1;
                while (last >= ls && dest.charAt(last) == ' ') last--;
                if (last >= ls) {
                    char c = dest.charAt(last);
                    if (c == '{' || c == '(' || c == '[' || c == ':') sb.append("    ");
                }
                return sb.toString();
            }
        }});
    }

    private int pendingLen = 0;

    private String grabAfter(Editable e, int start) {
        int total = e.length();
        int removed = pendingBefore.length();
        int added = total - pendingLen + removed;
        if (added < 0) added = 0;
        if (start + added > total) added = total - start;
        return added <= 0 ? "" : e.subSequence(start, start + added).toString();
    }

    private void recordEdit(int start, String before, String after) {
        long now = System.currentTimeMillis();
        ArrayList<Edit> u = history.undo;
        Edit prev = u.isEmpty() ? null : u.get(u.size() - 1);
        boolean merge = prev != null && before.isEmpty() && prev.before.isEmpty()
                && prev.pos + prev.after.length() == start && now - prev.time < 1200
                && after.indexOf('\n') < 0 && prev.after.indexOf('\n') < 0 && after.length() < 4;
        if (merge) {
            prev.after = prev.after + after;
            prev.time = now;
        } else {
            Edit ed = new Edit();
            ed.pos = start;
            ed.before = before;
            ed.after = after;
            ed.time = now;
            u.add(ed);
            while (u.size() > 300) u.remove(0);
        }
        history.redo.clear();
        notifyHistory();
    }

    private void notifyHistory() {
        if (historyListener != null) historyListener.run();
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        boolean was = replaying;
        replaying = true;
        super.setText(text, type);
        replaying = was;
        if (history != null) {
            history.undo.clear();
            history.redo.clear();
            notifyHistory();
        }
    }

    public void setHistoryListener(Runnable r) {
        historyListener = r;
    }

    public boolean canUndo() {
        return !history.undo.isEmpty();
    }

    public boolean canRedo() {
        return !history.redo.isEmpty();
    }

    public void undo() {
        if (history.undo.isEmpty() || getText() == null) return;
        Edit e = history.undo.remove(history.undo.size() - 1);
        replaying = true;
        try {
            int end = Math.min(getText().length(), e.pos + e.after.length());
            int st = Math.min(e.pos, end);
            getText().replace(st, end, e.before);
            setSelection(Math.min(getText().length(), st + e.before.length()));
        } finally {
            replaying = false;
        }
        history.redo.add(e);
        notifyHistory();
    }

    public void redo() {
        if (history.redo.isEmpty() || getText() == null) return;
        Edit e = history.redo.remove(history.redo.size() - 1);
        replaying = true;
        try {
            int end = Math.min(getText().length(), e.pos + e.before.length());
            int st = Math.min(e.pos, end);
            getText().replace(st, end, e.after);
            setSelection(Math.min(getText().length(), st + e.after.length()));
        } finally {
            replaying = false;
        }
        history.undo.add(e);
        notifyHistory();
    }

    public History takeHistory() {
        return history;
    }

    public void useHistory(History h) {
        history = h == null ? new History() : h;
        notifyHistory();
    }

    public void setTracking(boolean on) {
        tracking = on;
    }

    public void setCursorListener(CursorListener l) {
        cursorListener = l;
    }

    public void setAutoIndent(boolean on) {
        autoIndent = on;
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        if (cursorListener != null) cursorListener.onCursor(selStart, selEnd);
    }
}

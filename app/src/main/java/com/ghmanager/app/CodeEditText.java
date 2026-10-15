package com.ghmanager.app;

import android.content.Context;
import android.text.InputFilter;
import android.text.Spanned;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatEditText;

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

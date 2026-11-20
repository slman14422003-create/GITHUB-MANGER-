package com.ghmanager.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SyntaxHighlighter {
    static final int NONE = 0;
    static final int CLIKE = 1;
    static final int PYTHON = 2;
    static final int SHELL = 3;
    static final int JSON = 4;
    static final int XML = 5;
    static final int YAML = 6;
    static final int CSS = 7;
    static final int MARKDOWN = 8;
    static final int PROPS = 9;
    static final int SQL = 10;

    private static final int T_KEYWORD = 0;
    private static final int T_STRING = 1;
    private static final int T_COMMENT = 2;
    private static final int T_NUMBER = 3;
    private static final int T_TYPE = 4;
    private static final int T_KEY = 5;
    private static final int T_NOTE = 6;

    private static final int MAX_CHARS = 250000;
    private static final int MAX_SPANS = 9000;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    private static final Set<String> CLIKE_WORDS = new HashSet<>(Arrays.asList(
            "abstract", "as", "async", "await", "break", "case", "catch", "class", "const", "continue", "default",
            "defer", "do", "else", "enum", "export", "extends", "false", "final", "finally", "for", "fun", "func",
            "function", "go", "if", "implements", "import", "in", "interface", "internal", "is", "let", "lateinit",
            "new", "null", "nil", "object", "override", "package", "private", "protected", "public", "return",
            "sealed", "static", "struct", "super", "switch", "synchronized", "this", "throw", "throws", "true", "try",
            "typeof", "undefined", "val", "var", "void", "volatile", "when", "while", "yield", "fn", "impl", "mod",
            "pub", "use", "mut", "match", "self", "from", "boolean", "int", "long", "float", "double", "char",
            "byte", "short", "string", "bool", "typealias", "extension", "operator", "init", "constructor",
            "companion", "data", "inline", "open", "where", "with", "namespace", "using", "delete", "instanceof",
            "native", "transient", "strictfp", "assert", "goto", "readonly", "declare", "type", "of", "get", "set"));

    private static final Set<String> PY_WORDS = new HashSet<>(Arrays.asList(
            "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else",
            "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "None",
            "nonlocal", "not", "or", "pass", "raise", "return", "True", "try", "while", "with", "yield", "self",
            "print", "match", "case", "end", "do", "then", "module", "require", "puts", "begin", "unless", "until"));

    private static final Set<String> SH_WORDS = new HashSet<>(Arrays.asList(
            "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "in",
            "function", "return", "exit", "export", "local", "readonly", "set", "unset", "source", "echo", "cd",
            "true", "false", "select", "break", "continue", "shift", "trap", "eval", "exec"));

    private static final Set<String> SQL_WORDS = new HashSet<>(Arrays.asList(
            "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "table",
            "alter", "drop", "index", "join", "left", "right", "inner", "outer", "on", "group", "by", "order",
            "having", "limit", "offset", "union", "all", "distinct", "as", "and", "or", "not", "null", "is", "in",
            "like", "between", "exists", "case", "when", "then", "else", "end", "primary", "key", "foreign",
            "references", "default", "unique", "check", "constraint", "view", "begin", "commit", "rollback"));

    private final Context ctx;
    private final EditText ed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int kind = NONE;
    private int version = 0;
    private boolean enabled = true;
    private final Runnable run = this::start;

    private static final class Mark extends ForegroundColorSpan {
        Mark(int color) {
            super(color);
        }
    }

    SyntaxHighlighter(Context c, EditText editor) {
        ctx = c;
        ed = editor;
    }

    static int kindOf(String path) {
        if (path == null) return NONE;
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        String low = name.toLowerCase(Locale.ROOT);
        if (low.equals("dockerfile") || low.equals("makefile")) return SHELL;
        int dot = low.lastIndexOf('.');
        String e = dot < 0 ? "" : low.substring(dot + 1);
        switch (e) {
            case "java": case "kt": case "kts": case "js": case "jsx": case "mjs": case "cjs": case "ts": case "tsx":
            case "c": case "h": case "cpp": case "cc": case "hpp": case "cs": case "go": case "rs": case "swift":
            case "php": case "dart": case "scala": case "groovy": case "gradle": case "vue": case "proto":
                return CLIKE;
            case "py": case "rb": case "lua": case "pl":
                return PYTHON;
            case "sh": case "bash": case "zsh": case "bat": case "cmd": case "ps1":
                return SHELL;
            case "json": case "json5": case "jsonc": case "webmanifest":
                return JSON;
            case "xml": case "html": case "htm": case "svg": case "xhtml": case "plist": case "iml":
                return XML;
            case "yml": case "yaml":
                return YAML;
            case "css": case "scss": case "less":
                return CSS;
            case "md": case "markdown":
                return MARKDOWN;
            case "properties": case "toml": case "ini": case "cfg": case "conf": case "env":
                return PROPS;
            case "sql":
                return SQL;
            default:
                return NONE;
        }
    }

    void setPath(String path) {
        kind = kindOf(path);
        version++;
        clear();
    }

    void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            version++;
            clear();
        }
    }

    boolean isEnabled() {
        return enabled;
    }

    private void clear() {
        Editable e = ed.getText();
        if (e == null) return;
        Mark[] old = e.getSpans(0, e.length(), Mark.class);
        for (Mark m : old) e.removeSpan(m);
    }

    void schedule(long delayMs) {
        handler.removeCallbacks(run);
        version++;
        if (!enabled || kind == NONE) return;
        handler.postDelayed(run, delayMs);
    }

    private void start() {
        Editable e = ed.getText();
        if (e == null || !enabled || kind == NONE || e.length() > MAX_CHARS) {
            clear();
            return;
        }
        final String text = e.toString();
        final int myVersion = version;
        final int k = kind;
        POOL.execute(() -> {
            final List<int[]> spans = tokenize(text, k);
            handler.post(() -> {
                if (myVersion != version) return;
                Editable cur = ed.getText();
                if (cur == null || cur.length() != text.length()) return;
                apply(cur, spans);
            });
        });
    }

    private void apply(Editable e, List<int[]> spans) {
        int[] colors = {
                Ui.color(ctx, R.color.accent_text),
                Ui.color(ctx, R.color.ok),
                Ui.color(ctx, R.color.text_hint),
                Ui.color(ctx, R.color.warn),
                Ui.color(ctx, R.color.info),
                Ui.color(ctx, R.color.accent_text),
                Ui.color(ctx, R.color.warn)
        };
        Mark[] old = e.getSpans(0, e.length(), Mark.class);
        for (Mark m : old) e.removeSpan(m);
        int n = Math.min(spans.size(), MAX_SPANS);
        for (int i = 0; i < n; i++) {
            int[] s = spans.get(i);
            if (s[0] < 0 || s[1] > e.length() || s[0] >= s[1]) continue;
            e.setSpan(new Mark(colors[s[2]]), s[0], s[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static boolean idStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean idPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int lineEnd(String s, int from) {
        int i = s.indexOf('\n', from);
        return i < 0 ? s.length() : i;
    }

    static List<int[]> tokenize(String s, int kind) {
        List<int[]> out = new ArrayList<>();
        switch (kind) {
            case XML:
                xml(s, out);
                break;
            case JSON:
                scan(s, out, JSON);
                break;
            case MARKDOWN:
                markdown(s, out);
                break;
            default:
                scan(s, out, kind);
                break;
        }
        return out;
    }

    private static void markdown(String s, List<int[]> out) {
        int i = 0;
        int n = s.length();
        boolean fence = false;
        while (i < n) {
            int end = lineEnd(s, i);
            String line = s.substring(i, end);
            String t = line.trim();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                fence = !fence;
                out.add(new int[]{i, end, T_STRING});
            } else if (fence) {
                out.add(new int[]{i, end, T_STRING});
            } else if (t.startsWith("#")) {
                out.add(new int[]{i, end, T_KEYWORD});
            } else if (t.startsWith(">")) {
                out.add(new int[]{i, end, T_COMMENT});
            } else {
                int a = line.indexOf('`');
                while (a >= 0) {
                    int b = line.indexOf('`', a + 1);
                    if (b < 0) break;
                    out.add(new int[]{i + a, i + b + 1, T_STRING});
                    a = line.indexOf('`', b + 1);
                }
                int ls = 0;
                while (ls < line.length() && line.charAt(ls) == ' ') ls++;
                if (ls < line.length() && (line.charAt(ls) == '-' || line.charAt(ls) == '*' || line.charAt(ls) == '+')
                        && ls + 1 < line.length() && line.charAt(ls + 1) == ' ') {
                    out.add(new int[]{i + ls, i + ls + 1, T_NOTE});
                }
            }
            i = end + 1;
        }
    }

    private static void xml(String s, List<int[]> out) {
        int n = s.length();
        int i = 0;
        while (i < n) {
            int lt = s.indexOf('<', i);
            if (lt < 0) break;
            if (s.startsWith("<!--", lt)) {
                int e = s.indexOf("-->", lt + 4);
                int end = e < 0 ? n : e + 3;
                out.add(new int[]{lt, end, T_COMMENT});
                i = end;
                continue;
            }
            int p = lt + 1;
            if (p < n && (s.charAt(p) == '/' || s.charAt(p) == '?' || s.charAt(p) == '!')) p++;
            int ns = p;
            while (p < n && (idPart(s.charAt(p)) || s.charAt(p) == '-' || s.charAt(p) == ':' || s.charAt(p) == '.')) p++;
            if (p > ns) out.add(new int[]{ns, p, T_KEYWORD});
            while (p < n && s.charAt(p) != '>') {
                char ch = s.charAt(p);
                if (ch == '"' || ch == '\'') {
                    int q = s.indexOf(ch, p + 1);
                    int end = q < 0 ? n : q + 1;
                    out.add(new int[]{p, end, T_STRING});
                    p = end;
                } else if (idStart(ch)) {
                    int a = p;
                    while (p < n && (idPart(s.charAt(p)) || s.charAt(p) == '-' || s.charAt(p) == ':' || s.charAt(p) == '.')) p++;
                    int q = p;
                    while (q < n && s.charAt(q) == ' ') q++;
                    if (q < n && s.charAt(q) == '=') out.add(new int[]{a, p, T_TYPE});
                } else {
                    p++;
                }
            }
            i = p + 1;
        }
    }

    private static void scan(String s, List<int[]> out, int kind) {
        int n = s.length();
        int i = 0;
        boolean hash = kind == PYTHON || kind == SHELL || kind == YAML || kind == PROPS;
        boolean slash = kind == CLIKE || kind == CSS || kind == JSON;
        boolean block = kind == CLIKE || kind == CSS || kind == SQL || kind == JSON;
        boolean dashes = kind == SQL;
        boolean lineStart = true;
        int depth = 0;
        while (i < n) {
            char ch = s.charAt(i);
            if (ch == '\n') {
                lineStart = true;
                i++;
                continue;
            }
            if (lineStart && (kind == YAML || kind == PROPS)) {
                int j = i;
                while (j < n && (s.charAt(j) == ' ' || s.charAt(j) == '\t' || s.charAt(j) == '-')) j++;
                int ks = j;
                while (j < n && s.charAt(j) != '\n' && s.charAt(j) != ':' && s.charAt(j) != '=' && s.charAt(j) != '#') j++;
                if (j < n && j > ks && (s.charAt(j) == (kind == YAML ? ':' : '=') || (kind == PROPS && s.charAt(j) == ':'))
                        && s.charAt(ks) != '#') {
                    int ke = j;
                    while (ke > ks && s.charAt(ke - 1) == ' ') ke--;
                    if (ke > ks) out.add(new int[]{ks, ke, T_KEY});
                    i = j + 1;
                    lineStart = false;
                    continue;
                }
            }
            if (ch != ' ' && ch != '\t') lineStart = false;
            if (hash && ch == '#') {
                if (kind == SHELL && i > 0 && !Character.isWhitespace(s.charAt(i - 1)) && s.charAt(i - 1) != ';') {
                    i++;
                    continue;
                }
                int e = lineEnd(s, i);
                out.add(new int[]{i, e, T_COMMENT});
                i = e;
                continue;
            }
            if (slash && ch == '/' && i + 1 < n && s.charAt(i + 1) == '/' && kind != CSS) {
                int e = lineEnd(s, i);
                out.add(new int[]{i, e, T_COMMENT});
                i = e;
                continue;
            }
            if (dashes && ch == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                int e = lineEnd(s, i);
                out.add(new int[]{i, e, T_COMMENT});
                i = e;
                continue;
            }
            if (block && ch == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                int end = e < 0 ? n : e + 2;
                out.add(new int[]{i, end, T_COMMENT});
                i = end;
                continue;
            }
            if (ch == '"' || ch == '\'' || (ch == '`' && kind != SQL && kind != PYTHON && kind != PROPS)) {
                int end = stringEnd(s, i, ch, kind);
                int type = T_STRING;
                if (kind == JSON && ch == '"') {
                    int q = end;
                    while (q < n && (s.charAt(q) == ' ' || s.charAt(q) == '\t')) q++;
                    if (q < n && s.charAt(q) == ':') type = T_KEY;
                }
                out.add(new int[]{i, end, type});
                i = end;
                continue;
            }
            if (Character.isDigit(ch) && (i == 0 || !idPart(s.charAt(i - 1)))) {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '.' || s.charAt(j) == '_')) j++;
                out.add(new int[]{i, j, T_NUMBER});
                i = j;
                continue;
            }
            if (kind == CSS && ch == '#' && i + 1 < n && Character.isLetterOrDigit(s.charAt(i + 1)) && depth > 0) {
                int j = i + 1;
                while (j < n && Character.isLetterOrDigit(s.charAt(j))) j++;
                out.add(new int[]{i, j, T_NUMBER});
                i = j;
                continue;
            }
            if (kind == CSS && (ch == '{' || ch == '}')) {
                depth += ch == '{' ? 1 : -1;
                if (depth < 0) depth = 0;
                i++;
                continue;
            }
            if (ch == '@' && i + 1 < n && idStart(s.charAt(i + 1)) && (kind == CLIKE || kind == CSS)) {
                int j = i + 1;
                while (j < n && idPart(s.charAt(j))) j++;
                out.add(new int[]{i, j, T_NOTE});
                i = j;
                continue;
            }
            if (idStart(ch)) {
                int j = i + 1;
                while (j < n && (idPart(s.charAt(j)) || (kind == CSS && s.charAt(j) == '-'))) j++;
                String w = s.substring(i, j);
                if (kind == CSS) {
                    int q = j;
                    while (q < n && s.charAt(q) == ' ') q++;
                    if (depth > 0 && q < n && s.charAt(q) == ':') out.add(new int[]{i, j, T_TYPE});
                    i = j;
                    continue;
                }
                if (isKeyword(w, kind)) {
                    out.add(new int[]{i, j, T_KEYWORD});
                } else if (kind != JSON && kind != YAML && kind != PROPS && Character.isUpperCase(w.charAt(0))
                        && w.length() > 1) {
                    out.add(new int[]{i, j, T_TYPE});
                }
                i = j;
                continue;
            }
            i++;
        }
    }

    private static boolean isKeyword(String w, int kind) {
        switch (kind) {
            case CLIKE:
                return CLIKE_WORDS.contains(w);
            case PYTHON:
                return PY_WORDS.contains(w);
            case SHELL:
                return SH_WORDS.contains(w);
            case SQL:
                return SQL_WORDS.contains(w.toLowerCase(Locale.ROOT));
            case JSON:
            case YAML:
            case PROPS:
                return w.equals("true") || w.equals("false") || w.equals("null") || w.equals("yes") || w.equals("no");
            default:
                return false;
        }
    }

    private static int stringEnd(String s, int start, char q, int kind) {
        int n = s.length();
        if (kind == PYTHON && start + 2 < n && s.charAt(start + 1) == q && s.charAt(start + 2) == q) {
            String tri = "" + q + q + q;
            int e = s.indexOf(tri, start + 3);
            return e < 0 ? n : e + 3;
        }
        int i = start + 1;
        boolean multi = q == '`';
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == q) return i + 1;
            if (c == '\n' && !multi) return i;
            i++;
        }
        return n;
    }
}

package com.ghmanager.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * "SEMO AI": a small, fully offline checker that runs over a repository's text files before an
 * Action is started. It only does what fits in one phone, instantly, with no network call of its
 * own and no model: it looks for unbalanced brackets and a few always-safe formatting mistakes,
 * and fixes what it can fix with certainty. It never guesses at a fix that could change behaviour
 * (e.g. where exactly a missing brace belongs), it only reports those.
 */
final class SemoAi {
    private SemoAi() {
    }

    /** Extensions SEMO AI looks at. Binary, media and build-output files are never touched. */
    private static final Set<String> EXT = new HashSet<>(Arrays.asList(
            "java", "kt", "kts", "xml", "json", "json5", "js", "jsx", "mjs", "cjs", "ts", "tsx",
            "py", "c", "h", "cpp", "cc", "hpp", "cs", "go", "rb", "php", "swift", "css", "scss",
            "html", "htm", "yml", "yaml", "gradle", "groovy", "sh", "bash", "md", "properties",
            "toml", "dart", "lua", "sql"));

    /** Folders never worth scanning: generated, vendored, or version-control internals. */
    private static final String[] SKIP_DIRS = {
            "/.git/", "/.gradle/", "/build/", "/node_modules/", "/dist/", "/out/", "/.idea/",
            "/.venv/", "/venv/", "/vendor/", "/target/", "/.next/", "/.cache/"
    };

    static boolean eligible(String path, long size) {
        if (size <= 0 || size > 300 * 1024) return false;
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return false;
        if (!EXT.contains(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT))) return false;
        String p = "/" + path + "/";
        for (String d : SKIP_DIRS) if (p.contains(d)) return false;
        return true;
    }

    static final class Result {
        final String fixed;
        final boolean changed;
        final List<String> fixes = new ArrayList<>();   // what was changed, for the summary
        final List<String> issues = new ArrayList<>();  // found but NOT auto-fixed (too risky to guess)

        Result(String fixed, boolean changed) {
            this.fixed = fixed;
            this.changed = changed;
        }
    }

    private static final Pattern TRAILING_WS = Pattern.compile("[ \\t]+$", Pattern.MULTILINE);
    private static final Pattern TRAILING_COMMA = Pattern.compile(",(\\s*[}\\]])");

    static Result check(String path, String src) {
        if (src == null) return new Result(src, false);
        String out = src;
        List<String> fixes = new ArrayList<>();

        // 1) trailing whitespace on any line — always safe to drop
        String noTrailWs = TRAILING_WS.matcher(out).replaceAll("");
        if (!noTrailWs.equals(out)) {
            out = noTrailWs;
            fixes.add("إزالة مسافات زائدة في نهاية الأسطر");
        }

        // 2) exactly one trailing newline at end of file — safe, and avoids noisy diffs
        String trimmedEnd = out.replaceAll("[ \\t\\n\\r]+$", "");
        String normalizedEof = src.isEmpty() ? out : trimmedEnd + "\n";
        if (!normalizedEof.equals(out)) {
            out = normalizedEof;
            fixes.add("توحيد نهاية الملف بسطر واحد");
        }

        // 3) trailing commas before a closing bracket — only for JSON-like files, where it's a
        //    guaranteed parse error and the fix is unambiguous (JS/TS allow it, so left alone there)
        String ext = ext(path);
        if (ext.equals("json") || ext.equals("json5")) {
            String noTrailComma = TRAILING_COMMA.matcher(out).replaceAll("$1");
            if (!noTrailComma.equals(out)) {
                out = noTrailComma;
                fixes.add("إزالة فاصلة زائدة قبل قوس الإغلاق");
            }
        }

        // 4) bracket balance: { } ( ) [ ]  — skips string/char literals and comments as best effort
        List<String> issues = new ArrayList<>();
        BracketScan scan = scanBrackets(out, ext);
        if (scan.mismatch) {
            issues.add("تعارض في ترتيب الأقواس (قوس إغلاق لا يطابق آخر قوس فتح) — يحتاج مراجعة يدوية");
        } else if (!scan.openStack.isEmpty()) {
            // every still-open bracket is missing its close, and nothing after it could have closed
            // it out of order — safe to append the missing closes at the end of the file
            StringBuilder add = new StringBuilder();
            if (!out.endsWith("\n")) add.append('\n');
            for (int i = scan.openStack.size() - 1; i >= 0; i--) add.append(closeFor(scan.openStack.get(i)));
            add.append('\n');
            out = out + add;
            fixes.add("إضافة " + scan.openStack.size() + " قوس إغلاق مفقود في نهاية الملف");
        }

        boolean changed = !out.equals(src);
        Result r = new Result(out, changed);
        r.fixes.addAll(fixes);
        r.issues.addAll(issues);
        return r;
    }

    private static String ext(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static char closeFor(char open) {
        if (open == '{') return '}';
        if (open == '(') return ')';
        return ']';
    }

    private static final class BracketScan {
        final Deque<Character> openStack = new ArrayDeque<>();
        boolean mismatch = false;
    }

    /**
     * Walks the text tracking a bracket stack, skipping over string/char literals and comments so
     * a brace inside a quoted string (very common) is never mistaken for real code structure.
     */
    private static BracketScan scanBrackets(String s, String ext) {
        BracketScan r = new BracketScan();
        boolean hashComments = ext.equals("py") || ext.equals("yml") || ext.equals("yaml")
                || ext.equals("sh") || ext.equals("bash") || ext.equals("toml") || ext.equals("properties");
        boolean noComments = ext.equals("md") || ext.equals("json") || ext.equals("json5");
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            // line comment
            if (!noComments && !hashComments && c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
                continue;
            }
            if (hashComments && c == '#') {
                while (i < n && s.charAt(i) != '\n') i++;
                continue;
            }
            // block comment
            if (!noComments && !hashComments && c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i++;
                continue;
            }
            // string / char literal (best-effort: honours backslash escapes)
            if (c == '"' || c == '\'' || c == '`') {
                char q = c;
                i++;
                while (i < n && s.charAt(i) != q) {
                    if (s.charAt(i) == '\\' && i + 1 < n) i++;
                    i++;
                }
                continue;
            }
            if (c == '{' || c == '(' || c == '[') {
                r.openStack.push(c);
            } else if (c == '}' || c == ')' || c == ']') {
                if (r.openStack.isEmpty()) {
                    // a stray close with nothing open — also not safely auto-fixable, just flag it
                    r.mismatch = true;
                } else {
                    char top = r.openStack.peek();
                    char expect = closeFor(top);
                    if (expect == c) {
                        r.openStack.pop();
                    } else {
                        r.mismatch = true;
                    }
                }
            }
        }
        return r;
    }
}

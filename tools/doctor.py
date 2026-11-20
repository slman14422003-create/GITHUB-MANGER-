#!/usr/bin/env python3
"""Project doctor: looks for mistakes before the build, repairs the ones that are safe to repair, and
fails (exit 1) on everything else.

  doctor.py [--fix] [--report FILE] [ROOT]

Safe repairs (only with --fix):
  * an Activity class that is missing from AndroidManifest.xml is declared (not exported)
  * a string that is defined twice with the SAME text loses its second copy
  * a string used in code but missing everywhere gets a placeholder in values/strings_autofix.xml
  * a string that exists in values/ but not in values-en/ is copied (so the English build has no hole)
Everything else is a hard failure: broken XML, unbalanced braces, a missing class named in the manifest,
a missing layout / drawable / colour / id, a string defined twice with different text, a committed secret.
"""
import glob
import os
import re
import sys
import xml.dom.minidom as md

ROOT = "."
FIX = False
fixed, errors, warnings = [], [], []


def rd(p):
    with open(p, encoding="utf-8", errors="replace") as f:
        return f.read()


def wr(p, t):
    with open(p, "w", encoding="utf-8") as f:
        f.write(t)


def app_dir():
    for c in ("app/src/main", "src/main"):
        if os.path.isdir(os.path.join(ROOT, c)):
            return os.path.join(ROOT, c)
    return None


# ---------------------------------------------------------------- Java helpers
def strip_java(src):
    """Remove comments, string and char literals so braces can be counted."""
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            i = n if j < 0 else j + 3
        elif c in "\"'":
            q, i = c, i + 1
            while i < n and src[i] != q:
                i += 2 if src[i] == "\\" else 1
            i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check_balance(path, src):
    s = strip_java(src)
    stack = []
    pairs = {")": "(", "]": "[", "}": "{"}
    line = 1
    for ch in s:
        if ch == "\n":
            line += 1
        elif ch in "([{":
            stack.append((ch, line))
        elif ch in pairs:
            if not stack or stack[-1][0] != pairs[ch]:
                errors.append("%s:%d: unexpected '%s'" % (path, line, ch))
                return
            stack.pop()
    if stack:
        errors.append("%s:%d: '%s' is never closed" % (path, stack[-1][1], stack[-1][0]))


# ---------------------------------------------------------------- checks
def check_xml(main):
    files = glob.glob(os.path.join(main, "res", "**", "*.xml"), recursive=True)
    files.append(os.path.join(main, "AndroidManifest.xml"))
    for f in files:
        try:
            md.parse(f)
        except Exception as e:
            errors.append("%s: broken XML (%s)" % (os.path.relpath(f, ROOT), str(e).splitlines()[0]))


def string_table(main):
    """{values-dir: {name: (file, text)}} plus a list of duplicates."""
    tables, dups = {}, []
    for f in sorted(glob.glob(os.path.join(main, "res", "values*", "*.xml"))):
        d = os.path.basename(os.path.dirname(f))
        t = rd(f)
        for m in re.finditer(r'<string\s+name="([^"]+)"[^>]*>(.*?)</string>', t, re.S):
            name, text = m.group(1), m.group(2)
            tab = tables.setdefault(d, {})
            if name in tab and tab[name][0] != f:
                dups.append((d, name, tab[name], (f, text)))
            else:
                tab[name] = (f, text)
    return tables, dups


def fix_duplicates(dups):
    for d, name, first, second in dups:
        if first[1].strip() == second[1].strip():
            f = second[0]
            t = rd(f)
            new = re.sub(r'[ \t]*<string\s+name="%s"[^>]*>.*?</string>[ \t]*\n?' % re.escape(name), "", t, count=1, flags=re.S)
            if FIX and new != t:
                wr(f, new)
                fixed.append("removed duplicate string '%s' from %s" % (name, os.path.relpath(f, ROOT)))
                continue
        same = first[1].strip() == second[1].strip()
        errors.append("string '%s' is defined twice in %s%s (%s and %s)" % (
            name, d, " (same text, --fix removes it)" if same else " with different text",
            os.path.relpath(first[0], ROOT), os.path.relpath(second[0], ROOT)))


def resources(main):
    res = {k: set() for k in ("string", "color", "bool", "style", "dimen", "integer", "array", "plurals",
                              "drawable", "layout", "anim", "animator", "mipmap", "xml", "menu", "raw", "font", "id")}
    for f in glob.glob(os.path.join(main, "res", "values*", "*.xml")):
        t = rd(f)
        for k in ("string", "color", "bool", "dimen", "integer"):
            for m in re.finditer(r'<%s\s+name="([^"]+)"' % k, t):
                res[k].add(m.group(1))
        for m in re.finditer(r'<(?:string-array|integer-array|array)\s+name="([^"]+)"', t):
            res["array"].add(m.group(1))
        for m in re.finditer(r'<plurals\s+name="([^"]+)"', t):
            res["plurals"].add(m.group(1))
        for m in re.finditer(r'<style\s+name="([^"]+)"', t):
            res["style"].add(m.group(1).replace(".", "_"))
        for m in re.finditer(r'<item\s+name="([^"]+)"\s+type="id"', t):
            res["id"].add(m.group(1))
    for d in ("drawable", "layout", "anim", "animator", "mipmap", "xml", "menu", "raw", "font", "color"):
        for f in glob.glob(os.path.join(main, "res", d + "*", "*")):
            res[d].add(os.path.splitext(os.path.basename(f))[0])
    for f in glob.glob(os.path.join(main, "res", "**", "*.xml"), recursive=True):
        for m in re.finditer(r'@\+id/([A-Za-z0-9_]+)', rd(f)):
            res["id"].add(m.group(1))
    return res


LIB_IDS = {"alertTitle", "message", "spacer", "content", "title", "icon", "text1", "text2"}


def check_references(main, tables):
    res = resources(main)
    defined_strings = set(tables.get("values", {}))
    missing_strings = {}
    kinds = "string|color|drawable|layout|id|anim|animator|mipmap|bool|xml|menu|raw|font|dimen|integer|array|plurals"
    for f in glob.glob(os.path.join(main, "java", "**", "*.java"), recursive=True):
        src = strip_java_keep_idents(rd(f))
        for m in re.finditer(r'\bR\.(%s)\.([A-Za-z0-9_]+)' % kinds, src):
            k, n = m.group(1), m.group(2)
            if n in res[k] or (k == "id" and n in LIB_IDS):
                continue
            if k == "string":
                missing_strings.setdefault(n, []).append(os.path.relpath(f, ROOT))
            else:
                errors.append("%s: R.%s.%s does not exist" % (os.path.relpath(f, ROOT), k, n))
    for f in glob.glob(os.path.join(main, "res", "**", "*.xml"), recursive=True) + [os.path.join(main, "AndroidManifest.xml")]:
        for m in re.finditer(r'@(%s)/([A-Za-z0-9_.]+)' % kinds, rd(f)):
            k, n = m.group(1), m.group(2).replace(".", "_")
            if n in res[k] or k == "id":
                continue
            if k == "string":
                missing_strings.setdefault(n, []).append(os.path.relpath(f, ROOT))
            else:
                errors.append("%s: @%s/%s does not exist" % (os.path.relpath(f, ROOT), k, n))
    if missing_strings:
        if FIX:
            target = os.path.join(main, "res", "values", "strings_autofix.xml")
            body = rd(target) if os.path.exists(target) else '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
            add = ""
            for n in sorted(missing_strings):
                add += '    <string name="%s">%s</string>\n' % (n, n.replace("_", " "))
                fixed.append("added placeholder text for missing string '%s' (used in %s)" % (n, missing_strings[n][0]))
            wr(target, body.replace("</resources>", add + "</resources>"))
        else:
            for n, where in missing_strings.items():
                errors.append("string '%s' is used in %s but not defined" % (n, where[0]))


def strip_java_keep_idents(src):
    # keep identifiers (R.x.y) but drop comments and string literals
    return strip_java(src)


def check_english_gap(main, tables):
    ar, en = tables.get("values", {}), tables.get("values-en", {})
    if not en:
        return
    gap = [n for n in ar if n not in en and not re.search(r'translatable="false"', ar[n][1])]
    if not gap:
        return
    if FIX:
        target = os.path.join(main, "res", "values-en", "strings_autofix.xml")
        body = rd(target) if os.path.exists(target) else '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
        add = "".join('    <string name="%s">%s</string>\n' % (n, ar[n][1]) for n in sorted(gap))
        wr(target, body.replace("</resources>", add + "</resources>"))
        fixed.append("copied %d untranslated string(s) into values-en/strings_autofix.xml" % len(gap))
    else:
        warnings.append("%d string(s) have no English version, e.g. %s" % (len(gap), ", ".join(sorted(gap)[:5])))


def check_manifest(main):
    mf = os.path.join(main, "AndroidManifest.xml")
    t = rd(mf)
    pkg_dirs = {}
    for f in glob.glob(os.path.join(main, "java", "**", "*.java"), recursive=True):
        pkg_dirs[os.path.splitext(os.path.basename(f))[0]] = f
    declared = set(re.findall(r'<(?:activity|service|receiver|provider)[^>]*android:name="\.?([A-Za-z0-9_.]+)"', t))
    for name in sorted(declared):
        simple = name.split(".")[-1]
        if simple not in pkg_dirs and not name.startswith(("androidx.", "android.", "com.google.")):
            errors.append("AndroidManifest.xml declares '%s' but no such class exists" % name)
    activities = []
    for simple, f in pkg_dirs.items():
        src = strip_java(rd(f))
        if re.search(r'\bclass\s+%s\s+extends\s+(?:\w+\.)*(?:AppCompatActivity|Activity|BaseRepoActivity|FragmentActivity|ComponentActivity)\b' % re.escape(simple), src) \
                or re.search(r'\bclass\s+%s\s+extends\s+\w*Activity\b' % re.escape(simple), src):
            if re.search(r'\babstract\s+class\s+%s\b' % re.escape(simple), src):
                continue
            activities.append(simple)
    missing = [a for a in sorted(activities) if a not in {d.split(".")[-1] for d in declared}]
    if missing:
        if FIX:
            add = "".join('        <activity android:name=".%s" android:exported="false" />\n' % a for a in missing)
            new = t.replace("</application>", add + "    </application>", 1)
            wr(mf, new)
            for a in missing:
                fixed.append("declared activity %s in AndroidManifest.xml" % a)
        else:
            for a in missing:
                errors.append("activity %s is not declared in AndroidManifest.xml" % a)


LIFECYCLE = ("onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy", "onBackPressed", "onActivityResult")


def check_java(main):
    for f in glob.glob(os.path.join(main, "java", "**", "*.java"), recursive=True):
        rel = os.path.relpath(f, ROOT)
        src = rd(f)
        check_balance(rel, src)
        # the same lifecycle method written twice in one class does not compile ("already defined")
        body = strip_java(src)
        for m in LIFECYCLE:
            if len(re.findall(r"\n    (?:public|protected) void %s\(" % m, body)) > 1:
                errors.append("%s: method %s() is defined more than once in the class" % (rel, m))


SECRET = re.compile(r'(gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|-----BEGIN (?:RSA |EC |OPENSSH |)PRIVATE KEY-----|AKIA[0-9A-Z]{16})')


def check_secrets():
    bad_names = (".jks", ".keystore", ".p12", ".pfx")
    for dp, dn, fn in os.walk(ROOT):
        dn[:] = [d for d in dn if d not in (".git", "build", ".gradle", "node_modules")]
        for n in fn:
            p = os.path.join(dp, n)
            rel = os.path.relpath(p, ROOT)
            if n.lower().endswith(bad_names) or n == "keystore.properties":
                errors.append("%s: a signing key / keystore file is committed to the repository" % rel)
                continue
            try:
                if os.path.getsize(p) > 2_000_000:
                    continue
                if SECRET.search(rd(p)):
                    errors.append("%s: contains what looks like a secret token or private key" % rel)
            except Exception:
                pass


def check_workflows():
    try:
        import yaml
    except Exception:
        return
    for f in glob.glob(os.path.join(ROOT, ".github", "workflows", "*.y*ml")):
        try:
            yaml.safe_load(rd(f))
        except Exception as e:
            errors.append("%s: invalid YAML (%s)" % (os.path.relpath(f, ROOT), str(e).splitlines()[0]))


def main():
    global ROOT, FIX
    args, report = sys.argv[1:], None
    i = 0
    while i < len(args):
        if args[i] == "--fix":
            FIX = True
        elif args[i] == "--report":
            i += 1
            report = args[i]
        else:
            ROOT = args[i]
        i += 1
    m = app_dir()
    if not m:
        print("::error::no Android module found")
        sys.exit(1)
    check_secrets()
    check_workflows()
    check_xml(m)
    if not any("broken XML" in e for e in errors):
        tables, dups = string_table(m)
        fix_duplicates(dups)
        check_references(m, string_table(m)[0] if fixed else tables)
        check_english_gap(m, string_table(m)[0])
        check_manifest(m)
    check_java(m)

    lines = ["# Project doctor", ""]
    lines += ["## Repaired automatically (%d)" % len(fixed)] + ["- " + x for x in fixed] if fixed else ["## Repaired automatically: nothing"]
    lines += ["", "## Warnings (%d)" % len(warnings)] + ["- " + x for x in warnings]
    lines += ["", "## Errors that stop the build (%d)" % len(errors)] + ["- " + x for x in errors]
    text = "\n".join(lines) + "\n"
    print(text)
    if report:
        wr(report, text)
    for e in errors:
        print("::error title=Project doctor::" + e.replace("\n", " "))
    for w in warnings:
        print("::warning title=Project doctor::" + w)
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()

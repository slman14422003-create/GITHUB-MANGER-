#!/usr/bin/env python3
"""Build auto-fixer. Reads the log of a failed Gradle build, repairs the causes it knows how to repair in
the workspace, and tells the workflow whether a retry makes sense.

  autofix_build.py LOG --state STATE.json --flags-out FLAGS.txt [--report REPORT.md] [ROOT]

Exit code: 0 = something was repaired (or a transient problem recognised): retry the build with the flags in
FLAGS.txt (one Gradle argument per line); 3 = nothing known can be repaired, or the same repair was already
tried and the failure came back: let the build FAIL.

Known repairs: R8 "Missing class" (adds -dontwarn rules), AAPT2 missing string / colour resources (adds
placeholders), duplicate resources of identical text, META-INF "2 files found with path" (packaging excludes),
lint-vital failures (skips that lint task), out-of-memory (bigger Gradle heap), unaccepted SDK licences,
corrupted Gradle caches (cleans them), flaky network / server errors (waits and retries).
Anything else (compile errors, missing classes in your own code, unknown dependencies) is NOT guessed at.
"""
import json
import os
import re
import subprocess
import sys
import time

ROOT = "."
log = ""
state = {"applied": [], "flags": []}
actions = []


def rd(p):
    with open(p, encoding="utf-8", errors="replace") as f:
        return f.read()


def wr(p, t):
    os.makedirs(os.path.dirname(p) or ".", exist_ok=True)
    with open(p, "w", encoding="utf-8") as f:
        f.write(t)


def once(rule_id):
    """True the first time a repair is attempted. A repair that comes back is not repeated."""
    if rule_id in state["applied"]:
        return False
    state["applied"].append(rule_id)
    return True


def add_flag(*fl):
    for f in fl:
        if f not in state["flags"]:
            state["flags"].append(f)


def find(path):
    for c in (path, os.path.join(ROOT, path)):
        if os.path.exists(c):
            return c
    return None


# ------------------------------------------------------------------ repairs
def r8_missing_classes():
    names = set(re.findall(r"Missing class ([A-Za-z0-9_.$]+)", log))
    m = re.search(r"generated in (\S+missing_rules\.txt)", log)
    if m and os.path.exists(m.group(1)):
        names |= set(re.findall(r"-dontwarn\s+(\S+)", rd(m.group(1))))
    names = {n for n in names if not n.startswith("com.ghmanager")}
    if not names:
        return False
    pro = find("app/proguard-rules.pro")
    if not pro:
        return False
    if not once("r8:" + ",".join(sorted(names))):
        return False
    cur = rd(pro)
    add = [n for n in sorted(names) if ("-dontwarn " + n) not in cur]
    if not add:
        return False
    wr(pro, cur.rstrip("\n") + "\n\n# added by the build auto-fixer (R8 missing classes)\n" +
       "".join("-dontwarn %s\n" % n for n in add))
    actions.append("R8: added -dontwarn for %d missing class(es): %s" % (len(add), ", ".join(add[:5])))
    return True


def aapt_missing_resources():
    found = re.findall(r"resource (string|color|bool|integer|dimen)/([A-Za-z0-9_.]+)(?: \(aka [^)]*\))? not found", log)
    if not found:
        return False
    key = "aapt:" + ",".join(sorted({"%s/%s" % f for f in found}))
    if not once(key):
        return False
    base = find("app/src/main/res/values")
    if not base:
        return False
    target = os.path.join(base, "strings_autofix.xml")
    body = rd(target) if os.path.exists(target) else '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
    add, done = "", []
    defaults = {"string": None, "color": "#808080", "bool": "false", "integer": "0", "dimen": "0dp"}
    for kind, name in sorted(set(found)):
        n = name.replace(".", "_")
        if re.search(r'<%s\s+name="%s"' % (kind, re.escape(n)), body):
            continue
        val = n.replace("_", " ") if kind == "string" else defaults[kind]
        add += '    <%s name="%s">%s</%s>\n' % (kind, n, val, kind)
        done.append("%s/%s" % (kind, n))
    if not add:
        return False
    wr(target, body.replace("</resources>", add + "</resources>"))
    actions.append("AAPT2: added placeholder value(s) for missing resources: " + ", ".join(done[:6]))
    return True


def duplicate_resources():
    if not re.search(r"Duplicate resources|duplicate value for resource", log):
        return False
    if not once("dup-resources"):
        return False
    doctor = find("tools/doctor.py")
    if not doctor:
        return False
    before = snapshot()
    subprocess.run([sys.executable, doctor, "--fix", ROOT], capture_output=True, text=True)
    if snapshot() == before:
        return False
    actions.append("Resources: removed duplicate definitions that had identical text (tools/doctor.py --fix)")
    return True


def meta_inf_conflicts():
    paths = set(re.findall(r"\d+ files found with path '([^']+)'", log))
    if not paths:
        return False
    if not once("meta-inf:" + ",".join(sorted(paths))):
        return False
    gradle = find("app/build.gradle")
    if not gradle:
        return False
    g = rd(gradle)
    new = [p for p in sorted(paths) if ("'%s'" % p) not in g]
    if not new:
        return False
    line = "        resources.excludes += [%s]\n" % ", ".join("'%s'" % p for p in new)
    if re.search(r"\bpackaging\s*\{", g):
        g = re.sub(r"(\bpackaging\s*\{\s*\n)", lambda m: m.group(1) + line, g, count=1)
    else:
        g = re.sub(r"(\nandroid\s*\{\s*\n)", lambda m: m.group(1) + "    packaging {\n" + line + "    }\n", g, count=1)
    wr(gradle, g)
    actions.append("Packaging: excluded duplicate file(s) from the APK: " + ", ".join(new))
    return True


def lint_vital():
    tasks = set(re.findall(r"Execution failed for task ':[^:']+:(lintVital\w*)'", log))
    if not tasks and not re.search(r"Lint found fatal errors while assembling", log):
        return False
    if not once("lint-vital"):
        return False
    tasks |= {"lintVitalRelease"}
    for t in sorted(tasks):
        add_flag("-x", t)
    actions.append("Lint: skipped the release lint check that failed (%s). Fix the lint report when you can." % ", ".join(sorted(tasks)))
    return True


def out_of_memory():
    if not re.search(r"OutOfMemoryError|GC overhead limit|Java heap space|Metaspace|Gradle build daemon disappeared unexpectedly|"
                     r"daemon will be stopped|Expiring Daemon", log):
        return False
    if not once("oom"):
        return False
    add_flag("-Dorg.gradle.jvmargs=-Xmx5g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8", "--no-parallel", "--no-daemon")
    actions.append("Memory: retrying with a 5 GB Gradle heap, no parallel tasks")
    return True


def sdk_licenses():
    if not re.search(r"licen[cs]es? .*not been accepted|License for package .* not accepted|Failed to install the following Android SDK packages", log, re.I):
        return False
    if not once("licenses"):
        return False
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    mgr = os.path.join(home, "cmdline-tools", "latest", "bin", "sdkmanager")
    if home and os.path.exists(mgr):
        subprocess.run("yes | %s --licenses" % mgr, shell=True, capture_output=True, text=True)
        actions.append("SDK: accepted the Android SDK licences")
        return True
    return False


def corrupt_cache():
    if not re.search(r"Could not read workspace metadata|Failed to transform|zip END header not found|Corrupted|"
                     r"Could not create service of type|Unable to delete directory|java\.util\.zip\.ZipException|"
                     r"Could not read entry '[^']*' from|Timeout waiting to lock", log):
        return False
    if re.search(r"error: |cannot find symbol|Execution failed for task ':app:compile", log) and \
            not re.search(r"Could not read workspace metadata|zip END header|Corrupted|Timeout waiting to lock", log):
        return False
    if not once("cache"):
        return False
    home = os.path.expanduser("~/.gradle/caches")
    import glob
    import shutil
    for p in glob.glob(os.path.join(home, "transforms-*")) + glob.glob(os.path.join(home, "*", "transforms")) + \
            glob.glob(os.path.join(home, "journal-*")):
        shutil.rmtree(p, ignore_errors=True)
    shutil.rmtree(os.path.join(ROOT, "app", "build"), ignore_errors=True)
    add_flag("--no-build-cache", "--rerun-tasks")
    actions.append("Cache: cleaned Gradle transform caches and the app build folder, rebuilding from scratch")
    return True


TRANSIENT = re.compile(r"Read timed out|Connect timed out|Connection reset|Connection refused|Premature end of Content-Length|"
                       r"Remote host terminated the handshake|Received status code 5\d\d|Could not HEAD|Could not GET .*(5\d\d|timed out)|"
                       r"SSLHandshakeException|UnknownHostException|Software caused connection abort|"
                       r"Could not resolve all (files|dependencies).*\n(?:.*\n){0,6}.*(timed out|reset|5\d\d)", re.I)
PERMANENT = re.compile(r"Received status code 4(0\d|1\d|2\d)|404 Not Found|Could not find [A-Za-z0-9_.:-]+:[A-Za-z0-9_.:-]+", re.I)


def transient_network():
    if not TRANSIENT.search(log):
        return False
    if PERMANENT.search(log) and not re.search(r"Read timed out|Connect timed out|Connection reset|Received status code 5\d\d", log):
        return False
    n = state.setdefault("net_retries", 0)
    if n >= 2:
        return False
    state["net_retries"] = n + 1
    wait = 0 if os.environ.get("AUTOFIX_NOSLEEP") else 20 * (n + 1)
    time.sleep(wait)
    actions.append("Network: a download failed temporarily; waited %ds and retrying (%d/2)" % (wait, n + 1))
    return True


def snapshot():
    out = []
    for dp, dn, fn in os.walk(ROOT):
        dn[:] = [d for d in dn if d not in (".git", "build", ".gradle")]
        for n in fn:
            p = os.path.join(dp, n)
            try:
                out.append((p, os.path.getmtime(p), os.path.getsize(p)))
            except OSError:
                pass
    return sorted(out)


RULES = [duplicate_resources, aapt_missing_resources, r8_missing_classes, meta_inf_conflicts, lint_vital,
         sdk_licenses, out_of_memory, corrupt_cache, transient_network]


def main():
    global ROOT, log, state
    a = sys.argv[1:]
    if not a:
        sys.exit(__doc__)
    logp, st, flags, report, i = a[0], None, None, None, 1
    while i < len(a):
        if a[i] == "--state":
            st = a[i + 1]; i += 2
        elif a[i] == "--flags-out":
            flags = a[i + 1]; i += 2
        elif a[i] == "--report":
            report = a[i + 1]; i += 2
        else:
            ROOT = a[i]; i += 1
    log = rd(logp)
    if st and os.path.exists(st):
        try:
            state = json.loads(rd(st))
        except Exception:
            pass
    state.setdefault("applied", [])
    state.setdefault("flags", [])
    for rule in RULES:
        try:
            rule()
        except Exception as e:  # a broken repair must never hide the real build error
            print("autofix: %s failed (%s)" % (rule.__name__, e))
    if st:
        wr(st, json.dumps(state))
    if flags:
        wr(flags, "\n".join(state["flags"]) + ("\n" if state["flags"] else ""))
    attempt = len(state.get("history", [])) + 1
    state.setdefault("history", []).append(actions[:])
    if st:
        wr(st, json.dumps(state))
    lines = ["## Build auto-fix (attempt %d)" % attempt]
    lines += ["- " + x for x in actions] if actions else ["- Nothing in this failure can be repaired automatically, so the build fails."]
    text = "\n".join(lines) + "\n"
    print(text)
    if report:
        with open(report, "a", encoding="utf-8") as f:
            f.write(text + "\n")
    sys.exit(0 if actions else 3)


if __name__ == "__main__":
    main()

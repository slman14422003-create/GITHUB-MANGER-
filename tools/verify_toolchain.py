#!/usr/bin/env python3
"""Audits the pinned toolchain before a release is allowed to build.

Hard failures (exit code 1):
  * a version that is not a plain stable number (alpha, beta, rc, dev, snapshot, preview ...)
  * Gradle in the wrapper and in the workflows disagree
  * a version that does not exist in its official repository (or Gradle's list of stable releases)
  * the Gradle wrapper checksum is missing or differs from the published one
  * a GitHub Action that is not pinned to a full commit SHA
  * a release younger than MIN_AGE_DAYS (default 3): brand new releases are the ones that get pulled or abused

Usage: verify_toolchain.py [project_root] [--emit]    (env OFFLINE=1 skips the network checks)
"""
import datetime
import os
import re
import sys
from pathlib import Path

import toolchain_lib as L

args = [a for a in sys.argv[1:] if not a.startswith("--")]
ROOT = Path(args[0] if args else ".").resolve()
EMIT = "--emit" in sys.argv
OFFLINE = os.environ.get("OFFLINE") == "1"
MIN_AGE = float(os.environ.get("MIN_AGE_DAYS", "3"))

rows = []
failed = False


def check(name, ok, detail=""):
    global failed
    rows.append((name, ok, detail))
    if not ok:
        failed = True


def age_days(when):
    return (datetime.datetime.now(datetime.timezone.utc) - when).total_seconds() / 86400.0


def main():
    files = L.project_files(ROOT)
    cur = L.current_versions(ROOT)

    for key in ("gradle", "agp", "appcompat", "splashscreen", "kotlin_bom"):
        v = cur.get(key)
        check("%s is a stable version" % key, bool(v) and L.is_stable(v), str(v))
    for v in cur["build_tools"]:
        check("build-tools %s is stable" % v, L.is_stable(v), v)
    for v in cur["workflow_gradle"]:
        check("workflow Gradle %s matches the wrapper" % v, v == cur["gradle"], "wrapper: %s" % cur["gradle"])

    unpinned = []
    for w in files["workflows"]:
        for m in L.USES.finditer(L.read(w)):
            if m.group("path").startswith("./"):
                continue
            if not L.SHA40.match(m.group("ref")):
                unpinned.append("%s: %s@%s" % (w.name, m.group("path"), m.group("ref")))
    check("every GitHub Action is pinned to a commit SHA", not unpinned, "; ".join(unpinned[:6]))

    wrapper = L.read(files["wrapper"])
    m = re.search(r"distributionSha256Sum=([0-9a-f]{64})", wrapper)
    check("Gradle wrapper checksum is pinned", bool(m), "")

    if not OFFLINE:
        try:
            stable = L.gradle_versions()
            ok = cur["gradle"] in stable
            check("Gradle %s is a published stable release" % cur["gradle"], ok, "")
            if ok and stable[cur["gradle"]]:
                try:
                    built = datetime.datetime.strptime(stable[cur["gradle"]], "%Y%m%d%H%M%S%z")
                    check("Gradle release is at least %g days old" % MIN_AGE, age_days(built) >= MIN_AGE,
                          "%.1f days" % age_days(built))
                except ValueError:
                    pass
            if m:
                online = L.get("https://services.gradle.org/distributions/gradle-%s-bin.zip.sha256" % cur["gradle"]).decode().strip()
                check("Gradle wrapper checksum matches the published one", online == m.group(1), "")
        except Exception as e:
            check("Gradle repository reachable", False, str(e))
        for key, meta in (("agp", L.AGP_META), ("appcompat", L.APPCOMPAT_META),
                          ("splashscreen", L.SPLASH_META), ("kotlin_bom", L.KOTLIN_META)):
            try:
                check("%s %s exists in its repository" % (key, cur[key]), cur[key] in L.maven_versions(meta), "")
            except Exception as e:
                check("%s repository reachable" % key, False, str(e))
        try:
            tools = L.stable_build_tools(L.get(L.SDK_REPO).decode("utf-8"))
            for v in cur["build_tools"]:
                check("build-tools %s is a stable SDK package" % v, v in tools, "")
        except Exception as e:
            check("Android SDK repository reachable", False, str(e))
        seen = set()
        for w in files["workflows"]:
            for am in L.USES.finditer(L.read(w)):
                tag = re.search(r"v\d+\.\d+\.\d+", am.group("tail") or "")
                repo = "/".join(am.group("path").split("/")[:2])
                if am.group("path").startswith("./") or not tag or (repo, tag.group(0)) in seen:
                    continue
                seen.add((repo, tag.group(0)))
                try:
                    rel = L.get_json("https://api.github.com/repos/%s/releases/tags/%s" % (repo, tag.group(0)), github=True)
                    when = datetime.datetime.fromisoformat(rel["published_at"].replace("Z", "+00:00"))
                    check("%s %s is at least %g days old" % (repo, tag.group(0), MIN_AGE), age_days(when) >= MIN_AGE,
                          "%.1f days" % age_days(when))
                except Exception:
                    pass

    lines = ["## Toolchain audit", "", "| Check | Result | Detail |", "|---|---|---|"]
    for name, ok, detail in rows:
        lines.append("| %s | %s | %s |" % (name, "pass" if ok else "FAIL", detail))
    report = "\n".join(lines) + "\n"
    print(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(report)
    out = os.environ.get("GITHUB_OUTPUT")
    if EMIT and out:
        with open(out, "a", encoding="utf-8") as f:
            f.write("gradle=%s\n" % cur["gradle"])
            f.write("build_tools=%s\n" % (cur["build_tools"][0] if cur["build_tools"] else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

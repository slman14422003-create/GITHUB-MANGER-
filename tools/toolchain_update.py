#!/usr/bin/env python3
"""Moves the build toolchain to the newest STABLE versions and pins every GitHub Action to a commit.

Policy (kept conservative on purpose):
  * Gradle, AGP, androidx libraries: newest stable on the SAME major line (a new major is a manual decision).
  * Kotlin BOM: newest stable patch of the same major.minor line.
  * Android build-tools: newest stable on the same major as the one in use.
  * GitHub Actions: newest stable release on the same major, written as a full commit SHA with the version
    in a comment, so a moved tag can never change what runs.
  * compileSdk / targetSdk are never changed here.

Usage: toolchain_update.py [project_root]   (env UPDATE_INFO_PATH = where to write the JSON result)
"""
import json
import os
import re
import sys
from pathlib import Path

import toolchain_lib as L

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
# Workflow files can only be pushed with a token that has the "workflow" scope; the default GITHUB_TOKEN cannot.
TOUCH_WORKFLOWS = os.environ.get("UPDATE_WORKFLOWS") == "1"
changes = []
notes = []


def record(what, old, new):
    changes.append({"what": what, "from": old, "to": new})


def replace_in(path, regex, new_value, what, group=1):
    text = L.read(path)
    state = {"old": None}

    def repl(m):
        state["old"] = m.group(group)
        if m.group(group) == new_value:
            return m.group(0)
        s, e = m.span(group)
        base = m.start()
        return m.group(0)[: s - base] + new_value + m.group(0)[e - base:]

    out = regex.sub(repl, text) if hasattr(regex, "sub") else re.sub(regex, repl, text)
    if out != text:
        Path(path).write_text(out, encoding="utf-8")
        record(what, state["old"], new_value)


def update_gradle(files, cur):
    versions = L.gradle_versions()
    latest = L.newest_stable(list(versions), cur["gradle"])
    sha = ""
    try:
        sha = L.get("https://services.gradle.org/distributions/gradle-%s-bin.zip.sha256" % latest).decode().strip()
    except Exception as e:
        notes.append("Gradle checksum could not be fetched: %s" % e)
    wrapper = files["wrapper"]
    text = L.read(wrapper)
    new = L.GRADLE_URL.sub("gradle-%s-bin.zip" % latest, text)
    if sha and re.fullmatch(r"[0-9a-f]{64}", sha):
        if "distributionSha256Sum" in new:
            new = re.sub(r"distributionSha256Sum=.*", "distributionSha256Sum=" + sha, new)
        else:
            new = re.sub(r"(distributionUrl=[^\n]*\n)", r"\1distributionSha256Sum=" + sha + "\n", new)
    if new != text:
        wrapper.write_text(new, encoding="utf-8")
    if latest != cur["gradle"]:
        record("Gradle", cur["gradle"], latest)
    elif new != text:
        record("Gradle wrapper checksum", "-", "pinned")
    if TOUCH_WORKFLOWS:
        for w in files["workflows"]:
            replace_in(w, L.YAML_GRADLE, latest, "Gradle version in " + w.name)


def update_libs(files, cur):
    app = files["app_gradle"]
    for key, rx, meta, minor in (
        ("appcompat", L.DEP_APPCOMPAT, L.APPCOMPAT_META, False),
        ("splashscreen", L.DEP_SPLASH, L.SPLASH_META, False),
        ("kotlin_bom", L.DEP_KOTLIN, L.KOTLIN_META, True),
    ):
        if not cur[key]:
            continue
        latest = L.newest_stable(L.maven_versions(meta), cur[key], same_minor=minor)
        replace_in(app, rx, latest, key)
    if cur["agp"]:
        latest = L.newest_stable(L.maven_versions(L.AGP_META), cur["agp"])
        replace_in(files["root_gradle"], L.ROOT_AGP, latest, "Android Gradle Plugin")


def update_build_tools(files, cur):
    if not cur["build_tools"]:
        return
    stable = L.stable_build_tools(L.get(L.SDK_REPO).decode("utf-8"))
    prop = files["toolchain"]
    if prop.exists():
        old = L.TOOLS_PROP.search(L.read(prop))
        if old:
            replace_in(prop, L.TOOLS_PROP, L.newest_stable(stable, old.group(1)), "Android build-tools")
    if TOUCH_WORKFLOWS:
        for w in files["workflows"]:
            for old in set(L.YAML_BUILD_TOOLS.findall(L.read(w))):
                latest = L.newest_stable(stable, old)
                if latest != old:
                    Path(w).write_text(L.read(w).replace("build-tools;" + old, "build-tools;" + latest), encoding="utf-8")
                    record("Build-tools in " + w.name, old, latest)


def latest_release(repo, major):
    best = None
    for rel in L.get_json("https://api.github.com/repos/%s/releases?per_page=100" % repo, github=True):
        if rel.get("draft") or rel.get("prerelease"):
            continue
        m = re.fullmatch(r"v?(\d+)\.(\d+)\.(\d+)", rel.get("tag_name", ""))
        if not m or int(m.group(1)) != major:
            continue
        key = tuple(int(x) for x in m.groups())
        if best is None or key > best[0]:
            best = (key, rel["tag_name"])
    return best[1] if best else None


def tag_sha(repo, tag):
    ref = L.get_json("https://api.github.com/repos/%s/git/ref/tags/%s" % (repo, tag), github=True)["object"]
    if ref["type"] == "tag":
        ref = L.get_json("https://api.github.com/repos/%s/git/tags/%s" % (repo, ref["sha"]), github=True)["object"]
    return ref["sha"]


def pin_actions(files):
    cache = {}

    def target(repo, ref, comment):
        key = (repo, ref, comment)
        if key in cache:
            return cache[key]
        major = None
        if L.SHA40.match(ref):
            m = re.search(r"v?(\d+)\.\d+", comment or "")
            major = int(m.group(1)) if m else None
        else:
            m = re.match(r"v?(\d+)", ref)
            major = int(m.group(1)) if m else None
        result = None
        if major is not None:
            try:
                tag = latest_release(repo, major)
                if not tag and not L.SHA40.match(ref):
                    tag = ref
                if tag:
                    result = (tag_sha(repo, tag), tag)
            except Exception as e:
                notes.append("Action %s could not be resolved: %s" % (repo, e))
        else:
            notes.append("Action %s uses the moving ref '%s'; pin it by hand." % (repo, ref))
        cache[key] = result
        return result

    for w in files["workflows"]:
        text = L.read(w)

        def repl(m):
            path, ref = m.group("path"), m.group("ref")
            if path.startswith("./"):
                return m.group(0)
            repo = "/".join(path.split("/")[:2])
            t = target(repo, ref, (m.group("tail") or ""))
            if not t:
                return m.group(0)
            sha, tag = t
            new = "%s%s@%s # %s" % (m.group("head"), path, sha, tag)
            if new != m.group(0):
                record("Action %s (%s)" % (path, w.name), ref[:12], tag)
            return new

        out = L.USES.sub(repl, text)
        if out != text:
            Path(w).write_text(out, encoding="utf-8")


def main():
    files = L.project_files(ROOT)
    cur = L.current_versions(ROOT)
    if not cur["gradle"]:
        print("Gradle version not found in the wrapper properties", file=sys.stderr)
        return 1
    steps = [lambda: update_gradle(files, cur), lambda: update_libs(files, cur),
             lambda: update_build_tools(files, cur)]
    if TOUCH_WORKFLOWS:
        steps.append(lambda: pin_actions(files))
    else:
        notes.append("GitHub Actions were not touched: add a repository secret TOOLCHAIN_PAT (a token with the "
                     "'workflow' scope) to let the updater pin and update them.")
    for step in steps:
        try:
            step()
        except Exception as e:
            notes.append("Step failed: %s" % e)
    lines = ["## Toolchain update", ""]
    if changes:
        lines += ["| What | From | To |", "|---|---|---|"]
        lines += ["| %s | %s | %s |" % (c["what"], c["from"], c["to"]) for c in changes]
    else:
        lines.append("Everything is already on the newest stable versions.")
    if notes:
        lines += ["", "Notes:"] + ["- " + n for n in notes]
    report = "\n".join(lines) + "\n"
    print(report)
    info = {"changed": bool(changes), "changes": changes, "notes": notes}
    out = os.environ.get("UPDATE_INFO_PATH")
    if out:
        Path(out).write_text(json.dumps(info), encoding="utf-8")
    rp = os.environ.get("UPDATE_REPORT_PATH")
    if rp:
        Path(rp).write_text(report, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())

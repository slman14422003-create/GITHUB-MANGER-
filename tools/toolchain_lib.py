"""Shared helpers for the toolchain updater and the toolchain verifier."""
import json
import os
import re
import urllib.request
from pathlib import Path

ALLOWED_HOSTS = (
    "https://services.gradle.org/",
    "https://dl.google.com/",
    "https://repo.maven.apache.org/",
    "https://api.github.com/",
)

STABLE = re.compile(r"^\d+(\.\d+){1,3}$")
PRE_MARKERS = re.compile(r"(alpha|beta|rc|dev|snapshot|eap|preview|milestone|canary|nightly|-m\d)", re.I)
SHA40 = re.compile(r"^[0-9a-f]{40}$")
USES = re.compile(r"^(?P<head>\s*(?:-\s*)?uses:\s*)(?P<path>[\w.-]+/[\w./-]+)@(?P<ref>[^\s#]+)(?P<tail>[ \t]*#[^\n]*)?$", re.M)

GRADLE_URL = re.compile(r"gradle-(\d+(?:\.\d+)+)-bin\.zip")
YAML_GRADLE = re.compile(r"gradle-version:\s*'([\d.]+)'")
YAML_BUILD_TOOLS = re.compile(r"build-tools;([\d.]+)")
ROOT_AGP = re.compile(r"com\.android\.application'\s+version\s+'([\d.]+)'")
DEP_APPCOMPAT = re.compile(r"androidx\.appcompat:appcompat:([\d.]+)")
DEP_SPLASH = re.compile(r"androidx\.core:core-splashscreen:([\d.]+)")
DEP_KOTLIN = re.compile(r"kotlin-bom:([\d.]+)")
TOOLS_PROP = re.compile(r"^buildTools=([\d.]+)", re.M)


def get(url, github=False):
    if not url.startswith(ALLOWED_HOSTS):
        raise ValueError("host not allowed: " + url)
    req = urllib.request.Request(url, headers={"User-Agent": "toolchain-bot"})
    if github and os.environ.get("GITHUB_TOKEN"):
        req.add_header("Authorization", "Bearer " + os.environ["GITHUB_TOKEN"])
        req.add_header("Accept", "application/vnd.github+json")
    with urllib.request.urlopen(req, timeout=40) as r:
        return r.read()


def get_json(url, github=False):
    return json.loads(get(url, github).decode("utf-8"))


def vkey(v):
    return tuple(int(x) for x in v.split("."))


def is_stable(v):
    return bool(STABLE.match(v)) and not PRE_MARKERS.search(v)


def newest_stable(versions, current, same_minor=False):
    """Highest stable version on the same major line (or the same major.minor line) that is not older than current."""
    cur = vkey(current)
    best = current
    for v in versions:
        if not is_stable(v):
            continue
        k = vkey(v)
        if k[0] != cur[0]:
            continue
        if same_minor and k[:2] != cur[:2]:
            continue
        if k > vkey(best):
            best = v
    return best


def maven_versions(meta_url):
    text = get(meta_url).decode("utf-8")
    return re.findall(r"<version>([^<]+)</version>", text)


GOOGLE = "https://dl.google.com/dl/android/maven2/"
CENTRAL = "https://repo.maven.apache.org/maven2/"
AGP_META = GOOGLE + "com/android/tools/build/gradle/maven-metadata.xml"
APPCOMPAT_META = GOOGLE + "androidx/appcompat/appcompat/maven-metadata.xml"
SPLASH_META = GOOGLE + "androidx/core/core-splashscreen/maven-metadata.xml"
KOTLIN_META = CENTRAL + "org/jetbrains/kotlin/kotlin-bom/maven-metadata.xml"
GRADLE_ALL = "https://services.gradle.org/versions/all"
SDK_REPO = "https://dl.google.com/android/repository/repository2-3.xml"


def gradle_versions():
    out = {}
    for e in get_json(GRADLE_ALL):
        if e.get("snapshot") or e.get("nightly") or e.get("rcFor") or e.get("milestoneFor") or e.get("broken"):
            continue
        if e.get("releaseNightly"):
            continue
        out[e["version"]] = e.get("buildTime", "")
    return out


def stable_build_tools(xml_text):
    found = []
    for m in re.finditer(r'<remotePackage path="build-tools;([\d.]+)"[^>]*>(.*?)</remotePackage>', xml_text, re.S):
        if 'ref="channel-0"' in m.group(2):
            found.append(m.group(1))
    return found


def read(path):
    return Path(path).read_text(encoding="utf-8")


def project_files(root):
    root = Path(root)
    return {
        "wrapper": root / "gradle/wrapper/gradle-wrapper.properties",
        "toolchain": root / "gradle/toolchain.properties",
        "root_gradle": root / "build.gradle",
        "app_gradle": root / "app/build.gradle",
        "workflows": sorted((root / ".github/workflows").glob("*.y*ml")),
    }


def current_versions(root):
    f = project_files(root)
    out = {}
    m = GRADLE_URL.search(read(f["wrapper"]))
    out["gradle"] = m.group(1) if m else None
    m = ROOT_AGP.search(read(f["root_gradle"]))
    out["agp"] = m.group(1) if m else None
    app = read(f["app_gradle"])
    for key, rx in (("appcompat", DEP_APPCOMPAT), ("splashscreen", DEP_SPLASH), ("kotlin_bom", DEP_KOTLIN)):
        m = rx.search(app)
        out[key] = m.group(1) if m else None
    yaml_gradle, tools = set(), set()
    if f["toolchain"].exists():
        tools.update(TOOLS_PROP.findall(read(f["toolchain"])))
    for w in f["workflows"]:
        t = read(w)
        yaml_gradle.update(YAML_GRADLE.findall(t))
        tools.update(YAML_BUILD_TOOLS.findall(t))
    out["workflow_gradle"] = sorted(yaml_gradle)
    out["build_tools"] = sorted(tools)
    return out

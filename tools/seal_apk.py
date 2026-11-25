#!/usr/bin/env python3
"""Seals an APK: records the SHA-256 of every classes*.dex, resources.arsc and AndroidManifest.xml in
assets/guard.bin (HMAC-protected with a key derived from the signing certificate) and rewrites the APK.
The caller re-signs and zip-aligns afterwards (see .github/workflows/build.yml).

  seal_apk.py seal   IN.apk OUT.apk CERT_SHA256_HEX
  seal_apk.py verify APK CERT_SHA256_HEX          (same rules as the app's GuardCore; exit 1 on mismatch)
"""
import hashlib
import hmac
import sys
import zipfile

ENTRY = "assets/guard.bin"


def protected(name):
    return (name in ("resources.arsc", "AndroidManifest.xml")
            or (name.startswith("classes") and name.endswith(".dex") and "/" not in name))


def digests(z):
    return {i.filename: hashlib.sha256(z.read(i.filename)).hexdigest()
            for i in z.infolist() if not i.is_dir() and protected(i.filename)}


def mac(body, cert):
    return hmac.new(("gh-guard-v1|" + cert).encode(), body.encode(), hashlib.sha256).hexdigest()


def seal(src, dst, cert):
    cert = cert.replace(":", "").lower()
    with zipfile.ZipFile(src) as zin:
        d = digests(zin)
        if not any(n.endswith(".dex") for n in d) or "AndroidManifest.xml" not in d:
            sys.exit("seal: this does not look like an APK")
        body = "".join("%s=%s\n" % (k, d[k]) for k in sorted(d))
        with zipfile.ZipFile(dst, "w") as zout:
            for i in zin.infolist():
                n = i.filename
                # old signature and any previous seal are dropped: the APK is signed again afterwards
                if n == ENTRY or n.startswith("META-INF/"):
                    continue
                data = zin.read(n)
                if n == "resources.arsc":
                    # must stay STORED and 4-byte aligned for apps targeting Android 11+; zipalign fixes alignment
                    zout.writestr(zipfile.ZipInfo(n, i.date_time), data, zipfile.ZIP_STORED)
                else:
                    zi = zipfile.ZipInfo(n, i.date_time)
                    zi.external_attr = i.external_attr
                    zout.writestr(zi, data, zipfile.ZIP_STORED if i.compress_type == zipfile.ZIP_STORED else zipfile.ZIP_DEFLATED)
            zout.writestr(ENTRY, body + "mac=" + mac(body, cert), zipfile.ZIP_DEFLATED)
    print("sealed %d files" % len(d))


def verify(apk, cert):
    cert = cert.replace(":", "").lower()
    with zipfile.ZipFile(apk) as z:
        if ENTRY not in z.namelist():
            sys.exit("verify: guard.bin is missing")
        text = z.read(ENTRY).decode()
        at = text.rfind("mac=")
        body, got = text[:at], text[at + 4:].strip()
        if not hmac.compare_digest(mac(body, cert), got):
            sys.exit("verify: guard.bin is not authentic for this certificate")
        sealed = dict(l.split("=", 1) for l in body.split("\n") if l)
        if sealed != digests(z):
            sys.exit("verify: files differ from what was sealed")
    print("guard OK")


if __name__ == "__main__":
    a = sys.argv[1:]
    if len(a) == 4 and a[0] == "seal":
        seal(a[1], a[2], a[3])
    elif len(a) == 3 and a[0] == "verify":
        verify(a[1], a[2])
    else:
        sys.exit(__doc__)

#!/usr/bin/env python3
"""Validate the signed stable artifact before it becomes downloadable."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

build_tools, apk = Path(sys.argv[1]), Path(sys.argv[2])
metadata = json.loads((apk.parent / "output-metadata.json").read_text())
element = metadata["elements"][0]
version, code = element["versionName"], element["versionCode"]
assert re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version), "Stable version must have no prerelease suffix"
assert metadata["applicationId"] == "com.example.juke", "Package identity changed"
signing = subprocess.check_output([str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
expected = Path(".github/signing/debug-certificate.sha256").read_text().strip()
assert f"Signer #1 certificate SHA-256 digest: {expected}" in signing, "Installed-app signing identity changed"
badging = subprocess.check_output([str(build_tools / "aapt"), "dump", "badging", str(apk)], text=True)
package_line = badging.splitlines()[0]
assert "name='com.example.juke'" in package_line
assert f"versionName='{version}'" in package_line and f"versionCode='{code}'" in package_line
assert "application-debuggable" not in badging, "Stable APK is debuggable"
manifest = subprocess.check_output([str(build_tools / "aapt"), "dump", "xmltree", str(apk), "AndroidManifest.xml"], text=True)
assert not re.search(r"android:debuggable[^\n]*0xffffffff", manifest), "Stable manifest enables debugging"
output = Path("release-artifacts")
output.mkdir(exist_ok=True)
target = output / f"Music-Box-{version}.apk"
shutil.copyfile(apk, target)
digest = hashlib.sha256(target.read_bytes()).hexdigest()
(output / "SHA256SUMS.txt").write_text(f"{digest}  {target.name}\n")
(output / "signing-certificate.sha256").write_text(expected + "\n")
print(f"Verified stable {version} ({code}), package com.example.juke, non-debuggable, matching signature")
print(f"SHA256: {digest}")

#!/usr/bin/env python3
"""Validate the signed stable artifact before it becomes downloadable."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

def verify_pinned_certificate(signing: str, expected: str) -> None:
    """Read legacy and SDK 37 scheme-prefixed labels without changing the pin."""
    expected = expected.strip().replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("Invalid pinned signing certificate fingerprint")
    fingerprints = {
        value.replace(":", "").lower()
        for value in re.findall(
            r"^(?:Signer\b[^\r\n]*|V[1-4](?:\.\d+)?[ \t]+(?:Hybrid[ \t]+(?:Classical|PQC)[ \t]+)?Signer\b[^\r\n]*) certificate SHA-256 digest:[ \t]*([0-9a-f:]+)[ \t]*\r?$",
            signing, flags=re.MULTILINE | re.IGNORECASE)
    }
    if fingerprints != {expected}:
        raise ValueError(
            f"Installed-app signing identity changed: expected {expected}, "
            f"found {', '.join(sorted(fingerprints)) or 'no signer certificate'}")


def main(build_tools: Path, apk: Path) -> None:
    metadata = json.loads((apk.parent / "output-metadata.json").read_text())
    element = metadata["elements"][0]
    version, code = element["versionName"], element["versionCode"]
    assert re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version), "Stable version must have no prerelease suffix"
    assert metadata["applicationId"] == "in.synthora.musicbox", "Package identity changed"
    signing = subprocess.check_output([str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    expected = Path(".github/signing/debug-certificate.sha256").read_text().strip()
    print(signing, end="")  # Public certificates only; never private signing material.
    verify_pinned_certificate(signing, expected)
    badging = subprocess.check_output([str(build_tools / "aapt"), "dump", "badging", str(apk)], text=True)
    package_line = badging.splitlines()[0]
    assert "name='in.synthora.musicbox'" in package_line
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
    print(f"Verified stable {version} ({code}), package in.synthora.musicbox, non-debuggable, matching signature")
    print(f"SHA256: {digest}")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))

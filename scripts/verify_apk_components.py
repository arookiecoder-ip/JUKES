"""Verify app-owned manifest entry points exist as class definitions in the APK."""
import glob
import os
import re
import struct
import subprocess
import sys
import zipfile


def dex_classes(data):
    def word(offset):
        return struct.unpack_from("<I", data, offset)[0]

    strings = word(60)
    types = word(68)
    count, definitions = word(96), word(100)
    result = set()
    for index in range(count):
        type_index = word(definitions + index * 32)
        string_index = word(types + type_index * 4)
        offset = word(strings + string_index * 4)
        while data[offset] & 0x80:
            offset += 1
        offset += 1
        result.add(data[offset:data.index(b"\0", offset)].decode("utf-8"))
    return result


def verify(apk, aapt):
    tree = subprocess.check_output([aapt, "dump", "xmltree", apk, "AndroidManifest.xml"], text=True)
    package = re.search(r' A: package="([^"]+)"', tree).group(1)
    components = []
    current = None
    for line in tree.splitlines():
        element = re.match(r"\s*E: (\w+)", line)
        if element:
            current = element.group(1)
        name = re.search(r' A: android:name\([^)]*\)="([^"]+)"', line)
        if name and current in {"application", "activity", "service", "receiver", "provider"}:
            value = name.group(1)
            if value.startswith("."):
                value = package + value
            if value.startswith(package + "."):
                components.append(value)
    with zipfile.ZipFile(apk) as archive:
        classes = set().union(*(dex_classes(archive.read(name)) for name in archive.namelist()
                                if re.fullmatch(r"classes\d*\.dex", name)))
    missing = [name for name in components if "L" + name.replace(".", "/") + ";" not in classes]
    if missing:
        raise SystemExit("Missing APK entry points: " + ", ".join(missing))
    print(f"Verified {len(components)} app-owned manifest components for {package}")


if __name__ == "__main__":
    sdk = os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", ""))
    aapt = sys.argv[2] if len(sys.argv) > 2 else sorted(glob.glob(sdk + "/build-tools/*/aapt"))[-1]
    verify(sys.argv[1], aapt)

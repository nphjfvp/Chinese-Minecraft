#!/usr/bin/env python3
"""Build the customised "Learn or Die: Chinese" mod jar for NeoForge (Minecraft 26.3).

The original release jar is the base. This script replaces in it:
  * the Chinese language pack (everything under langpack-chinese/), and
  * the Learn or Die core mod, if mod/build/libs/ holds a freshly compiled jar.

Only the Python standard library is needed. Run it from anywhere:

    python3 tools/build_jar.py

The finished jar lands in build/.
"""

import json
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

VERSION = "0.3.5"
MC = "26.3"
ORIGINAL_NAME = f"learn-or-die-chinese-{VERSION}+mc{MC}.jar"
ORIGINAL_URL = (
    "https://github.com/Larenlearn/learn-or-die/releases/download/"
    f"v{VERSION}/{ORIGINAL_NAME}"
)
CORE_PATH_IN_JAR = f"META-INF/jarjar/learn-or-die-neoforge-{VERSION}+mc{MC}.jar"

ROOT = Path(__file__).resolve().parent.parent
LANGPACK = ROOT / "langpack-chinese"
CORE_BUILD_DIR = ROOT / "mod" / "build" / "libs"
BUILD = ROOT / "build"
ORIGINAL = BUILD / "original" / ORIGINAL_NAME
OUTPUT = BUILD / f"learn-or-die-chinese-custom+mc{MC}.jar"

# Files that are already compressed are stored as they are.
STORED_SUFFIXES = {".ogg", ".png", ".jar", ".ttf"}


def download_original() -> None:
    if ORIGINAL.exists():
        return
    ORIGINAL.parent.mkdir(parents=True, exist_ok=True)
    print(f"Downloading the original mod: {ORIGINAL_URL}")
    tmp = ORIGINAL.with_suffix(".part")
    with urllib.request.urlopen(ORIGINAL_URL) as response, open(tmp, "wb") as out:
        shutil.copyfileobj(response, out)
    tmp.rename(ORIGINAL)


def check_json() -> None:
    """Stop with a readable message if an edited JSON file is broken."""
    broken = []
    for path in sorted(LANGPACK.rglob("*.json")):
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError) as error:
            broken.append(f"  {path.relative_to(ROOT)}: {error}")
    if broken:
        sys.exit("Broken JSON, nothing was built:\n" + "\n".join(broken))


def find_core_jar() -> Path | None:
    if not CORE_BUILD_DIR.is_dir():
        return None
    jars = [
        jar
        for jar in CORE_BUILD_DIR.glob("*.jar")
        if not jar.name.endswith(("-sources.jar", "-javadoc.jar"))
    ]
    return max(jars, key=lambda jar: jar.stat().st_mtime) if jars else None


def add_file(out: zipfile.ZipFile, source: Path, name: str) -> None:
    method = zipfile.ZIP_STORED if source.suffix in STORED_SUFFIXES else zipfile.ZIP_DEFLATED
    out.write(source, name, compress_type=method)


def build() -> None:
    download_original()
    check_json()

    langpack_files = {
        path.relative_to(LANGPACK).as_posix(): path
        for path in sorted(LANGPACK.rglob("*"))
        if path.is_file()
    }
    core_jar = find_core_jar()

    BUILD.mkdir(parents=True, exist_ok=True)
    tmp = OUTPUT.with_suffix(".part")
    with zipfile.ZipFile(ORIGINAL) as original, zipfile.ZipFile(tmp, "w") as out:
        for entry in original.infolist():
            name = entry.filename
            # The language pack in the repo replaces the original one completely,
            # so files deleted in the repo are gone from the jar too.
            if name.startswith("assets/zhnarrator_zh/") or name in langpack_files:
                continue
            if core_jar is not None and name == CORE_PATH_IN_JAR:
                continue
            out.writestr(entry, original.read(entry), compress_type=entry.compress_type)
        for name, path in langpack_files.items():
            add_file(out, path, name)
        if core_jar is not None:
            add_file(out, core_jar, CORE_PATH_IN_JAR)
    tmp.replace(OUTPUT)

    size_mb = OUTPUT.stat().st_size / 1_000_000
    print(f"Built {OUTPUT.relative_to(ROOT)} ({size_mb:.1f} MB)")
    print("  Language pack: langpack-chinese/")
    if core_jar is not None:
        print(f"  Core mod:      {core_jar.relative_to(ROOT)} (compiled from mod/)")
    else:
        print("  Core mod:      unchanged from the original release")


if __name__ == "__main__":
    build()

#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository root.
"""Build an unsigned native fixture APK locally; never sign, install or deploy."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build_fixture(*, sources, manifest, assets, source_apk, android_jar,
                  build_tools, javac, output, seconds=120):
    if Path(output).is_symlink():
        raise ValueError("The output must not be an existing symbolic link.")
    sources = [Path(p).resolve() for p in sources]
    manifest, assets, source_apk, android_jar, build_tools, javac, output = (
        Path(p).resolve() for p in
        (manifest, assets, source_apk, android_jar, build_tools, javac, output))
    tools = [javac, *(build_tools / name for name in ("d8", "aapt2", "zipalign"))]
    if not math.isfinite(seconds) or seconds <= 0:
        raise ValueError("The build deadline must be finite and positive.")
    if not sources or any(not p.is_file() or p.suffix != ".java" for p in sources):
        raise ValueError("Supply existing Java source files.")
    if any(not p.is_file() for p in [manifest, source_apk, android_jar, *tools]):
        raise ValueError("A manifest, source APK, Android jar or build tool is missing.")
    if not assets.is_dir() or (assets / "source.apk").exists() or (assets / "source.apk").is_symlink():
        raise ValueError("Supply an asset directory without the reserved source.apk.")
    if output.exists() or not output.parent.is_dir():
        raise ValueError("The output must be new, in an existing directory.")

    deadline = time.monotonic() + seconds
    checks = []

    def run(name, argv, stage):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError("The fixture build deadline expired.")
        try:
            result = subprocess.run([str(v) for v in argv], cwd=stage,
                                    capture_output=True, text=True, timeout=remaining)
        except (OSError, subprocess.TimeoutExpired) as error:
            raise RuntimeError(f"{name} could not complete: {error}") from error
        checks.append({"name": name, "exitCode": result.returncode})
        if result.returncode:
            raise RuntimeError(f"{name} failed: {result.stdout}\n{result.stderr}")

    try:
        with tempfile.TemporaryDirectory(prefix="portal-native-fixture-",
                                         dir=output.parent) as directory:
            stage = Path(directory)
            copied_assets = stage / "assets"
            shutil.copytree(assets, copied_assets)
            source_hash = digest(source_apk)
            shutil.copyfile(source_apk, copied_assets / "source.apk")
            if digest(copied_assets / "source.apk") != source_hash:
                raise RuntimeError("The source APK changed while being copied.")
            copied_manifest = stage / "AndroidManifest.xml"
            shutil.copyfile(manifest, copied_manifest)
            copied_sources = []
            for index, source in enumerate(sources):
                destination = stage / "sources" / str(index) / source.name
                destination.parent.mkdir(parents=True)
                shutil.copyfile(source, destination)
                copied_sources.append(destination)
            classes, dex = stage / "classes", stage / "dex"
            classes.mkdir()
            dex.mkdir()
            # Keep host boot classes: Android's API stubs lack LambdaMetafactory.
            run("javac", [javac, "-source", "8", "-target", "8",
                          "-classpath", android_jar, "-d", classes, *copied_sources], stage)
            compiled = sorted(classes.rglob("*.class"))
            if not compiled:
                raise RuntimeError("javac produced no fixture classes.")
            run("d8", [build_tools / "d8", "--lib", android_jar, "--min-api", "29",
                       "--output", dex, *compiled], stage)
            dex_file = dex / "classes.dex"
            if not dex_file.is_file():
                raise RuntimeError("D8 produced no classes.dex.")
            unsigned, aligned = stage / "unsigned.apk", stage / "aligned.apk"
            run("aapt2", [build_tools / "aapt2", "link", "-I", android_jar,
                          "--manifest", copied_manifest, "-A", copied_assets,
                          "-o", unsigned], stage)
            if not unsigned.is_file() or not zipfile.is_zipfile(unsigned):
                raise RuntimeError("aapt2 produced no APK archive.")
            with zipfile.ZipFile(unsigned, "a") as archive:
                if "classes.dex" in archive.namelist():
                    raise RuntimeError("The packaged APK already contains classes.dex.")
                archive.write(dex_file, "classes.dex")
            run("zipalign", [build_tools / "zipalign", "-f", "4", unsigned, aligned], stage)
            if not aligned.is_file() or not zipfile.is_zipfile(aligned):
                raise RuntimeError("zipalign produced no APK archive.")
            with zipfile.ZipFile(aligned) as archive:
                names = archive.namelist()
                if len(names) != len(set(names)) or archive.read("classes.dex") != dex_file.read_bytes():
                    raise RuntimeError("The final APK has duplicate entries or incorrect DEX.")
                if hashlib.sha256(archive.read("assets/source.apk")).hexdigest() != source_hash:
                    raise RuntimeError("The final APK does not contain the captured source APK.")
            if time.monotonic() >= deadline:
                raise RuntimeError("The fixture build deadline expired.")
            receipt = {"output": str(output), "sourceApkSha256": source_hash,
                       "apkSha256": digest(aligned), "bytes": aligned.stat().st_size,
                       "checks": checks}
            # A hard link publishes complete bytes atomically and refuses a competing output.
            os.link(aligned, output)
            return receipt
    except (OSError, zipfile.BadZipFile, KeyError) as error:
        raise RuntimeError(f"Fixture packaging failed: {error}") from error


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", dest="sources", type=Path, action="append", required=True)
    for name in ("manifest", "assets", "source-apk", "android-jar",
                 "build-tools", "javac", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--seconds", type=float, default=120)
    args = vars(parser.parse_args())
    try:
        print(json.dumps(build_fixture(**args), indent=2))
    except (ValueError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

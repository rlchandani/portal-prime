#!/usr/bin/env python3
"""ROOT ONLY: generate fixed JPEG assets or run one admitted fixture and always retire it."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time
import zipfile

PKG = "com.immortal.wallpaperexiffixture"
parser = argparse.ArgumentParser()
sub = parser.add_subparsers(dest="command", required=True)
generate = sub.add_parser("generate")
generate.add_argument("assets", type=Path)
run = sub.add_parser("run")
run.add_argument("--adb", required=True)
run.add_argument("--serial", required=True, choices=["192.168.0.70:5555"])
run.add_argument("--fixture", type=Path, required=True)
run.add_argument("--source", type=Path, required=True)
run.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
if args.command == "generate":
    from PIL import Image, ImageDraw
    directory = args.assets / "generated"
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    for orientation in range(1, 9):
        image = Image.new("RGB", (360, 240), (64, 96, 128))
        draw = ImageDraw.Draw(image)
        for box, color in [
            ((0, 0, 79, 79), (255, 51, 68)),
            ((280, 0, 359, 79), (51, 221, 102)),
            ((0, 160, 79, 239), (51, 85, 238)),
            ((280, 160, 359, 239), (255, 221, 51)),
        ]:
            draw.rectangle(box, fill=color)
        exif = Image.Exif()
        exif[274] = orientation
        image.save(directory / f"o{orientation}.jpg", quality=100, subsampling=0, exif=exif)
        image.close()
else:
    args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    digest = lambda data: hashlib.sha256(data).hexdigest()
    source_hash = digest(args.source.read_bytes())
    with zipfile.ZipFile(args.fixture) as archive:
        assert archive.namelist().count("assets/source.apk") == 1
        assert digest(archive.read("assets/source.apk")) == source_hash
    external = "/sdcard/Android/data/" + PKG
    def adb(*items, check=True, timeout=30):
        return subprocess.run([args.adb, "-s", args.serial, *items],
            check=check, capture_output=True, text=True, timeout=timeout)
    retirement = {}
    # Admission must succeed before entering cleanup ownership.
    assert adb("shell", "pm", "path", PKG, check=False).returncode != 0, "fixture already installed"
    assert adb("shell", "test", "-e", external, check=False).returncode != 0, "fixture directory exists"
    try:
        installed = adb("install", "-r", str(args.fixture), timeout=60)
        (args.output / "install.log").write_text(installed.stdout + installed.stderr)
        launched = adb("shell", "am", "start", "-n", PKG + "/.WallpaperExifActivity",
            "--es", "sourceSha256", source_hash)
        (args.output / "launch.log").write_text(launched.stdout + launched.stderr)
        deadline = time.monotonic() + 40
        remote_report = external + "/files/report.json"
        while time.monotonic() < deadline:
            probe = adb("shell", "cat", remote_report, check=False, timeout=5)
            if probe.returncode == 0:
                report = json.loads(probe.stdout)
                (args.output / "native-report.json").write_text(json.dumps(report, indent=2) + "\n")
                assert report["sourceApkSha256"] == source_hash
                for row in report.get("tests", []):
                    if "png" in row:
                        adb("pull", external + "/files/" + row["png"],
                            str(args.output / row["png"]))
                        assert digest((args.output / row["png"]).read_bytes()) == row["pngSha256"]
                break
            time.sleep(0.25)
        else:
            raise TimeoutError("native terminal report not produced")
    finally:
        adb("shell", "am", "force-stop", PKG, check=False)
        uninstall = adb("uninstall", PKG, check=False)
        (args.output / "uninstall.log").write_text(uninstall.stdout + uninstall.stderr)
        # Only this fresh fixture's admitted external path is removed.
        adb("shell", "rm", "-rf", external, check=False)
        retirement["packageAbsent"] = adb("shell", "pm", "path", PKG, check=False).returncode != 0
        retirement["processAbsent"] = not adb("shell", "pidof", PKG, check=False).stdout.strip()
        retirement["externalDirectoryAbsent"] = adb("shell", "test", "-e", external, check=False).returncode != 0
        (args.output / "retirement.json").write_text(json.dumps(retirement, indent=2) + "\n")
    assert all(retirement.values()), "fixture retirement failed"

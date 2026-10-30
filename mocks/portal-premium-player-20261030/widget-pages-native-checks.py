#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Native generated widget producer checks, confined to the owned API 34 test AVD."""
import argparse
import hashlib
import json
import pathlib
import struct
import subprocess
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

SERIAL = "emulator-5556"
AVD = "PortalMusic20261030"
PACKAGE = "com.immortal.launcher.debug"
COMPONENT = PACKAGE + "/com.immortal.launcher.WidgetPagesPreviewActivity"
REMOTE = "cache/widget-pages-native"
CHECKPOINTS = {"baseline-three", "scoped-zero", "scoped-one", "scoped-two", "host-paused"}
SOURCES = (
    "app/src/main/java/com/immortal/launcher/ApprovedWidgetPageScope.kt",
    "app/src/main/java/com/immortal/launcher/HomeActivity.kt",
    "app/src/main/java/com/immortal/launcher/ApprovedLauncherTabs.kt",
    "app/src/main/java/com/immortal/launcher/ApprovedHomePhoto.kt",
    "app/src/main/java/com/immortal/launcher/ui/components/Reuse.kt",
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", choices=[SERIAL], default=SERIAL)
    parser.add_argument("--timeout", type=int, choices=range(15, 86), default=85)
    parser.add_argument("--stop-owned-avd", action="store_true")
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    out = pathlib.Path(__file__).resolve().parent / "evidence/widget-pages-native"
    out.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    deadline = started + args.timeout
    started_utc = datetime.now(timezone.utc).isoformat()
    artifacts, seen, host_checks = [], set(), []
    report = None
    ownership_verified = False
    sources_before = {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in SOURCES}
    apk = root / "app/build/outputs/apk/debug/app-debug.apk"
    apk_digest = hashlib.sha256(apk.read_bytes()).hexdigest()

    def adb(*command, check=True, cleanup=False):
        remaining = (started + 118 if cleanup else deadline) - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Widget fixture driver exhausted its bounded runtime")
        return subprocess.run(["adb", "-s", args.serial, *command], capture_output=True,
                              check=check, timeout=min(3 if cleanup else 12, remaining))

    def manifest_contains(path):
        tree = ET.parse(path)
        key = "{http://schemas.android.com/apk/res/android}name"
        return any(node.get(key, "").endswith(".WidgetPagesPreviewActivity")
                   for node in tree.iter("activity"))

    assert adb("emu", "avd", "name").stdout.decode().splitlines()[0] == AVD
    assert adb("shell", "getprop", "ro.build.version.sdk").stdout.decode().strip() == "34"
    ownership_verified = True
    try:
        installed_path = adb("shell", "pm", "path", PACKAGE).stdout.decode().strip().splitlines()[0]
        assert installed_path.startswith("package:")
        installed_digest = adb("shell", "sha256sum", installed_path.removeprefix("package:")).stdout.decode().split()[0]
        assert installed_digest == apk_digest, "Installed APK differs from parent-built exact candidate"
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "run-as", PACKAGE, "rm", "-rf", REMOTE)
        adb("shell", "am", "start", "-W", "-n", COMPONENT)
        while time.monotonic() < deadline:
            result = adb("exec-out", "run-as", PACKAGE, "cat", REMOTE + "/checks.json", check=False)
            try:
                candidate = json.loads(result.stdout)
            except json.JSONDecodeError:
                time.sleep(0.12)
                continue
            report = candidate
            checkpoint = report.get("checkpoint")
            if checkpoint and checkpoint not in seen:
                assert checkpoint in CHECKPOINTS, checkpoint
                content = adb("exec-out", "screencap", "-p").stdout
                assert content[:8] == b"\x89PNG\r\n\x1a\n"
                width, height = struct.unpack(">II", content[16:24])
                assert width > 0 and height > 0
                name = checkpoint + ".png"
                (out / name).write_bytes(content)
                artifacts.append({"file": name, "sha256": hashlib.sha256(content).hexdigest(),
                                  "width": width, "height": height})
                seen.add(checkpoint)
                adb("shell", "run-as", PACKAGE, "touch", REMOTE + "/continue-" + checkpoint)
            if report.get("complete"):
                break
            time.sleep(0.12)
        assert report and report.get("complete"), "Actual native fixture failed to finish within bound"
        assert report.get("success"), report.get("failure", "Native fixture failed")
        assert seen == CHECKPOINTS, seen
        baseline = report["redBaseline"]
        assert baseline["expectedFailureDetected"] and baseline["observedProducerBodies"] == 3
        assert baseline["observedResumedPhotoWorkers"] == 3 and baseline["replayInCurrentApk"]
        host_checks.append("Red baseline actual old-wiring replay violates one-producer predicate: 3 > 1")
        for observation in report["observations"]:
            pages = observation["pages"]
            label = observation["label"]
            if label.startswith(("scoped-page-", "reverse-page-")):
                assert sum(page["producerBodiesRunning"] for page in pages) == 1
                assert sum(page["photoWorkersRunning"] for page in pages) == 1
                assert all(page["parentGateIdentityPreserved"] for page in pages)
            if label == "cleanup-unmounted":
                assert all(page["created"] == page["disposed"] == 1 for page in pages)
                assert all(page["producerBodiesRunning"] == page["photoWorkersRunning"] == page["inFlight"] == 0
                           for page in pages)
                assert all(page["peakInFlight"] <= 1 for page in pages)
        host_checks.append("Green scope: selected-only polling/lifecycle workers, retained owners, clean disposal")
        assert manifest_contains(root / "app/src/debug/AndroidManifest.xml")
        assert not manifest_contains(root / "app/src/main/AndroidManifest.xml")
        assert (root / "app/src/debug/java/com/immortal/launcher/WidgetPagesPreviewActivity.kt").exists()
        assert not (root / "app/src/main/java/com/immortal/launcher/WidgetPagesPreviewActivity.kt").exists()
        host_checks.append("Fixture class and activity registration belong only to debug source set")
        assert sources_before == {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in SOURCES}
        assert apk_digest == hashlib.sha256(apk.read_bytes()).hexdigest()
        host_checks.append("Production source and parent-built APK hashes remained unchanged during native run")
        report["hostSuccess"] = True
    except Exception as error:
        if report is None:
            report = {"complete": False, "success": False}
        report["nativeSuccess"] = report.get("success", False)
        report["success"] = False
        report["hostSuccess"] = False
        report["checkerFailure"] = str(error)
        raise
    finally:
        if report is None:
            report = {"complete": False, "success": False}
        report.update({
            "serial": SERIAL, "avd": AVD, "hostChecks": host_checks, "artifacts": artifacts,
            "startedUtc": started_utc, "elapsedSeconds": round(time.monotonic() - started, 3),
            "debugApkSha256": apk_digest, "debugApkBytes": apk.stat().st_size,
            "installedApkSha256": locals().get("installed_digest"),
            "productionSourceSha256": sources_before, "foreignAvd5554Touched": False,
            "debugSourceSetOnly": True, "releaseApkBuiltOrInspected": False,
            "requestSimulation": "Generated data; NonCancellable delay on Dispatchers.IO (35ms / 420ms)",
            "physicalPortalPerformanceMeasured": False, "fpsMeasured": False,
        })
        if ownership_verified:
            adb("shell", "am", "force-stop", PACKAGE, check=False, cleanup=True)
            adb("shell", "run-as", PACKAGE, "rm", "-rf", REMOTE, check=False, cleanup=True)
            report["fixtureCacheRemoved"] = adb(
                "shell", "run-as", PACKAGE, "test", "!", "-e", REMOTE, check=False, cleanup=True).returncode == 0
            report["dedicatedAvdStopped"] = False
            if args.stop_owned_avd:
                assert adb("emu", "avd", "name", cleanup=True).stdout.decode().splitlines()[0] == AVD
                adb("emu", "kill", cleanup=True)
                stop_deadline = min(time.monotonic() + 7, started + 118)
                while time.monotonic() < stop_deadline:
                    if adb("get-state", check=False, cleanup=True).returncode != 0:
                        report["dedicatedAvdStopped"] = True
                        break
                    time.sleep(0.15)
        report["elapsedSecondsIncludingCleanup"] = round(time.monotonic() - started, 3)
        report["finishedUtc"] = datetime.now(timezone.utc).isoformat()
        (out / "checks.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"PASS: {report['passed']} native checks + {len(host_checks)} host checks; "
          f"{report['elapsedSeconds']}s on {AVD}, API 34; {len(artifacts)} generated screenshots.")
    print("APK SHA256:", apk_digest)
    print("Evidence:", out)
    print("Limits: old wiring replay, generated retained subtree, synthetic lifecycle; "
          "no household sources, physical FPS, or third-party AppWidget provider claims.")


if __name__ == "__main__":
    main()

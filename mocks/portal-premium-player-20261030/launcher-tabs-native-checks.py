#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Observe actual Compose launcher fallback on the dedicated API 34 test AVD."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

SERIAL = "emulator-5556"
AVD = "PortalMusic20261030"
PACKAGE = "com.immortal.launcher.debug"
COMPONENT = PACKAGE + "/com.immortal.launcher.LauncherTabsPreviewActivity"
REMOTE = "cache/launcher-tabs-native"
TABS = ("home", "apps", "tools")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", choices=[SERIAL], default=SERIAL)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--stop-owned-avd", action="store_true",
                        help="Stop the dedicated AVD after collecting evidence and clearing fixture cache")
    args = parser.parse_args()
    out = pathlib.Path(__file__).resolve().parent / "evidence" / "launcher-tabs-native"
    out.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    started_utc = datetime.now(timezone.utc).isoformat()
    artifacts = []
    host_checks = []
    seen = set()
    report = None

    def adb(*command, check=True):
        return subprocess.run(["adb", "-s", args.serial, *command], capture_output=True,
                              check=check, timeout=25)

    def save(name, content):
        (out / name).write_bytes(content)
        artifacts.append({"file": name, "sha256": hashlib.sha256(content).hexdigest()})

    assert adb("emu", "avd", "name").stdout.decode().splitlines()[0] == AVD
    assert adb("shell", "getprop", "ro.build.version.sdk").stdout.decode().strip() == "34"
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "run-as", PACKAGE, "rm", "-rf", REMOTE)
    adb("shell", "am", "start", "-W", "-n", COMPONENT)
    try:
        while time.monotonic() - started < args.timeout:
            result = adb("exec-out", "run-as", PACKAGE, "cat", REMOTE + "/checks.json", check=False)
            if result.returncode == 0:
                try:
                    report = json.loads(result.stdout)
                except json.JSONDecodeError:
                    # exec-out can return zero with cat's missing-file diagnostic during startup.
                    time.sleep(0.15)
                    continue
                checkpoint = report.get("checkpoint")
                if checkpoint and checkpoint not in seen:
                    xml_path = "/sdcard/launcher-tabs-native-check.xml"
                    adb("shell", "uiautomator", "dump", xml_path)
                    xml = adb("shell", "cat", xml_path).stdout
                    save(checkpoint + ".xml", xml)
                    save(checkpoint + ".png", adb("exec-out", "screencap", "-p").stdout)
                    nodes = list(ET.fromstring(xml).iter("node"))
                    labels = {node.get(attribute, "") for node in nodes
                              for attribute in ("text", "content-desc")}
                    expected = report.get("expectedAccessibleTab")
                    for tab in TABS:
                        actual = "Activate " + tab + " fixture" in labels
                        assert actual == (tab == expected), (checkpoint, tab, actual, labels)
                        page_visible = tab + " fixture page" in labels
                        assert page_visible == (tab == expected), (checkpoint, tab, labels)
                    host_checks.append(checkpoint + ": only active page has accessibility labels")
                    seen.add(checkpoint)
                    adb("shell", "run-as", PACKAGE, "touch", REMOTE + "/continue-" + checkpoint)
                if report.get("complete"):
                    break
            time.sleep(0.15)
        assert report and report.get("complete"), "Fixture did not complete within bounded timeout"
        assert report.get("success"), report.get("failure", "Native fixture failed")
        assert seen == {"home", "apps", "tools", "inactive", "final-home"}, seen
        assert report["nativeHomeCandidateUsed"] is False
        assert all(row["created"] == row["disposed"] == 1
                   for row in report["finalCounters"].values())
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
            "serial": SERIAL, "avd": AVD, "hostChecks": host_checks,
            "artifacts": artifacts, "startedUtc": started_utc,
            "elapsedSeconds": round(time.monotonic() - started, 3),
            "physicalDisplayMeasured": False, "physicalPortalPerformanceMeasured": False,
            "fpsMeasured": False,
        })
        apk = pathlib.Path(__file__).resolve().parents[2] / "app/build/outputs/apk/debug/app-debug.apk"
        root = pathlib.Path(__file__).resolve().parents[2]
        report["productionSourceSha256"] = {
            name: hashlib.sha256((root / name).read_bytes()).hexdigest()
            for name in (
                "app/src/main/java/com/immortal/launcher/ApprovedLauncherTabs.kt",
                "app/src/main/java/com/immortal/launcher/ApprovedHomeTabTransition.kt",
            )
        }
        if apk.exists():
            report["debugApkSha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
            installed = adb("shell", "pm", "path", PACKAGE).stdout.decode().strip().splitlines()[0]
            assert installed.startswith("package:")
            digest = adb("shell", "sha256sum", installed.removeprefix("package:")).stdout.decode().split()[0]
            report["installedApkSha256"] = digest
            assert digest == report["debugApkSha256"], "Installed APK differs from the fixture build"
        adb("shell", "am", "force-stop", PACKAGE, check=False)
        adb("shell", "run-as", PACKAGE, "rm", "-rf", REMOTE, check=False)
        adb("shell", "rm", "-f", "/sdcard/launcher-tabs-native-check.xml", check=False)
        report["fixtureCacheRemoved"] = adb(
            "shell", "run-as", PACKAGE, "test", "!", "-e", REMOTE, check=False).returncode == 0
        report["dedicatedAvdStopped"] = False
        if args.stop_owned_avd:
            assert adb("emu", "avd", "name").stdout.decode().splitlines()[0] == AVD
            adb("emu", "kill")
            deadline = time.monotonic() + 12
            while time.monotonic() < deadline:
                if adb("get-state", check=False).returncode != 0:
                    report["dedicatedAvdStopped"] = True
                    break
                time.sleep(0.2)
        report["elapsedSecondsIncludingCleanup"] = round(time.monotonic() - started, 3)
        report["finishedUtc"] = datetime.now(timezone.utc).isoformat()
        (out / "checks.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"PASS: {report['passed']} fixture checks and {len(host_checks)} accessibility checks; "
          f"{report['elapsedSeconds']} seconds on {AVD}, API 34.")
    print("Evidence:", out)
    print("Limits: controlled animation clock and synthetic parent lifecycle; layout/counter "
          "observations do not establish physical Portal FPS or display presentation.")


if __name__ == "__main__":
    main()

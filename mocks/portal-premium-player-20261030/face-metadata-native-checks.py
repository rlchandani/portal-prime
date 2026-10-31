#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Verify rendered date/battery changes on the parent's owned API 34 AVD.

No installation, AVD lifecycle or direct preference writes. The parent retains a
complete private preference backup. Raw hierarchy/settings stay outside the repo.
"""
import argparse
import json
import pathlib
import re
import subprocess
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

SERIAL, AVD, PACKAGE = "emulator-5556", "PortalMusic20261030", "com.immortal.launcher.debug"
PREVIEW = PACKAGE + "/com.immortal.launcher.PhotoFramePreviewActivity"
UI_PATH = "/sdcard/portal-face-metadata-native.xml"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--connection", type=pathlib.Path, required=True)
    parser.add_argument("--private-dir", type=pathlib.Path, required=True)
    parser.add_argument("--expected-apk-sha256", required=True)
    args = parser.parse_args()
    assert args.private_dir.is_dir() and args.private_dir.is_absolute()
    assert not args.private_dir.resolve().is_relative_to(pathlib.Path(__file__).resolve().parents[2])
    connection = json.loads(args.connection.read_text())
    assert urllib.parse.urlparse(connection["base"]).hostname == "127.0.0.1"
    assert urllib.parse.urlparse(connection["base"]).scheme == "http"
    assert re.fullmatch(r"[0-9a-f]{64}", args.expected_apk_sha256)
    started = time.monotonic()
    deadline = started + 75
    guarded = False
    changed = {}
    report = {"passed": False, "serial": SERIAL, "avd": AVD, "api": 34,
              "debugApkSha256": args.expected_apk_sha256, "physicalPortalVerified": False}

    def remaining():
        value = deadline - time.monotonic()
        assert value > 0, "whole_test_budget_exhausted"
        return min(8, value)

    def adb(*command):
        result = subprocess.run(["adb", "-s", SERIAL, *command], capture_output=True,
                                timeout=remaining(), check=True)
        return result.stdout

    def api(route, values=None):
        body = None if values is None else json.dumps(
            {"domain": "screensaver", "values": values}).encode()
        request = urllib.request.Request(connection["base"] + route, data=body, headers={
            "Authorization": "Bearer " + connection["token"], "Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=remaining()) as response:
            return json.load(response)

    def controls():
        profile = api("/remote/settings/profile")
        assert profile["profileComplete"]
        domain = next(d for d in profile["settings"]["domains"] if d["id"] == "screensaver")
        return {c["key"]: c["value"] for c in domain["controls"] if "value" in c}

    def texts(name):
        adb("shell", "uiautomator", "dump", "--compressed", UI_PATH)
        raw = adb("exec-out", "cat", UI_PATH)
        (args.private_dir / (name + ".xml")).write_bytes(raw)
        return [node.get("text", "") for node in ET.fromstring(raw).iter("node")]

    try:
        assert adb("emu", "avd", "name").decode().splitlines() == [AVD, "OK"]
        assert adb("shell", "getprop", "ro.build.version.sdk").strip() == b"34"
        path = adb("shell", "pm", "path", PACKAGE).decode().strip()
        assert path.startswith("package:") and path.endswith("/base.apk")
        assert adb("exec-out", "sha256sum", path[8:]).decode().split()[0] == args.expected_apk_sha256
        assert api("/remote/sources")["sources"]["source"] == "default"
        guarded = True
        before = controls()
        values = {"enabled": True, "mode": "wholePhoto", "photo_motion": False,
                  "intervalSec": 600, "darkRoomClock": False, "antiBurnIn": False,
                  "showNowPlaying": False, "idleSleepMin": 0, "overnightEnabled": False}
        changed.update({k: before[k] for k in values})
        assert set(api("/remote/settings", values)["applied"]) == set(values)
        assert all(controls()[k] == v for k, v in values.items())
        adb("shell", "am", "start", "-W", "-n", PREVIEW, "--ez", "launch_dismiss_app", "false")
        time.sleep(3)
        pid = adb("shell", "pidof", PACKAGE).strip()
        initial = texts("initial")
        dates = [t for t in initial if re.match(r"^(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\b", t)]
        assert len(dates) == 1, "rendered_date_missing"
        assert any(re.fullmatch(r"\d+%(?: ⚡)?", t) for t in initial), "rendered_battery_missing"
        adb("shell", "dumpsys", "battery", "unplug")
        adb("shell", "dumpsys", "battery", "set", "level", "41")
        adb("shell", "dumpsys", "battery", "set", "status", "3")
        time.sleep(1.5)
        discharge = texts("discharging")
        assert "41%" in discharge and dates[0] in discharge
        adb("shell", "dumpsys", "battery", "set", "ac", "1")
        adb("shell", "dumpsys", "battery", "set", "level", "50")
        adb("shell", "dumpsys", "battery", "set", "status", "2")
        time.sleep(1.5)
        charge = texts("charging")
        assert "50% ⚡" in charge and dates[0] in charge
        assert adb("shell", "pidof", PACKAGE).strip() == pid
        assert PREVIEW in adb("shell", "dumpsys", "activity", "activities").decode()
        report.update({"passed": True, "renderedDatePreserved": True,
                       "discharging41PercentPublished": True, "charging50PercentPublished": True,
                       "sameProcess": True, "previewStillPresent": True})
    except Exception as error:
        report["failure"] = type(error).__name__
        # Exception messages and raw outputs may contain private settings; keep them private.
        (args.private_dir / "failure.txt").write_text(str(error))
    finally:
        deadline = started + 90
        if guarded:
            try:
                adb("shell", "dumpsys", "battery", "reset")
                if changed:
                    assert set(api("/remote/settings", changed)["applied"]) == set(changed)
                    assert all(controls()[k] == v for k, v in changed.items())
                adb("shell", "am", "force-stop", PACKAGE)
                adb("shell", "rm", "-f", UI_PATH)
                report["batteryAndSettingValuesRestored"] = True
            except Exception as error:
                report["cleanupFailure"] = type(error).__name__
                report["passed"] = False
        report["elapsedSeconds"] = round(time.monotonic() - started, 3)
        (args.private_dir / "checks.json").write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report, indent=2), flush=True)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

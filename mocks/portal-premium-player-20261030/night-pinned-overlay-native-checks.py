#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Night fade on an actual accessibility overlay over retained Android Files.

The parent prepares the explicitly owned API34 emulator and restores its exact
preferences/accessibility settings afterwards. No installs, Activity fixtures,
global brightness writes, foreign emulators or physical-panel claims here.
"""
import argparse
import hashlib
import importlib.util
import json
import pathlib
import re
import sys
import time
import urllib.request

sys.dont_write_bytecode = True
BASE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("pin_brightness", BASE / "night-brightness-native-checks.py")
brightness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(brightness)


class Driver(brightness.Driver):
    def __init__(self, connection):
        super().__init__(connection, 180, BASE.parents[1] / "app/build/outputs/apk/debug/app-debug.apk", False)
        self.out = BASE / "evidence/native-night-pinned-overlay"
        self.overlay_id = None

    def command(self, route, values=None):
        self.verify_owner()
        req = urllib.request.Request(
            self.connection["base"] + route,
            headers={"Authorization": "Bearer " + self.connection["token"],
                     "Content-Type": "application/json"},
            data=None if values is None else json.dumps(values).encode())
        with urllib.request.urlopen(req, timeout=min(10, self.remaining())) as response:
            result = json.load(response)
        self.require("production_command_" + route, result.get("ok") is True)
        return result

    def underlying_identity(self):
        text = self.adb("shell", "dumpsys", "activity", "activities").decode()
        matches = re.findall(r"ActivityRecord\{([^}\n]*com\.google\.android\.documentsui/[^}\n]*)\}", text)
        self.require("actual_Files_activity_present", bool(matches))
        return matches[0]

    def window_state(self):
        text = self.adb("shell", "dumpsys", "window", "windows").decode()
        blocks = re.split(r"(?m)^\s*Window #\d+ ", text)[1:]
        found = [block for block in blocks
                 if "com.immortal.launcher.debug" in block
                 and re.search(r"\bty=(?:ACCESSIBILITY_OVERLAY|2032)\b", block)
                 and "(fillxfill)" in block and "isOnScreen=true" in block]
        self.require("one_actual_fullscreen_accessibility_cover", len(found) == 1)
        block = found[0]
        identity = block.splitlines()[0].strip()
        attrs = re.split(r"\n\s*m[A-Z]", block.split("mAttrs=", 1)[1], maxsplit=1)[0]
        value = re.search(r"\b(?:mScreenBrightness|screenBrightness|sbrt)\s*=\s*(-?\d+(?:\.\d+)?)", attrs)
        return {"identity": identity, "brightness": float(value.group(1)) if value else -1.0,
                "brightnessExplicit": value is not None, "visible": True}

    def sample(self, baseline):
        before = time.monotonic()
        window = self.window_state()
        after = time.monotonic()
        self.require(self.phase + "_same_visible_overlay",
                     window["identity"] == baseline[0]["identity"] and window["visible"])
        return {"seconds": round((before + after) / 2 - self.started, 6),
                "readDurationMs": round((after - before) * 1000, 3),
                "brightness": window["brightness"], "explicit": window["brightnessExplicit"]}

    def publish(self):
        result = super().publish()
        path = self.out / "checks.json"
        receipt = json.loads(path.read_text())
        receipt.update({
            "activity": None, "surface": "TYPE_ACCESSIBILITY_OVERLAY",
            "scope": "Production PinnedAppFrameHost over retained Android Files on owned API34; "
                     "actual overlay attributes, native clock/photo regions and one-tap reveal.",
            "limits": "Window brightness commands and API34 software rendering are verified; "
                      "physical Portal backlight, pinned-overlay FPS and physical tap latency are not measured.",
        })
        path.write_text(json.dumps(receipt, indent=2) + "\n")
        return result

    def run(self, tap_in_night=False):
        self.preflight()
        self.configure(120)
        self.light(500)
        self.adb("shell", "am", "start", "-W", "-n", "com.google.android.documentsui/com.android.documentsui.LauncherActivity", mutate=True)
        self.wait_until(time.monotonic() + 1)
        identity = self.underlying_identity()
        pid = self.adb("shell", "pidof", "com.google.android.documentsui").decode().strip()
        before = self.command("/remote/pin")
        self.require("overlay_service_available_and_unpinned", before["available"] and not before["pinned"])
        pin = self.command("/remote/pin", {"action": "pin"})
        self.require("Files_pinned_with_live_session",
                     pin["pinned"] and pin["targetPackage"] == "com.google.android.documentsui" and not pin["lost"])
        self.launch_device_epoch = float(self.adb("shell", "date", "+%s.%N").decode().strip())
        self.pid = self.adb("shell", "pidof", brightness.retained.PACKAGE).decode().strip().split()[0]
        show = self.command("/remote/pin", {"action": "show"})
        self.require("production_accessibility_cover_shown", show["covered"] and not show["nativeCover"])
        self.wait_scenes(2, 25)
        self.wait_until(time.monotonic() + 3)
        baseline = (self.window_state(), None)
        self.require("overlay_initial_default_brightness", baseline[0]["brightness"] == -1.0)
        photo = self.screenshot("before").crop(brightness.retained.ROI).tobytes()
        dark = self.light(0)
        self.wait_until(dark + 17)
        down = self.collect("pinned-dark-down", baseline, .03, -1.0)
        self.assert_progression("pinned-dark-down", down, -1, .03)
        clock = self.screenshot("clock").crop(brightness.retained.ROI)
        self.require("overlay_clock_has_black_background", sum(p == (0, 0, 0) for p in clock.getdata()) > clock.width * clock.height * .7)
        self.require("overlay_clock_has_visible_warm_ink", sum(p == (185, 159, 135) for p in clock.getdata()) > 50)
        if not tap_in_night:
            bright = self.light(500)
            self.wait_until(bright + 3)
            up = self.collect("pinned-bright-up", baseline, -1.0, .03)
            self.assert_progression("pinned-bright-up", up, 1, -1.0, restore_default=True)
            returned = self.screenshot("returned").crop(brightness.retained.ROI).tobytes()
            self.require("overlay_returns_exact_same_photo", photo == returned)
        still = self.command("/remote/pin")
        self.require("cover_retains_Files_window_and_valid_pin",
                     still["covered"] and not still["lost"] and still["targetWindowId"] == pin["targetWindowId"])
        self.require("same_underlying_Files_activity", self.underlying_identity() == identity)
        self.require("same_underlying_Files_process",
                     self.adb("shell", "pidof", "com.google.android.documentsui").decode().strip() == pid)
        self.adb("shell", "input", "tap", "1080", "720", mutate=True)
        self.wait_until(time.monotonic() + .4)
        after = self.command("/remote/pin")
        self.require("one_tap_removes_cover_and_keeps_pin", not after["covered"] and after["pinned"] and not after["lost"])
        self.require("tap_reveals_same_Files_activity", self.underlying_identity() == identity)
        self.require("tap_reveals_same_Files_process",
                     self.adb("shell", "pidof", "com.google.android.documentsui").decode().strip() == pid)
        self.require("overlay_retirement_has_no_failed_cleanup", after["failedCleanupCount"] == 0)
        self.observations["pinnedOverlay"] = {
            "underlyingActivitySha256": hashlib.sha256(identity.encode()).hexdigest(),
            "samePhoto": None if tap_in_night else True, "sameUnderlyingActivityAndProcess": True,
            "oneTapReveal": True, "tapWhileNightClock": tap_in_night, "physicalPanelMeasured": False,
        }
        self.command("/remote/pin", {"action": "unpin"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--connection", type=pathlib.Path, required=True)
    parser.add_argument("--tap-in-night", action="store_true")
    args = parser.parse_args()
    connection = json.loads(args.connection.read_text())
    if not connection["base"].startswith("http://127.0.0.1:"):
        raise ValueError("Only the owned-emulator localhost connection is allowed")
    driver = Driver(connection)
    if args.tap_in_night:
        driver.out = BASE / "evidence/native-night-pinned-tap"
    try:
        driver.run(args.tap_in_night)
    except Exception as error:
        driver.failure = str(error) if isinstance(error, brightness.CheckFailure) else type(error).__name__
    finally:
        if driver.preflight_complete:
            try:
                driver.command("/remote/pin", {"action": "unpin"})
            except Exception:
                driver.failure = driver.failure or "owned_pin_cleanup_failed"
        driver.cleanup()
    return driver.publish()


if __name__ == "__main__":
    raise SystemExit(main())

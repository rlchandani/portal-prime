#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Production photo preview -> sensor-driven quiet night -> retained photo, owned AVD only.

Run with the bundled Python/Pillow after the parent confirms emulator readiness.
Connection credentials and complete settings profiles remain exclusively in memory/private
input. Public evidence contains native PNGs, sanitized accessibility XML and scalar receipts.
"""
import argparse
import hashlib
import io
import json
import pathlib
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

from PIL import Image

SERIAL = "emulator-5556"
AVD = "PortalMusic20261030"
PACKAGE = "com.immortal.launcher.debug"
ACTIVITY = "com.immortal.launcher.PhotoFramePreviewActivity"
COMPONENT = PACKAGE + "/" + ACTIVITY
PRIVATE_CONNECTION = pathlib.Path(
    "/private/tmp/portal-night-readiness-20261030-232118/connection.json"
)
UI_FILE = "/sdcard/portal-native-night-recovery.xml"
WEEKDAYS = r"(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)"
CLOCK = re.compile(r"^\d{1,2}:\d{2}, .*" + WEEKDAYS + r".*\d", re.I)


class CheckFailure(Exception):
    pass


class Driver:
    def __init__(self, connection, timeout):
        self.started = time.monotonic()
        self.deadline = self.started + timeout
        self.cleanup_deadline = None
        self.connection = connection
        self.root = pathlib.Path(__file__).resolve().parents[2]
        self.out = pathlib.Path(__file__).resolve().parent / "evidence/native-night-recovery"
        self.checks = []
        self.observations = {}
        self.artifacts = []
        self.changed = {}
        self.ownership_verified = False
        self.pid = None
        self.launch_device_epoch = 0
        self.failure = None

    def remaining(self):
        deadline = self.cleanup_deadline or self.deadline
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise CheckFailure("bounded_runtime_exhausted")
        return remaining

    def require(self, name, condition):
        passed = bool(condition)
        self.checks.append({"name": name, "passed": passed})
        if not passed:
            raise CheckFailure(name)

    def adb(self, *command, mutate=False):
        if mutate:
            self.verify_owner()
        result = subprocess.run(
            ["adb", "-s", SERIAL, *command], capture_output=True,
            timeout=min(12, self.remaining()),
        )
        if result.returncode != 0:
            # Never echo subprocess output: arbitrary app/transport data is not public evidence.
            raise CheckFailure("owned_adb_command_failed")
        return result.stdout

    def verify_owner(self):
        lines = self.adb("emu", "avd", "name").decode().splitlines()
        if not lines or lines[0].strip() != AVD:
            raise CheckFailure("owned_avd_identity_mismatch")
        self.ownership_verified = True

    def api(self, path, values=None):
        if values is not None:
            self.verify_owner()
        body = None if values is None else json.dumps(
            {"domain": "screensaver", "values": values}
        ).encode()
        request = urllib.request.Request(
            self.connection["base"].rstrip("/") + path,
            data=body,
            headers={"Authorization": "Bearer " + self.connection["token"],
                     "Content-Type": "application/json"},
            method="GET" if body is None else "POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=min(8, self.remaining())) as response:
                result = json.load(response)
        except urllib.error.HTTPError as error:
            raise CheckFailure("settings_http_status_" + str(error.code)) from None
        except (urllib.error.URLError, ValueError):
            raise CheckFailure("private_settings_transport_failed") from None
        if not result.get("ok"):
            raise CheckFailure("registered_settings_api_rejected_request")
        return result

    def profile(self):
        result = self.api("/remote/settings/profile")
        if not result.get("profileComplete"):
            raise CheckFailure("complete_private_profile_required")
        domains = result["settings"]["domains"]
        screensaver = next(domain for domain in domains if domain["id"] == "screensaver")
        return {control["key"]: control for control in screensaver["controls"]}

    def configure(self):
        controls = self.profile()
        # DerivedSpec("source") deliberately has no schema control; use its read-only façade.
        sources = self.api("/remote/sources")["sources"]
        self.require("owned_AVD_uses_default_photo_source",
                     sources.get("source") == "default")
        desired = {
            "enabled": True, "darkRoomClock": True, "darkRoomLux": 3,
            "nightClockBrightness": 3, "mode": "wholePhoto",
            "photo_motion": False, "intervalSec": 120,
            "includeVideo": False, "idleSleepMin": 0, "overnightEnabled": False,
            "antiBurnIn": False, "showNowPlaying": False,
        }
        for key, value in desired.items():
            control = controls.get(key)
            self.require("registry_has_" + key, control is not None)
            kind = control["type"]
            valid = (
                kind == "bool" and isinstance(value, bool)
                or kind == "int" and isinstance(value, int) and not isinstance(value, bool)
                and control["min"] <= value <= control["max"]
                and (value - control["min"]) % control.get("step", 1) == 0
                or kind == "enum"
                and value in [option["value"] for option in control["options"]]
            )
            self.require("registry_accepts_" + key, valid)
            if control["value"] != value:
                # Save before POST: a transport failure may occur after the server applies it.
                self.changed[key] = control["value"]
        if self.changed:
            result = self.api("/remote/settings",
                              {key: desired[key] for key in self.changed})
            self.require("registered_batch_applied_all_modified_fields",
                         set(result.get("applied", [])) == set(self.changed))
        after = self.profile()
        self.require("registered_test_configuration_verified",
                     all(after[key]["value"] == value for key, value in desired.items()))
        self.observations["modifiedSettingsCount"] = len(self.changed)

    def light(self, lux):
        output = self.adb("emu", "sensor", "set", "light", str(lux), mutate=True).decode()
        if "KO:" in output or "OK" not in output:
            raise CheckFailure("owned_light_sensor_injection_failed")
        observed = self.adb("emu", "sensor", "get", "light").decode()
        match = re.search(r"light\s*=\s*(-?\d+(?:\.\d+)?)", observed)
        if not match or abs(float(match.group(1)) - lux) > 0.01:
            raise CheckFailure("owned_light_sensor_value_not_confirmed")
        return time.monotonic()

    def wait_until(self, target):
        while time.monotonic() < target:
            time.sleep(min(0.25, target - time.monotonic(), self.remaining()))

    def screenshot(self, name):
        image = Image.open(io.BytesIO(self.adb("exec-out", "screencap", "-p"))).convert("RGB")
        self.require(name + "_native_2160x1440", image.size == (2160, 1440))
        path = self.out / (name + ".png")
        image.save(path)
        self.artifacts.append({"file": path.name,
                               "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        return image

    def ui(self, name=None, include_system=False):
        self.adb("shell", "uiautomator", "dump", "--compressed", UI_FILE, mutate=True)
        raw = self.adb("exec-out", "cat", UI_FILE)
        tree = ET.fromstring(raw)
        nodes = [node for node in tree.iter("node")
                 if include_system or node.get("package") == PACKAGE]
        if name:
            # Save actual hierarchy/bounds, but only explicitly safe descriptions/text.
            safe = ET.fromstring(raw)
            for node in safe.iter("node"):
                for key in ("text", "content-desc", "resource-id"):
                    value = node.get(key, "")
                    allowed = key != "resource-id" and (
                        value == "Photo slideshow" or bool(CLOCK.fullmatch(value))
                    )
                    if value and not allowed:
                        node.set(key, "[omitted]")
            ET.ElementTree(safe).write(self.out / (name + ".xml"), encoding="utf-8",
                                       xml_declaration=True)
            self.artifacts.append({"file": name + ".xml"})
        return nodes

    def dismiss_immersive_education(self):
        nodes = self.ui(include_system=True)
        system = [node for node in nodes if node.get("package") == "android"]
        if not any(node.get("text") == "Viewing full screen" for node in system):
            return
        button = next((node for node in system
                       if node.get("text") == "Got it"
                       and node.get("class") == "android.widget.Button"), None)
        bounds = None if button is None else re.fullmatch(
            r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", button.get("bounds", "")
        )
        self.require("actual_system_immersive_prompt_has_observed_button", bounds is not None)
        x0, y0, x1, y1 = map(int, bounds.groups())
        self.adb("shell", "input", "tap", str((x0 + x1) // 2),
                 str((y0 + y1) // 2), mutate=True)
        self.observations["systemImmersiveEducationDismissed"] = True

    @staticmethod
    def clock_nodes(nodes):
        found = []
        for node in nodes:
            description = node.get("content-desc", "")
            bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",
                                  node.get("bounds", ""))
            if CLOCK.fullmatch(description) and bounds:
                x0, y0, x1, y1 = map(int, bounds.groups())
                if x1 - x0 >= 1944 and y1 - y0 >= 1296:
                    found.append(node)
        return found

    def window(self):
        # API 34's "windows" subcommand omits current focus; the full dump includes both.
        text = self.adb("shell", "dumpsys", "window").decode()
        focus = re.search(r"mCurrentFocus=Window\{([^}]+)\}", text)
        if not focus or COMPONENT not in focus.group(1):
            raise CheckFailure("production_photo_preview_is_not_focused")
        identity = focus.group(1).split()[0]
        headers = list(re.finditer(r"Window #\d+ Window\{([^}]+)\}:", text))
        own = None
        for index, header in enumerate(headers):
            if header.group(1).split()[0] == identity and COMPONENT in header.group(1):
                end = headers[index + 1].start() if index + 1 < len(headers) else len(text)
                own = text[header.end():end]
                break
        if own is None or "mAttrs=" not in own:
            raise CheckFailure("own_resumed_window_attributes_unavailable")
        attrs = re.split(r"\n\s*m[A-Z]", own.split("mAttrs=", 1)[1], maxsplit=1)[0]
        brightness = re.search(
            r"\b(mScreenBrightness|screenBrightness|sbrt)\s*=\s*(-?\d+(?:\.\d+)?)", attrs
        )
        # LayoutParams.toString omits sbrt when BRIGHTNESS_OVERRIDE_NONE (-1) is in use.
        return {"identity": identity,
                "brightness": float(brightness.group(2)) if brightness else -1.0,
                "brightnessExplicit": brightness is not None,
                "attributeAlias": brightness.group(1) if brightness else "default_sbrt_omitted"}

    def events(self):
        text = self.adb("logcat", "-d", "-v", "epoch", "--pid", self.pid,
                        "-s", "ImmortalNight:I", "ImmortalSlideshow:I", "*:S").decode()
        found = {"night": [], "resumed": [], "scene": []}
        markers = {"night": "dark room: fading to clock; photos suspended",
                   "resumed": "light returned: fading to retained photo",
                   "scene": "ready native scene:"}
        for line in text.splitlines():
            stamp = re.match(r"\s*(\d+\.\d+)", line)
            if stamp and float(stamp.group(1)) >= self.launch_device_epoch:
                for key, marker in markers.items():
                    if marker in line:
                        found[key].append(float(stamp.group(1)))
        return found

    @staticmethod
    def crop_hash(image, roi):
        return hashlib.sha256(image.crop(roi).tobytes()).hexdigest()

    @staticmethod
    def clock_pixels(image):
        gray = image.convert("L")
        histogram = gray.histogram()
        black_fraction = sum(histogram[:6]) / (image.width * image.height)
        visible_ink = sum(histogram[12:220])
        return black_fraction >= 0.80 and visible_ink >= 500

    def run(self):
        self.verify_owner()
        self.out.mkdir(parents=True, exist_ok=True)
        for name in ("before.png", "before-stable.png", "dark-early.png", "night-clock.png",
                     "bright-early.png", "after.png", "before.xml", "night-clock.xml",
                     "after.xml", "checks.json"):
            (self.out / name).unlink(missing_ok=True)
        self.require("owned_AVD_API34",
                     self.adb("shell", "getprop", "ro.build.version.sdk").decode().strip() == "34")
        apk = self.root / "app/build/outputs/apk/debug/app-debug.apk"
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        installed = self.adb("shell", "pm", "path", PACKAGE).decode().strip()
        self.require("owned_debug_package_installed", installed.startswith("package:"))
        installed_digest = self.adb(
            "shell", "sha256sum", installed.splitlines()[0].removeprefix("package:")
        ).decode().split()[0]
        self.require("parent_built_APK_is_installed", installed_digest == digest)
        self.observations["debugApkSha256"] = digest
        self.configure()
        self.light(500)
        self.launch_device_epoch = float(self.adb("shell", "date", "+%s.%N").decode().strip())
        # Each run constructs one fresh baseline; no launch occurs during dark/bright recovery.
        start = self.adb("shell", "am", "start", "-W", "-f", "0x10008000",
                         "-n", COMPONENT, mutate=True).decode()
        self.require("production_preview_explicit_component_launched",
                     "Error" not in start and "Exception" not in start)
        self.pid = self.adb("shell", "pidof", PACKAGE).decode().strip().split()[0]
        self.dismiss_immersive_education()
        ready_deadline = min(time.monotonic() + 60, self.deadline)
        while time.monotonic() < ready_deadline:
            if self.events()["scene"]:
                break
            self.wait_until(min(time.monotonic() + 0.5, ready_deadline))
        self.require("actual_native_scene_reported_ready", bool(self.events()["scene"]))
        self.wait_until(time.monotonic() + 2)
        baseline_nodes = self.ui("before")
        self.require("native_photo_stage_accessible",
                     any(node.get("content-desc") == "Photo slideshow" for node in baseline_nodes))
        self.require("bright_initial_has_no_quiet_clock", not self.clock_nodes(baseline_nodes))
        self.require("native_scene_has_no_WebView",
                     not any("WebView" in node.get("class", "") for node in baseline_nodes))
        baseline_window = self.window()
        before = self.screenshot("before")
        self.wait_until(time.monotonic() + 0.6)
        stable = self.screenshot("before-stable")
        roi = (604, 403, 1555, 950)  # Central photo interior, outside corner clock/status UI.
        baseline_hash = self.crop_hash(before, roi)
        crop = before.crop(roi)
        self.require("photo_ROI_has_nontrivial_image_content", crop.convert("L").entropy() >= 3.0)
        self.require("still_photo_ROI_is_stable_before_night",
                     self.crop_hash(stable, roi) == baseline_hash)
        baseline_events = self.events()
        self.observations.update({"photoRoiBounds": list(roi), "photoRoiSha256": baseline_hash,
                                  "photoRoiEntropy": round(crop.convert("L").entropy(), 3),
                                  "stablePhotoRoiSha256": self.crop_hash(stable, roi),
                                  "initialWindowBrightness": baseline_window["brightness"],
                                  "initialBrightnessExplicit": baseline_window["brightnessExplicit"]})

        dark_device_epoch = float(self.adb("shell", "date", "+%s.%N").decode().strip())
        dark_at = self.light(0)
        self.wait_until(dark_at + 2)
        early_dark = self.screenshot("dark-early")
        self.observations["darkEarlyCaptureSeconds"] = round(time.monotonic() - dark_at, 3)
        self.require("dark_early_capture_is_before_dark_dwell",
                     self.observations["darkEarlyCaptureSeconds"] < 20)
        self.require("dark_change_does_not_immediately_replace_photo",
                     self.crop_hash(early_dark, roi) == baseline_hash)
        self.require("early_dark_window_brightness_unchanged",
                     self.window()["brightness"] == baseline_window["brightness"])

        self.wait_until(dark_at + 22)
        night = self.screenshot("night-clock")
        night_nodes = self.ui("night-clock")
        clock = self.clock_nodes(night_nodes)
        self.require("actual_fullscreen_native_clock_has_time_weekday_date", len(clock) == 1)
        self.require("native_clock_draws_visible_ink_on_black", self.clock_pixels(night))
        night_window = self.window()
        self.require("same_production_window_is_retained_at_night",
                     night_window["identity"] == baseline_window["identity"])
        self.require("own_window_explicit_night_brightness_is_003",
                     night_window["brightnessExplicit"]
                     and abs(night_window["brightness"] - 0.03) < 0.000001)
        dark_events = [stamp for stamp in self.events()["night"] if stamp >= dark_device_epoch]
        self.require("actual_dark_room_photos_suspended_event", len(dark_events) == 1)
        self.require("single_dark_change_honors_20_second_dwell",
                     19 <= dark_events[0] - dark_device_epoch <= 26)
        self.observations["darkEventDelaySeconds"] = round(dark_events[0] - dark_device_epoch, 3)
        self.observations["nightWindowBrightness"] = night_window["brightness"]
        self.observations["nightBrightnessAttributeAlias"] = night_window["attributeAlias"]

        bright_device_epoch = float(self.adb("shell", "date", "+%s.%N").decode().strip())
        bright_at = self.light(500)
        self.wait_until(bright_at + 2)
        early_bright = self.screenshot("bright-early")
        self.observations["brightEarlyCaptureSeconds"] = round(time.monotonic() - bright_at, 3)
        self.require("bright_early_capture_is_before_bright_dwell",
                     self.observations["brightEarlyCaptureSeconds"] < 5)
        self.require("clock_remains_visible_during_bright_dwell", self.clock_pixels(early_bright))
        self.require("own_window_remains_dim_during_bright_dwell",
                     abs(self.window()["brightness"] - 0.03) < 0.000001)

        self.wait_until(bright_at + 7)
        after = self.screenshot("after")
        after_nodes = self.ui("after")
        self.require("quiet_clock_disappears_after_bright_dwell", not self.clock_nodes(after_nodes))
        self.require("native_photo_stage_returns",
                     any(node.get("content-desc") == "Photo slideshow" for node in after_nodes))
        after_window = self.window()
        self.require("same_production_window_returns_to_photos",
                     after_window["identity"] == baseline_window["identity"])
        self.require("own_window_brightness_restored_to_initial_attributes",
                     after_window["brightness"] == baseline_window["brightness"]
                     and after_window["brightnessExplicit"] == baseline_window["brightnessExplicit"])
        self.require("retained_photo_ROI_pixels_are_exactly_identical",
                     self.crop_hash(after, roi) == baseline_hash)
        self.observations["returnedPhotoRoiSha256"] = self.crop_hash(after, roi)
        end_events = self.events()
        resumed_events = [stamp for stamp in end_events["resumed"] if stamp >= bright_device_epoch]
        self.require("actual_retained_photos_resumed_event", len(resumed_events) == 1)
        self.require("single_bright_change_honors_5_second_dwell",
                     4.5 <= resumed_events[0] - bright_device_epoch <= 12)
        self.require("no_replacement_native_scene_was_presented",
                     len(end_events["scene"]) == len(baseline_events["scene"]))
        self.require("parent_APK_unchanged_during_native_check",
                     hashlib.sha256(apk.read_bytes()).hexdigest() == digest)
        self.observations["brightEventDelaySeconds"] = round(
            resumed_events[0] - bright_device_epoch, 3
        )
        self.observations["returnedWindowBrightness"] = after_window["brightness"]

    def cleanup(self):
        self.cleanup_deadline = time.monotonic() + 45
        if not self.ownership_verified:
            return
        try:
            if self.changed:
                self.api("/remote/settings", self.changed)
                restored = self.profile()
                self.require("all_modified_registered_settings_restored",
                             all(restored[key]["value"] == value
                                 for key, value in self.changed.items()))
            else:
                self.require("all_modified_registered_settings_restored", True)
        except Exception:
            self.checks.append({"name": "settings_restoration_transport_succeeded", "passed": False})
            self.failure = self.failure or "registered_settings_restore_failed"
        try:
            self.light(500)
            self.require("owned_light_restored_to_500", True)
            self.adb("shell", "rm", "-f", UI_FILE, mutate=True)
        except Exception:
            self.checks.append({"name": "owned_light_cleanup_succeeded", "passed": False})
            self.failure = self.failure or "owned_light_restore_failed"

    def publish(self):
        self.out.mkdir(parents=True, exist_ok=True)
        passed = sum(check["passed"] for check in self.checks)
        failed = len(self.checks) - passed
        receipt = {
            "success": self.failure is None and failed == 0,
            "passed": passed, "failed": failed, "checks": self.checks,
            "failureCode": self.failure, "observations": self.observations,
            "artifacts": self.artifacts, "avd": AVD, "serial": SERIAL,
            "activity": ACTIVITY, "utc": datetime.now(timezone.utc).isoformat(),
            "elapsedSeconds": round(time.monotonic() - self.started, 3),
            "physicalPortalMeasured": False, "fpsMeasured": False,
            "foreignAvdTouched": False, "hiddenHostCaseRun": False,
            "userPausedStateCaseRun": False,
            "scope": "Production native preview, actual light observer/controller/window; stable photo ROI",
            "privacy": "No token, profile, source URL or raw logcat in public evidence",
        }
        (self.out / "checks.json").write_text(json.dumps(receipt, indent=2) + "\n")
        print(f"{'PASS' if receipt['success'] else 'FAIL'}: "
              f"{passed} actual native assertions passed; {failed} failed.")
        if self.failure:
            print("Failure code:", self.failure)
        print("Evidence:", self.out)
        print("Limits: dedicated AVD integration; no physical Portal, FPS, hidden-host or user-pause proof.")
        return 0 if receipt["success"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--connection", type=pathlib.Path, default=PRIVATE_CONNECTION)
    parser.add_argument("--timeout", type=int, choices=range(60, 301), default=180,
                        metavar="SECONDS", help="Bound main flow to 60–300 seconds; cleanup adds at most 45")
    args = parser.parse_args()
    try:
        connection = json.loads(args.connection.read_text())
        base = urllib.parse.urlparse(connection["base"])
        if (base.scheme != "http" or base.hostname != "127.0.0.1" or not base.port
                or base.path not in ("", "/") or base.query or base.fragment
                or not isinstance(connection.get("token"), str) or not connection["token"]):
            raise ValueError()
    except Exception:
        print("FAIL: valid private emulator connection is required; no device mutation performed.")
        return 1
    driver = Driver(connection, args.timeout)
    try:
        driver.run()
    except CheckFailure as error:
        driver.failure = str(error)  # Only constant checker codes can reach this branch.
        if not driver.checks or driver.checks[-1]["passed"]:
            driver.checks.append({"name": driver.failure, "passed": False})
    except Exception as error:
        driver.failure = "bounded_harness_error_" + type(error).__name__
        driver.checks.append({"name": "native_harness_completed", "passed": False})
    finally:
        driver.cleanup()
    return driver.publish()


if __name__ == "__main__":
    raise SystemExit(main())

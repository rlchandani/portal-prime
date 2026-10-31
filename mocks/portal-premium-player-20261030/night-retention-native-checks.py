#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Actual USER-PAUSED / HIDDEN-HOST night acceptance on the parent's owned AVD.

Private connection/profile/logcat stay in memory. Only scalar receipts and native
central screenshot ROIs from the verified default source are published. No AVD
lifecycle, APK install, CLEAR_TASK, product instrumentation, or source changes.
"""
import argparse
import hashlib
import importlib.util
import io
import json
import pathlib
import re
import sys
import time
import urllib.parse
from datetime import datetime, timezone

sys.dont_write_bytecode = True
BASE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "night_recovery_utilities", BASE / "night-recovery-native-checks.py"
)
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)
from PIL import Image

SERIAL, AVD, PACKAGE = recovery.SERIAL, recovery.AVD, recovery.PACKAGE
ACTIVITY, COMPONENT = recovery.ACTIVITY, recovery.COMPONENT
CheckFailure = recovery.CheckFailure
ROI = (604, 403, 1555, 950)
UI_FILE = "/sdcard/portal-native-night-recovery.xml"  # Imported Driver.ui's temporary file.
DESIRED = {
    "enabled": True, "darkRoomClock": True, "darkRoomLux": 3,
    "nightClockBrightness": 3, "mode": "wholePhoto", "photo_motion": False,
    "intervalSec": 5, "includeVideo": False, "idleSleepMin": 0,
    "overnightEnabled": False, "antiBurnIn": False, "showNowPlaying": False,
}


class Driver(recovery.Driver):
    def __init__(self, connection, timeout):
        super().__init__(connection, timeout)
        self.out = BASE / "evidence/native-night-retention"
        self.preflight_complete = False
        self.apk = self.root / "app/build/outputs/apk/debug/app-debug.apk"
        self.apk_digest = None
        self.cases = {}
        self.phase = "preflight"

    def adb(self, *command, mutate=False):
        if mutate and not self.preflight_complete:
            raise CheckFailure("mutation_blocked_before_complete_preflight")
        return super().adb(*command, mutate=mutate)

    def api(self, path, values=None):
        if values is not None and not self.preflight_complete:
            raise CheckFailure("registry_mutation_blocked_before_complete_preflight")
        return super().api(path, values)

    def verify_owner(self):
        super().verify_owner()
        # Check the installed/local artifacts again at every mutation boundary.
        if self.preflight_complete:
            if hashlib.sha256(self.apk.read_bytes()).hexdigest() != self.apk_digest:
                raise CheckFailure("parent_APK_changed_mutation_blocked")
            if self.adb("shell", "getprop", "ro.build.version.sdk").decode().strip() != "34":
                raise CheckFailure("owned_API_changed_mutation_blocked")
            installed = self.adb("shell", "pm", "path", PACKAGE).decode().splitlines()
            if len(installed) != 1 or not installed[0].startswith("package:"):
                raise CheckFailure("owned_package_changed_mutation_blocked")
            digest = self.adb("shell", "sha256sum", installed[0][8:]).decode().split()[0]
            if digest != self.apk_digest:
                raise CheckFailure("installed_APK_changed_mutation_blocked")

    def preflight(self):
        self.verify_owner()
        self.require("exact_owned_AVD_name", self.ownership_verified)
        self.require("owned_API34",
                     self.adb("shell", "getprop", "ro.build.version.sdk").decode().strip() == "34")
        self.apk_digest = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        installed = self.adb("shell", "pm", "path", PACKAGE).decode().splitlines()
        self.require("single_owned_debug_APK_installed",
                     len(installed) == 1 and installed[0].startswith("package:"))
        digest = self.adb("shell", "sha256sum", installed[0][8:]).decode().split()[0]
        self.require("installed_current_debug_APK_hash_matches_local", digest == self.apk_digest)
        self.profile()  # Full profile endpoint rejects incomplete profiles.
        self.require("full_private_registry_profile_available", True)
        self.require("default_photo_source_before_mutations",
                     self.api("/remote/sources")["sources"].get("source") == "default")
        self.preflight_complete = True
        self.out.mkdir(parents=True, exist_ok=True)
        self.observations.update({"debugApkSha256": self.apk_digest,
                                  "photoRoiBounds": list(ROI),
                                  "source": "default", "api": 34})

    def finish_prior_preview(self):
        text = self.adb("shell", "dumpsys", "activity", "activities").decode()
        records = re.findall(r"(?m)^\s*\* Hist\s+#\d+: ActivityRecord\{([^}]+)\}", text)
        if any(COMPONENT in record for record in records):
            self.launch()
            self.tap_node("finish_prior_preview_through_observed_Done", self.toolbar("Done"))
            self.wait_until(time.monotonic() + .8)
            text = self.adb("shell", "dumpsys", "activity", "activities").decode()
            records = re.findall(r"(?m)^\s*\* Hist\s+#\d+: ActivityRecord\{([^}]+)\}", text)
            self.require("prior_preview_finished_through_production_UI",
                         not any(COMPONENT in record for record in records))
            self.observations["priorPreviewFinishedThroughDone"] = True

    def configure(self, interval):
        desired = dict(DESIRED, intervalSec=interval)
        controls = self.profile()
        self.require("default_photo_source_before_configuration",
                     self.api("/remote/sources")["sources"].get("source") == "default")
        updates = {}
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
            self.require("registry_validates_" + key, valid)
            if control["value"] != value:
                # Record original values once, before potentially ambiguous transport.
                self.changed.setdefault(key, control["value"])
                updates[key] = value
        if updates:
            result = self.api("/remote/settings", updates)
            self.require("registry_applied_complete_batch",
                         set(result.get("applied", [])) == set(updates))
        after = self.profile()
        self.require("registry_configuration_readback",
                     all(after[key]["value"] == value for key, value in desired.items()))

    def progress(self, phase):
        self.phase = phase
        print("Native retention:", phase, flush=True)

    def screenshot(self, name):
        image = Image.open(io.BytesIO(self.adb("exec-out", "screencap", "-p"))).convert("RGB")
        self.require(name + "_2160x1440_native_capture", image.size == (2160, 1440))
        # Only the central default-photo / synthetic clock interior is public.
        path = self.out / (name + "-roi.png")
        image.crop(ROI).save(path)
        self.artifacts.append({
            "file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "nativeCropBounds": list(ROI),
        })
        return image

    def window_state(self):
        text = self.adb("shell", "dumpsys", "window").decode()
        focus = re.search(r"mCurrentFocus=Window\{([^}]+)\}", text)
        headers = list(re.finditer(r"Window #\d+ Window\{([^}]+)\}:", text))
        matches = []
        for index, header in enumerate(headers):
            if COMPONENT not in header.group(1):
                continue
            end = headers[index + 1].start() if index + 1 < len(headers) else len(text)
            block = text[header.end():end]
            if "mAttrs=" not in block:
                continue
            attrs = re.split(r"\n\s*m[A-Z]", block.split("mAttrs=", 1)[1], maxsplit=1)[0]
            brightness = re.search(
                r"\b(mScreenBrightness|screenBrightness|sbrt)\s*=\s*(-?\d+(?:\.\d+)?)", attrs
            )
            identity = header.group(1).split()[0]
            matches.append({
                "identity": identity,
                "focused": bool(focus and focus.group(1).split()[0] == identity),
                "brightness": float(brightness.group(2)) if brightness else -1.0,
                "brightnessExplicit": brightness is not None,
            })
        self.require(self.phase + "_one_actual_preview_window", len(matches) == 1)
        return matches[0]

    def activity_state(self):
        text = self.adb("shell", "dumpsys", "activity", "activities").decode()
        headers = list(re.finditer(
            r"(?m)^\s*\* Hist\s+#\d+: ActivityRecord\{([^}]+)\}", text
        ))
        matches = []
        for index, header in enumerate(headers):
            if COMPONENT not in header.group(1):
                continue
            end = headers[index + 1].start() if index + 1 < len(headers) else len(text)
            block = text[header.end():end]
            state = re.search(r"\b(?:mState|state)=([A-Z_]+)", block)
            stopped = re.search(r"\bmStopped=(true|false)", block)
            matches.append({"identity": header.group(1).split()[0],
                            "state": state.group(1) if state else None,
                            "stopped": stopped.group(1) == "true" if stopped else None})
        self.require(self.phase + "_one_actual_preview_activity_record", len(matches) == 1)
        return matches[0]

    def identities(self):
        return self.window_state(), self.activity_state()

    def same_owner(self, name, baseline, focused=True):
        window, activity = self.identities()
        self.require(name + "_same_window", window["identity"] == baseline[0]["identity"])
        self.require(name + "_same_activity_record", activity["identity"] == baseline[1]["identity"])
        self.require(name + "_same_process",
                     self.adb("shell", "pidof", PACKAGE).decode().strip().split()[0] == self.pid)
        self.require(name + "_correct_focus", window["focused"] == focused)
        self.require(name + "_correct_lifecycle",
                     activity["state"] == ("RESUMED" if focused else "STOPPED")
                     and activity["stopped"] in (None, not focused))
        return window

    @staticmethod
    def node_bounds(node):
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        return tuple(map(int, match.groups())) if match else None

    def tap_node(self, name, node):
        bounds = self.node_bounds(node)
        self.require(name + "_observed_bounds", bounds is not None)
        x0, y0, x1, y1 = bounds
        self.require(name + "_bounds_in_native_screen",
                     0 <= x0 < x1 <= 2160 and 0 <= y0 < y1 <= 1440)
        self.adb("shell", "input", "tap", str((x0 + x1) // 2),
                 str((y0 + y1) // 2), mutate=True)
        return list(bounds)

    def toolbar(self, label):
        nodes = self.ui()
        found = [node for node in nodes if node.get("text") == label
                 and node.get("enabled") == "true" and node.get("clickable") == "true"]
        if not found:
            stage = next((node for node in nodes
                          if node.get("content-desc") == "Photo slideshow"), None)
            self.require("actual_accessible_stage_to_open_toolbar", stage is not None)
            self.tap_node("show_toolbar", stage)
            nodes = self.ui()
            found = [node for node in nodes if node.get("text") == label
                     and node.get("enabled") == "true" and node.get("clickable") == "true"]
        self.require("actual_toolbar_" + label, len(found) == 1)
        return found[0]

    def launch(self):
        # NEW_TASK only. Never CLEAR_TASK or force-stop, including initial launch.
        start = self.adb("shell", "am", "start", "-W", "-f", "0x10000000",
                         "-n", COMPONENT, mutate=True).decode()
        self.require("production_preview_started_without_CLEAR_TASK",
                     "Error" not in start and "Exception" not in start)
        new_pid = self.adb("shell", "pidof", PACKAGE).decode().strip().split()[0]
        if self.pid is not None:
            self.require("preview_launch_keeps_process", new_pid == self.pid)
        self.pid = new_pid

    def wait_scenes(self, count, seconds=45):
        stop = min(self.deadline, time.monotonic() + seconds)
        while time.monotonic() < stop:
            events = self.events()
            if len(events["scene"]) >= count:
                return events
            self.wait_until(min(stop, time.monotonic() + 0.4))
        raise CheckFailure("actual_native_scene_readiness_timeout")

    def settle_startup(self, old_count):
        # Construction immediately presents bundled photo, then source discovery
        # presents the actual default-feed scene. Wait for both before acceptance.
        self.wait_scenes(old_count + 2, 55)
        self.require("native_stage_without_WebView",
                     not any("WebView" in node.get("class", "") for node in self.ui()))

    def capture_baseline(self, name):
        image = self.screenshot(name)
        self.wait_until(time.monotonic() + 0.6)
        stable = self.screenshot(name + "-stable")
        digest = self.crop_hash(image, ROI)
        self.require(name + "_photo_content", image.crop(ROI).convert("L").entropy() >= 3)
        self.require(name + "_stable_ROI", digest == self.crop_hash(stable, ROI))
        return digest

    def demand_clock(self, name, baseline):
        image = self.screenshot(name)
        self.require(name + "_actual_accessible_fullscreen_clock",
                     len(self.clock_nodes(self.ui())) == 1)
        self.require(name + "_visible_clock_ink_on_black", self.clock_pixels(image))
        window = self.same_owner(name, baseline)
        self.require(name + "_actual_window_brightness_003",
                     window["brightnessExplicit"] and abs(window["brightness"] - .03) < 1e-6)

    def demand_photo(self, name, baseline, photo_hash):
        # am start -W can report RESUMED while the compositor still shows the
        # outgoing cover activity. Observe the actual native UI before capturing.
        nodes = self.ui()
        self.require(name + "_photo_stage_and_no_clock",
                     not self.clock_nodes(nodes)
                     and any(node.get("content-desc") == "Photo slideshow" for node in nodes))
        window = self.same_owner(name, baseline)
        image = self.screenshot(name)
        self.require(name + "_actual_brightness_restored",
                     window["brightness"] == baseline[0]["brightness"]
                     and window["brightnessExplicit"] == baseline[0]["brightnessExplicit"])
        self.require(name + "_retained_photo_ROI", self.crop_hash(image, ROI) == photo_hash)

    def pause_case(self):
        self.progress("USER_PAUSED")
        case = self.cases["userPaused"] = {"executed": True, "success": False}
        self.configure(5)
        self.light(500)
        self.launch_device_epoch = float(self.adb("shell", "date", "+%s.%N").decode().strip())
        self.launch()
        self.dismiss_immersive_education()
        self.settle_startup(0)
        case["pauseTapBounds"] = self.tap_node("Pause", self.toolbar("Pause"))
        self.wait_until(time.monotonic() + 2)
        nodes = self.ui()
        self.require("toolbar_confirms_USER_PAUSED",
                     any(node.get("text") == "Resume" for node in nodes)
                     and any(node.get("text") == "Paused" for node in nodes))
        photo_hash = self.capture_baseline("paused-before")
        baseline = self.identities()
        baseline_events = self.events()
        case["baselineSceneCount"] = len(baseline_events["scene"])
        case["windowIdentitySha256"] = hashlib.sha256(baseline[0]["identity"].encode()).hexdigest()
        case["activityIdentitySha256"] = hashlib.sha256(baseline[1]["identity"].encode()).hexdigest()
        case["baselinePhotoRoiSha256"] = photo_hash

        dark_epoch = float(self.adb("shell", "date", "+%s.%N").decode().strip())
        dark_at = self.light(0)
        self.wait_until(dark_at + 22)
        self.demand_clock("paused-night", baseline)
        night_events = [stamp for stamp in self.events()["night"] if stamp >= dark_epoch]
        self.require("USER_PAUSED_dark_20s_settle_event", len(night_events) == 1
                     and 19 <= night_events[0] - dark_epoch <= 28)
        case["darkEventDelaySeconds"] = round(night_events[0] - dark_epoch, 3)

        bright_epoch = float(self.adb("shell", "date", "+%s.%N").decode().strip())
        bright_at = self.light(500)
        self.wait_until(bright_at + 7)
        self.demand_photo("paused-return", baseline, photo_hash)
        nodes = self.ui()
        self.require("USER_PAUSED_choice_retained_after_night",
                     any(node.get("text") == "Resume" for node in nodes)
                     and any(node.get("text") == "Paused" for node in nodes))
        resumed = [stamp for stamp in self.events()["resumed"] if stamp >= bright_epoch]
        self.require("USER_PAUSED_light_5s_settle_event",
                     len(resumed) == 1 and 4.5 <= resumed[0] - bright_epoch <= 14)
        case["brightEventDelaySeconds"] = round(resumed[0] - bright_epoch, 3)
        hold_started = time.monotonic()
        self.wait_until(hold_started + 7)
        self.demand_photo("paused-held-beyond-interval", baseline, photo_hash)
        held = self.events()
        self.require("USER_PAUSED_no_new_scene_after_night_and_7s_hold",
                     len(held["scene"]) == len(baseline_events["scene"]))
        case["pausedHoldSeconds"] = round(time.monotonic() - hold_started, 3)
        case["pausedSceneCountAfterHold"] = len(held["scene"])
        case["resumeTapBounds"] = self.tap_node("Resume", self.toolbar("Resume"))
        resumed_at = time.monotonic()
        advanced = self.wait_scenes(len(held["scene"]) + 1, 40)
        self.wait_until(time.monotonic() + .8)
        advanced_image = self.screenshot("explicit-resume-advanced")
        self.require("explicit_Resume_restarts_actual_automatic_scene_advance",
                     len(advanced["scene"]) > len(held["scene"]))
        self.require("explicit_Resume_changes_actual_photo_ROI",
                     self.crop_hash(advanced_image, ROI) != photo_hash)
        self.same_owner("explicit_Resume", baseline)
        case["automaticAdvanceSecondsAfterResume"] = round(time.monotonic() - resumed_at, 3)
        case["sceneCountAfterExplicitResume"] = len(advanced["scene"])
        case["success"] = True
        # A fresh configured controller is needed for the 120s hidden-host case.
        # Finish through its observed production Done button; never CLEAR_TASK.
        self.tap_node("finish_completed_pause_case", self.toolbar("Done"))

    def cover(self, baseline, name):
        start = self.adb("shell", "am", "start", "-W", "-f", "0x10000000",
                         "-a", "android.settings.SETTINGS", mutate=True).decode()
        self.require(name + "_cover_activity_started",
                     "Error" not in start and "Exception" not in start)
        self.wait_until(time.monotonic() + .8)
        return self.same_owner(name, baseline, focused=False)

    def hidden_case(self):
        self.progress("HIDDEN_HOST")
        case = self.cases["hiddenHost"] = {"executed": True, "success": False}
        self.configure(120)
        self.light(500)
        old_count = len(self.events()["scene"])
        self.launch()
        self.settle_startup(old_count)
        photo_hash = self.capture_baseline("hidden-before")
        baseline = self.identities()
        events_before = self.events()
        case.update({
            "baselinePhotoRoiSha256": photo_hash,
            "baselineSceneCount": len(events_before["scene"]),
            "windowIdentitySha256": hashlib.sha256(baseline[0]["identity"].encode()).hexdigest(),
            "activityIdentitySha256": hashlib.sha256(baseline[1]["identity"].encode()).hexdigest(),
        })
        dark_at = self.light(0)
        self.wait_until(dark_at + 2)
        covered_window = self.cover(baseline, "hidden_before_dark_settle")
        case["hiddenBeforeSettleSeconds"] = round(time.monotonic() - dark_at, 3)
        self.require("host_hidden_before_20s_dark_deadline",
                     case["hiddenBeforeSettleSeconds"] < 20)
        self.wait_until(dark_at + 24)
        hidden_window = self.same_owner("hidden_past_dark_deadline", baseline, focused=False)
        hidden_events = self.events()
        self.require("hidden_host_does_not_enter_clock",
                     len(hidden_events["night"]) == len(events_before["night"]))
        self.require("hidden_host_window_does_not_dim",
                     hidden_window["brightness"] == baseline[0]["brightness"]
                     and covered_window["brightness"] == baseline[0]["brightness"])
        self.require("hidden_host_does_not_present_new_scene",
                     len(hidden_events["scene"]) == len(events_before["scene"]))
        case["hiddenPastDarkDeadlineSeconds"] = round(time.monotonic() - dark_at, 3)
        case["nightEventsWhileHidden"] = len(hidden_events["night"]) - len(events_before["night"])

        return_epoch = float(self.adb("shell", "date", "+%s.%N").decode().strip())
        self.launch()
        returned_at = time.monotonic()
        self.demand_photo("hidden-return-before-settle", baseline, photo_hash)
        self.require("hidden_return_has_fresh_dark_settle",
                     len(self.events()["night"]) == len(events_before["night"]))
        self.wait_until(returned_at + 22)
        self.demand_clock("hidden-return-night", baseline)
        night_events = [stamp for stamp in self.events()["night"] if stamp >= return_epoch]
        self.require("hidden_return_20s_dark_settle_event",
                     len(night_events) == 1 and 19 <= night_events[0] - return_epoch <= 30)
        self.require("hidden_return_reuses_scene",
                     len(self.events()["scene"]) == len(events_before["scene"]))
        case["returnedDarkEventDelaySeconds"] = round(night_events[0] - return_epoch, 3)

        self.progress("HIDDEN_WHILE_ALREADY_CLOCK")
        self.cover(baseline, "hidden_while_already_clock")
        hidden_bright_at = self.light(500)
        self.wait_until(hidden_bright_at + 7)
        hidden_clock_window = self.same_owner("hidden_clock_after_bright", baseline, focused=False)
        self.require("already_clock_hidden_window_remains_dim",
                     hidden_clock_window["brightnessExplicit"]
                     and abs(hidden_clock_window["brightness"] - .03) < 1e-6)
        self.require("already_clock_hidden_host_does_not_process_bright_settle",
                     len(self.events()["resumed"]) == len(events_before["resumed"]))
        return_bright_epoch = float(self.adb("shell", "date", "+%s.%N").decode().strip())
        self.launch()
        bright_return_at = time.monotonic()
        # Check actual window immediately; UI dumps would consume the 5s early window.
        early_window = self.same_owner("clock_host_return_early", baseline)
        self.require("already_clock_return_stays_dim_during_fresh_bright_settle",
                     abs(early_window["brightness"] - .03) < 1e-6)
        self.wait_until(bright_return_at + 7)
        self.demand_photo("hidden-clock-bright-return", baseline, photo_hash)
        resumed_events = [stamp for stamp in self.events()["resumed"] if stamp >= return_bright_epoch]
        self.require("hidden_clock_return_fresh_5s_bright_settle",
                     len(resumed_events) == 1
                     and 4.5 <= resumed_events[0] - return_bright_epoch <= 15)
        self.require("hidden_cycles_no_additional_native_scene",
                     len(self.events()["scene"]) == len(events_before["scene"]))
        case.update({
            "alreadyClockHiddenCaseRun": True,
            "returnedBrightEventDelaySeconds": round(resumed_events[0] - return_bright_epoch, 3),
            "finalSceneCount": len(self.events()["scene"]),
            "success": True,
        })

    def run(self):
        self.preflight()
        self.finish_prior_preview()
        self.pause_case()
        self.hidden_case()
        self.require("default_photo_source_after_cases",
                     self.api("/remote/sources")["sources"].get("source") == "default")
        self.require("parent_APK_unchanged_after_cases",
                     hashlib.sha256(self.apk.read_bytes()).hexdigest() == self.apk_digest)

    def cleanup(self):
        # Separate hard 45s cleanup allowance, even if the 300s test budget expires.
        self.cleanup_deadline = time.monotonic() + 45
        if not self.preflight_complete:
            return
        self.progress("CLEANUP")
        try:
            if self.changed:
                restored_result = self.api("/remote/settings", self.changed)
                self.require("all_original_registry_values_accepted_for_restore",
                             set(restored_result.get("applied", [])) == set(self.changed))
            restored = self.profile()
            self.require("all_modified_registry_settings_restored",
                         all(restored[key]["value"] == value
                             for key, value in self.changed.items()))
        except Exception:
            self.checks.append({"name": "registry_restoration_completed", "passed": False})
            self.failure = self.failure or "registered_settings_restore_failed"
        try:
            self.light(500)
            self.require("owned_light_restored_500", True)
            self.adb("shell", "rm", "-f", UI_FILE, mutate=True)
        except Exception:
            self.checks.append({"name": "owned_light_cleanup_completed", "passed": False})
            self.failure = self.failure or "owned_light_restore_failed"

    def publish(self):
        self.out.mkdir(parents=True, exist_ok=True)
        passed = sum(check["passed"] for check in self.checks)
        failed = len(self.checks) - passed
        receipt = {
            "success": self.failure is None and failed == 0,
            "passed": passed, "failed": failed, "failureCode": self.failure,
            "lastPhase": self.phase, "checks": self.checks, "cases": self.cases,
            "observations": dict(self.observations, modifiedSettingsCount=len(self.changed)),
            "artifacts": self.artifacts, "avd": AVD, "serial": SERIAL,
            "activity": ACTIVITY, "utc": datetime.now(timezone.utc).isoformat(),
            "elapsedSeconds": round(time.monotonic() - self.started, 3),
            "hiddenHostCaseRun": self.cases.get("hiddenHost", {}).get("executed", False),
            "userPausedStateCaseRun": self.cases.get("userPaused", {}).get("executed", False),
            "physicalPortalMeasured": False, "fpsMeasured": False, "cpuMeasured": False,
            "foreignAvdTouched": False, "clearTaskUsed": False,
            "scope": "Production light observer/controller; actual UI taps, window/activity identity, "
                     "scene readiness events and native default-photo ROIs",
            "retentionClaim": "Same window/activity/process plus no additional ready-native-scene "
                              "events across each suspended cycle and matching photo ROI. "
                              "No direct heap/controller object identity measurement.",
            "privacy": "No credentials, full settings, source URLs, raw hierarchy or raw logs exported; "
                       "only scalar receipts and default-photo/synthetic-clock screenshot interiors",
        }
        (self.out / "checks.json").write_text(json.dumps(receipt, indent=2) + "\n")
        print(f"{'PASS' if receipt['success'] else 'FAIL'}: {passed} native assertions; {failed} failed.",
              flush=True)
        if self.failure:
            print("Failure code:", self.failure, flush=True)
        print("Evidence:", self.out, flush=True)
        return 0 if receipt["success"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--connection", type=pathlib.Path, required=True)
    parser.add_argument("--timeout", type=int, choices=range(120, 301), default=300,
                        metavar="SECONDS", help="Hard main budget <=300s, cleanup <=45s")
    args = parser.parse_args()
    try:
        connection = json.loads(args.connection.read_text())
        base = urllib.parse.urlparse(connection["base"])
        if (base.scheme != "http" or base.hostname != "127.0.0.1" or not base.port
                or base.path not in ("", "/") or base.query or base.fragment
                or not isinstance(connection.get("token"), str) or not connection["token"]):
            raise ValueError()
    except Exception:
        print("FAIL: private localhost connection required; no device mutation performed.")
        return 1
    driver = Driver(connection, args.timeout)
    try:
        driver.run()
    except CheckFailure as error:
        driver.failure = str(error)
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

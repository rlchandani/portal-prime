#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Bounded gradual night-brightness acceptance on the parent's already-ready AVD.

Use the parent's current installed APK and private localhost connection. This
script never installs/builds, starts an AVD, changes global brightness, clears a
task, or exports raw logs/settings/UI. Only default-photo/clock ROI captures and
scalar window-attribute samples are published. No physical-panel claims.
"""
import argparse
import hashlib
import importlib.util
import json
import pathlib
import sys
import time
import urllib.parse
from datetime import datetime, timezone

sys.dont_write_bytecode = True
BASE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "brightness_live_utilities", BASE / "night-live-settings-native-checks.py"
)
live = importlib.util.module_from_spec(spec)
spec.loader.exec_module(live)
retained = live.retained
CheckFailure = retained.CheckFailure
EPS = 1e-6
TARGET = .03
RAMP_SECONDS = 3.0
POLL_SECONDS = .04


class Driver(live.Driver):
    def __init__(self, connection, timeout, apk, live_reversal):
        super().__init__(connection)
        self.deadline = self.started + timeout
        self.main_budget = timeout
        self.apk = apk
        self.live_reversal = live_reversal
        self.out = BASE / "evidence/native-night-brightness"
        self.ramps = {}

    def progress(self, phase):
        self.phase = phase
        print("Native brightness:", phase, flush=True)

    def update(self, values):
        """Existing live registry path, without its ramp-obscuring .5s wait."""
        controls = self.profile()
        for key, value in values.items():
            control = controls.get(key)
            self.require("live_registered_" + key, control is not None)
            self.require("live_valid_" + key,
                         control["type"] == "int" and isinstance(value, int)
                         and not isinstance(value, bool)
                         and control["min"] <= value <= control["max"]
                         and (value - control["min"]) % control.get("step", 1) == 0)
            # Preserve originals before POST, including an ambiguous transport failure.
            self.changed.setdefault(key, control["value"])
        result = self.api("/remote/settings", values)
        self.require("live_batch_accepted",
                     set(result.get("applied", [])) == set(values))
        self.require("live_batch_readback",
                     all(self.profile()[key]["value"] == value
                         for key, value in values.items()))

    def sample(self, baseline):
        before = time.monotonic()
        window = self.window_state()
        after = time.monotonic()
        self.require(self.phase + "_sample_same_focused_window",
                     window["identity"] == baseline[0]["identity"] and window["focused"])
        return {
            "seconds": round((before + after) / 2 - self.started, 6),
            "readDurationMs": round((after - before) * 1000, 3),
            "brightness": window["brightness"],
            "explicit": window["brightnessExplicit"],
        }

    def collect(self, name, baseline, endpoint, start_value, seconds=10):
        """Poll attributes only; slow UI/logcat/capture work stays outside ramps."""
        self.progress(name)
        samples = []
        receipt = self.ramps[name] = {
            "samples": samples, "complete": False, "startValue": start_value,
            "targetValue": endpoint, "samplingBudgetSeconds": seconds,
        }
        stop = min(self.deadline, time.monotonic() + seconds)
        moved = False
        stable_since = None
        while time.monotonic() < stop:
            sample = self.sample(baseline)
            samples.append(sample)
            value = sample["brightness"]
            moved = moved or abs(value - start_value) > EPS
            if moved and abs(value - endpoint) <= EPS:
                if stable_since is None:
                    stable_since = time.monotonic()
                if time.monotonic() - stable_since >= .3:
                    receipt["complete"] = True
                    return samples
            else:
                stable_since = None
            self.wait_until(min(stop, time.monotonic() + POLL_SECONDS))
        raise CheckFailure(name + "_bounded_ramp_completion_timeout")

    def assert_progression(self, name, samples, direction, target, restore_default=False):
        positive = [sample for sample in samples if sample["brightness"] >= 0]
        values = [sample["brightness"] for sample in positive]
        self.require(name + "_several_observed_values", len(values) >= 5)
        self.require(name + "_normalized_window_bounds",
                     all(-EPS <= value <= 1 + EPS for value in values))
        self.require(name + "_monotonic",
                     all(direction * (b - a) >= -EPS for a, b in zip(values, values[1:])))
        if restore_default:
            # -1 is Android's sentinel, not a luminance value. Check positive
            # interpolation separately, then exact restoration of the sentinel.
            self.require(name + "_starts_at_night_target",
                         abs(values[0] - TARGET) <= EPS)
            intermediate = [value for value in values if value > TARGET + EPS]
            self.require(name + "_observed_positive_upward_motion", bool(intermediate))
            self.require(name + "_exact_system_default_restored",
                         samples[-1]["brightness"] == -1.0)
            sentinel = next((i for i, sample in enumerate(samples)
                             if sample["brightness"] == -1.0), len(samples))
            self.require(name + "_no_override_reapplied_after_restore",
                         all(sample["brightness"] == -1.0 for sample in samples[sentinel:]))
            end_sample = samples[sentinel]
            moved = next(sample for sample in positive if sample["brightness"] > TARGET + EPS)
        else:
            intermediate = [value for value in values if value > target + EPS]
            first_explicit = next(i for i, sample in enumerate(samples)
                                  if sample["brightness"] >= 0)
            self.require(name + "_no_default_override_during_downward_ramp",
                         all(sample["explicit"] and sample["brightness"] >= 0
                             for sample in samples[first_explicit:]))
            self.require(name + "_reaches_exact_target",
                         samples[-1]["explicit"] and abs(samples[-1]["brightness"] - target) <= EPS)
            end_sample = next(sample for sample in positive
                              if abs(sample["brightness"] - target) <= EPS)
            moved = positive[0]
        distinct = sorted({round(value, 6) for value in intermediate})
        self.require(name + "_at_least_four_distinct_intermediate_values", len(distinct) >= 4)
        span = abs(values[-1] - values[0])
        jumps = [abs(b - a) for a, b in zip(values, values[1:])]
        gaps = [b["seconds"] - a["seconds"] for a, b in zip(samples, samples[1:])]
        duration = end_sample["seconds"] - moved["seconds"]
        self.require(name + "_observed_gradual_duration",
                     1.8 <= duration <= 4.0 + max(gaps, default=0))
        self.require(name + "_no_single_observed_snap",
                     max(jumps, default=0) <= max(.015, span * .45))
        self.ramps[name].update({
            "distinctIntermediateValues": len(distinct),
            "observedProgressionSeconds": round(duration, 6),
            "largestObservedStep": round(max(jumps, default=0), 6),
            "largestSampleGapSeconds": round(max(gaps, default=0), 6),
            "sampleCount": len(samples),
        })

    def reversal_case(self, baseline, scene_count):
        """Retarget an unfinished .03 -> .15 ramp back to .03 via registry."""
        self.progress("LIVE_TARGET_REVERSAL")
        case = self.cases["liveTargetReversal"] = {"executed": True, "success": False}
        self.update({"nightClockBrightness": 15})
        samples = []
        self.ramps["live-up-before-reversal"] = {
            "samples": samples, "complete": False, "targetValue": .15,
            "intentionallyInterrupted": True,
        }
        stop = min(self.deadline, time.monotonic() + 3)
        while time.monotonic() < stop:
            sample = self.sample(baseline)
            samples.append(sample)
            if .045 < sample["brightness"] < .11:
                break
            self.wait_until(min(stop, time.monotonic() + POLL_SECONDS))
        self.require("live_edit_observed_before_old_target",
                     bool(samples) and .045 < samples[-1]["brightness"] < .11)
        # Registry/ownership transport takes time; expose that unsampled aperture.
        # Do not pretend this gives an exact read of the callback's last value.
        before_edit = samples[-1]
        request_started = time.monotonic()
        self.update({"nightClockBrightness": 3})
        request_finished = time.monotonic()
        reversed_samples = self.collect("live-reversed-down", baseline, TARGET,
                                       before_edit["brightness"], seconds=5)
        self.assert_progression("live-reversed-down", reversed_samples, -1, TARGET)
        first = reversed_samples[0]
        aperture = first["seconds"] - before_edit["seconds"]
        # Conservative 3x average slope bound for an eased perceptual .12 span.
        self.require("live_retarget_has_no_observable_restart_snap",
                     abs(first["brightness"] - before_edit["brightness"])
                     <= .12 * min(1, 3 * aperture / RAMP_SECONDS) + .005)
        self.require("live_reversal_does_not_reach_abandoned_target",
                     all(TARGET - EPS <= sample["brightness"] < .15 - EPS
                         for sample in reversed_samples))
        self.demand_clock("live-target-back-at-003", baseline)
        self.require("live_reversal_no_new_scene", len(self.events()["scene"]) == scene_count)
        case.update({
            "success": True, "targetEditTransportSeconds": round(request_finished - request_started, 6),
            "unsampledRetargetApertureSeconds": round(aperture, 6),
            "lastObservedBeforeEdit": before_edit["brightness"],
            "firstObservedAfterEdit": first["brightness"],
        })

    def run(self):
        self.preflight()
        self.finish_prior_preview()
        self.progress("PAUSED_BASELINE")
        case = self.cases["pausedBrightnessCycle"] = {"executed": True, "success": False}
        self.configure(5)
        self.light(500)
        self.launch_device_epoch = float(self.adb("shell", "date", "+%s.%N").decode().strip())
        self.launch()
        self.dismiss_immersive_education()
        self.settle_startup(0)
        self.tap_node("Pause", self.toolbar("Pause"))
        self.wait_until(time.monotonic() + 2)
        self.require("baseline_USER_PAUSED",
                     any(node.get("text") == "Resume" for node in self.ui()))
        photo_hash = self.capture_baseline("paused-before")
        baseline = self.identities()
        self.require("initial_window_override_is_exact_system_default",
                     baseline[0]["brightness"] == -1.0)
        scene_count = len(self.events()["scene"])
        # Read only: never override the device's global brightness to make a test pass.
        system_setting = self.adb("shell", "settings", "get", "system", "screen_brightness").decode().strip()
        self.require("system_brightness_supports_visible_down_and_up_range",
                     system_setting.isdigit() and 25 <= int(system_setting) <= 255)
        case.update({
            "initialOverride": -1.0, "initialOverrideExplicit": baseline[0]["brightnessExplicit"],
            "systemBrightnessSetting": int(system_setting),
            "baselineSceneCount": scene_count, "baselinePhotoRoiSha256": photo_hash,
            "windowIdentitySha256": hashlib.sha256(baseline[0]["identity"].encode()).hexdigest(),
            "activityIdentitySha256": hashlib.sha256(baseline[1]["identity"].encode()).hexdigest(),
            "processId": int(self.pid),
        })
        dark_epoch = float(self.adb("shell", "date", "+%s.%N").decode().strip())
        dark_at = self.light(0)
        self.wait_until(dark_at + 17)
        down = self.collect("dark-entry-down", baseline, TARGET, -1.0)
        self.assert_progression("dark-entry-down", down, -1, TARGET)
        # Imported demand_clock requires exact .03. The ramp is settled first.
        self.demand_clock("settled-night", baseline)
        night = [stamp for stamp in self.events()["night"] if stamp >= dark_epoch]
        self.require("real_sensor_dark_settle_event", len(night) == 1 and 19 <= night[0] - dark_epoch <= 28)
        self.require("dark_entry_retains_scene", len(self.events()["scene"]) == scene_count)
        case["darkSettleEventSeconds"] = round(night[0] - dark_epoch, 3)
        if self.live_reversal:
            self.reversal_case(baseline, scene_count)
        else:
            self.cases["liveTargetReversal"] = {"executed": False}
        bright_epoch = float(self.adb("shell", "date", "+%s.%N").decode().strip())
        bright_at = self.light(500)
        self.wait_until(bright_at + 3)
        up = self.collect("bright-return-up", baseline, -1.0, TARGET)
        self.assert_progression("bright-return-up", up, 1, -1.0, restore_default=True)
        self.demand_photo("paused-return", baseline, photo_hash)
        resumed = [stamp for stamp in self.events()["resumed"] if stamp >= bright_epoch]
        self.require("real_sensor_bright_settle_event",
                     len(resumed) == 1 and 4.5 <= resumed[0] - bright_epoch <= 14)
        case["brightSettleEventSeconds"] = round(resumed[0] - bright_epoch, 3)
        nodes = self.ui()
        self.require("USER_PAUSED_choice_retained",
                     any(node.get("text") == "Resume" for node in nodes)
                     and any(node.get("text") == "Paused" for node in nodes))
        self.wait_until(time.monotonic() + 7)
        self.demand_photo("paused-held-beyond-interval", baseline, photo_hash)
        self.require("whole_cycle_and_paused_hold_no_new_scene",
                     len(self.events()["scene"]) == scene_count)
        self.require("default_source_after_case",
                     self.api("/remote/sources")["sources"].get("source") == "default")
        self.require("global_brightness_setting_unchanged",
                     self.adb("shell", "settings", "get", "system", "screen_brightness").decode().strip()
                     == system_setting)
        case["success"] = True
        self.tap_node("finish_bounded_case", self.toolbar("Done"))

    def publish(self):
        self.out.mkdir(parents=True, exist_ok=True)
        passed = sum(check["passed"] for check in self.checks)
        failed = len(self.checks) - passed
        receipt = {
            "success": self.failure is None and failed == 0,
            "passed": passed, "failed": failed, "failureCode": self.failure,
            "lastPhase": self.phase, "checks": self.checks,
            "cases": self.cases, "ramps": self.ramps,
            "observations": dict(self.observations, modifiedSettingsCount=len(self.changed)),
            "artifacts": self.artifacts, "avd": retained.AVD, "serial": retained.SERIAL,
            "activity": retained.ACTIVITY, "api": 34, "utc": datetime.now(timezone.utc).isoformat(),
            "elapsedSeconds": round(time.monotonic() - self.started, 3),
            "mainBudgetSeconds": self.main_budget, "cleanupBudgetSeconds": 45,
            "rampContractMs": 3000, "implementationCadenceContractMs": 33,
            "requestedObservationPollMs": POLL_SECONDS * 1000,
            "physicalPortalMeasured": False, "panelFpsMeasured": False,
            "comfortMeasured": False, "cpuMeasured": False, "foreignAvdTouched": False,
            "clearTaskUsed": False, "globalBrightnessWritten": False,
            "scope": "One paused PhotoFramePreviewActivity on owned API34 AVD; real registry and "
                     "emulated light sensor, actual Window.LayoutParams screenBrightness.",
            "limits": "ADB samples observe several monotonic intermediate window attributes and "
                      "bounded ramp duration; they do not measure every 33ms callback, exact easing, "
                      "idle scheduler work, physical panel output, or hidden/stopped cancellation. "
                      "Retarget transport aperture is reported. Host object identity is inferred "
                      "from process/activity/window, unchanged scene count and matching photo ROI.",
            "privacy": "Only scalar receipts and default-photo/synthetic-clock interior ROIs; "
                       "no credentials, source URLs, complete profiles, raw hierarchy or raw logs.",
        }
        (self.out / "checks.json").write_text(json.dumps(receipt, indent=2) + "\n")
        print(f"{'PASS' if receipt['success'] else 'FAIL'}: {passed} native assertions; {failed} failed.")
        if self.failure:
            print("Failure code:", self.failure)
        print("Evidence:", self.out)
        return 0 if receipt["success"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--connection", type=pathlib.Path, required=True)
    parser.add_argument("--apk", type=pathlib.Path,
                        default=BASE.parents[1] / "app/build/outputs/apk/debug/app-debug.apk",
                        help="Parent-supplied current APK; must already match installed package")
    parser.add_argument("--timeout", type=int, choices=range(120, 241), default=180,
                        metavar="SECONDS", help="Main budget 120..240s; existing cleanup <=45s")
    parser.add_argument("--skip-live-reversal", action="store_true")
    args = parser.parse_args()
    try:
        connection = json.loads(args.connection.read_text())
        url = urllib.parse.urlparse(connection["base"])
        if (url.scheme != "http" or url.hostname != "127.0.0.1" or not url.port
                or url.path not in ("", "/") or url.query or url.fragment
                or url.username or url.password
                or not isinstance(connection.get("token"), str) or not connection["token"]
                or not args.apk.is_file()):
            raise ValueError()
    except Exception:
        print("FAIL: private localhost connection and current APK required; no mutation performed.")
        return 1
    driver = Driver(connection, args.timeout, args.apk.resolve(), not args.skip_live_reversal)
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
        # Inherited cleanup restores every saved original registered value,
        # verifies readback, restores the owned sensor to 500, and removes UI temp.
        driver.cleanup()
    return driver.publish()


if __name__ == "__main__":
    raise SystemExit(main())

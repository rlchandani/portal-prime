#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Bounded native Family silent music drag checks; parent owns the device.

Reuse the touch verifier's native slider binding and BASE's exact physical/APK
guards, observer requests, private PNG/JSON frames, deadlines, and cleanup.
The parent supplies capture(case, command=None) and the native gesture receipt.
Twenty gestures cover both directions at both font scales in both themes,
plus disabled seek/volume in both themes. No audio/provider, production UI,
every-pixel touch coverage, or panel FPS acceptance is claimed.
"""

import importlib.util
import json
import math
import pathlib
import time


SPEC = importlib.util.spec_from_file_location(
    "family_music_touch",
    pathlib.Path(__file__).with_name("family-music-touch-checks.py"),
)
TOUCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOUCH)
BASE = TOUCH.BASE

PHASES = ("pressed", "mid", "beforeRelease", "released")
COUNTER_KEYS = ("fixtureMusicSeekCommandCount", "fixtureMusicVolumeCommandCount")


class DragVerifier(TOUCH.TouchVerifier):
    def __init__(self, args, adb_path):
        super().__init__(args, adb_path)
        self.gesture_count = 0
        self.report.update(
            productionUiChanged=False, gestureCount=0,
            commandsCoalescedUntilReleaseVerified=False,
            everyTouchPixelTested=False, panelFpsMeasured=False,
        )

    def native_frame(self, snapshot):
        BASE.require(self.frames and self.frames[-1]["frameNumber"] == snapshot.frame,
                     "gesture_snapshot_binding_required")
        try:
            value = json.loads(
                pathlib.Path(self.frames[-1]["nativeJsonPath"]).read_text(encoding="utf-8")
            )
        except (OSError, json.JSONDecodeError, UnicodeDecodeError):
            raise BASE.VerificationFailure("gesture_native_snapshot_malformed") from None
        BASE.require(isinstance(value, dict), "gesture_native_snapshot_not_object")
        return value

    def counters(self, value, keys=COUNTER_KEYS):
        BASE.require(all(type(value.get(key)) is int for key in keys),
                     "gesture_command_counters_missing_or_malformed")
        return tuple(value[key] for key in keys)

    def native_range(self, value):
        BASE.require(isinstance(value, dict)
                     and all(type(value.get(key)) in (int, float)
                             and math.isfinite(value[key])
                             for key in ("minimum", "maximum", "current")),
                     "gesture_range_missing_or_malformed")
        return value

    def fraction(self, value):
        span = value["maximum"] - value["minimum"]
        return ((value["current"] - value["minimum"]) / span
                if span > 0 else float("nan"))

    def range_check(self, snapshot, name, value, initial):
        self.check(self.stage, name + ": coherent native range", (
            value["maximum"] > value["minimum"]
            and value["minimum"] <= value["current"] <= value["maximum"]
            and value["minimum"] == initial["minimum"]
            and value["maximum"] == initial["maximum"]), snapshot,
            minimum=value["minimum"], maximum=value["maximum"], current=value["current"])

    def receipt(self, raw):
        value = raw.get("musicSliderGesture")
        BASE.require(isinstance(value, dict), "music_slider_gesture_receipt_missing")
        BASE.require(isinstance(value.get("control"), str)
                     and type(value.get("enabled")) is bool
                     and type(value.get("moveEventCount")) is int
                     and type(value.get("completed")) is bool,
                     "music_slider_gesture_receipt_malformed")
        phases = value.get("phases")
        BASE.require(isinstance(phases, list)
                     and all(isinstance(phase, dict)
                             and isinstance(phase.get("phase"), str) for phase in phases),
                     "music_slider_gesture_phases_missing_or_malformed")
        names = [phase["phase"] for phase in phases]
        BASE.require(len(names) == len(set(names)) and set(PHASES).issubset(names)
                     and set(names).issubset({"initial", *PHASES}),
                     "music_slider_gesture_phases_missing_or_malformed")
        for phase in phases:
            self.native_range(phase.get("range"))
            self.counters(phase, ("seekCommands", "volumeCommands"))
        return value

    def drag(self, snapshot, label, start, end, disabled=False):
        case = self.stage
        raw_before = self.native_frame(snapshot)
        initial_counts = self.counters(raw_before)
        node = snapshot.control(label, disabled=disabled)
        native = self.native_slider(snapshot, label)
        initial = self.native_range(native.get("range"))
        box = BASE.bounds(node)
        if disabled:
            # before_input intentionally rejects disabled controls. Keep its
            # freshness/window/display guards without that enabled-only path.
            BASE.require(time.monotonic() - snapshot.observed_at <= 20,
                         "disabled_input_observation_stale")
            BASE.require(node.get("enabled") == "false"
                         and node.get("visible") == "true", "disabled_slider_required")
            BASE.require(self.foreground() == snapshot.window_id,
                         "disabled_input_window_changed")
            left, top, right, bottom = box
            BASE.require(0 <= left < right <= BASE.WIDTH
                         and 0 <= top < bottom <= BASE.HEIGHT,
                         "disabled_input_bounds_outside_physical_display")
        else:
            box = self.before_input(snapshot, node)
        left, top, right, bottom = box
        self.check(case, label + ": observed native gesture bounds cover 48dp",
                   right - left >= BASE.MIN_TARGET and bottom - top >= BASE.MIN_TARGET,
                   snapshot, nativeGestureWidthPx=right - left,
                   nativeGestureHeightPx=bottom - top, minimumTargetPx=BASE.MIN_TARGET)
        self.range_check(snapshot, label + " initial", initial, initial)
        self.check(case, "initial fixture command counters are nonnegative",
                   all(count >= 0 for count in initial_counts), snapshot)

        after = self.capture(case + "-released", command={
            "command": "musicSliderGesture", "control": label,
            "startFraction": start, "endFraction": end, "moveMs": 480, "steps": 24,
        })
        self.gesture_count += 1
        self.report["gestureCount"] = self.gesture_count
        # capture owns the final PNG/JSON and registers the frame. Do not use
        # observe here: a plain snapshot would discard the gesture receipt.
        self.stage = case
        raw_after = self.native_frame(after)
        receipt = self.receipt(raw_after)
        self.check(case, label + ": completed native 24-move gesture",
                   receipt["control"] == label and receipt["enabled"] == (not disabled)
                   and receipt["moveEventCount"] == 24 and receipt["completed"], after)
        by_phase = {phase["phase"]: phase for phase in receipt["phases"]}
        phases = [by_phase[name] for name in PHASES]
        ranges = [phase["range"] for phase in phases]
        if "initial" in by_phase:
            observed_initial = by_phase["initial"]
            self.range_check(after, label + " receipt initial", observed_initial["range"], initial)
            self.check(case, label + ": receipt initial belongs to observed slider",
                       observed_initial["range"] == initial
                       and self.counters(observed_initial, ("seekCommands", "volumeCommands"))
                       == initial_counts, after)
        expected = list(initial_counts)
        if not disabled:
            expected[0 if label == "Track position" else 1] += 1
        expected = tuple(expected)
        for phase, value in zip(phases, ranges):
            phase_name = phase["phase"]
            self.range_check(after, label + " " + phase_name, value, initial)
            counts = self.counters(phase, ("seekCommands", "volumeCommands"))
            wanted = expected if phase_name == "released" else initial_counts
            self.check(case, label + " " + phase_name + ": commands coalesced until release",
                       counts == wanted, after, seekCommands=counts[0],
                       volumeCommands=counts[1], expectedSeekCommands=wanted[0],
                       expectedVolumeCommands=wanted[1])
            if disabled:
                self.check(case, label + " " + phase_name + ": disabled range unchanged",
                           value == initial, after)

        final = self.native_range(self.native_slider(after, label).get("range"))
        self.range_check(after, label + " final", final, initial)
        final_counts = self.counters(raw_after)
        self.check(case, label + ": final counters match released receipt",
                   final_counts == expected
                   and final_counts == self.counters(phases[-1], ("seekCommands", "volumeCommands")),
                   after, seekCommands=final_counts[0], volumeCommands=final_counts[1],
                   expectedSeekCommands=expected[0], expectedVolumeCommands=expected[1])
        final_fraction = self.fraction(final)
        released_fraction = self.fraction(ranges[-1])
        self.check(case, label + ": final RangeInfo matches released phase",
                   abs(final_fraction - released_fraction) <= .001, after)
        if disabled:
            self.check(case, label + ": disabled final range unchanged", final == initial, after)
        else:
            pressed, mid, before_release, released = map(self.fraction, ranges)
            initial_fraction = self.fraction(initial)
            direction = 1 if end > start else -1
            # Material may wait for touch slop before moving the thumb. Compare
            # progress to observed initial/final values, never startFraction;
            # the pressed sample need not have jumped to the requested start.
            self.check(case, label + ": mid drag progresses toward endpoint", (
                direction * (mid - initial_fraction) > .01
                and abs(mid - final_fraction) < abs(initial_fraction - final_fraction) + .04
                and direction * (end - mid) >= -.04), after,
                initialFraction=initial_fraction, pressedFraction=pressed,
                midFraction=mid, finalFraction=final_fraction, targetFraction=end)
            self.check(case, label + ": beforeRelease reaches target", (
                abs(before_release - end) <= .04
                and direction * (before_release - mid) >= -.01), after,
                beforeReleaseFraction=before_release, targetFraction=end)
            self.check(case, label + ": release and final RangeInfo reach target",
                       abs(released - end) <= .04 and abs(final_fraction - end) <= .04,
                       after, releasedFraction=released, finalFraction=final_fraction,
                       targetFraction=end)
        if label == "Track position":
            duration = 257 if after.current_has("Blue distance") else 240
            elapsed = after.elapsed_seconds()
            self.check(case, "paused elapsed text matches final native range",
                       abs(elapsed - final_fraction * duration) <= 2
                       and after.has("Play") and not after.has("Pause"), after,
                       elapsedSeconds=elapsed, durationSeconds=duration)
        else:
            # The volume label intentionally stays server-confirmed while the
            # thumb is dragged; require acknowledgement only after release.
            percent = round(final_fraction * 100) if math.isfinite(final_fraction) else -1
            self.check(case, "released volume label acknowledges native range",
                       after.has(f"{percent}%"), after, expectedPercent=percent)
        return after

    def run(self):
        self.preflight()
        for theme in ("dark", "light"):
            for font in (1.0, 1.8):
                case = f"{theme}-font-{str(font).replace('.', '_')}"
                snapshot = self.launch(case + "-ready", theme, font_scale=font)
                snapshot = self.step(snapshot, "Blue distance", case + "-queue",
                                     lambda state: state.current_has("Blue distance"))
                for direction, start, end in (("forward", .15, .85), ("reverse", .85, .15)):
                    self.stage = case + "-seek-" + direction
                    snapshot = self.drag(snapshot, "Track position", start, end)
                snapshot = self.step(snapshot, "Players", case + "-players",
                                     lambda state: state.has("Family Area volume"))
                for direction, start, end in (("forward", .15, .85), ("reverse", .85, .15)):
                    self.stage = case + "-volume-" + direction
                    snapshot = self.drag(snapshot, "Family Area volume", start, end)
                self.case_count += 1
            case = theme + "-offline"
            snapshot = self.launch(case + "-ready", theme, scenario="offline")
            self.stage = case + "-seek-forward"
            snapshot = self.drag(snapshot, "Track position", .15, .85, disabled=True)
            snapshot = self.step(snapshot, "Players", case + "-players",
                                 lambda state: state.has("Family Area volume"))
            self.stage = case + "-volume-forward"
            snapshot = self.drag(snapshot, "Family Area volume", .15, .85, disabled=True)
            self.case_count += 1
        self.check("matrix", "all six cases and twenty gestures completed",
                   self.case_count == 6 and self.gesture_count == 20, snapshot)
        self.report["complete"] = True

    def finish(self):
        self.report.update(
            productionUiChanged=False, gestureCount=self.gesture_count,
            commandsCoalescedUntilReleaseVerified=(
                self.report["complete"] and self.case_count == 6 and self.gesture_count == 20
                and bool(self.checks) and all(check["passed"] for check in self.checks)
                and not self.report["failureCode"] and self.report["cleanupSucceeded"]),
            everyTouchPixelTested=False, panelFpsMeasured=False,
        )
        return super().finish()


def main():
    original = BASE.Verifier
    BASE.Verifier = DragVerifier
    try:
        return BASE.main()
    finally:
        BASE.Verifier = original


if __name__ == "__main__":
    raise SystemExit(main())

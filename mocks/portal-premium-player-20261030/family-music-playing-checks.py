#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Native silent-player checks while its playback clock advances.

The guarded parent owns installation, observation and restoration. These checks
use actual accessibility ranges and native pointer events, not browser playback
or provider/audio claims. Four releases and four cancellations cover both themes,
both directions and seek/volume at 1.8x font scale.
"""

import importlib.util
import math
import pathlib


SPEC = importlib.util.spec_from_file_location(
    "family_music_drag", pathlib.Path(__file__).with_name("family-music-drag-checks.py"))
DRAG = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DRAG)
BASE = DRAG.BASE
ORIGINAL_VERIFIER = BASE.Verifier


class PlayingVerifier(DRAG.DragVerifier):
    def receipt(self, raw):
        receipt = raw.get("musicSliderGesture")
        BASE.require(isinstance(receipt, dict), "playing_receipt_missing")
        phases = receipt.get("phases")
        BASE.require(isinstance(phases, list) and all(isinstance(p, dict) for p in phases),
                     "playing_phases_missing")
        names = [p.get("phase") for p in phases]
        terminal = "cancelled" if receipt.get("finishAction") == "cancel" else "released"
        expected = ["playInitial", "playAdvanced", "initial", "pressed",
                    "mid", "held", "beforeRelease", terminal]
        BASE.require(names == expected,
                     "playing_phase_contract")
        times = []
        for phase in phases:
            self.native_range(phase.get("range"))
            self.native_range(phase.get("trackRange"))
            self.counters(phase, ("seekCommands", "volumeCommands"))
            stamp = phase.get("elapsedRealtime")
            BASE.require(type(stamp) is int and stamp >= 0, "playing_timestamp_missing")
            times.append(stamp)
        BASE.require(times == sorted(times), "playing_timestamps_not_monotone")
        return receipt, {p["phase"]: p for p in phases}, terminal

    def track_ms(self, phase):
        return self.fraction(phase["trackRange"]) * 240_000

    def advancing(self, snapshot, phases, first, last, name):
        a, b = phases[first], phases[last]
        elapsed = b["elapsedRealtime"] - a["elapsedRealtime"]
        advanced = self.track_ms(b) - self.track_ms(a)
        self.check(self.stage, name, elapsed >= 1_800 and
                   abs(advanced - elapsed) <= 1_000 and advanced >= 1_500, snapshot,
                   elapsedMs=elapsed, visibleAdvanceMs=round(advanced, 3))

    def gesture(self, snapshot, label, start, end, cancel):
        case = self.stage
        before_counts = self.counters(self.native_frame(snapshot))
        node = snapshot.control(label)
        left, top, right, bottom = self.before_input(snapshot, node)
        self.check(case, "observed native gesture area is at least 48dp",
                   right - left >= BASE.MIN_TARGET and bottom - top >= BASE.MIN_TARGET,
                   snapshot, widthPx=right - left, heightPx=bottom - top)
        after = self.capture(case + "-terminal", command={
            "command": "musicSliderGesture", "control": label,
            "startFraction": start, "endFraction": end, "moveMs": 480, "steps": 24,
            "startPlayback": True, "midHoldMs": 2200, "finishWithCancel": cancel,
        })
        self.stage = case
        raw = self.native_frame(after)
        receipt, phases, terminal = self.receipt(raw)
        self.gesture_count += 1
        self.check(case, "native gesture completed on the requested enabled control",
                   receipt.get("control") == label and receipt.get("enabled") is True
                   and receipt.get("moveEventCount") == 24
                   and receipt.get("completed") is True
                   and receipt.get("finishAction") == ("cancel" if cancel else "release"), after)
        self.advancing(after, phases, "playInitial", "playAdvanced",
                       "playback clock visibly advances before the drag")
        for name, phase in phases.items():
            expected = list(before_counts)
            if name == "released":
                expected[0 if label == "Track position" else 1] += 1
            counts = self.counters(phase, ("seekCommands", "volumeCommands"))
            self.check(case, name + ": command count follows release-only contract",
                       counts == tuple(expected), after,
                       seekCommands=counts[0], volumeCommands=counts[1],
                       expectedSeekCommands=expected[0], expectedVolumeCommands=expected[1])
        held_elapsed = phases["held"]["elapsedRealtime"] - phases["mid"]["elapsedRealtime"]
        mid = self.fraction(phases["mid"]["range"])
        held = self.fraction(phases["held"]["range"])
        self.check(case, "held thumb stays on the local draft while time passes",
                   held_elapsed >= 2_000 and abs(held - mid) <= .001, after,
                   heldMs=held_elapsed, midFraction=mid, heldFraction=held)
        if label != "Track position":
            self.advancing(after, phases, "mid", "held",
                           "volume drag leaves the playback clock advancing")
        before_release = self.fraction(phases["beforeRelease"]["range"])
        self.check(case, "native drag reaches its endpoint before completion",
                   abs(before_release - end) <= .04, after,
                   beforeReleaseFraction=before_release, targetFraction=end)
        final = self.native_range(self.native_slider(after, label).get("range"))
        final_fraction = self.fraction(final)
        terminal_fraction = self.fraction(phases[terminal]["range"])
        # Playing seek can tick once between receipt sampling and the final tree.
        self.check(case, "final range agrees with terminal native observation",
                   math.isfinite(final_fraction)
                   and abs(final_fraction - terminal_fraction) <= .005, after)
        expected = list(before_counts)
        if not cancel:
            expected[0 if label == "Track position" else 1] += 1
        self.check(case, "final counters match the requested completion",
                   self.counters(raw) == tuple(expected), after)
        if not cancel:
            self.check(case, "released control reaches requested value",
                       abs(final_fraction - end) <= .04, after,
                       finalFraction=final_fraction, targetFraction=end)
        else:
            # A cancelled gesture must relinquish its local draft, not commit it.
            self.check(case, "cancelled control relinquishes its draft",
                       abs(final_fraction - end) > .1, after,
                       finalFraction=final_fraction, cancelledFraction=end)
            initial = phases["initial"]
            if label == "Track position":
                expected_ms = min(240_000, self.track_ms(initial) +
                                  phases[terminal]["elapsedRealtime"] - initial["elapsedRealtime"])
                actual_ms = self.track_ms(phases[terminal])
                self.check(case, "cancelled seek returns to the continuing playback position",
                           abs(actual_ms - expected_ms) <= 1_000, after,
                           expectedPositionMs=round(expected_ms, 3),
                           observedPositionMs=round(actual_ms, 3))
            else:
                original_fraction = self.fraction(initial["range"])
                self.check(case, "cancelled volume returns to its original value",
                           abs(final_fraction - original_fraction) <= .001, after,
                           originalFraction=original_fraction, finalFraction=final_fraction)
        if label != "Track position":
            backward = self.track_ms(phases["beforeRelease"]) - self.track_ms(phases[terminal])
            self.check(case, "volume completion does not rewind the playing clock",
                       backward <= 750, after, backwardMs=round(backward, 3))
            percent = round(final_fraction * 100)
            self.check(case, "volume label agrees with native control",
                       after.has(f"{percent}%"), after, expectedPercent=percent)
        else:
            elapsed = after.elapsed_seconds()
            self.check(case, "elapsed text agrees with playing native range",
                       abs(elapsed - final_fraction * 240) <= 2, after,
                       elapsedSeconds=elapsed, rangeSeconds=round(final_fraction * 240, 3))
        self.check(case, "same selected track stays playing",
                   after.current_has("After the sun") and after.has("Pause")
                   and not after.has("Play"), after)
        return after

    def run(self):
        self.preflight()
        for cancel in (False, True):
            for theme in ("dark", "light"):
                start, end = (.15, .85) if theme == "dark" else (.85, .15)
                for label, short in (("Track position", "seek"), ("Family Area volume", "volume")):
                    case = f"{theme}-{short}-{'cancel' if cancel else 'release'}"
                    snapshot = self.launch(case + "-ready", theme, font_scale=1.8)
                    if short == "volume":
                        snapshot = self.step(snapshot, "Players", case + "-players",
                                             lambda s: s.has("Family Area volume"))
                    self.stage = case
                    snapshot = self.gesture(snapshot, label, start, end, cancel)
                    self.case_count += 1
        self.check("matrix", "all eight playing cases completed",
                   self.case_count == 8 and self.gesture_count == 8, snapshot)
        self.report["complete"] = True

    def finish(self):
        self.report.update(
            gestureCount=self.gesture_count, silentAdvancingFixture=True,
            audioProviderTested=False, panelFpsMeasured=False,
            commandsCoalescedUntilReleaseVerified=(
                self.report["complete"] and self.case_count == 8
                and bool(self.checks) and all(check["passed"] for check in self.checks)
                and not self.report["failureCode"] and self.report["cleanupSucceeded"]),
        )
        return ORIGINAL_VERIFIER.finish(self)


def main():
    original = BASE.Verifier
    BASE.Verifier = PlayingVerifier
    try:
        return BASE.main()
    finally:
        BASE.Verifier = original


if __name__ == "__main__":
    raise SystemExit(main())

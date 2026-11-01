#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Bounded Family silent music touch checks; parent owns APK/observer/restoration.

Uses the real native controls in MusicDesignPreviewActivity. Seek and volume are
simulated, never provider/audio commands. Tests points 25px above/below the
observed slider center (inside its declared 54px/48dp layout), including points
outside the visible thumb. This verifies those points, not every touch pixel.
Native isChecked/checkable/state description verify non-tab queue selection.
Raw native snapshots remain private. No FPS, backlight or production-dock claim.
"""

import importlib.util
import json
import pathlib


SPEC = importlib.util.spec_from_file_location(
    "family_music_ui",
    pathlib.Path(__file__).with_name("family-music-ui-checks.py"),
)
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)


class TouchVerifier(BASE.Verifier):
    def native_slider(self, snapshot, label):
        raw = json.loads(pathlib.Path(self.frames[-1]["nativeJsonPath"]).read_text())
        matches = []

        def visit(node):
            if node.get("description") == label and node.get("class") == "android.widget.SeekBar":
                matches.append(node)
            for child in node.get("children", []):
                visit(child)

        visit(raw)
        BASE.require(len(matches) == 1, "one_native_slider_required")
        native = matches[0]
        BASE.require(tuple(native["bounds"]) == BASE.bounds(snapshot.control(label, disabled=True)),
                     "slider_snapshot_binding_required")
        return native

    def queue_state(self, snapshot, label, current):
        row = snapshot.control(label)
        self.check(self.stage, label + ": correct non-tab selection", (
            row.get("checkable") == "true" and row.get("checked") == str(current).lower()
            and row.get("selected") == "false"
            and bool(row.get("state-description"))), snapshot)

    def touch(self, snapshot, label, fraction, offset, disabled=False):
        node = snapshot.control(label, disabled=disabled)
        native = self.native_slider(snapshot, label)
        left, top, right, bottom = BASE.bounds(node)
        if not disabled:
            self.before_input(snapshot, node)
        else:
            BASE.require(node.get("enabled") == "false", "disabled_slider_required")
            BASE.require(self.foreground() == snapshot.window_id, "disabled_input_window_changed")
        x = round(left + (right - left) * fraction)
        y = round((top + bottom) / 2) + offset
        BASE.require(0 <= x < BASE.WIDTH and 0 <= y < BASE.HEIGHT,
                     "expanded_touch_within_display")
        # The fixed native bounds include the full gesture layout. Its visual thumb
        # remains 16dp (18px here); compare the point to that glyph, not the hit box.
        self.check(self.stage, label + ": edge point beyond painted thumb",
                   abs(offset) > 9, snapshot,
                   nativeGestureHeightPx=bottom - top, paintedThumbHeightPx=18,
                   verticalOffsetPx=offset,
                   declaredLayoutMinimumPx=54, testedVerticalSpanPx=50)
        if not disabled:
            self.check(self.stage, label + ": native gesture bounds cover 48dp",
                       bottom - top >= 54, snapshot)
        before = native["range"]["current"]
        self.adb("shell", "input", "tap", str(x), str(y))
        after = self.observe(self.stage + "-after-edge")
        observed = self.native_slider(after, label)
        value = observed["range"]["current"]
        minimum, maximum = observed["range"]["minimum"], observed["range"]["maximum"]
        span = maximum - minimum
        self.check(self.stage, label + ": coherent native range",
                   minimum <= value <= maximum and span > 0, after)
        if disabled:
            self.check(self.stage, label + ": disabled value unchanged",
                       value == before, after)
        else:
            self.check(self.stage, label + ": edge touch changes confirmed value",
                       abs((value - minimum) / span - fraction) <= .04
                       and abs(value - before) > span * .05, after)
        if label == "Track position":
            duration = 257 if after.current_has("Blue distance") else 240
            self.check(self.stage, "paused elapsed text matches native seek value",
                       abs(after.elapsed_seconds() - value * duration) <= 2 and after.has("Play"),
                       after)
        else:
            self.check(self.stage, "volume label acknowledges native value",
                       after.has(f"{round(value)}%"), after)
        return after

    def run(self):
        self.preflight()
        for theme in ("dark", "light"):
            for font in (1.0, 1.8):
                case = f"{theme}-font-{str(font).replace('.', '_')}"
                self.stage = case
                snapshot = self.launch(case + "-ready", theme, font_scale=font)
                self.queue_state(snapshot, "After the sun", True)
                self.queue_state(snapshot, "Blue distance", False)
                snapshot = self.step(snapshot, "Blue distance", case + "-queue",
                                     lambda state: state.current_has("Blue distance"))
                self.queue_state(snapshot, "After the sun", False)
                self.queue_state(snapshot, "Blue distance", True)
                for fraction, offset in ((.25, -25), (.75, 25)):
                    self.stage = case + "-seek-" + str(int(fraction * 100))
                    snapshot = self.touch(snapshot, "Track position", fraction, offset)
                snapshot = self.step(snapshot, "Players", case + "-players",
                                     lambda state: state.has("Family Area volume"))
                for fraction, offset in ((.25, -25), (.75, 25)):
                    self.stage = case + "-volume-" + str(int(fraction * 100))
                    snapshot = self.touch(snapshot, "Family Area volume", fraction, offset)
                self.case_count += 1
            case = theme + "-offline"
            self.stage = case
            snapshot = self.launch(case + "-ready", theme, scenario="offline")
            snapshot = self.touch(snapshot, "Track position", .75, 25, disabled=True)
            snapshot = self.step(snapshot, "Players", case + "-players",
                                 lambda state: state.has("Family Area volume"))
            snapshot = self.touch(snapshot, "Family Area volume", .75, 25, disabled=True)
            self.case_count += 1
        self.report["complete"] = True
        checks_passed = self.case_count == 6 and all(check["passed"] for check in self.checks)
        self.report.update(queueNativeCheckableVerified=checks_passed, sliderEdgePointsTested=True,
                           sliderEdgeChecksPassed=checks_passed,
                           everyTouchPixelTested=False, productionUiChanged=True)


def main():
    original = BASE.Verifier
    BASE.Verifier = TouchVerifier
    try:
        return BASE.main()
    finally:
        BASE.Verifier = original


if __name__ == "__main__":
    raise SystemExit(main())

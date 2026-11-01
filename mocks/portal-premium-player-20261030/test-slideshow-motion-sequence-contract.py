# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Fixture-free captured-frame regressions; no external files or native inference."""

import pathlib
import types
import unittest
from unittest.mock import patch

from PIL import Image, ImageDraw


PATH = pathlib.Path(__file__).with_name("slideshow-motion-sequence-contract.py")
CONTRACT = types.ModuleType("slideshow_motion_sequence_contract")
exec(compile(PATH.read_bytes(), str(PATH), "exec"), CONTRACT.__dict__)
ROI = {"photo": (0, 0, 1, 1)}


def frame(color=(40, 60, 80), size=(20, 10)):
    return Image.new("RGB", size, color)


class MotionSequenceContractTest(unittest.TestCase):
    def inspect(self, frames, times=None, rois=None, thresholds=None):
        return CONTRACT.inspect(frames, list(range(len(frames))) if times is None else times,
                                ROI if rois is None else rois, thresholds)

    def test_stationary_and_changing_counts_without_automatic_acceptance(self):
        first = frame()
        second = first.copy()
        second.putpixel((3, 4), (40, 61, 80))  # One channel, one pixel.
        result = self.inspect([first, first.copy(), second])
        self.assertTrue(result["valid"])
        self.assertIsNone(result["pass"])
        metrics = result["rois"]["photo"]
        self.assertEqual([None, 0, 1], [f["changedPixels"] for f in metrics["perFrame"]])
        self.assertEqual(1 / 200, metrics["maxChangedFraction"])
        self.assertTrue(metrics["motionObserved"])
        self.assertFalse(result["smoothnessAssessed"])
        stationary = self.inspect([first, first.copy()])["rois"]["photo"]
        self.assertFalse(stationary["motionObserved"])
        self.assertEqual(0, stationary["changedComparisons"])

    def test_clock_outside_named_photo_roi_is_not_photo_motion(self):
        first = frame()
        second = first.copy()
        ImageDraw.Draw(second).rectangle((0, 8, 19, 9), fill=(255, 255, 255))
        result = self.inspect([first, second], rois={
            "photo": (0, 0, 1, .8), "clock": (0, .8, 1, 1)})
        self.assertFalse(result["rois"]["photo"]["motionObserved"])
        self.assertEqual(40, result["rois"]["clock"]["perFrame"][1]["changedPixels"])

    def test_rgb_near_black_boundary_and_exact_99_percent(self):
        exactly = frame((8, 8, 8), (10, 10))
        exactly.putpixel((0, 0), (8, 8, 9))
        below = exactly.copy()
        below.putpixel((1, 0), (9, 8, 8))
        result = self.inspect([exactly, below, frame((8, 9, 8), (10, 10))])
        values = result["rois"]["photo"]["perFrame"]
        self.assertEqual([99, 98, 0], [f["nearBlackPixels"] for f in values])
        self.assertEqual([True, False, False], [f["nearBlack"] for f in values])

    def test_timestamp_runs_and_endpoints_with_irregular_intervals(self):
        frames = [frame(), frame((0, 0, 0)), frame((8, 8, 8)),
                  frame(), frame((0, 0, 0)), frame((0, 0, 0))]
        result = self.inspect(frames, [10, 10.2, 11.7, 12, 20, 25])
        metrics = result["rois"]["photo"]
        self.assertEqual([1.5, 5], [r["durationSeconds"] for r in metrics["nearBlackRuns"]])
        longest = metrics["longestNearBlackRun"]
        self.assertEqual((4, 5, 20, 25, 2),
                         (longest["startIndex"], longest["endIndex"],
                          longest["startTimestampSeconds"], longest["endTimestampSeconds"],
                          longest["frameCount"]))
        self.assertEqual(5, metrics["longestConsecutiveNearBlackDurationSeconds"])
        self.assertFalse(metrics["finalFrameExtrapolated"])
        self.assertEqual("first_to_last_observed_black_timestamp", longest["durationMeaning"])
        self.assertEqual(15, result["observedSpanSeconds"])

    def test_single_black_frame_has_zero_span_not_next_frame_interval(self):
        result = self.inspect([frame((0, 0, 0)), frame()], [2, 200])
        run = result["rois"]["photo"]["longestNearBlackRun"]
        self.assertEqual(0, run["durationSeconds"])
        self.assertEqual(2, run["endTimestampSeconds"])
        single = self.inspect([frame((0, 0, 0))], [500])
        self.assertEqual(0, single["comparisonCount"])
        self.assertEqual(0, single["rois"]["photo"]["longestNearBlackRun"]["durationSeconds"])
        self.assertIsNone(single["rois"]["photo"]["perFrame"][0]["changedPixels"])

    def test_gaps_cannot_infer_panel_fps_or_smoothness(self):
        frames = [frame(), frame((100, 60, 80)), frame()]
        dense = self.inspect(frames, [0, .001, .002])
        sparse = self.inspect(frames, [0, 7, 80])
        self.assertTrue(dense["valid"] and sparse["valid"])
        self.assertEqual(2, sparse["rois"]["photo"]["changedComparisons"])
        self.assertEqual([7, 73], sparse["captureIntervalsSeconds"])
        self.assertFalse(sparse["smoothnessAssessed"])
        self.assertFalse(sparse["blackContinuityBetweenSamplesAssessed"])
        self.assertIsNone(sparse["pass"])
        self.assertNotIn("fps", str(sparse).lower())
        self.assertEqual(
            [f["changedPixels"] for f in dense["rois"]["photo"]["perFrame"]],
            [f["changedPixels"] for f in sparse["rois"]["photo"]["perFrame"]])

    def test_multiple_black_runs_choose_first_duration_tie(self):
        result = self.inspect([frame((0, 0, 0)), frame((0, 0, 0)), frame(),
                               frame((0, 0, 0)), frame((0, 0, 0))])
        self.assertEqual(0, result["rois"]["photo"]["longestNearBlackRun"]["startIndex"])
        bright = self.inspect([frame(), frame()])
        self.assertIsNone(bright["rois"]["photo"]["longestNearBlackRun"])
        self.assertEqual(0, bright["rois"]["photo"]["maxConsecutiveNearBlackFrames"])

    def test_roi_rounds_outward_and_uses_roi_area(self):
        result = self.inspect([frame((8, 8, 8))], rois={"tiny": (.11, .21, .19, .29)})
        metrics = result["rois"]["tiny"]
        self.assertEqual([2, 2, 4, 3], metrics["pixelBounds"])
        self.assertEqual(2, metrics["pixelCount"])
        self.assertEqual(2, metrics["perFrame"][0]["nearBlackPixels"])

    def test_caller_thresholds_only_and_inclusive_limits(self):
        frames = [frame(), frame((0, 0, 0)), frame((0, 0, 0))]
        limits = {"photo": dict(maxNearBlackDurationSeconds=1,
                               maxConsecutiveNearBlackFrames=2,
                               minChangedComparisons=1, maxChangedFraction=1)}
        self.assertTrue(self.inspect(frames, thresholds=limits)["pass"])
        limits["photo"]["maxNearBlackDurationSeconds"] = .9
        self.assertFalse(self.inspect(frames, thresholds=limits)["pass"])
        self.assertIsNone(self.inspect(frames, thresholds={})["pass"])
        # Zero duration alone cannot reject a single black observation.
        self.assertFalse(self.inspect([frame((0, 0, 0))], thresholds={
            "photo": {"maxConsecutiveNearBlackFrames": 0}})["pass"])
        self.assertIsNone(self.inspect([frame()], thresholds={
            "photo": {"maxChangedFraction": 0}})["pass"])

    def test_invalid_times_and_frame_inputs(self):
        for times in ([0], [0, 0], [1, 0], [0, float("nan")],
                      [0, float("inf")], [0, True], [0, "1"], [-1e308, 1e308],
                      [0, 10**1000]):
            with self.subTest(times=times):
                self.assertFalse(self.inspect([frame(), frame()], times)["valid"])
        for frames in ([], [None], [frame().convert("RGBA")], [frame(), frame(size=(21, 10))]):
            self.assertFalse(self.inspect(frames)["valid"])
        self.assertTrue(self.inspect([frame(), frame()], [-10, -9])["valid"])
        self.assertFalse(CONTRACT.inspect(None, [], ROI)["valid"])

    def test_invalid_roi_and_threshold_schemas(self):
        for rois in ({}, {"x": (0, 0, 1.1, 1)}, {"x": (-.1, 0, 1, 1)},
                     {"x": (1, 0, 0, 1)}, {"x": (0, 0, 0, 1)},
                     {"x": (0, 0, 1)}, {"": (0, 0, 1, 1)},
                     {"x": (False, 0, 1, 1)}, {"x": (0, 0, float("nan"), 1)}):
            self.assertFalse(self.inspect([frame()], rois=rois)["valid"])
        for limits in ([], {"other": {}}, {"photo": {"minFps": 60}},
                       {"photo": {"maxChangedFraction": 1.1}},
                       {"photo": {"maxNearBlackDurationSeconds": -1}},
                       {"photo": {"minChangedComparisons": 1.5}},
                       {"photo": {"maxConsecutiveNearBlackFrames": True}}):
            self.assertFalse(self.inspect([frame()], thresholds=limits)["valid"])

    def test_resource_bounds_fail_before_crop(self):
        for name, limit, frames, rois in (
            ("MAX_FRAMES", 1, [frame(), frame()], ROI),
            ("MAX_ROIS", 1, [frame()], {"a": (0, 0, 1, 1), "b": (0, 0, 1, 1)}),
            ("MAX_FRAME_PIXELS", 199, [frame()], ROI),
            ("MAX_DIMENSION", 19, [frame()], ROI),
            ("MAX_ANALYSIS_PIXELS", 399, [frame(), frame()], ROI),
        ):
            with patch.object(CONTRACT, name, limit), \
                    patch.object(Image.Image, "crop", side_effect=AssertionError("unbounded crop")):
                self.assertFalse(self.inspect(frames, rois=rois)["valid"])
        with patch.object(CONTRACT, "MAX_FRAMES", 2), \
                patch.object(CONTRACT, "MAX_ANALYSIS_PIXELS", 400):
            self.assertTrue(self.inspect([frame(), frame()])["valid"])

    def test_inputs_unchanged_and_core_has_no_io(self):
        frames, times, rois = [frame(), frame((0, 0, 0))], [0, 2], dict(ROI)
        before = [image.tobytes() for image in frames]
        with patch("builtins.open", side_effect=AssertionError("file I/O")), \
                patch.object(Image, "open", side_effect=AssertionError("image I/O")):
            result = self.inspect(frames, times, rois)
        self.assertTrue(result["valid"])
        self.assertEqual(before, [image.tobytes() for image in frames])
        self.assertEqual([0, 2], times)
        self.assertEqual(ROI, rois)


if __name__ == "__main__":
    unittest.main()

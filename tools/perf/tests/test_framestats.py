"""framestats / meminfo parsing on real captures from the Family Portal (official 1.314, CC door-row tap)."""

import os
import unittest

from perflib import framestats, meminfo

FIX = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures")


def fixture(name):
    with open(os.path.join(FIX, name), encoding="utf-8") as f:
        return f.read()


class FramestatsTest(unittest.TestCase):
    def setUp(self):
        self.text = fixture("framestats-cc-tap.txt")

    def test_frames_from_the_control_center_window(self):
        frames = framestats.parse_frames(self.text)
        self.assertEqual(len(frames), 4)
        self.assertTrue(all(f.window.endswith("ControlCenterActivity") for f in frames))
        self.assertEqual([round(f.total_ms, 1) for f in frames], [11.1, 16.8, 35.1, 30.3])

    def test_summary_metrics(self):
        s = framestats.summarize(framestats.parse_frames(self.text))
        self.assertEqual((s["frames"], s["over16"], s["over32"], s["over60"]), (4, 3, 1, 0))
        self.assertEqual(s["worst"], 35.1)
        self.assertEqual(s["janky_pct"], 75.0)
        self.assertEqual(s["p50"], 30.3)  # nearest rank on [11.1, 16.8, 30.3, 35.1]
        self.assertEqual(s["p99"], 35.1)
        self.assertEqual(s["sum_over32"], 35.1)
        self.assertEqual(s["windows"], {"ControlCenterActivity": 4})

    def test_hwui_process_summary(self):
        h = framestats.parse_hwui(self.text)
        self.assertEqual((h.frames, h.janky, h.janky_pct, h.p50, h.p90, h.p99), (4, 2, 50.0, 29, 34, 34))

    def test_repeated_polls_are_deduplicated(self):
        twice = framestats.parse_frames(self.text + "\n" + self.text)
        self.assertEqual(len(twice), 4)

    def test_window_filter_and_empty(self):
        frames = framestats.parse_frames(self.text)
        self.assertEqual(framestats.summarize(frames, window="HomeActivity")["frames"], 0)
        self.assertEqual(framestats.summarize([])["worst"], 0.0)

    def test_flagged_rows_skipped_unless_asked(self):
        flagged = self.text.replace("\n0,99370047962115,", "\n1,99370047962115,")
        self.assertEqual(len(framestats.parse_frames(flagged)), 3)
        self.assertEqual(len(framestats.parse_frames(flagged, all_flags=True)), 4)

    def test_back_to_home_frames_carry_flag_1(self):
        # Real capture (Family, official 1.326, bench.py cc-open): BACK from Control Center to Home.
        text = fixture("framestats-cc-back-home.txt")
        self.assertEqual(framestats.parse_frames(text), [])  # the survey's flags-0 rule sees nothing
        frames = framestats.parse_frames(text, flags=(0, 1))
        self.assertEqual([round(f.total_ms, 1) for f in frames], [80.5, 40.1])
        self.assertEqual(framestats.summarize(frames)["windows"], {"HomeActivity": 2})
        self.assertEqual(framestats.parse_hwui(text).frames, 7)

    def test_photo_beats_and_windows(self):
        log = ("99370.100 21250 21250 I ImmortalEngine: photo_beat galleryWall @file:///scheduler.js:80\n"
               "99370.300 21250 21250 I ImmortalEngine: photo_strips 3\n"
               "99370.400 21250 21250 I ImmortalEngine: photo_beat cinema @file:///scheduler.js:80\n")
        beats = framestats.parse_beats(log, since_s=99370.0)
        self.assertEqual(beats, [(99370.1, "galleryWall"), (99370.4, "cinema")])
        self.assertEqual(framestats.parse_beats(log, since_s=99370.2), [(99370.4, "cinema")])
        frames = framestats.parse_frames(self.text)
        wins = framestats.beat_windows(frames, beats, pre_s=0.5, post_s=3.0)
        self.assertEqual([(m, len(fr)) for _, m, fr in wins], [("cinema", 4)])  # the first beat is the start
        self.assertEqual(len(framestats.beat_windows(frames, beats, skip_first=False)), 2)


class MeminfoTest(unittest.TestCase):
    def test_total_pss_and_graphics(self):
        self.assertEqual(meminfo.parse(fixture("meminfo-launcher.txt")), {"pss_mb": 177.5, "graphics_mb": 46.0})

    def test_missing_values(self):
        self.assertEqual(meminfo.parse("nothing here"), {"pss_mb": None, "graphics_mb": None})


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Synthetic dump tests; never read a device or private captured dumps."""

import unittest

from portal_warm_dream_contract import activity_contract, parse_activity_dump


class ActivityContractTest(unittest.TestCase):
    def test_one_live_home_and_preview(self):
        text = """
ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
  * Hist #1: ActivityRecord{home1 u0 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity t23}
  * Hist #0: ActivityRecord{preview1 u0 com.immortal.launcher.debug/com.immortal.launcher.PhotoFramePreviewActivity t23}
    mResumedActivity: ActivityRecord{home1 u0 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity t23}
"""
        self.assertEqual(parse_activity_dump(text), {
            "resumed_component": "com.immortal.launcher.debug/com.immortal.launcher.HomeActivity",
            "resumed_token": "home1",
            "live_records": [
                {"token": "home1", "component": "com.immortal.launcher.debug/com.immortal.launcher.HomeActivity"},
                {"token": "preview1", "component": "com.immortal.launcher.debug/com.immortal.launcher.PhotoFramePreviewActivity"},
            ],
        })
        self.assertEqual(activity_contract(text), {
            "resumedKind": "home", "resumedToken": "home1",
            "liveHomeCount": 1, "livePreviewCount": 1,
        })

    def test_package_relative_shorthand_does_not_use_source_namespace(self):
        for cls, kind, count in [
            (".HomeActivity", "other", 0),
            ("HomeActivity", "other", 0),
            ("com.immortal.launcher.debug.HomeActivity", "other", 0),
            ("com.immortal.launcher.HomeActivity", "home", 1),
            ("com.immortal.launcher.PhotoFramePreviewActivity", "preview", 0),
            (".PhotoFramePreviewActivity", "other", 0),
        ]:
            with self.subTest(cls=cls):
                row = f"ActivityRecord{{a1 u0 com.immortal.launcher.debug/{cls} t1}}"
                result = activity_contract(f"mResumedActivity: {row}\n * Hist #0: {row}")
                self.assertEqual(result["resumedKind"], kind)
                self.assertEqual(result["liveHomeCount"], count)
                self.assertEqual(result["livePreviewCount"], int(kind == "preview"))
        parsed = parse_activity_dump(
            "mResumedActivity: ActivityRecord{a1 u0 com.immortal.launcher.debug/.HomeActivity t1}"
        )
        self.assertEqual(parsed["resumed_component"],
                         "com.immortal.launcher.debug/com.immortal.launcher.debug.HomeActivity")

    def test_release_shorthand_normalizes_against_release_package(self):
        text = """
mResumedActivity: ActivityRecord{r1 u0 com.immortal.launcher/.HomeActivity t1}
Hist #1: ActivityRecord{r1 u0 com.immortal.launcher/.HomeActivity t1}
Hist #0: ActivityRecord{r2 u0 com.immortal.launcher/.PhotoFramePreviewActivity t1}
"""
        self.assertEqual(activity_contract(text, package="com.immortal.launcher"), {
            "resumedKind": "home", "resumedToken": "r1",
            "liveHomeCount": 1, "livePreviewCount": 1,
        })
        self.assertEqual(activity_contract(text)["resumedKind"], "other")
        self.assertEqual(activity_contract(text)["liveHomeCount"], 0)

    def test_exact_package_excludes_prefixes_and_unrelated_callers(self):
        for package in ["com.immortal.launcher", "com.immortal.launcher.debug.extra",
                        "com.immortal.launcher.debugger", "org.example.caller"]:
            with self.subTest(package=package):
                row = f"ActivityRecord{{a1 u0 {package}/com.immortal.launcher.HomeActivity t1}}"
                self.assertEqual(activity_contract(f"mResumedActivity: {row}\nHist #0: {row}"), {
                    "resumedKind": "other", "resumedToken": "a1",
                    "liveHomeCount": 0, "livePreviewCount": 0,
                })
        text = "mResumedActivity: ActivityRecord{caller u0 org.example.caller/.MainActivity t99}"
        self.assertEqual(activity_contract(text)["resumedKind"], "other")
        self.assertEqual(parse_activity_dump(text)["resumed_component"],
                         "org.example.caller/org.example.caller.MainActivity")

    def test_historical_references_do_not_supply_resumed_or_live_activities(self):
        text = """
mLastPausedActivity: ActivityRecord{old1 u0 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity t1}
mLastStoppedActivity: ActivityRecord{old2 u0 com.immortal.launcher.debug/com.immortal.launcher.PhotoFramePreviewActivity t1}
Recent #0: ActivityRecord{old3 u0 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity t1}
reference: Hist #0: ActivityRecord{old4 u0 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity t1}
Recent tasks:
  * Hist #0: ActivityRecord{old5 u0 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity t1}
  mResumedActivity: ActivityRecord{old5 u0 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity t1}
ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
  Hist #0: ActivityRecord{live1 u0 org.example.caller/.MainActivity t99}
ACTIVITY MANAGER RECENT TASKS (dumpsys activity recents)
  Hist #0: ActivityRecord{old6 u0 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity t1}
"""
        self.assertEqual(activity_contract(text), {
            "resumedKind": "none", "resumedToken": None,
            "liveHomeCount": 0, "livePreviewCount": 0,
        })
        self.assertEqual(len(parse_activity_dump(text)["live_records"]), 1)

    def test_empty_null_missing_and_malformed_resumed(self):
        for text in [
            "", "mResumedActivity: null", "mResumedActivity=null",
            "mResumedActivity: ActivityRecord{broken}",
            "mResumedActivity: ActivityRecord{a1 u0 com.immortal.launcher.debug/.HomeActivity}",
            "mResumedActivity: ActivityRecord{a1 u0 com.immortal.launcher.debug/ t1}",
            "mResumedActivity: ActivityRecord{a1 u0 com.immortal.launcher.debug/.HomeActivity tX}",
            "mResumedActivity: ActivityRecord{a1 u0 com.immortal.launcher.debug/.HomeActivity t1",
            "reference: mResumedActivity: ActivityRecord{a1 u0 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity t1}",
        ]:
            with self.subTest(text=text):
                self.assertEqual(parse_activity_dump(text), {
                    "resumed_component": None, "resumed_token": None, "live_records": [],
                })

    def test_live_rows_are_independent_of_missing_or_null_resumed(self):
        for prefix in ["", "mResumedActivity: null\n"]:
            with self.subTest(prefix=prefix):
                self.assertEqual(activity_contract(prefix +
                    "Hist #0: ActivityRecord{a1 u0 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity t1}"), {
                    "resumedKind": "none", "resumedToken": None,
                    "liveHomeCount": 1, "livePreviewCount": 0,
                })

    def test_distinct_duplicate_own_records_are_counted(self):
        text = """
Hist #3: ActivityRecord{h1 u0 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity t1}
Hist #2: ActivityRecord{h2 u0 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity t2}
Hist #1: ActivityRecord{p1 u0 com.immortal.launcher.debug/com.immortal.launcher.PhotoFramePreviewActivity t1}
Hist #0: ActivityRecord{p2 u0 com.immortal.launcher.debug/com.immortal.launcher.PhotoFramePreviewActivity t2}
mResumedActivity: ActivityRecord{p2 u0 com.immortal.launcher.debug/com.immortal.launcher.PhotoFramePreviewActivity t2}
"""
        self.assertEqual(activity_contract(text), {
            "resumedKind": "preview", "resumedToken": "p2",
            "liveHomeCount": 2, "livePreviewCount": 2,
        })

    def test_resumed_reference_is_not_an_extra_live_record(self):
        text = """
mResumedActivity: ActivityRecord{h1 u0 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity t1}
Hist #0: ActivityRecord{h1 u0 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity t1}
"""
        self.assertEqual(activity_contract(text)["liveHomeCount"], 1)

    def test_malformed_live_rows_and_other_users_are_ignored(self):
        text = """
Hist #x: ActivityRecord{h1 u0 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity t1}
Hist #0: ActivityRecord{h1 u10 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity t1}
Hist #1: ActivityRecord{h1 u0 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity tX}
Hist #2: ActivityRecord{h1 u0 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity t1
"""
        self.assertEqual(parse_activity_dump(text)["live_records"], [])


if __name__ == "__main__":
    unittest.main()

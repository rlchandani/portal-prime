# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Target-selection contracts; fixtures contain only fixed public labels."""
import importlib.util
import os
from pathlib import Path
import unittest

helper = Path(os.environ.get("PORTAL_GEOMETRY_HELPER",
    str(Path(__file__).with_name("portal_native_geometry.py"))))
spec = importlib.util.spec_from_file_location("geometry", helper)
geometry = importlib.util.module_from_spec(spec)
spec.loader.exec_module(geometry)


def row(key, bounds, clickable=True, **extra):
    return dict(key=key, bounds=bounds, clickable=clickable, editable=False, **extra)


def state(*extra):
    return dict(screenWidth=2160, screenHeight=1440, nodes=[
        row("home", [774, 1327, 972, 1413]),
        row("apps", [981, 1327, 1179, 1413]),
        row("tools", [1188, 1327, 1386, 1413]), *extra])


class GeometryTests(unittest.TestCase):
    def test_partial_nonclickable_label_does_not_poison_clickable_target(self):
        button = row("toolsNotes", [800, 500, 1300, 700])
        ui = state(row("toolsNotes", [-20, 500, 100, 550], False), button)
        self.assertEqual([button], geometry.targets(ui, "toolsNotes", "body", True))

    def test_partial_same_key_dock_label_does_not_poison_dock(self):
        ui = state(row("tools", [2000, 150, 2190, 210], False))
        self.assertEqual([ui["nodes"][2]], geometry.targets(ui, "tools", "bottom", True))

    def test_partial_label_is_valid_observation_evidence(self):
        label = row("tools", [-10, 50, 100, 110], False)
        self.assertEqual([label], geometry.targets(state(label), "tools", "body", False))

    def test_partial_clickable_target_still_rejected(self):
        with self.assertRaises(RuntimeError):
            geometry.targets(state(row("toolsNotes", [-10, 500, 200, 600])),
                             "toolsNotes", "body", True)

    def test_valid_target_cannot_mask_partial_action_candidate(self):
        with self.assertRaises(RuntimeError):
            geometry.targets(state(row("toolsNotes", [-10, 500, 200, 600]),
                                   row("toolsNotes", [800, 500, 1300, 700])),
                             "toolsNotes", "body", True)

    def test_partial_dock_action_still_rejected(self):
        ui = state()
        ui["nodes"][2]["bounds"][3] = 1441
        with self.assertRaises(RuntimeError):
            geometry.dock_top(ui)

    def test_malformed_relevant_target_rejected(self):
        for bounds in ([1, 2, 3], [0, 0, True, 100], [0, 0, 100.0, 100],
                       [10, 10, 10, 20], [20, 20, 10, 30], ["1", 2, 3, 4]):
            with self.subTest(bounds=bounds), self.assertRaises(RuntimeError):
                geometry.targets(state(row("toolsNotes", bounds)), "toolsNotes", "body", True)

    def test_fully_offscreen_target_rejected(self):
        with self.assertRaises(RuntimeError):
            geometry.targets(state(row("toolsNotes", [2200, 200, 2300, 300])),
                             "toolsNotes", "body", True)

    def test_duplicate_region_targets_rejected(self):
        with self.assertRaises(RuntimeError):
            geometry.targets(state(row("toolsNotes", [800, 400, 1000, 500]),
                                   row("toolsNotes", [1100, 400, 1300, 500])),
                             "toolsNotes", "body", True)

    def test_wrong_region_not_selected(self):
        self.assertEqual([], geometry.targets(state(row("toolsNotes", [800, 1330, 1000, 1400])),
                                               "toolsNotes", "body", True))

    def test_editable_and_checked_controls_not_selected(self):
        edit = row("toolsNotes", [800, 400, 1000, 500])
        edit["editable"] = True
        self.assertEqual([], geometry.targets(state(edit), "toolsNotes", "body", True))
        self.assertEqual([], geometry.targets(state(row("toolsNotes", [800, 400, 1000, 500], checked=False)),
                                               "toolsNotes", "body", True))

    def test_missing_dock_rejected(self):
        ui = state()
        ui["nodes"].pop()
        with self.assertRaises(RuntimeError):
            geometry.dock_top(ui)

    def test_overlapping_dock_rejected(self):
        ui = state()
        ui["nodes"][1]["bounds"][0] = 970
        with self.assertRaises(RuntimeError):
            geometry.dock_top(ui)

    def test_real_home_dock_geometry(self):
        self.assertEqual(1327, geometry.dock_top(state()))

    def test_left_and_right_regions_use_observed_rail(self):
        rail = [row("toolsNotes", [80, 450, 370, 510]),
                row("toolsConverter", [80, 250, 370, 310])]
        heading = row("toolsNotes", [440, 110, 1100, 170], False)
        ui = state(*rail, heading)
        self.assertEqual([rail[0]], geometry.targets(ui, "toolsNotes", "left", True))
        self.assertEqual([heading], geometry.targets(ui, "toolsNotes", "right", False))

    def test_inconsistent_rail_rejected(self):
        ui = state(row("toolsNotes", [80, 450, 370, 510]),
                   row("toolsConverter", [81, 250, 370, 310]))
        with self.assertRaises(RuntimeError):
            geometry.rail_edge(ui)

    def test_overlapping_cross_key_rail_controls_rejected(self):
        for second in ([80, 480, 370, 540], [80, 450, 370, 510]):
            with self.subTest(second=second), self.assertRaises(RuntimeError):
                geometry.rail_edge(state(row("toolsNotes", [80, 450, 370, 510]),
                                         row("toolsConverter", second)))

    def test_missing_target_returns_empty(self):
        self.assertEqual([], geometry.targets(state(), "toolsNotes", "body", True))

    def test_ambiguous_labels_still_rejected(self):
        with self.assertRaises(RuntimeError):
            geometry.targets(state(row("tools", [-10, 50, 100, 110], False),
                                   row("tools", [110, 50, 200, 110], False)),
                             "tools", "body", False)

    def test_regions_and_roles_require_exact_values(self):
        for region, clickable in (("unknown", True), ("body", 1), ("body", None)):
            with self.subTest(region=region, clickable=clickable), self.assertRaises(RuntimeError):
                geometry.targets(state(), "toolsNotes", region, clickable)

    def test_exported_screen_dimensions_drive_geometry(self):
        ui = dict(screenWidth=1200, screenHeight=800, nodes=[
            row("home", [400, 730, 500, 780]), row("apps", [510, 730, 610, 780]),
            row("tools", [620, 730, 720, 780])])
        self.assertEqual(730, geometry.dock_top(ui))
        ui["nodes"][2]["bounds"][2] = 1201
        with self.assertRaises(RuntimeError):
            geometry.dock_top(ui)


if __name__ == "__main__":
    unittest.main()

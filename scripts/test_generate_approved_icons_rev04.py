#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Geometry and paint regression checks; no Gradle, Android SDK, or device needed."""

from pathlib import Path
from contextlib import contextmanager
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

try:
    import generate_approved_icons_rev04 as icons
except ModuleNotFoundError:
    icons = None


ANDROID = "{http://schemas.android.com/apk/res/android}"


class ApprovedIconConversionTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(icons, "The approved SVG-to-vector generator is missing")

    def vector(self, contents, attributes='viewBox="0 0 24 24"'):
        return ET.fromstring(icons.to_vector(f"<svg {attributes}>{contents}</svg>", "fixture"))

    @contextmanager
    def fresh_clone(self):
        """Copy just the reproducible inputs and existing approved output files."""
        with tempfile.TemporaryDirectory(prefix="approved-icons-rev04-") as directory:
            root = Path(directory)
            inputs = (
                "scripts/generate_approved_icons_rev04.py",
                "scripts/resources/approved-icons-rev04.json",
                "app/src/main/java/com/immortal/launcher/ApprovedIconCatalog.kt",
            )
            outputs = tuple(str(path.relative_to(icons.ROOT)) for path in
                            (icons.ROOT / "app/src/main/res/drawable").glob("approved_icon_*.xml"))
            for relative in inputs + outputs:
                source = icons.ROOT / relative
                self.assertTrue(source.is_file(), f"Missing reproducible input: {relative}")
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
            self.assertFalse((root / "mocks").exists())
            yield root

    def run_generator(self, root, *args):
        return subprocess.run(
            [sys.executable, str(root / "scripts/generate_approved_icons_rev04.py"), *args],
            capture_output=True, text=True, check=False,
            env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
        )

    def test_preserves_original_paths_arcs_and_inherited_paint(self):
        data = "M21 12.8A8 8 0 1 1 11.2 3a6 6 0 0 0 9.8 9.8z"
        vector = self.vector(
            f'<path d="{data}"/>',
            'viewBox="0 0 24 24" fill="none" stroke="currentColor" '
            'stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"',
        )
        path = vector.find("path")
        self.assertEqual(path.get(ANDROID + "pathData"), data)
        self.assertEqual(path.get(ANDROID + "fillColor"), "#00000000")
        self.assertEqual(path.get(ANDROID + "strokeColor"), "#FFFFFFFF")
        self.assertEqual(path.get(ANDROID + "strokeWidth"), "1.8")
        self.assertEqual(path.get(ANDROID + "strokeLineCap"), "round")
        self.assertEqual(path.get(ANDROID + "strokeLineJoin"), "round")

    def test_rounded_rect_uses_elliptical_arcs_instead_of_quadratic_approximation(self):
        vector = self.vector('<rect x="3" y="5" width="18" height="16" rx="2"/>')
        self.assertEqual(
            vector.find("path").get(ANDROID + "pathData"),
            "M5 5H19A2 2 0 0 1 21 7V19A2 2 0 0 1 19 21"
            "H5A2 2 0 0 1 3 19V7A2 2 0 0 1 5 5Z",
        )

    def test_circle_and_line_keep_precise_geometry_and_local_stroke_override(self):
        vector = self.vector(
            '<circle cx="9" cy="10" r="1.8" fill="#fff" stroke="none"/>'
            '<line x1="2" y1="3" x2="5" y2="7"/>',
            'viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2"',
        )
        circle, line = vector.findall("path")
        self.assertEqual(
            circle.get(ANDROID + "pathData"),
            "M10.8 10A1.8 1.8 0 1 0 7.2 10A1.8 1.8 0 1 0 10.8 10Z",
        )
        self.assertEqual(circle.get(ANDROID + "fillColor"), "#FFFFFFFF")
        self.assertEqual(circle.get(ANDROID + "strokeColor"), "#00000000")
        self.assertEqual(line.get(ANDROID + "pathData"), "M2 3L5 7")
        self.assertEqual(line.get(ANDROID + "strokeLineCap"), "butt")
        self.assertEqual(line.get(ANDROID + "strokeLineJoin"), "miter")

    def test_polyline_stays_open_and_polygon_closes(self):
        vector = self.vector(
            '<polyline points="1,2 3,4 5,6"/><polygon points="1 2 3 4 5 6"/>'
        )
        paths = vector.findall("path")
        self.assertEqual(paths[0].get(ANDROID + "pathData"), "M1 2L3 4L5 6")
        self.assertEqual(paths[1].get(ANDROID + "pathData"), "M1 2L3 4L5 6Z")

    def test_negative_viewbox_origin_scales_into_24_units_without_rewriting_path(self):
        data = "M421-80H539V-880H421Z"
        vector = self.vector(
            f'<path d="{data}"/>', 'viewBox="0 -960 960 960" fill="#fff"'
        )
        self.assertEqual(vector.get(ANDROID + "viewportWidth"), "24")
        self.assertEqual(vector.get(ANDROID + "viewportHeight"), "24")
        group = vector.find("group")
        self.assertEqual(group.get(ANDROID + "scaleX"), "0.025")
        self.assertEqual(group.get(ANDROID + "scaleY"), "0.025")
        self.assertEqual(group.get(ANDROID + "translateY"), "24")
        self.assertEqual(group.find("path").get(ANDROID + "pathData"), data)
        self.assertEqual(group.find("path").get(ANDROID + "strokeColor"), "#00000000")

    def test_preserves_fill_rule_opacity_and_nondefault_stroke_width(self):
        vector = self.vector(
            '<path d="M8 3h8l3 16H5z" opacity=".95" fill-rule="evenodd"/>'
            '<path d="M12 5v14" fill="none" stroke="#fff" stroke-width="2.4"/>',
            'viewBox="0 0 24 24" fill="#fff"',
        )
        fill, stroke = vector.findall("path")
        self.assertEqual(fill.get(ANDROID + "fillAlpha"), "0.95")
        self.assertEqual(fill.get(ANDROID + "fillType"), "evenOdd")
        self.assertEqual(stroke.get(ANDROID + "strokeWidth"), "2.4")

    def test_rejects_unhandled_svg_features_instead_of_silently_losing_artwork(self):
        for contents in (
            '<path d="M0 0L1 1" transform="rotate(45)"/>',
            '<text x="2" y="3">?</text>',
            '<path d="M0 0L1 1" fill="#ff0000"/>',
        ):
            with self.subTest(contents=contents), self.assertRaises(ValueError):
                self.vector(contents)

    def test_library_covers_all_frozen_names_and_curated_app_aliases(self):
        library = icons.load_icons()
        self.assertEqual(len(library), 45)
        for name in ("gear", "moonz", "phone", "gridapps", "photo", "tools", "info"):
            self.assertIn(name, library)
        for name, target in {
            "moon": "moonz", "settings": "gear", "apps": "gridapps", "photos": "photo",
            "calendar": "cal", "store": "bag", "browser": "globe", "newpipe": "film",
            "ha": "home", "files": "folder", "f-droid": "bag",
            "org.videolan.vlc": "vlc",
            "io.homeassistant.companion.android.minimal": "home",
        }.items():
            with self.subTest(name=name):
                self.assertEqual(icons.ALIASES[name], target)
                self.assertIn(target, library)

    def test_check_mode_reports_asset_drift_without_rewriting_it(self):
        with self.fresh_clone() as root:
            verified = self.run_generator(root, "--check")
            self.assertEqual(verified.returncode, 0, verified.stderr)
            gear = root / "app/src/main/res/drawable/approved_icon_gear.xml"
            broken = gear.read_text().replace("M421-80", "M422-80")
            gear.write_text(broken)
            verified = self.run_generator(root, "--check")
            self.assertEqual(verified.returncode, 1)
            self.assertIn("approved_icon_gear.xml", verified.stderr)
            self.assertEqual(gear.read_text(), broken)

    def test_fresh_clone_generates_and_checks_identical_outputs_without_mocks(self):
        with self.fresh_clone() as root:
            outputs = list((root / "app/src/main/res/drawable").glob("approved_icon_*.xml"))
            self.assertEqual(len(outputs), 45)
            outputs.append(root / "app/src/main/java/com/immortal/launcher/ApprovedIconCatalog.kt")
            expected = {path: path.read_bytes() for path in outputs}
            for path in outputs:
                path.unlink()
            generated = self.run_generator(root)
            self.assertEqual(generated.returncode, 0, generated.stderr)
            for path, contents in expected.items():
                self.assertEqual(path.read_bytes(), contents, path.name)
            verified = self.run_generator(root, "--check")
            self.assertEqual(verified.returncode, 0, verified.stderr)
            self.assertFalse((root / "mocks").exists())

    def test_bundled_input_tampering_is_rejected_before_writing_outputs(self):
        with self.fresh_clone() as root:
            snapshot = root / "scripts/resources/approved-icons-rev04.json"
            snapshot.write_text(snapshot.read_text().replace("M421-80", "M422-80"))
            gear = root / "app/src/main/res/drawable/approved_icon_gear.xml"
            expected = gear.read_bytes()
            generated = self.run_generator(root)
            self.assertNotEqual(generated.returncode, 0)
            self.assertIn("Bundled approved input changed", generated.stderr)
            self.assertEqual(gear.read_bytes(), expected)

    def test_source_verification_is_opt_in_and_rejects_missing_source(self):
        with self.fresh_clone() as root:
            verified = self.run_generator(root, "--check")
            self.assertEqual(verified.returncode, 0, verified.stderr)
            verified = self.run_generator(
                root, "--check", "--verify-source-root", str(root / "missing-mock"),
            )
            self.assertNotEqual(verified.returncode, 0)
            self.assertIn("Approved source file unavailable", verified.stderr)


if __name__ == "__main__":
    unittest.main()

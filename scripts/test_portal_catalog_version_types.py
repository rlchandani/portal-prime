# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Exercise the existing version-code integer contract through the offline CLI."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
MISSING = object()


class CatalogVersionTypesTest(unittest.TestCase):
    def run_validator(self, version=MISSING):
        catalog = {
            "schemaVersion": 2,
            "categories": [{
                "name": "Media",
                "apps": [{
                    "name": "Example",
                    "packageName": "com.example.media",
                    "description": "Example catalog entry.",
                }],
            }],
        }
        if version is not MISSING:
            catalog["categories"][0]["apps"][0]["versionCode"] = version
        with tempfile.TemporaryDirectory() as scratch:
            repo = Path(scratch)
            script = repo / "scripts/validate_catalog.py"
            script.parent.mkdir()
            script.write_bytes((ROOT / "scripts/validate_catalog.py").read_bytes())
            bundled = repo / "app/src/main/assets/catalog.json"
            bundled.parent.mkdir(parents=True)
            text = json.dumps(catalog)
            (repo / "catalog.json").write_text(text, encoding="utf-8")
            bundled.write_text(text, encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(script)], cwd=repo,
                capture_output=True, text=True, timeout=10,
            )

    def assert_integer_rejected(self, value):
        result = self.run_validator(value)
        output = result.stdout + result.stderr
        self.assertEqual(1, result.returncode, output)
        self.assertIn("versionCode must be an integer", result.stdout)
        self.assertNotIn("differs from catalog.json", result.stdout)
        self.assertNotIn("Traceback", result.stderr)

    def assert_accepted(self, value=MISSING):
        result = self.run_validator(value)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("OK: 1 apps validated", result.stdout)
        self.assertNotIn("FAIL:", result.stdout)
        self.assertNotIn("Traceback", result.stderr)

    def test_boolean_true_is_not_an_integer_version_code(self):
        self.assert_integer_rejected(True)

    def test_boolean_false_is_not_an_integer_version_code(self):
        self.assert_integer_rejected(False)

    def test_positive_integer_is_accepted(self):
        self.assert_accepted(7)

    def test_zero_integer_remains_accepted(self):
        self.assert_accepted(0)

    def test_negative_integer_remains_accepted(self):
        self.assert_accepted(-1)

    def test_string_integer_is_rejected(self):
        self.assert_integer_rejected("7")

    def test_missing_version_remains_accepted(self):
        self.assert_accepted()

    def test_null_version_remains_accepted(self):
        self.assert_accepted(None)


if __name__ == "__main__":
    unittest.main()

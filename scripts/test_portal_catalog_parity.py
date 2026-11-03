# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Exercise bundled-catalog drift through the actual offline validator CLI."""

import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
BUNDLED = Path("app/src/main/assets/catalog.json")


class CatalogParityTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.repo = Path(self.scratch.name)
        self.catalog = {
            "schemaVersion": 2,
            "updated": "2026-11-03",
            "categories": [{
                "name": "Media",
                "apps": [{
                    "name": "Example",
                    "packageName": "com.example.media",
                    "description": "Example catalog entry.",
                }],
            }],
        }
        (self.repo / "catalog.json").write_text(json.dumps(self.catalog), encoding="utf-8")
        (self.repo / BUNDLED).parent.mkdir(parents=True)

    def run_validator(self, bundled):
        if bundled is not None:
            text = bundled if isinstance(bundled, str) else json.dumps(bundled)
            (self.repo / BUNDLED).write_text(text, encoding="utf-8")
        return subprocess.run(
            [sys.executable, str(ROOT / "scripts/validate_catalog.py")],
            cwd=self.repo, capture_output=True, text=True, timeout=10,
        )

    def assert_rejected(self, bundled):
        result = self.run_validator(bundled)
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn(BUNDLED.as_posix(), result.stdout)
        self.assertNotIn("Traceback", result.stderr)

    def test_matching_catalog_passes(self):
        result = self.run_validator(self.catalog)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("OK: 1 apps validated", result.stdout)

    def test_formatting_and_object_key_order_do_not_count_as_drift(self):
        result = self.run_validator(json.dumps(self.catalog, indent=4, sort_keys=True))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_missing_app_is_rejected(self):
        bundled = copy.deepcopy(self.catalog)
        bundled["categories"][0]["apps"] = []
        self.assert_rejected(bundled)

    def test_stale_release_metadata_is_rejected(self):
        bundled = copy.deepcopy(self.catalog)
        bundled["categories"][0]["apps"][0]["versionCode"] = 3
        self.assert_rejected(bundled)

    def test_boolean_does_not_match_integer_version_code(self):
        self.catalog["categories"][0]["apps"][0]["versionCode"] = 1
        (self.repo / "catalog.json").write_text(json.dumps(self.catalog), encoding="utf-8")
        bundled = copy.deepcopy(self.catalog)
        bundled["categories"][0]["apps"][0]["versionCode"] = True
        self.assert_rejected(bundled)

    def test_stale_catalog_timestamp_is_rejected(self):
        bundled = copy.deepcopy(self.catalog)
        bundled["updated"] = "2026-07-01"
        self.assert_rejected(bundled)

    def test_changed_app_order_is_rejected(self):
        second = dict(self.catalog["categories"][0]["apps"][0],
                      name="Second", packageName="com.example.second")
        self.catalog["categories"][0]["apps"].append(second)
        (self.repo / "catalog.json").write_text(json.dumps(self.catalog), encoding="utf-8")
        bundled = copy.deepcopy(self.catalog)
        bundled["categories"][0]["apps"].reverse()
        self.assert_rejected(bundled)

    def test_missing_bundled_file_is_rejected(self):
        self.assert_rejected(None)

    def test_invalid_bundled_json_is_rejected(self):
        self.assert_rejected("{")

    def test_repository_catalogs_match(self):
        hosted = json.loads((ROOT / "catalog.json").read_text(encoding="utf-8"))
        bundled = json.loads((ROOT / BUNDLED).read_text(encoding="utf-8"))
        self.assertEqual(json.dumps(hosted, sort_keys=True), json.dumps(bundled, sort_keys=True),
                         "The app's first catalog must match the repository catalog")


if __name__ == "__main__":
    unittest.main()

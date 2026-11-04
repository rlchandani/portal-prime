# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository root.
"""Run the actual readiness CLI; only Node is an inert process fixture."""
import json
import shutil
import subprocess
import sys
import unittest

from test_portal_release_readiness import ReadinessFixture, SCRIPT


class JavascriptChecksTest(unittest.TestCase):
    def setUp(self):
        self.fixture = ReadinessFixture()
        self.addCleanup(self.fixture.close)

    def run_checks(self, *extra):
        f = self.fixture
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--repo-root", str(f.repo),
             "--javascript-only", "--output", str(f.output), *extra],
            env=f.env, capture_output=True, text=True, timeout=15)

    def test_checks_both_suites_without_android_or_manifest_and_cannot_claim_apk_ready(self):
        f = self.fixture
        shutil.rmtree(f.sdk)
        f.baseline.unlink()
        (f.repo / "version.json").unlink()
        (f.repo / "gradlew").unlink()
        result = self.run_checks()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        receipt = f.receipt()
        self.assertEqual("checked", receipt["status"])
        self.assertEqual("local-javascript-checks", receipt["scope"])
        self.assertEqual({"slideshow-js": 1, "remote-js": 1}, receipt["javascript"])
        self.assertFalse(receipt["published"])
        self.assertFalse(receipt["deployed"])
        self.assertNotIn("artifact", receipt)
        self.assertNotIn("jvm", receipt)
        calls = [json.loads(line) for line in (f.repo / "commands.log").read_text().splitlines()]
        self.assertEqual(3, len(calls))
        for call in calls[1:]:
            self.assertIn("--test-reporter=tap", call)
        self.assertFalse((f.output / "candidate.apk").exists())

    def test_failed_node_exit_has_a_failed_receipt_and_stops_before_the_other_suite(self):
        f = self.fixture
        f.executable(f.tools / "node", """
import sys
if '--version' in sys.argv: print('v24.0.0')
else:
 print('# tests 1\\n# fail 1')
 sys.exit(7)
""")
        result = self.run_checks()
        self.assertNotEqual(0, result.returncode)
        self.assertTrue((f.output / "receipt.json").exists(), result.stdout + result.stderr)
        receipt = f.receipt()
        self.assertEqual("failed", receipt["status"])
        failed = [check for check in receipt["checks"] if check.get("exitCode") == 7]
        self.assertEqual(["slideshow-js"], [check["name"] for check in failed])
        self.assertNotIn("remote-js", receipt["javascript"])
        self.assertFalse((f.output / "candidate.apk").exists())

    def test_missing_required_suite_cannot_report_checked(self):
        f = self.fixture
        (f.repo / "scripts/tests/slideshow_native_filmstrip.test.cjs").unlink()
        result = self.run_checks()
        self.assertNotEqual(0, result.returncode)
        self.assertTrue((f.output / "receipt.json").exists(), result.stdout + result.stderr)
        self.assertEqual("failed", f.receipt()["status"])
        self.assertIn("missing", f.receipt()["error"].lower())

    def test_source_change_during_node_checks_cannot_report_checked(self):
        f = self.fixture
        f.executable(f.tools / "node", """
import pathlib,sys
if '--version' in sys.argv: print('v24.0.0')
else:
 pathlib.Path('app/src/test/js/example.test.mjs').write_text('// changed during check')
 print('# tests 1\\n# fail 0')
""")
        result = self.run_checks()
        self.assertNotEqual(0, result.returncode)
        self.assertTrue((f.output / "receipt.json").exists(), result.stdout + result.stderr)
        self.assertEqual("failed", f.receipt()["status"])
        self.assertIn("changed", f.receipt()["error"].lower())

    def test_existing_receipt_is_preserved(self):
        f = self.fixture
        f.output.mkdir()
        sentinel = b"previous checked evidence\n"
        (f.output / "receipt.json").write_bytes(sentinel)
        result = self.run_checks()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(sentinel, (f.output / "receipt.json").read_bytes())

    def test_apk_mode_still_requires_a_baseline(self):
        f = self.fixture
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--repo-root", str(f.repo),
             "--output", str(f.output)],
            env=f.env, capture_output=True, text=True, timeout=15)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("baseline", result.stderr.lower())
        self.assertFalse(f.output.exists())


if __name__ == "__main__":
    unittest.main()

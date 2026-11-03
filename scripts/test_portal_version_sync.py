# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository root.
"""Exercise the complete production version guard in isolated ordinary projects."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "check-version-sync.sh"
STABLE_URL = "https://github.com/starbrightlab/immortal/releases/latest/download/immortal.apk"


class PortalVersionSyncTest(unittest.TestCase):
    def run_guard(self, declarations, *, code=90, name="2.9", url=STABLE_URL, notes="A release."):
        with tempfile.TemporaryDirectory(prefix="portal-version-sync-test-") as temporary:
            root = Path(temporary)
            (root / "scripts").mkdir()
            (root / "app").mkdir()
            guard = root / "scripts" / "check-version-sync.sh"
            shutil.copyfile(SCRIPT, guard)
            (root / "app" / "build.gradle.kts").write_text(
                "android {\n    defaultConfig {\n" + declarations + "    }\n}\n", encoding="utf-8")
            (root / "version.json").write_text(json.dumps({
                "versionCode": code, "versionName": name, "apkUrl": url, "notes": notes,
            }) + "\n", encoding="utf-8")
            env = dict(os.environ)
            env.pop("RELEASE_REPO", None)
            return subprocess.run(["bash", str(guard)], cwd=root, env=env,
                                  capture_output=True, text=True, timeout=10)

    def assert_synced(self, result):
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("version sync OK", result.stdout)
        self.assertIn("versionCode 90, versionName 2.9", result.stdout)
        self.assertEqual("", result.stderr)

    def assert_rejected(self, result, *reasons):
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("Release metadata is inconsistent.", result.stderr)
        self.assertNotIn("version sync OK", result.stdout)
        for reason in reasons:
            self.assertIn(reason, result.stderr)

    def test_commented_previous_pair_does_not_reject_matching_current_manifest(self):
        result = self.run_guard('        // versionCode = 80\n        // versionName = "1.8"\n'
                                '        versionCode = 90\n        versionName = "2.9"\n')
        self.assert_synced(result)

    def test_commented_previous_pair_cannot_accept_a_stale_manifest(self):
        result = self.run_guard('        // versionCode = 80\n        // versionName = "1.8"\n'
                                '        versionCode = 90\n        versionName = "2.9"\n',
                                code=80, name="1.8")
        self.assert_rejected(result, "versionCode mismatch", "gradle=90, version.json=80",
                             "versionName mismatch", "gradle=2.9, version.json=1.8")

    def test_commented_previous_code_does_not_override_current_code(self):
        result = self.run_guard('        // versionCode = 80\n        versionCode = 90\n'
                                '        versionName = "2.9"\n')
        self.assert_synced(result)

    def test_commented_previous_name_does_not_override_current_name(self):
        result = self.run_guard('        // versionName = "1.8"\n        versionCode = 90\n'
                                '        versionName = "2.9"\n')
        self.assert_synced(result)

    def test_indented_matching_declarations_still_pass(self):
        result = self.run_guard('        versionCode = 90\n        versionName = "2.9"\n')
        self.assert_synced(result)

    def test_normal_mismatch_still_rejects_both_fields(self):
        result = self.run_guard('        versionCode = 90\n        versionName = "2.9"\n',
                                code=91, name="2.10")
        self.assert_rejected(result, "versionCode mismatch", "gradle=90, version.json=91",
                             "versionName mismatch", "gradle=2.9, version.json=2.10")

    def test_wrong_stable_url_still_rejects(self):
        result = self.run_guard('        versionCode = 90\n        versionName = "2.9"\n',
                                url="https://example.test/immortal.apk")
        self.assert_rejected(result, "version.json apkUrl is 'https://example.test/immortal.apk'",
                             "expected '" + STABLE_URL + "'")

    def test_blank_notes_still_reject(self):
        result = self.run_guard('        versionCode = 90\n        versionName = "2.9"\n', notes=" \t \n")
        self.assert_rejected(result, "version.json notes is empty")


if __name__ == "__main__":
    unittest.main()

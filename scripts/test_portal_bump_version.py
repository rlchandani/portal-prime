"""Exercise tools/scripts/bump-version.sh in isolated copies of the release metadata."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[1]
BUMP = REPO / "tools" / "scripts" / "bump-version.sh"
GUARD = REPO / "scripts" / "check-version-sync.sh"
STABLE_URL = "https://github.com/rlchandani/portal-prime/releases/latest/download/hearthpane.apk"
GRADLE = ("android {\n    defaultConfig {\n        minSdk = 24\n"
          "        versionCode = 90\n        versionName = \"2.9\"\n    }\n}\n")
MANIFEST = {"versionCode": 90, "versionName": "2.9", "apkUrl": STABLE_URL, "notes": "Old."}


class PortalBumpVersionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="portal-bump-version-test-")
        self.root = Path(self.temporary.name)
        for source, relative in ((BUMP, "tools/scripts/bump-version.sh"),
                                 (GUARD, "scripts/check-version-sync.sh")):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        (self.root / "app").mkdir()
        self.gradle = self.root / "app" / "build.gradle.kts"
        self.manifest = self.root / "version.json"
        self.gradle.write_text(GRADLE, encoding="utf-8")
        self.manifest.write_text(json.dumps(MANIFEST, indent=2) + "\n", encoding="utf-8")

    def tearDown(self):
        self.temporary.cleanup()

    def bump(self, *args):
        env = {k: v for k, v in os.environ.items() if k not in ("RELEASE_REPO", "RELEASE_APK_ASSET")}
        return subprocess.run(["bash", str(self.root / "tools/scripts/bump-version.sh"), *args],
                              cwd=self.root, env=env, capture_output=True, text=True, timeout=10)

    def assert_unchanged(self, result):
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(GRADLE, self.gradle.read_text(encoding="utf-8"))
        self.assertEqual(MANIFEST, json.loads(self.manifest.read_text(encoding="utf-8")))

    def test_bumps_gradle_and_manifest_together(self):
        result = self.bump("2.10", "  Faster   photos.\n")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        gradle = self.gradle.read_text(encoding="utf-8")
        self.assertIn("        versionCode = 91\n", gradle)
        self.assertIn('        versionName = "2.10"\n', gradle)
        self.assertIn("        minSdk = 24\n", gradle)
        self.assertEqual({"versionCode": 91, "versionName": "2.10", "apkUrl": STABLE_URL,
                          "notes": "Faster photos."},
                         json.loads(self.manifest.read_text(encoding="utf-8")))
        self.assertIn("version sync OK", result.stdout)

    def test_repairs_a_stale_manifest_url(self):
        self.manifest.write_text(json.dumps(dict(MANIFEST, apkUrl="https://x/immortal.apk")),
                                 encoding="utf-8")
        result = self.bump("2.10", "Notes.")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(STABLE_URL, json.loads(self.manifest.read_text())["apkUrl"])

    def test_requires_both_arguments(self):
        self.assert_unchanged(self.bump())
        self.assert_unchanged(self.bump("2.10"))
        self.assert_unchanged(self.bump("2.10", " \t "))

    def test_rejects_malformed_or_unchanged_version_name(self):
        self.assert_unchanged(self.bump("v2.10", "Notes."))
        self.assert_unchanged(self.bump("2", "Notes."))
        result = self.bump("2.9", "Notes.")
        self.assert_unchanged(result)
        self.assertIn("unchanged", result.stderr)

    def test_rejects_notes_longer_than_the_ota_limit(self):
        self.assert_unchanged(self.bump("2.10", "x" * 281))

    def test_rejects_missing_gradle_declarations(self):
        self.gradle.write_text("android {}\n", encoding="utf-8")
        result = self.bump("2.10", "Notes.")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(MANIFEST, json.loads(self.manifest.read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main()

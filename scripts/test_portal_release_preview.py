# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository root.
"""Actual CLI policy regressions; external tools use the existing fixtures."""
import json
import unittest

from test_portal_release_readiness import ReadinessFixture


class ReleasePreviewTest(unittest.TestCase):
    def setUp(self):
        self.fixture = ReadinessFixture()
        self.addCleanup(self.fixture.close)

    def older_manifest(self):
        f = self.fixture
        data = json.loads((f.repo / "version.json").read_text())
        data.update(versionCode=61, versionName="1.67")
        f.write("version.json", json.dumps(data))
        return (f.repo / "version.json").read_bytes()

    def test_explicit_preview_accepts_version_divergence_and_preserves_manifest(self):
        f = self.fixture
        before = self.older_manifest()
        # The strict manifest gate must never be invoked for an explicit preview.
        f.write("scripts/check-version-sync.sh", "#!/bin/bash\nexit 19\n")
        result = f.run("--local-preview")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        r = f.receipt()
        self.assertEqual("ready", r["status"])
        self.assertEqual("local-preview", r["versionPolicy"])
        self.assertEqual({"versionCode": 61, "versionName": "1.67"},
                         r["releaseManifestVersion"])
        self.assertEqual(62, r["artifact"]["versionCode"])
        self.assertFalse(r["published"])
        self.assertFalse(r["deployed"])
        self.assertEqual(before, (f.repo / "version.json").read_bytes())
        self.assertFalse(any(c["name"] == "version-sync" for c in r["checks"]))

    def test_default_policy_still_rejects_version_divergence_before_build(self):
        f = self.fixture
        self.older_manifest()
        result = f.run()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("failed", f.receipt()["status"])
        self.assertIn("disagree", f.receipt()["error"])
        self.assertFalse((f.repo / "commands.log").exists())

    def test_release_variant_cannot_waive_manifest_gate(self):
        f = self.fixture
        result = f.run("--local-preview", "--variant", "release")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("failed", f.receipt()["status"])
        self.assertIn("release", f.receipt()["error"].lower())
        self.assertFalse((f.output / "candidate.apk").exists())


if __name__ == "__main__":
    unittest.main()

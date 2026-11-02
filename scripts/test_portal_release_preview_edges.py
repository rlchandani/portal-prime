# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository root.
"""Preview must preserve negative readiness gates through the actual CLI."""
import json
import unittest

from test_portal_release_readiness import ReadinessFixture


class ReleasePreviewEdgesTest(unittest.TestCase):
    def fixture(self):
        fixture = ReadinessFixture()
        self.addCleanup(fixture.close)
        manifest = json.loads((fixture.repo / "version.json").read_text())
        manifest.update(versionCode=61, versionName="1.67")
        fixture.write("version.json", json.dumps(manifest))
        return fixture

    def assert_blocked(self, fixture, result, error_pattern, policy="local-preview"):
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue((fixture.output / "receipt.json").is_file(), result.stderr)
        receipt = fixture.receipt()
        self.assertEqual("failed", receipt["status"])
        self.assertEqual(policy, receipt["versionPolicy"])
        self.assertFalse(receipt["published"])
        self.assertFalse(receipt["deployed"])
        self.assertIn("error", receipt, result.stderr)
        self.assertRegex(receipt["error"].lower(), error_pattern)
        self.assertNotIn("Traceback", result.stderr)
        self.assertNotIn("artifact", receipt)
        self.assertFalse((fixture.output / "candidate.apk").exists())
        self.assertFalse((fixture.output / ".candidate.pending.apk").exists())
        return receipt

    def test_preview_rejects_stale_apk_code_name_and_wrong_package(self):
        for field, value, error in (
            ("code", 61, r"versioncode|stale|inconsistent"),
            ("name", "1.67", r"versionname|stale|inconsistent"),
            ("package", "com.example.other", r"package"),
        ):
            with self.subTest(field=field):
                f = self.fixture()
                f.write("candidate.json", json.dumps(dict(f.metadata, **{field: value})))
                receipt = self.assert_blocked(f, f.run("--local-preview"), error)
                self.assertEqual(0, next(check["exitCode"] for check in receipt["checks"]
                                         if check["name"] == "android-gates"))

    def test_preview_rejects_candidate_signed_by_another_key(self):
        f = self.fixture()
        f.write("candidate.json", json.dumps(dict(f.metadata, signer="b" * 64)))
        receipt = self.assert_blocked(f, f.run("--local-preview"), r"sign|certificate")
        self.assertEqual(["a" * 64], receipt["baseline"]["signerSha256"])
        self.assertTrue(any(check["name"] == "candidate-signature"
                            for check in receipt["checks"]))

    def test_preview_rejects_malformed_or_nonpositive_gradle_version(self):
        for declarations in (
            'versionCode = 0\nversionName = "1.68"\n',
            'versionCode = -1\nversionName = "1.68"\n',
            'versionCode = "62"\nversionName = "1.68"\n',
            'versionCode = 62\nversionName = ""\n',
            'versionCode = 62\nversionName = "   "\n',
            'versionCode = 62\nversionName = 168\n',
            'versionCode = 62\nversionCode = 63\nversionName = "1.68"\n',
        ):
            with self.subTest(declarations=declarations):
                f = self.fixture()
                f.write("app/build.gradle.kts", declarations)
                self.assert_blocked(f, f.run("--local-preview"), r"gradle|version|metadata")
                self.assertFalse((f.repo / "commands.log").exists(),
                                 "Invalid versions must block before external gates")

    def test_preview_rejects_malformed_manifest_and_invalid_manifest_versions(self):
        for manifest in (
            "{",
            "[]",
            "null",
            "{}",
            '{"versionCode": true, "versionName": "1.67"}',
            '{"versionCode": "61", "versionName": "1.67"}',
            '{"versionCode": 0, "versionName": "1.67"}',
            '{"versionCode": -1, "versionName": "1.67"}',
            '{"versionCode": 61, "versionName": 167}',
            '{"versionCode": 61, "versionName": "   "}',
        ):
            with self.subTest(manifest=manifest):
                f = self.fixture()
                f.write("version.json", manifest)
                self.assert_blocked(f, f.run("--local-preview"), r"manifest|version|metadata")
                self.assertFalse((f.repo / "commands.log").exists(),
                                 "Invalid manifests must block before external gates")

    def test_preview_rejects_source_changes_during_successful_build(self):
        f = self.fixture()
        source = f.write("app/src/main/preview_source.kt", "// original source\n")
        gradle = f.repo / "gradlew"
        gradle.write_text(gradle.read_text() + """
(r/'app/src/main/preview_source.kt').write_text('// changed during checks\\n')
""")
        receipt = self.assert_blocked(f, f.run("--local-preview"), r"source|input|chang")
        self.assertEqual("// changed during checks\n", source.read_text())
        self.assertEqual(0, next(check["exitCode"] for check in receipt["checks"]
                                 if check["name"] == "android-gates"))

    def test_preview_remains_blocked_when_build_fails(self):
        f = self.fixture()
        f.write("fail-gradle", "")
        receipt = self.assert_blocked(f, f.run("--local-preview"), r"android.gates|build")
        self.assertEqual(7, next(check["exitCode"] for check in receipt["checks"]
                                 if check["name"] == "android-gates"))
        self.assertFalse(any(check["name"] == "candidate-metadata"
                             for check in receipt["checks"]))

    def test_default_matching_versions_still_runs_failing_version_sync_gate(self):
        f = self.fixture()
        manifest = json.loads((f.repo / "version.json").read_text())
        manifest.update(versionCode=62, versionName="1.68")
        f.write("version.json", json.dumps(manifest))
        f.write("scripts/check-version-sync.sh", "#!/bin/bash\nexit 23\n")
        receipt = self.assert_blocked(f, f.run(), r"version.sync", policy="release-manifest")
        gate = next(check for check in receipt["checks"] if check["name"] == "version-sync")
        self.assertEqual(["bash", "scripts/check-version-sync.sh"], gate["argv"])
        self.assertEqual(23, gate["exitCode"])
        self.assertFalse((f.repo / "commands.log").exists(),
                         "The strict manifest gate must stop readiness before build")


if __name__ == "__main__":
    unittest.main()

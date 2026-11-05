# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository root.
"""Actual CLI inspection of supplied APKs; SDK executables are isolated fixtures."""
import hashlib
import json
import unittest
import zipfile

from test_portal_release_readiness import ReadinessFixture


class ArtifactInspectionTest(unittest.TestCase):
    def setUp(self):
        self.f = ReadinessFixture()
        self.addCleanup(self.f.close)
        self.candidate = self.f.root / "frozen.apk"
        self.assets = {"scene.js": b"current", "fonts/sample.bin": b"\0\xff"}
        for name, data in self.assets.items():
            path = self.f.repo / "app/src/main/assets" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.package()
        for name in ("aapt", "apksigner"):
            path = self.f.sdk / "build-tools/36.0.0" / name
            path.write_text(path.read_text().replace(
                "d=json.loads(pathlib.Path(sys.argv[-1]).read_text())",
                "import zipfile\np=pathlib.Path(sys.argv[-1])\n"
                "d=json.loads(zipfile.ZipFile(p).read('metadata.json')) "
                "if zipfile.is_zipfile(p) else json.loads(p.read_text())"))

    def package(self, metadata=None, assets=None):
        with zipfile.ZipFile(self.candidate, "w") as archive:
            archive.writestr("metadata.json", json.dumps(metadata or self.f.metadata))
            for name, data in (self.assets if assets is None else assets).items():
                archive.writestr("assets/" + name, data)

    def inspect(self, *extra):
        return self.f.run("--inspect-apk", str(self.candidate), *extra)

    def failed(self, expected, *extra):
        result = self.inspect(*extra)
        self.assertNotEqual(0, result.returncode)
        self.assertTrue((self.f.output / "receipt.json").is_file(), result.stderr)
        receipt = self.f.receipt()
        self.assertEqual("local-artifact-inspection", receipt["scope"])
        self.assertEqual("failed", receipt["status"])
        self.assertIn(expected, receipt["error"].lower())
        self.assertNotIn("artifact", receipt)
        return receipt

    def test_valid_supplied_apk_checked_without_build_tests_or_input_writes(self):
        f = self.f
        prior = f.write("app/build/test-results/testDebugUnitTest/TEST-old.xml", "old evidence")
        built = f.write("app/build/outputs/apk/performance/app-performance.apk", "old build")
        watched = (self.candidate, f.baseline, prior, built, f.repo / "version.json")
        before = [(p.read_bytes(), p.stat().st_mtime_ns) for p in watched]
        f.executable(f.tools / "node", "raise SystemExit('Node must not run')\n")
        f.executable(f.repo / "gradlew", "raise SystemExit('Gradle must not run')\n")
        result = self.inspect()
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        r = f.receipt()
        self.assertEqual(("local-artifact-inspection", "checked"), (r["scope"], r["status"]))
        self.assertFalse(r["published"])
        self.assertFalse(r["deployed"])
        self.assertEqual(str(self.candidate.resolve()), r["artifact"]["file"])
        self.assertEqual(hashlib.sha256(self.candidate.read_bytes()).hexdigest(),
                         r["artifact"]["sha256"])
        self.assertEqual(self.candidate.stat().st_size, r["artifact"]["bytes"])
        self.assertEqual(("com.immortal.launcher.debug", 62, "1.68", ["a" * 64]),
                         tuple(r["artifact"][k] for k in
                               ("package", "versionCode", "versionName", "signerSha256")))
        self.assertEqual(2, r["sourceAssets"]["fileCount"])
        self.assertEqual(before, [(p.read_bytes(), p.stat().st_mtime_ns) for p in watched])
        for key in ("jvm", "pythonTests", "javascript", "archivedPriorJvmReports"):
            self.assertNotIn(key, r)
        self.assertFalse((f.repo / "commands.log").exists())
        self.assertFalse((f.output / "candidate.apk").exists())

    def test_wrong_candidate_package(self):
        self.package(dict(self.f.metadata, package="com.example.wrong"))
        self.failed("package")

    def test_wrong_baseline_package(self):
        self.f.baseline.write_text(json.dumps(dict(self.f.metadata, package="com.example.wrong")))
        self.failed("baseline package")

    def test_stale_candidate_version(self):
        self.package(dict(self.f.metadata, code=61))
        self.failed("versioncode")

    def test_different_signer(self):
        self.package(dict(self.f.metadata, signer="b" * 64))
        self.failed("signing")

    def test_stale_same_size_asset(self):
        self.package(assets=dict(self.assets, **{"scene.js": b"outdate"}))
        self.failed("asset bytes")

    def test_candidate_mutation_during_metadata(self):
        tool = self.f.sdk / "build-tools/36.0.0/aapt"
        tool.write_text(tool.read_text() +
                        "\nif p.name=='frozen.apk':\n with p.open('ab') as f:f.write(b' ')\n")
        self.failed("candidate apk changed")

    def test_baseline_mutation_during_signature(self):
        tool = self.f.sdk / "build-tools/36.0.0/apksigner"
        tool.write_text(tool.read_text() +
                        "\nif p.name=='accepted.apk':\n with p.open('ab') as f:f.write(b' ')\n")
        self.failed("baseline apk changed")

    def test_source_mutation_during_inspection(self):
        tool = self.f.sdk / "build-tools/36.0.0/apksigner"
        tool.write_text(tool.read_text() +
                        "\nif p.name=='frozen.apk':\n"
                        " pathlib.Path('scripts/test_portal_fixture.py').write_text('changed')\n")
        self.failed("source/build inputs changed")

    def test_local_preview_allows_unsynchronized_manifest(self):
        self.f.write("version.json", '{"versionCode":1,"versionName":"published"}')
        result = self.inspect("--local-preview")
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        self.assertEqual("local-preview", self.f.receipt()["versionPolicy"])
        self.assertEqual("checked", self.f.receipt()["status"])

    def test_release_manifest_policy_is_retained(self):
        self.f.write("version.json", '{"versionCode":1,"versionName":"published"}')
        self.failed("versions disagree")

    def test_release_variant_cannot_use_local_preview(self):
        self.failed("local preview requires", "--variant", "release", "--local-preview")

    def test_existing_output_is_preserved(self):
        self.f.output.mkdir()
        saved = self.f.output / "receipt.json"
        saved.write_text("existing")
        result = self.inspect()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("existing", saved.read_text())

    def test_javascript_mode_is_mutually_exclusive(self):
        result = self.inspect("--javascript-only")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.f.output.exists())


if __name__ == "__main__":
    unittest.main()

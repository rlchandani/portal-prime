# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository root.
"""Offline fixture packaging contracts; generated files and inert command doubles only."""
import hashlib
import math
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

import portal_native_fixture as fixture


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class NativeFixtureFiles:
    def __init__(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="native-fixture-test-")
        self.root = Path(self.temporary.name)
        self.sources = [self.root / "src/Main.java", self.root / "src/pkg/Helper.java"]
        for path in self.sources:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("// generated source\n", encoding="utf-8")
        self.manifest = self.root / "AndroidManifest.xml"
        self.manifest.write_text(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="example.fixture"><application/></manifest>', encoding="utf-8")
        self.assets = self.root / "assets"
        (self.assets / "nested").mkdir(parents=True)
        (self.assets / "payload.txt").write_bytes(b"frozen payload\n")
        (self.assets / "nested/second.txt").write_bytes(b"nested payload\n")
        self.source_apk = self.root / "source-debug.apk"
        with zipfile.ZipFile(self.source_apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"generated source manifest")
            archive.writestr("classes.dex", b"generated source dex")
        self.android_jar = self.root / "android.jar"
        self.android_jar.write_bytes(b"generated classpath boundary")
        self.build_tools = self.root / "build-tools"
        self.build_tools.mkdir()
        self.javac = self.root / "javac"
        for tool in [self.javac] + [self.build_tools / name
                                  for name in ("d8", "aapt2", "zipalign")]:
            tool.write_bytes(b"generated executable boundary\n")
            tool.chmod(0o755)
        self.output = self.root / "fixture.apk"

    def close(self):
        self.temporary.cleanup()

    def arguments(self):
        return dict(sources=self.sources, manifest=self.manifest, assets=self.assets,
                    source_apk=self.source_apk, android_jar=self.android_jar,
                    build_tools=self.build_tools, javac=self.javac, output=self.output,
                    seconds=30)


class FakeCommands:
    """Produce actual class/DEX/ZIP files at command-requested destinations."""
    def __init__(self):
        self.calls = []
        self.fail_stage = None
        self.omit_stage = None
        self.raise_stage = None
        self.exception = None
        self.before = None
        self.after = None
        self.linked_manifest = None

    def __call__(self, command, **kwargs):
        command = [str(value) for value in command]
        stage = Path(command[0]).name
        self.calls.append((stage, command, kwargs))
        if self.before:
            self.before(stage, command)
        if stage == self.raise_stage:
            raise self.exception
        if stage == self.fail_stage:
            return subprocess.CompletedProcess(command, 7, stdout="", stderr="generated failure")
        if stage != self.omit_stage:
            if stage == "javac":
                destination = Path(command[command.index("-d") + 1])
                destination.mkdir(parents=True, exist_ok=True)
                for relative in ("Main.class", "Main$Nested.class", "pkg/Helper.class"):
                    path = destination / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(b"generated class")
            elif stage == "d8":
                destination = Path(command[command.index("--output") + 1])
                destination.mkdir(parents=True, exist_ok=True)
                (destination / "classes.dex").write_bytes(b"generated fixture dex")
            elif stage == "aapt2":
                output = Path(command[command.index("-o") + 1])
                assets = Path(command[command.index("-A") + 1])
                self.linked_manifest = Path(
                    command[command.index("--manifest") + 1]).read_bytes()
                with zipfile.ZipFile(output, "w") as archive:
                    archive.writestr("AndroidManifest.xml", b"generated compiled manifest")
                    for path in sorted(assets.rglob("*")):
                        if path.is_file():
                            archive.write(path, "assets/" + path.relative_to(assets).as_posix())
            elif stage == "zipalign":
                shutil.copyfile(command[-2], command[-1])
            else:
                raise AssertionError("Unexpected command: " + repr(command))
        if self.after:
            self.after(stage, command)
        return subprocess.CompletedProcess(command, 0, stdout="", stderr="")


class NativeFixtureTest(unittest.TestCase):
    def setUp(self):
        self.files = NativeFixtureFiles()
        self.addCleanup(self.files.close)
        self.runner = FakeCommands()
        self.stages = []
        # Observe owned staging lifetimes without requiring a particular internal layout.
        actual_temporary_directory = tempfile.TemporaryDirectory

        def temporary_directory(*args, **kwargs):
            directory = actual_temporary_directory(*args, **kwargs)
            self.stages.append(Path(directory.name))
            return directory

        self.addCleanup(mock.patch.stopall)
        mock.patch.object(fixture.tempfile, "TemporaryDirectory",
                          side_effect=temporary_directory).start()
        mock.patch.object(fixture.subprocess, "run", side_effect=self.runner).start()

    def build(self, **changes):
        arguments = self.files.arguments()
        arguments.update(changes)
        return fixture.build_fixture(**arguments)

    def assert_retired(self):
        self.assertTrue(self.stages, "A build owns isolated temporary staging")
        for directory in self.stages:
            self.assertFalse(directory.exists(), "Owned staging survived: " + str(directory))

    def assert_unpublished(self):
        self.assertFalse(self.files.output.exists())
        self.assert_retired()

    def test_success_uses_classpath_all_classes_and_exact_payload_receipt(self):
        f = self.files
        original_apk = f.source_apk.read_bytes()
        original_assets = {p.relative_to(f.assets).as_posix(): p.read_bytes()
                           for p in f.assets.rglob("*") if p.is_file()}
        receipt = self.build()
        self.assertEqual(["javac", "d8", "aapt2", "zipalign"],
                         [stage for stage, _, _ in self.runner.calls])
        compile_command = self.runner.calls[0][1]
        self.assertIn("-classpath", compile_command)
        self.assertEqual(f.android_jar.resolve(),
                         Path(compile_command[compile_command.index("-classpath") + 1]).resolve())
        self.assertNotIn("-bootclasspath", compile_command)
        for option in ("-source", "-target"):
            self.assertEqual("8", compile_command[compile_command.index(option) + 1])
        self.assertEqual({"Main.java", "Helper.java"},
                         {Path(arg).name for arg in compile_command if arg.endswith(".java")})
        d8 = self.runner.calls[1][1]
        self.assertEqual(f.android_jar.resolve(), Path(d8[d8.index("--lib") + 1]).resolve())
        self.assertEqual("29", d8[d8.index("--min-api") + 1])
        self.assertEqual({"Main.class", "Main$Nested.class", "Helper.class"},
                         {Path(arg).name for arg in d8 if arg.endswith(".class")})
        aapt = self.runner.calls[2][1]
        self.assertEqual("link", aapt[1])
        self.assertEqual(f.android_jar.resolve(), Path(aapt[aapt.index("-I") + 1]).resolve())
        self.assertEqual(f.manifest.read_bytes(), self.runner.linked_manifest)
        self.assertEqual(["-f", "4"], self.runner.calls[3][1][1:3])
        with zipfile.ZipFile(f.output) as archive:
            self.assertEqual(len(archive.namelist()), len(set(archive.namelist())))
            self.assertEqual(b"generated fixture dex", archive.read("classes.dex"))
            self.assertEqual(original_apk, archive.read("assets/source.apk"))
            for relative, data in original_assets.items():
                self.assertEqual(data, archive.read("assets/" + relative))
        self.assertEqual(f.output.resolve(), Path(receipt["output"]).resolve())
        self.assertEqual(hashlib.sha256(original_apk).hexdigest(), receipt["sourceApkSha256"])
        self.assertEqual(sha256(f.output), receipt["apkSha256"])
        self.assertEqual(f.output.stat().st_size, receipt["bytes"])
        self.assertIsInstance(receipt["checks"], list)
        self.assertTrue(receipt["checks"])
        self.assertEqual(original_apk, f.source_apk.read_bytes())
        self.assertEqual(original_assets, {p.relative_to(f.assets).as_posix(): p.read_bytes()
                                         for p in f.assets.rglob("*") if p.is_file()})
        self.assert_retired()

    def test_assets_and_embedded_source_are_frozen_before_commands(self):
        f = self.files
        original = f.source_apk.read_bytes()

        def mutate(stage, _command):
            if stage == "javac":
                f.source_apk.write_bytes(b"later caller replacement")
                (f.assets / "payload.txt").write_bytes(b"later caller asset")
                (f.assets / "nested/second.txt").unlink()

        self.runner.before = mutate
        receipt = self.build()
        with zipfile.ZipFile(f.output) as archive:
            self.assertEqual(original, archive.read("assets/source.apk"))
            self.assertEqual(b"frozen payload\n", archive.read("assets/payload.txt"))
            self.assertEqual(b"nested payload\n", archive.read("assets/nested/second.txt"))
        self.assertEqual(hashlib.sha256(original).hexdigest(), receipt["sourceApkSha256"])
        self.assert_retired()

    def test_each_nonzero_stage_stops_before_later_commands_and_publication(self):
        stages = ("javac", "d8", "aapt2", "zipalign")
        for index, stage in enumerate(stages):
            with self.subTest(stage=stage):
                self.runner.calls.clear()
                self.runner.fail_stage = stage
                with self.assertRaises(RuntimeError):
                    self.build()
                self.assertEqual(list(stages[:index + 1]),
                                 [name for name, _, _ in self.runner.calls])
                self.assert_unpublished()

    def test_missing_prerequisites_fail_before_any_command(self):
        f = self.files
        paths = [f.sources[0], f.manifest, f.assets, f.source_apk, f.android_jar,
                 f.javac, f.build_tools / "d8", f.build_tools / "aapt2",
                 f.build_tools / "zipalign"]
        for path in paths:
            with self.subTest(path=path.name):
                held = path.with_name(path.name + ".held")
                path.rename(held)
                try:
                    with self.assertRaises(ValueError):
                        self.build()
                    self.assertEqual([], self.runner.calls)
                    self.assertFalse(f.output.exists())
                finally:
                    held.rename(path)
        with self.assertRaises(ValueError):
            self.build(sources=[])
        self.assertEqual([], self.runner.calls)

    def test_existing_output_and_source_alias_are_never_overwritten(self):
        f = self.files
        sentinel = b"accepted output sentinel"
        f.output.write_bytes(sentinel)
        with self.assertRaises(ValueError):
            self.build()
        self.assertEqual(sentinel, f.output.read_bytes())
        original = f.source_apk.read_bytes()
        with self.assertRaises(ValueError):
            self.build(output=f.source_apk)
        self.assertEqual(original, f.source_apk.read_bytes())
        self.assertEqual([], self.runner.calls)

    def test_preexisting_source_asset_is_rejected_without_mutation(self):
        reserved = self.files.assets / "source.apk"
        reserved.write_bytes(b"caller-owned reserved asset")
        with self.assertRaises(ValueError):
            self.build()
        self.assertEqual(b"caller-owned reserved asset", reserved.read_bytes())
        self.assertEqual([], self.runner.calls)
        self.assertFalse(self.files.output.exists())

    def test_dangling_output_link_is_preserved_without_creating_its_target(self):
        target = self.files.root / "another-owner.apk"
        self.files.output.symlink_to(target)
        with self.assertRaises(ValueError):
            self.build()
        self.assertTrue(self.files.output.is_symlink())
        self.assertFalse(target.exists())
        self.assertEqual([], self.runner.calls)

    def test_invalid_deadline_is_rejected_before_commands(self):
        for seconds in (0, -1, math.nan, math.inf):
            with self.subTest(seconds=seconds):
                with self.assertRaises(ValueError):
                    self.build(seconds=seconds)
        self.assertEqual([], self.runner.calls)
        self.assertFalse(self.files.output.exists())

    def test_process_os_error_and_timeout_retire_owned_staging(self):
        for error in (OSError("generated execution failure"),
                      subprocess.TimeoutExpired("generated d8", 30)):
            with self.subTest(error=type(error).__name__):
                self.runner.calls.clear()
                self.runner.raise_stage = "d8"
                self.runner.exception = error
                with self.assertRaises(RuntimeError):
                    self.build()
                self.assertEqual(["javac", "d8"], [s for s, _, _ in self.runner.calls])
                self.assert_unpublished()

    def test_success_exit_without_required_stage_product_cannot_publish(self):
        for stage in ("javac", "d8", "aapt2", "zipalign"):
            with self.subTest(stage=stage):
                self.runner.calls.clear()
                self.runner.omit_stage = stage
                with self.assertRaises(RuntimeError):
                    self.build()
                self.assert_unpublished()

    def test_output_created_during_build_is_preserved(self):
        sentinel = b"another owner published first"

        def late_output(stage, _command):
            if stage == "zipalign":
                self.files.output.write_bytes(sentinel)

        self.runner.after = late_output
        with self.assertRaises((ValueError, RuntimeError)):
            self.build()
        self.assertEqual(sentinel, self.files.output.read_bytes())
        self.assert_retired()


if __name__ == "__main__":
    unittest.main()

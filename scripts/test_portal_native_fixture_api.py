# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository root.
"""Public native-fixture API-level contracts using generated files and inert commands."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import sys
import unittest
from unittest import mock
import zipfile

import portal_native_fixture as fixture
import test_portal_native_fixture as existing


class NativeFixtureApiTest(unittest.TestCase):
    def setUp(self):
        self.files = existing.NativeFixtureFiles()
        self.addCleanup(self.files.close)
        self.runner = existing.FakeCommands()
        patch = mock.patch.object(fixture.subprocess, "run", side_effect=self.runner)
        patch.start()
        self.addCleanup(patch.stop)

    def build(self, **changes):
        arguments = self.files.arguments()
        arguments.update(changes)
        return fixture.build_fixture(**arguments)

    def build_with_api(self, min_api):
        try:
            return self.build(min_api=min_api)
        except TypeError as error:
            self.fail("The public build_fixture API must accept min_api: " + str(error))

    def d8_api(self):
        calls = [command for stage, command, _ in self.runner.calls if stage == "d8"]
        self.assertEqual(1, len(calls), "A successful build invokes D8 once")
        command = calls[0]
        self.assertIn("--min-api", command)
        return command[command.index("--min-api") + 1]

    def assert_payload(self, receipt):
        original = self.files.source_apk.read_bytes()
        with zipfile.ZipFile(self.files.output) as archive:
            self.assertEqual(original, archive.read("assets/source.apk"))
            self.assertEqual(b"generated fixture dex", archive.read("classes.dex"))
            self.assertEqual(len(archive.namelist()), len(set(archive.namelist())))
        self.assertEqual(hashlib.sha256(original).hexdigest(), receipt["sourceApkSha256"])
        self.assertEqual(hashlib.sha256(self.files.output.read_bytes()).hexdigest(),
                         receipt["apkSha256"])
        self.assertEqual(self.files.output.stat().st_size, receipt["bytes"])
        self.assertEqual(self.files.output.resolve(), Path(receipt["output"]).resolve())

    def test_api28_selects_d8_argument_and_receipt_with_exact_embedded_source(self):
        receipt = self.build_with_api(28)
        self.assertEqual("28", self.d8_api())
        self.assertEqual(28, receipt.get("minApi"))
        self.assert_payload(receipt)

    def test_api24_selects_d8_argument_and_receipt_with_exact_embedded_source(self):
        receipt = self.build_with_api(24)
        self.assertEqual("24", self.d8_api())
        self.assertEqual(24, receipt.get("minApi"))
        self.assert_payload(receipt)

    def test_cli_min_api28_returns_json_and_passes_the_d8_argument(self):
        f = self.files
        argv = ["portal_native_fixture.py"]
        for source in f.sources:
            argv.extend(["--source", str(source)])
        for option, value in (
                ("manifest", f.manifest), ("assets", f.assets),
                ("source-apk", f.source_apk), ("android-jar", f.android_jar),
                ("build-tools", f.build_tools), ("javac", f.javac),
                ("output", f.output)):
            argv.extend(["--" + option, str(value)])
        argv.extend(["--seconds", "30", "--min-api", "28"])
        stdout, stderr = io.StringIO(), io.StringIO()
        try:
            with mock.patch.object(sys, "argv", argv), contextlib.redirect_stdout(stdout), \
                    contextlib.redirect_stderr(stderr):
                status = fixture.main()
        except SystemExit as error:
            self.fail("The public CLI must accept --min-api 28, not exit " + str(error.code))
        self.assertEqual(0, status)
        self.assertEqual("", stderr.getvalue())
        receipt = json.loads(stdout.getvalue())
        self.assertIsInstance(receipt, dict)
        self.assertEqual(28, receipt.get("minApi"))
        self.assertEqual("28", self.d8_api())
        self.assert_payload(receipt)

    def test_default_receipt_records_api29(self):
        receipt = self.build()
        self.assertEqual(29, receipt.get("minApi"))
        self.assert_payload(receipt)

    def test_invalid_api_values_rejected_before_commands_or_output(self):
        for min_api in (True, 28.0, 23, 37):
            with self.subTest(min_api=min_api):
                with self.assertRaises(ValueError):
                    self.build_with_api(min_api)
                self.assertEqual([], self.runner.calls)
                self.assertFalse(self.files.output.exists())

    def test_default_d8_argument_remains_api29(self):
        self.build()
        self.assertEqual("29", self.d8_api())

    def test_preexisting_output_sentinel_is_preserved_without_commands(self):
        sentinel = b"caller-owned accepted fixture"
        self.files.output.write_bytes(sentinel)
        with self.assertRaises(ValueError):
            self.build()
        self.assertEqual(sentinel, self.files.output.read_bytes())
        self.assertEqual([], self.runner.calls)

    def test_invalid_deadline_is_rejected_before_commands(self):
        with self.assertRaises(ValueError):
            self.build(seconds=0)
        self.assertEqual([], self.runner.calls)
        self.assertFalse(self.files.output.exists())


if __name__ == "__main__":
    unittest.main()

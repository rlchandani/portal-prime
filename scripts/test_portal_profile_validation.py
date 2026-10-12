#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
# This source code is licensed under the MIT license found in the LICENSE file.
"""Regression for the tool-directory boundary that blocked Clock verification."""
import hashlib
import json
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

from portal_profile_validation import (ValidationError, checked_input, relocated_snapshots,
                                       reserve_workspace, run_tool, validate_profiles)


class ProfileValidationTest(unittest.TestCase):
    def test_workspace_is_a_new_child_of_tool_directory_not_archive(self):
        # Returning the archive path reproduces the actual CLI rejection.
        with tempfile.TemporaryDirectory(dir="/private/tmp") as temp:
            root = Path(temp)
            tool = root / "tool"
            tool.mkdir()
            archive = root / "archive"
            work = reserve_workspace(tool, archive)
            self.assertEqual(tool.resolve(), work.parent.resolve())
            self.assertTrue(work.is_dir())
            self.assertNotEqual(archive, work)
            self.assertFalse(archive.exists())
            self.assertEqual([], list(work.iterdir()))

    def test_workspaces_are_unique_and_keep_existing_tool_evidence(self):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as temp:
            tool = Path(temp) / "tool"
            tool.mkdir()
            existing = tool / "earlier-proof.json"
            existing.write_text('{"accepted": true}')
            first = reserve_workspace(tool, Path(temp) / "archive")
            second = reserve_workspace(tool, Path(temp) / "archive")
            self.assertNotEqual(first, second)
            self.assertEqual('{"accepted": true}', existing.read_text())

    def test_existing_evidence_is_rejected_before_workspace_creation(self):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as temp:
            tool = Path(temp) / "tool"
            tool.mkdir()
            archive = Path(temp) / "archive"
            archive.mkdir()
            sentinel = archive / "previous.json"
            sentinel.write_bytes(b"historical evidence")
            with self.assertRaisesRegex(ValidationError, "never be overwritten"):
                reserve_workspace(tool, archive)
            self.assertEqual(b"historical evidence", sentinel.read_bytes())
            self.assertEqual([], list(tool.iterdir()))

    def test_empty_existing_evidence_is_also_rejected(self):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as temp:
            tool = Path(temp) / "tool"
            tool.mkdir()
            archive = Path(temp) / "archive"
            archive.mkdir()
            with self.assertRaisesRegex(ValidationError, "already exists"):
                reserve_workspace(tool, archive)
            self.assertEqual([], list(tool.iterdir()))

    def test_relative_evidence_path_is_rejected(self):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as temp:
            with self.assertRaisesRegex(ValidationError, "absolute"):
                reserve_workspace(Path(temp), Path("relative-evidence"))

    def test_non_private_tool_directory_is_rejected(self):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as temp:
            with self.assertRaisesRegex(ValidationError, "child of /private/tmp"):
                reserve_workspace(Path(__file__).parent, Path(temp) / "archive")

    def test_missing_tool_directory_is_rejected_without_creation(self):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as temp:
            tool = Path(temp) / "missing"
            with self.assertRaisesRegex(ValidationError, "child of /private/tmp"):
                reserve_workspace(tool, Path(temp) / "archive")
            self.assertFalse(tool.exists())

    def test_wrong_input_hash_is_rejected(self):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as temp:
            source = Path(temp) / "input.apk"
            source.write_bytes(b"frozen APK bytes")
            with self.assertRaisesRegex(ValidationError, "SHA-256 mismatch"):
                checked_input(source, "0" * 64, 64)
            self.assertEqual(b"frozen APK bytes", source.read_bytes())

    def test_profile_magic_and_version_are_exclusive(self):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as temp:
            source = Path(temp) / "reference.prof"
            for data in (b"bad\x00010\x00" + b"x" * 20, b"pro\x00015\x00" + b"x" * 20):
                with self.subTest(data=data[:8]):
                    source.write_bytes(data)
                    with self.assertRaisesRegex(ValidationError, "no bundled-profile fallback"):
                        checked_input(source, hashlib.sha256(data).hexdigest(), 128, profile=True)

    def test_exact_v010_input_is_accepted(self):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as temp:
            source = Path(temp) / "reference.prof"
            data = b"pro\x00010\x00" + b"x" * 20
            source.write_bytes(data)
            self.assertEqual(source.resolve(), checked_input(source, hashlib.sha256(data).hexdigest(),
                                                             128, profile=True))

    def test_input_size_limit_is_enforced(self):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as temp:
            source = Path(temp) / "input.apk"
            data = b"x" * 33
            source.write_bytes(data)
            with self.assertRaisesRegex(ValidationError, "size boundary"):
                checked_input(source, hashlib.sha256(data).hexdigest(), 32)

    def test_bad_deadline_fails_before_creating_evidence(self):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as temp:
            output = Path(temp) / "archive"
            for timeout in (0, -1, float("nan"), float("inf")):
                with self.subTest(timeout=timeout):
                    with self.assertRaisesRegex(ValidationError, "finite and positive"):
                        validate_profiles(Path(temp), Path(temp) / "absent.apk", "0" * 64,
                                          Path(temp) / "absent.prof", "0" * 64, output, timeout)
                    self.assertFalse(output.exists())

    def test_relocation_changes_only_matching_snapshot_metadata(self):
        original = {"sha256": "pinned", "snapshotPath": "/old/input.prof",
                    "nested": [{"snapshotPath": "/old/input.prof"}],
                    "command": ["/old/input.prof"], "other": {"snapshotPath": "/different.prof"}}
        expected = {"sha256": "pinned", "snapshotPath": "/new/input.prof",
                    "nested": [{"snapshotPath": "/new/input.prof"}],
                    "command": ["/old/input.prof"], "other": {"snapshotPath": "/different.prof"}}
        self.assertEqual(expected, relocated_snapshots(original, "/old/input.prof", "/new/input.prof"))
        self.assertEqual("/old/input.prof", original["snapshotPath"])

    def test_expired_deadline_never_launches_command(self):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as temp:
            output = Path(temp)
            marker = output / "should-not-exist"
            command = [sys.executable, "-c", "from pathlib import Path; Path(%r).touch()" % str(marker)]
            with self.assertRaisesRegex(ValidationError, "deadline expired"):
                run_tool(command, output, "expired", time.monotonic() - 1, {0})
            self.assertFalse(marker.exists())
            self.assertEqual([], list(output.iterdir()))

    def test_timeout_kills_decoder_descendants(self):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as temp:
            output = Path(temp)
            marker = output / "descendant-finished"
            child = "import time; from pathlib import Path; time.sleep(0.5); Path(%r).touch()" % str(marker)
            parent = "import subprocess, sys, time; subprocess.Popen([sys.executable, '-c', %r]); time.sleep(10)" % child
            with self.assertRaises(subprocess.TimeoutExpired):
                run_tool([sys.executable, "-c", parent], output, "timeout", time.monotonic() + 0.15, {0})
            time.sleep(0.6)
            self.assertFalse(marker.exists())

    def test_unexpected_tool_exit_cannot_pass(self):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as temp:
            with self.assertRaisesRegex(ValidationError, "exit 2"):
                run_tool([sys.executable, "-c", "raise SystemExit(2)"], Path(temp),
                         "bad-map", time.monotonic() + 3, {0, 1})


if __name__ == "__main__":
    unittest.main()

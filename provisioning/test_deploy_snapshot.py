#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Frozen host regression: actual deploy orchestration, generated APK bytes only."""
from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
SOURCE = Path(os.environ.get(
    "PORTAL_DEPLOY_SOURCE",
    str(Path(__file__).with_name("room-fleet.py")),
))
spec = importlib.util.spec_from_file_location("snapshot_fleet", SOURCE)
fleet = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = fleet
spec.loader.exec_module(fleet)
PACKAGE = "com.immortal.launcher.debug"
SELECTED = b"generated selected APK A"
REPLACEMENT = b"generated replacement APK B"


class DeploySnapshotTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="snapshot-case-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name).resolve()
        self.original = self.root / "selected.apk"
        self.original.write_bytes(SELECTED)
        self.sentinel = self.root / "unrelated.bin"
        self.sentinel.write_bytes(b"unrelated retained bytes")
        self.rooms = [
            fleet.Room(self.root / (name + ".json"), {
                "name": name, "serial": "generated-" + name,
                "ip": "192.0.2.1", "agentPort": 8765,
                "token": "generated-not-an-owner-token",
            }) for name in ("One", "Two")
        ]
        self.calls = []
        self.remote = {}
        self.output = io.StringIO()
        self.exception = None

    def run_deploy(self, *, self_update=True, count=1, before_cli=None,
                   after_first_cli=None, reject_first=False, cli_exception=None):
        """HTTP/CLI boundaries are inert; remote hashes derive from CLI-consumed bytes."""
        info_counts = {}

        def verify(room, *args):
            name = room.data["name"]
            info_counts[name] = info_counts.get(name, 0) + 1
            if info_counts[name] == 1 and before_cli:
                before_cli(room)

        def own(room, *args):
            if not self_update:
                return None
            changed = room.data["name"] in self.remote
            return ("/data/app/" + PACKAGE + ("-new" if changed else "-old") + "/base.apk",
                    101 if changed else 100)

        def invoke(argv, **kwargs):
            self.assertEqual(argv[:3], ["/generated/fleetctl", "dev", "update"])
            self.assertEqual(argv[4:7], ["--package", PACKAGE, "--device"])
            self.assertEqual(kwargs["env"]["IMMORTAL_FLEET_DIR"], str(self.root))
            path = Path(argv[3])
            call = {
                "room": argv[7], "path": path,
                "mode": stat.S_IMODE(path.stat().st_mode) if path.exists() else None,
                "parentMode": stat.S_IMODE(path.parent.stat().st_mode),
            }
            self.calls.append(call)
            if cli_exception is not None:
                raise cli_exception
            # This models fleet.rs cmd_dev_update's real fs::read(apk), including
            # read failure when the original pathname disappeared.
            body = path.read_bytes()
            call["bytes"] = body
            self.remote[argv[7]] = body
            if len(self.calls) == 1 and after_first_cli:
                after_first_cli()
            result = {"ok": False} if reject_first and len(self.calls) == 1 else {
                "ok": True, "result": "installed", "packageName": PACKAGE,
            }
            return subprocess.CompletedProcess(argv, 0, json.dumps(result) + "\n", "")

        def remote_hash(room, route, limit, timeout, deadline=None, **kwargs):
            self.assertTrue(kwargs.get("hashing"), "Unexpected non-hashing fake HTTP call")
            body = self.remote[room.data["name"]]
            return len(body), hashlib.sha256(body).hexdigest()

        with patch.object(fleet, "verify_info", side_effect=verify), \
                patch.object(fleet, "own_apk", side_effect=own), \
                patch.object(fleet, "deploy_get", side_effect=remote_hash), \
                patch.object(fleet.subprocess, "run", side_effect=invoke), \
                redirect_stdout(self.output):
            try:
                self.result = fleet.deploy(
                    "/generated/fleetctl", self.root, self.rooms[:count],
                    self.original, PACKAGE,
                )
            except BaseException as error:
                self.exception = error
        self.assertEqual(self.sentinel.read_bytes(), b"unrelated retained bytes")

    def assert_retired_snapshot(self):
        self.assertTrue(self.calls, "Fixture reached no CLI handoff")
        for call in self.calls:
            self.assertNotEqual(call["path"], self.original,
                                "CLI still receives the caller's mutable pathname")
            self.assertFalse(call["path"].exists(), "Owned APK snapshot leaked after deployment")

    def test_stable_self_update_reads_selected_bytes_and_verifies_actual_hash(self):
        self.run_deploy()
        self.assertIsNone(self.exception)
        self.assertEqual(self.result, 0)
        self.assertEqual([x["bytes"] for x in self.calls], [SELECTED])
        self.assertIn("verified installed APK SHA-256", self.output.getvalue())
        self.assertEqual(self.original.read_bytes(), SELECTED)

    def test_stable_nonself_update_preserves_native_acknowledgment(self):
        self.run_deploy(self_update=False)
        self.assertIsNone(self.exception)
        self.assertEqual(self.result, 0)
        self.assertEqual([x["bytes"] for x in self.calls], [SELECTED])
        self.assertIn("native result: installed", self.output.getvalue())
        self.assertEqual(self.original.read_bytes(), SELECTED)

    def test_original_overwrite_after_hash_cannot_change_cli_bytes(self):
        for self_update in (False, True):
            with self.subTest(self_update=self_update):
                self.original.write_bytes(SELECTED)
                self.calls.clear()
                self.remote.clear()
                self.run_deploy(self_update=self_update,
                                before_cli=lambda _: self.original.write_bytes(REPLACEMENT))
                self.assertIsNone(self.exception)
                self.assertEqual([x["bytes"] for x in self.calls], [SELECTED])
                self.assertEqual(self.result, 0)
                self.assertEqual(self.original.read_bytes(), REPLACEMENT)
                self.assert_retired_snapshot()

    def test_original_deleted_after_hash_does_not_abort_selected_deployment(self):
        for self_update in (False, True):
            with self.subTest(self_update=self_update):
                self.original.write_bytes(SELECTED)
                self.calls.clear()
                self.remote.clear()
                self.run_deploy(self_update=self_update,
                                before_cli=lambda _: self.original.unlink())
                self.assertIsNone(self.exception)
                self.assertEqual(self.result, 0)
                self.assertEqual([x["bytes"] for x in self.calls], [SELECTED])
                self.assertFalse(self.original.exists())
                self.assert_retired_snapshot()

    def test_group_input_changes_between_rooms_cannot_change_second_handoff(self):
        for mutation in ("overwrite", "delete"):
            with self.subTest(mutation=mutation):
                self.original.write_bytes(SELECTED)
                self.calls.clear()
                self.remote.clear()
                change = (lambda: self.original.write_bytes(REPLACEMENT)) if mutation == "overwrite" \
                    else self.original.unlink
                self.run_deploy(count=2, after_first_cli=change)
                self.assertIsNone(self.exception)
                self.assertEqual(self.result, 0)
                self.assertEqual([x.get("bytes") for x in self.calls], [SELECTED, SELECTED])
                self.assertEqual([x["room"] for x in self.calls], ["One", "Two"])
                self.assert_retired_snapshot()

    def test_group_uses_one_private_snapshot_path_and_retires_it(self):
        self.run_deploy(count=2)
        self.assertIsNone(self.exception)
        self.assertEqual(self.result, 0)
        self.assertEqual([x["bytes"] for x in self.calls], [SELECTED, SELECTED])
        self.assertEqual(len({x["path"] for x in self.calls}), 1)
        self.assertEqual([x["mode"] & 0o077 for x in self.calls], [0, 0])
        self.assertEqual([x["parentMode"] & 0o077 for x in self.calls], [0, 0])
        self.assert_retired_snapshot()
        self.assertEqual(self.original.read_bytes(), SELECTED)

    def test_first_room_rejection_continues_and_cleans_group_snapshot(self):
        self.run_deploy(count=2, reject_first=True)
        self.assertIsNone(self.exception)
        self.assertEqual(self.result, 1)
        self.assertEqual([x["room"] for x in self.calls], ["One", "Two"])
        self.assertEqual([x["bytes"] for x in self.calls], [SELECTED, SELECTED])
        self.assertIn("One: deploy failed", self.output.getvalue())
        self.assertIn("Two: deployed", self.output.getvalue())
        self.assert_retired_snapshot()

    def test_cli_exception_retires_snapshot_and_preserves_original(self):
        for failure in (OSError("generated CLI launch failure"),
                        RuntimeError("generated unexpected CLI failure")):
            with self.subTest(failure=type(failure).__name__):
                self.calls.clear()
                self.exception = None
                self.run_deploy(cli_exception=failure)
                if isinstance(failure, OSError):
                    self.assertIsNone(self.exception)
                    self.assertEqual(self.result, 1)
                else:
                    self.assertIs(self.exception, failure)
                self.assert_retired_snapshot()
                self.assertEqual(self.original.read_bytes(), SELECTED)

    def test_keyboard_interrupt_retires_snapshot_and_remains_interrupt(self):
        failure = KeyboardInterrupt()
        self.run_deploy(cli_exception=failure)
        self.assertIs(self.exception, failure)
        self.assert_retired_snapshot()
        self.assertEqual(self.original.read_bytes(), SELECTED)

    def test_empty_and_missing_inputs_never_reach_cli_or_device_preflight(self):
        for missing in (False, True):
            with self.subTest(missing=missing):
                if missing:
                    self.original.unlink(missing_ok=True)
                else:
                    self.original.write_bytes(b"")
                with patch.object(fleet, "verify_info") as verify, \
                        patch.object(fleet.subprocess, "run") as invoke:
                    with self.assertRaises(fleet.FleetError):
                        fleet.deploy("/generated/fleetctl", self.root, self.rooms,
                                     self.original, PACKAGE)
                    verify.assert_not_called()
                    invoke.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)

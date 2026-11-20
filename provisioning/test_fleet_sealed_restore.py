#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Offline restore checks for sealed snapshots, against a loopback stand-in Portal."""

import base64
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import unittest


SOURCE = Path(__file__).resolve().parent
sys.path.insert(0, str(SOURCE))
from sealed_portal_fake import FAKE_ADB, FakeSealedPortal, SECRET_MARKERS  # noqa: E402

SERIAL = "public-sealed-room"
OTHER = "public-other-room"
TOKEN = "synthetic-sealed-restore-token"
FAKE_CLI = r'''#!/usr/bin/env python3
import json, os, sys
with open(os.environ["FAKE_CALLS"], "a") as stream:
    stream.write(json.dumps(sys.argv[1:]) + "\n")
sys.exit(95)
'''


class SealedRestoreTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="public-sealed-restore-")
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        self.kit = self.root / "kit"
        self.kit.mkdir()
        for name in ("fleet-restore.sh", "fleet-backup-data.py", "fleet-backup-seal.py"):
            shutil.copyfile(SOURCE / name, self.kit / name)
        self.cli = self.root / "fleetctl"
        self.cli.write_text(FAKE_CLI)
        self.cli.chmod(0o700)
        self.calls = self.root / "calls.jsonl"
        self.adb = self.root / "adb"
        self.adb.write_text(FAKE_ADB)
        self.adb.chmod(0o700)
        self.tmp = self.root / "tmp"
        self.tmp.mkdir()
        self.key_file = self.root / "mac" / "fleet-backup-key"
        self.registry = self.kit / "fleet"
        self.registry.mkdir(mode=0o700)
        self.portals = {SERIAL: self.portal(SERIAL, "Sealed Room"), OTHER: self.portal(OTHER, "Other Room")}
        self.portals[OTHER].device = "fedcba9876543210fedcba9876543210"
        self.backup = self.snapshot(SERIAL)

    def portal(self, serial, name):
        portal = FakeSealedPortal(TOKEN)
        self.addCleanup(portal.close)
        path = self.registry / (serial + ".json")
        path.write_text(json.dumps({"serial": serial, "name": name, "ip": "127.0.0.1",
                                    "agentPort": portal.port, "token": TOKEN}))
        path.chmod(0o600)
        return portal

    def env(self):
        return {**os.environ, "PYTHONDONTWRITEBYTECODE": "1", "FLEETCTL": str(self.cli),
                "IMMORTAL_FLEET_DIR": str(self.registry), "FAKE_CALLS": str(self.calls),
                "IMMORTAL_BACKUP_KEYSTORE": "file", "IMMORTAL_BACKUP_KEY_FILE": str(self.key_file),
                "TMPDIR": str(self.tmp), "IMMORTAL_BACKUP_ADB": str(self.adb),
                "FAKE_ADB_CALLS": str(self.root / "adb-calls.jsonl"),
                "FAKE_ADB_PORTS": json.dumps({s: p.port for s, p in getattr(self, "portals", {}).items()})}

    def snapshot(self, serial):
        directory = self.kit / "backups" / serial / "snap"
        directory.mkdir(parents=True)
        (directory / "registry.json").write_text(json.dumps({"serial": serial, "token": "old"}))
        result = subprocess.run([sys.executable, str(self.kit / "fleet-backup-seal.py"), "export",
                                 str(self.registry / (serial + ".json")), str(directory)],
                                env=self.env(), capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stderr)
        (directory.parent / "latest").symlink_to("snap")
        self.portals[serial].calls.clear()
        return directory

    def restore(self, target, *args):
        result = subprocess.run(["/bin/bash", str(self.kit / "fleet-restore.sh"), target, *args],
                                env=self.env(), capture_output=True, text=True, timeout=60, cwd=self.kit)
        output = result.stdout + result.stderr
        self.assertNotIn(TOKEN, output)
        for marker in SECRET_MARKERS:
            self.assertNotIn(marker, output)
        self.assertFalse(self.calls.exists(), "a preferences restore needs no other device command")
        self.assertEqual(list(self.tmp.rglob("*")), [], "restore must leave no scratch files")
        return result.returncode, output

    def change_portal(self, serial=SERIAL):
        portal = self.portals[serial]
        portal.prefs = {"immortal_screensaver": {"dark_room_lux": ["i", 12]}}
        portal.files = {}
        return portal

    def test_same_device_round_trip_restores_preferences(self):
        portal = self.change_portal()
        code, output = self.restore(SERIAL, "--prefs")
        self.assertEqual(code, 0, output)
        self.assertEqual(portal.prefs["immortal_screensaver"]["dark_room_lux"], ["i", 7])
        self.assertEqual(portal.prefs["immortal_household"]["k"], ["s", SECRET_MARKERS[0]])
        self.assertIn("files/household_list.json", portal.files)
        self.assertEqual(portal.applied_household, [])
        self.assertIn("restored 4 preference files", output)
        self.assertIn("reboot the Portal", output)
        self.assertEqual(portal.calls, [("/backup/import", "begin"), ("/backup/import", "apply")])

    def test_a_rate_limited_step_is_retried_after_its_wait(self):
        portal = self.change_portal()
        portal.last_call[("/backup/import", "POST", "begin")] = time.monotonic()
        code, output = self.restore(SERIAL, "--prefs")
        self.assertEqual(code, 0, output)
        self.assertEqual(portal.rate_limited, 1)
        self.assertEqual(portal.prefs["immortal_screensaver"]["dark_room_lux"], ["i", 7])

    def test_the_wire_never_carries_the_archive_key_or_plaintext(self):
        portal = self.change_portal()
        seen = []
        original = portal.apply
        portal.apply = lambda body: (seen.append(json.dumps(body)), original(body))[1]
        code, output = self.restore(SERIAL, "--prefs")
        self.assertEqual(code, 0, output)
        sealed = json.loads((self.backup / "sealed-backup.json").read_text())["backup"]
        self.assertNotIn(sealed["wrappedKey"], seen[0], "the data key must be re-wrapped to the one-time key")
        for marker in SECRET_MARKERS:
            self.assertNotIn(marker, seen[0])

    def test_dry_run_opens_locally_and_never_contacts_the_portal(self):
        portal = self.change_portal()
        code, output = self.restore(SERIAL, "--prefs", "--dry-run")
        self.assertEqual(code, 0, output)
        self.assertIn("sealed backup of %s" % SERIAL, output)
        self.assertIn("4 preference files (5 values)", output)
        self.assertEqual(portal.calls, [])
        self.assertEqual(portal.prefs["immortal_screensaver"]["dark_room_lux"], ["i", 12])

    def test_another_portal_needs_cross_device_and_gets_household_only(self):
        other = self.portals[OTHER]
        code, output = self.restore(OTHER, "--prefs", "--backup", str(self.backup))
        self.assertNotEqual(code, 0)
        self.assertIn("needs --cross-device", output)
        self.assertEqual(other.calls, [], "refused before the Portal is contacted")
        before = json.dumps(other.prefs, sort_keys=True)
        code, output = self.restore(OTHER, "--prefs", "--cross-device", "--backup", str(self.backup))
        self.assertEqual(code, 0, output)
        self.assertEqual(json.dumps(other.prefs, sort_keys=True), before)
        self.assertEqual(other.applied_household, [{"screensaver.shuffle": True}])
        self.assertIn("device-local and secret items skipped", output)

    def test_portal_side_device_check_is_reported(self):
        portal = self.change_portal()
        portal.device = "fedcba9876543210fedcba9876543210"  # e.g. reinstalled with another signing key
        code, output = self.restore(SERIAL, "--prefs")
        self.assertNotEqual(code, 0)
        self.assertIn("app identity differs", output)
        self.assertEqual(portal.prefs["immortal_screensaver"]["dark_room_lux"], ["i", 12])

    def test_same_serial_restores_after_an_identity_change_on_this_serial_only(self):
        portal = self.change_portal()
        portal.device = "fedcba9876543210fedcba9876543210"
        code, output = self.restore(SERIAL, "--prefs")
        self.assertIn("--same-serial", output)
        code, output = self.restore(SERIAL, "--prefs", "--same-serial")
        self.assertEqual(code, 0, output)
        self.assertEqual(portal.prefs["immortal_household"]["k"], ["s", SECRET_MARKERS[0]])
        other = self.portals[OTHER]
        code, output = self.restore(OTHER, "--prefs", "--same-serial", "--backup", str(self.backup))
        self.assertNotEqual(code, 0)
        self.assertEqual(other.calls, [], "the serial check runs before the Portal is contacted")

    def test_a_changed_snapshot_fails_before_any_device_call(self):
        path = self.backup / "sealed-backup.json"
        snapshot = json.loads(path.read_text())
        ciphertext = bytearray(base64.b64decode(snapshot["backup"]["ciphertext"]))
        ciphertext[9] ^= 1
        snapshot["backup"]["ciphertext"] = base64.b64encode(bytes(ciphertext)).decode()
        path.write_text(json.dumps(snapshot))
        code, output = self.restore(SERIAL, "--prefs")
        self.assertNotEqual(code, 0)
        self.assertIn("seal does not verify", output)
        self.assertEqual(self.portals[SERIAL].calls, [])

    def test_a_missing_backup_key_fails_before_any_device_call(self):
        self.key_file.unlink()
        code, output = self.restore(SERIAL, "--prefs")
        self.assertNotEqual(code, 0)
        self.assertIn("no fleet backup key", output)
        self.assertEqual(self.portals[SERIAL].calls, [])

    def test_a_symlinked_sealed_file_is_refused(self):
        path = self.backup / "sealed-backup.json"
        moved = self.root / "elsewhere.json"
        path.rename(moved)
        path.symlink_to(moved)
        code, output = self.restore(SERIAL, "--prefs")
        self.assertNotEqual(code, 0)
        self.assertEqual(self.portals[SERIAL].calls, [])

    def test_cross_device_and_config_need_the_right_snapshot(self):
        code, output = self.restore(SERIAL, "--config")
        self.assertEqual(code, 2)
        self.assertIn("restores with --prefs", output)
        legacy = self.kit / "backups" / SERIAL / "legacy"
        (legacy / "shared_prefs").mkdir(parents=True)
        (legacy / "shared_prefs" / "a.xml").write_text("<map/>")
        code, output = self.restore(SERIAL, "--prefs", "--cross-device", "--backup", str(legacy))
        self.assertEqual(code, 2)
        self.assertIn("need --prefs and a sealed snapshot", output)
        code, output = self.restore(SERIAL, "--prefs", "--cross-device", "--same-serial")
        self.assertEqual(code, 2)

    def test_the_snapshot_file_is_private_and_sealed(self):
        path = self.backup / "sealed-backup.json"
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        data = path.read_bytes()
        for marker in SECRET_MARKERS:
            self.assertNotIn(marker.encode(), data)
        self.assertNotIn(TOKEN.encode(), data)


if __name__ == "__main__":
    unittest.main()

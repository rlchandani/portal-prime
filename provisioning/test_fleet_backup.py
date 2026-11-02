#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Offline end-to-end backup checks using /bin/bash and synthetic fleet data."""

import json
import os
from pathlib import Path
import select
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
import unittest


SOURCE = Path(__file__).resolve().parent
PACKAGE = "com.immortal.launcher"
STAMP = "20261102-225614"
XML = b'<?xml version="1.0" encoding="utf-8"?>\n<map><string name="label">Public</string></map>\n'
FAKE_TOKEN = "synthetic-backup-token"

FAKE_CLI = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
with open(os.environ["FAKE_BACKUP_CALLS"], "a") as stream:
    stream.write(json.dumps(args) + "\n")
config = json.loads(pathlib.Path(os.environ["FAKE_BACKUP_CONFIG"]).read_text())
if "--device" not in args or args[0] not in {"info", "apps", "ls", "pull"}:
    sys.exit(95)
serial = args[args.index("--device") + 1]
device = config.get("devices", {}).get(serial, {})
package = os.environ.get("IMMORTAL_BACKUP_PACKAGE", "com.immortal.launcher")
xml = b'<?xml version="1.0" encoding="utf-8"?>\n<map><string name="label">Public</string></map>\n'
defaults = {
    "info": {"ok": True, "model": "Public",
             "app": {"versionName": "Public", "versionCode": 1}},
    "apps": {"ok": True, "apps": [{"packageName": "org.example", "installed": True}]},
    "ls": {"ok": True, "path": "/data/data/" + package + "/shared_prefs",
           "entries": [{"name": "prefs.xml", "type": "file", "readable": True,
                        "size": len(xml), "mtime": 1}]},
}
command = args[0]
reply = device.get(command, {})
if command == "pull":
    target = pathlib.Path(args[2])
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(reply.get("xml", xml.decode()).encode())
    sys.exit(reply.get("exit", 0))
body = reply.get("body", defaults[command])
if isinstance(body, str):
    sys.stdout.write(body)
else:
    sys.stdout.write(json.dumps(body, separators=(",", ":")) if config.get("minified")
                     else json.dumps(body, indent=2))
sys.exit(reply.get("exit", 0))
'''

DELAYED_LN = r'''#!/usr/bin/env python3
import pathlib, subprocess, sys, time
args = sys.argv[1:]
destination = pathlib.Path(args[-1])
if destination.name == "latest" and any(a.startswith("-") and "f" in a[1:] for a in args):
    # Expose ln -sfn's real unlink/create gap without relying on scheduler luck.
    try:
        destination.unlink()
    except FileNotFoundError:
        pass
    time.sleep(0.08)
sys.exit(subprocess.call(["/bin/ln", *args]))
'''


class FleetBackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="public-fleet-backup-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.kit = self.root / "kit"
        self.kit.mkdir()
        shutil.copyfile(SOURCE / "fleet-backup.sh", self.kit / "fleet-backup.sh")
        helper = SOURCE / "fleet-backup-data.py"
        if helper.exists():
            shutil.copyfile(helper, self.kit / helper.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.executable(self.bin / "fleetctl", FAKE_CLI)
        self.executable(self.bin / "date", "#!/bin/sh\nprintf '%s\\n' '" + STAMP + "'\n")
        self.registry = self.kit / "fleet"
        self.registry.mkdir(mode=0o700)
        self.calls = self.root / "calls.jsonl"
        self.config = self.root / "config.json"
        self.state = {"devices": {}}
        self.write_device("family-public", "Family Public")

    def executable(self, path, text):
        path.write_text(text, encoding="utf-8")
        path.chmod(0o700)

    def write_device(self, serial, name):
        path = self.registry / (serial + ".json")
        path.write_text(json.dumps({
            "serial": serial, "name": name, "ip": "192.0.2.10",
            "agentPort": 8723, "token": FAKE_TOKEN,
        }), encoding="utf-8")
        path.chmod(0o600)
        return path

    def reply(self, command, body=None, exit_code=0, serial="family-public", **extra):
        value = {"exit": exit_code, **extra}
        if body is not None:
            value["body"] = body
        self.state["devices"].setdefault(serial, {})[command] = value

    def backup_environment(self, package=None, runtime=None):
        self.config.write_text(json.dumps(self.state), encoding="utf-8")
        env = {**os.environ, "PYTHONDONTWRITEBYTECODE": "1",
               "FLEETCTL": str(self.bin / "fleetctl"),
               "IMMORTAL_FLEET_DIR": str(self.registry),
               "FAKE_BACKUP_CALLS": str(self.calls),
               "FAKE_BACKUP_CONFIG": str(self.config),
               "PATH": str(self.bin) + os.pathsep + os.environ["PATH"]}
        env.pop("IMMORTAL_BACKUP_PACKAGE", None)
        env.pop("PYTHON3", None)
        if package is not None:
            env["IMMORTAL_BACKUP_PACKAGE"] = package
        if runtime is not None:
            env["PYTHON3"] = str(runtime)
        return env

    def run_backup(self, target="family-public", package=None, runtime=None):
        env = self.backup_environment(package, runtime)
        # Explicit umask 022 proves production establishes its own private permissions.
        result = subprocess.run(
            ["/bin/bash", "-c", 'umask 022; exec /bin/bash "$1" "$2"',
             "backup-test", str(self.kit / "fleet-backup.sh"), target],
            env=env, capture_output=True, text=True, timeout=10,
        )
        output = result.stdout + result.stderr
        self.assertNotIn(FAKE_TOKEN, output)
        for args in self.commands():
            self.assertIn(args[0], {"info", "apps", "ls", "pull"}, "backup must stay read-only")
        return result.returncode, output

    def commands(self):
        return [json.loads(line) for line in self.calls.read_text().splitlines()] if self.calls.exists() else []

    def device_root(self, serial="family-public"):
        return self.kit / "backups" / serial

    def seed_previous(self, serial="family-public"):
        root = self.device_root(serial)
        previous = root / "previous-complete"
        (previous / "shared_prefs").mkdir(parents=True, mode=0o700)
        (previous / "shared_prefs" / "old.xml").write_bytes(b"<map><string name='old'>Public</string></map>")
        (previous / "registry.json").write_bytes(b'{"public":"previous registry bytes"}\n')
        (previous / "MANIFEST.txt").write_bytes(b"previous manifest bytes\n")
        (root / "latest").symlink_to(previous.name)
        return self.snapshot(root)

    def snapshot(self, root):
        result = {}
        for path in sorted(root.rglob("*")):
            relative = str(path.relative_to(root))
            if path.is_symlink():
                result[relative] = ("symlink", os.readlink(path))
            elif path.is_file():
                result[relative] = ("file", path.read_bytes())
            else:
                result[relative] = ("directory",)
        return result

    def assert_failure_preserves_previous(self, previous, serial="family-public"):
        code, output = self.run_backup(serial)
        self.assertNotEqual(code, 0)
        self.assertNotIn("backed up 1 device", output)
        self.assertEqual(self.snapshot(self.device_root(serial)), previous,
                         "failed backup must preserve previous/latest and remove owned partial data")
        return output

    def completed(self, serial="family-public", unique=True):
        root = self.device_root(serial)
        latest = root / "latest"
        self.assertTrue(latest.is_symlink())
        target = latest.resolve(strict=True)
        self.assertEqual(target.parent, root)
        if unique:
            self.assertRegex(target.name, "^" + STAMP + r"[.-][A-Za-z0-9_-]+$")
        self.assertEqual((target / "shared_prefs" / "prefs.xml").read_bytes(), XML)
        self.assertEqual((target / "registry.json").read_bytes(),
                         (self.registry / (serial + ".json")).read_bytes())
        return target

    def listing(self, **changes):
        value = {"ok": True, "path": "/data/data/" + PACKAGE + "/shared_prefs",
                 "entries": [{"name": "prefs.xml", "type": "file", "readable": True,
                              "size": len(XML), "mtime": 1}]}
        value.update(changes)
        return value

    def test_success_preserves_exact_data_and_private_permissions_under_umask022(self):
        code, output = self.run_backup()
        self.assertEqual(code, 0, output)
        target = self.completed()
        self.assertIn("backed up 1 device", output)
        for path in [self.kit / "backups", self.device_root(), target, *target.rglob("*")]:
            if not path.is_symlink():
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o700 if path.is_dir() else 0o600)
        manifest = (target / "MANIFEST.txt").read_text()
        self.assertIn("org.example", manifest)
        self.assertIn("Public", manifest)

    def test_valid_minified_json_produces_complete_backup(self):
        self.state["minified"] = True
        code, output = self.run_backup()
        self.assertEqual(code, 0, output)
        self.completed()

    def test_info_requires_literal_ok_true_and_valid_json(self):
        previous = self.seed_previous()
        for body, exit_code in (({"ok": False}, 0), ({"ok": 1}, 0),
                                ("not JSON", 0), ("", 0), ([], 0), ({"ok": True}, 1)):
            with self.subTest(body=body, exit_code=exit_code):
                self.reply("info", body, exit_code)
                self.assert_failure_preserves_previous(previous)

    def test_apps_failure_or_invalid_json_cannot_publish_complete_snapshot(self):
        previous = self.seed_previous()
        for body, exit_code in (({"ok": False}, 0), ({"ok": "true", "apps": []}, 0),
                                ("not JSON", 0), ([], 0), ({"ok": True, "apps": []}, 1)):
            with self.subTest(body=body, exit_code=exit_code):
                self.reply("apps", body, exit_code)
                self.assert_failure_preserves_previous(previous)

    def test_apps_missing_or_invalid_installed_marker_cannot_publish_snapshot(self):
        previous = self.seed_previous()
        for entry in (
            {"packageName": "org.example"},
            {"packageName": "org.example", "installed": "true"},
            {"packageName": "org.example", "installed": "1"},
            {"packageName": "org.example", "installed": -1},
            {"packageName": "org.example", "installed": None},
            {"packageName": "org.example", "installed": 1.5},
        ):
            with self.subTest(entry=entry):
                self.reply("apps", {"ok": True, "apps": [entry]})
                self.assert_failure_preserves_previous(previous)

    def test_apps_literal_bool_and_legacy_nonnegative_int_markers_are_valid(self):
        for marker in (True, False, 0, 1, 123):
            with self.subTest(marker=marker):
                self.reply("apps", {"ok": True, "apps": [
                    {"packageName": "org.example", "installed": marker},
                ]})
                code, output = self.run_backup()
                self.assertEqual(code, 0, output)
                target = self.completed()
                manifest = (target / "MANIFEST.txt").read_text()
                self.assertEqual("  org.example\n" in manifest,
                                 marker is True or type(marker) is int)

    def test_ls_requires_literal_success_valid_json_and_exact_path(self):
        previous = self.seed_previous()
        for body, exit_code in ((self.listing(ok=False), 0), (self.listing(ok=1), 0),
                                ("not JSON", 0), ([], 0),
                                (self.listing(path="/data/data/other.app/shared_prefs"), 0),
                                (self.listing(), 1)):
            with self.subTest(body=body, exit_code=exit_code):
                self.reply("ls", body, exit_code)
                self.assert_failure_preserves_previous(previous)

    def test_no_readable_safe_xml_file_means_backup_is_incomplete(self):
        previous = self.seed_previous()
        for change in ({"name": "../escaped.xml"}, {"name": "/absolute.xml"},
                       {"name": ""}, {"name": "prefs.xml", "type": "directory"},
                       {"readable": False}, {"readable": 1}, {"name": "unrelated.txt"}):
            with self.subTest(change=change):
                entry = dict(self.listing()["entries"][0], **change)
                self.reply("ls", self.listing(entries=[entry]))
                self.assert_failure_preserves_previous(previous)
                self.assertFalse((self.device_root() / "escaped.xml").exists())

    def test_failed_pull_removes_partial_and_preserves_previous_latest(self):
        previous = self.seed_previous()
        self.reply("pull", exit_code=1, xml="<map>public partial")
        self.assert_failure_preserves_previous(previous)
        self.assertTrue(any(args[0] == "pull" for args in self.commands()))

    def test_empty_or_invalid_xml_cannot_publish_complete_snapshot(self):
        previous = self.seed_previous()
        for xml in ("", "<map>", "not XML", "<map></different>", "<map/><map/>"):
            with self.subTest(xml=xml):
                self.reply("pull", xml=xml)
                self.assert_failure_preserves_previous(previous)

    def test_empty_listing_cannot_claim_complete_backup(self):
        previous = self.seed_previous()
        self.reply("ls", self.listing(entries=[]))
        self.assert_failure_preserves_previous(previous)

    def test_one_failed_device_does_not_prevent_next_device_backup(self):
        previous = self.seed_previous()
        self.write_device("living-public", "Living Public")
        self.reply("apps", {"ok": False})
        code, output = self.run_backup("all")
        self.assertNotEqual(code, 0)
        self.assertEqual(self.snapshot(self.device_root()), previous)
        self.completed("living-public")
        self.assertIn("backed up 1 device", output)
        self.assertNotIn("backed up 2 device", output)
        self.assertTrue(any(args[-1] == "living-public" for args in self.commands()))

    def outside_destination(self):
        outside = self.root / "public-outside"
        previous = outside / "previous-complete"
        previous.mkdir(parents=True)
        (previous / "public-sentinel").write_bytes(b"outside bytes must remain unchanged\n")
        (outside / "latest").symlink_to(previous.name)
        outside.chmod(0o755)
        previous.chmod(0o750)
        (previous / "public-sentinel").chmod(0o640)
        return outside

    def outside_state(self, outside):
        modes = {".": stat.S_IMODE(outside.stat().st_mode)}
        for path in outside.rglob("*"):
            if not path.is_symlink():
                modes[str(path.relative_to(outside))] = stat.S_IMODE(path.stat().st_mode)
        return self.snapshot(outside), modes

    def test_backups_symlink_rejected_without_touching_outside_bytes_modes_or_latest(self):
        outside = self.outside_destination()
        before = self.outside_state(outside)
        link = self.kit / "backups"
        link.symlink_to(outside, target_is_directory=True)
        code, output = self.run_backup()
        self.assertEqual(self.outside_state(outside), before)
        self.assertTrue(link.is_symlink())
        self.assertEqual(os.readlink(link), str(outside))
        self.assertNotEqual(code, 0)
        self.assertNotIn("backed up 1 device", output)

    def test_device_destination_symlink_rejected_without_touching_outside(self):
        outside = self.outside_destination()
        before = self.outside_state(outside)
        (self.kit / "backups").mkdir(mode=0o700)
        link = self.device_root()
        link.symlink_to(outside, target_is_directory=True)
        code, output = self.run_backup()
        self.assertEqual(self.outside_state(outside), before)
        self.assertTrue(link.is_symlink())
        self.assertEqual(os.readlink(link), str(outside))
        self.assertNotEqual(code, 0)
        self.assertNotIn("backed up 1 device", output)

    def test_obstructed_device_destination_continues_to_next_device_and_fails_aggregate(self):
        self.write_device("living-public", "Living Public")
        (self.kit / "backups").mkdir(mode=0o700)
        obstruction = self.device_root()
        obstruction.write_bytes(b"public obstruction bytes\n")
        obstruction.chmod(0o640)
        code, output = self.run_backup("all")
        self.assertEqual(obstruction.read_bytes(), b"public obstruction bytes\n")
        self.assertEqual(stat.S_IMODE(obstruction.stat().st_mode), 0o640)
        self.completed("living-public")
        self.assertNotEqual(code, 0)
        self.assertIn("backed up 1 device", output)
        self.assertNotIn("backed up 2 device", output)
        self.assertTrue(any(args[-1] == "living-public" for args in self.commands()))

    def test_committed_publish_is_success_even_when_output_pipe_is_closed(self):
        code, output = self.run_backup()
        self.assertEqual(code, 0, output)
        previous = self.completed()
        previous_bytes = self.snapshot(previous)
        stage = self.device_root() / ".partial-public-pipe"
        shutil.copytree(previous, stage)
        read_fd, write_fd = os.pipe()
        os.close(read_fd)
        # Unbuffered output makes EPIPE occur inside publication, rather than during exit flush.
        with os.fdopen(write_fd, "wb") as sink:
            result = subprocess.run(
                [sys.executable, "-u", str(self.kit / "fleet-backup-data.py"),
                 "publish", str(stage), STAMP],
                stdout=sink, stderr=subprocess.PIPE, timeout=5,
                env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
            )
        latest = self.completed()
        self.assertNotEqual(latest, previous)
        self.assertEqual(self.snapshot(previous), previous_bytes)
        self.assertFalse(stage.exists())
        self.assertNotIn(FAKE_TOKEN.encode(), result.stderr)
        self.assertEqual(result.returncode, 0,
                         "committed latest must not be reported as failure when stdout breaks")
        self.assertNotIn(b"backup data is incomplete or invalid", result.stderr)

    def run_backup_closed_output(self, merge_stderr=False):
        process = subprocess.Popen(
            ["/bin/bash", "-c", 'umask 022; exec /bin/bash "$1" all',
             "backup-test", str(self.kit / "fleet-backup.sh")],
            env=self.backup_environment(), stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT if merge_stderr else subprocess.PIPE,
        )
        try:
            ready, _, _ = select.select([process.stdout], [], [], 5)
            self.assertTrue(ready, "wrapper did not produce its first banner")
            banner = process.stdout.readline()
            self.assertIn(b"backing up family-public", banner)
            process.stdout.close()
            process.stdout = None  # communicate drains stderr only when it has a separate pipe.
            _, stderr = process.communicate(timeout=10)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()
            if process.stdout is not None:
                process.stdout.close()
            if process.stderr is not None:
                process.stderr.close()
        self.assertNotIn(FAKE_TOKEN.encode(), banner + (stderr or b""))
        commands = self.commands()
        self.assertTrue(all(args[0] in {"info", "apps", "ls", "pull"} for args in commands))
        return process.returncode, commands

    def test_wrapper_closed_stdout_after_first_banner_still_completes_both_devices(self):
        self.write_device("living-public", "Living Public")
        code, commands = self.run_backup_closed_output()
        self.completed("family-public")
        self.completed("living-public")
        self.assertEqual(code, 0,
                         "informational output failure must not abort rooms or change backup status")
        self.assertEqual({args[-1] for args in commands}, {"family-public", "living-public"})

    def test_wrapper_closed_merged_output_after_first_failure_still_completes_next_device(self):
        self.write_device("living-public", "Living Public")
        self.reply("apps", {"ok": False})
        code, commands = self.run_backup_closed_output(merge_stderr=True)
        self.completed("living-public")
        self.assertFalse(os.path.lexists(self.device_root() / "latest"))
        self.assertEqual(list(self.device_root().iterdir()), [],
                         "failed first room must leave no partial or completed snapshot")
        self.assertEqual(code, 1,
                         "closed error output must preserve aggregate failure and continue rooms")
        self.assertEqual([args[0] for args in commands if args[-1] == "family-public"],
                         ["info", "apps"])
        self.assertEqual([args[0] for args in commands if args[-1] == "living-public"],
                         ["info", "apps", "ls", "pull"])

    def test_same_second_successes_have_distinct_paths_and_preserve_first_bytes(self):
        code, output = self.run_backup()
        self.assertEqual(code, 0, output)
        first = self.completed()
        original = self.snapshot(first)
        code, output = self.run_backup()
        self.assertEqual(code, 0, output)
        second = self.completed()
        self.assertNotEqual(first, second)
        self.assertEqual(self.snapshot(first), original)
        self.assertEqual(len([path for path in self.device_root().iterdir()
                              if path.is_dir() and not path.is_symlink()]), 2)

    def test_latest_replacement_has_no_unlink_gap(self):
        self.seed_previous()
        self.executable(self.bin / "ln", DELAYED_LN)
        latest = self.device_root() / "latest"
        missing = []
        stop = threading.Event()

        def observe():
            while not stop.is_set():
                if not latest.is_symlink():
                    missing.append(True)
                stop.wait(0.001)

        observer = threading.Thread(target=observe, daemon=True)
        observer.start()
        try:
            code, output = self.run_backup()
        finally:
            stop.set()
            observer.join(1)
        self.assertEqual(code, 0, output)
        self.assertFalse(missing, "latest must be replaced atomically")
        self.completed()

    def test_debug_package_reads_only_requested_shared_prefs(self):
        code, output = self.run_backup(package="com.immortal.launcher.debug")
        self.assertEqual(code, 0, output)
        self.completed()
        for args in self.commands():
            if args[0] in {"ls", "pull"}:
                self.assertTrue(args[1].startswith("/data/data/com.immortal.launcher.debug/shared_prefs"))

    def test_invalid_package_is_rejected_before_any_cli_call(self):
        for package in ("../other", "com.immortal.launcher/../other", "com.example;echo nope", ""):
            with self.subTest(package=package):
                if self.calls.exists():
                    self.calls.unlink()
                code, _ = self.run_backup(package=package)
                self.assertNotEqual(code, 0)
                self.assertEqual(self.commands(), [])
                self.assertFalse((self.kit / "backups").exists())

    def test_explicit_python_runtime_path_is_used(self):
        marker = self.root / "runtime-used"
        runtime = self.bin / "public-python"
        self.executable(runtime, "#!/usr/bin/env python3\n"
                        "import os, pathlib, sys\n"
                        f"pathlib.Path({str(marker)!r}).write_text('used')\n"
                        f"os.execv({sys.executable!r}, [{sys.executable!r}, *sys.argv[1:]])\n")
        code, output = self.run_backup(runtime=runtime)
        self.assertEqual(code, 0, output)
        self.assertTrue(marker.exists(), "PYTHON3 must select the validation runtime")
        self.completed()


if __name__ == "__main__":
    unittest.main()

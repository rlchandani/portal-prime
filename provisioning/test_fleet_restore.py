#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Offline end-to-end restore checks with synthetic backups and CLI replies."""

import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest


SOURCE = Path(__file__).resolve().parent
SERIAL = "public-restore-room"
TOKEN = "synthetic-restore-token-do-not-echo"
FAKE_CLI = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
with open(os.environ["FAKE_RESTORE_CALLS"], "a") as stream:
    stream.write(json.dumps(args) + "\n")
config = json.loads(pathlib.Path(os.environ["FAKE_RESTORE_CONFIG"]).read_text())
if config.get("mutate_original_registry"):
    original = pathlib.Path(os.environ["FAKE_ORIGINAL_REGISTRY"])
    active = pathlib.Path(os.environ["IMMORTAL_FLEET_DIR"]).resolve()
    records = sorted(active.glob("*.json"))
    observation = {
        "command": args[0], "selector": args[-1], "registry_path": str(active),
        "record_count": len(records), "directory_mode": active.stat().st_mode & 0o7777,
        "scratch_mode": active.parent.stat().st_mode & 0o7777,
        "plan_file_modes": [path.stat().st_mode & 0o7777 for path in sorted(active.parent.glob("*.args"))],
        "file_modes": [path.stat().st_mode & 0o7777 for path in records],
        "selected_record_matches": (
            len(records) == 1
            and json.loads(records[0].read_text()) == config["expected_registry_record"]),
    }
    with open(os.environ["FAKE_RESTORE_REGISTRY_OBSERVATIONS"], "a") as stream:
        stream.write(json.dumps(observation) + "\n")
    if args[0] in ("install", "screensaver"):
        # Change the original after this command reads its registry, before the next command.
        injected = dict(config["expected_registry_record"])
        injected.update(serial="public-injected-room", name=args[-1])
        (original / "public-injected-room.json").write_text(json.dumps(injected))
if args[0] == "install":
    if config.get("mutate_config_xml"):
        pathlib.Path(config["config_xml_path"]).write_text(config["mutate_config_xml"])
    for path, value in config.get("replace_preferences", {}).items():
        pathlib.Path(path).write_text(value)
    package = args[1]
    reply = config.get("install", {}).get(package, {})
    default = {"ok": True, "packageName": package, "result": "installed"}
elif args[0] == "apps":
    reply = config.get("readback", {})
    default = {"ok": True, "apps": [
        {"packageName": "org.alpha", "installed": True},
        {"packageName": "org.beta", "installed": True},
        {"packageName": "org.gamma", "installed": True},
    ]}
elif args[0] == "push":
    source = pathlib.Path(args[1])
    observation = {
        "name": source.name, "bytes": source.read_bytes().hex(),
        "source": str(source), "file_mode": source.stat().st_mode & 0o7777,
        "directory_mode": source.parent.stat().st_mode & 0o7777,
    }
    with open(os.environ["FAKE_RESTORE_PUSH_OBSERVATIONS"], "a") as stream:
        stream.write(json.dumps(observation) + "\n")
    for path, value in config.get("replace_preferences_on_push", {}).items():
        pathlib.Path(path).write_text(value)
    reply, default = config.get(args[0], {}), {"ok": True}
elif args[0] in ("action", "screensaver", "calendar"):
    reply, default = config.get(args[0], {}), {"ok": True}
else:
    sys.exit(95)
body = reply.get("body", default)
sys.stdout.write(body if isinstance(body, str) else json.dumps(body))
sys.stderr.write(reply.get("stderr", ""))
sys.exit(reply.get("exit", 0))
'''


class FleetRestoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="public-fleet-restore-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.kit = self.root / "kit"
        self.kit.mkdir()
        shutil.copyfile(SOURCE / "fleet-restore.sh", self.kit / "fleet-restore.sh")
        helper = SOURCE / "fleet-backup-data.py"
        if helper.exists():
            shutil.copyfile(helper, self.kit / helper.name)
        self.cli = self.root / "public-fleetctl"
        self.cli.write_text(FAKE_CLI)
        self.cli.chmod(0o700)
        self.registry = self.kit / "fleet"
        self.registry.mkdir(mode=0o700)
        self.write_registry()
        self.backup = self.root / "public-backup"
        (self.backup / "shared_prefs").mkdir(parents=True, mode=0o700)
        (self.backup / "shared_prefs" / "prefs.xml").write_bytes(
            b'<map><string name="label">Public</string></map>\n')
        self.apps_file = self.backup / "apps.json"
        self.write_apps([{"packageName": "org.alpha", "installed": True}])
        self.config = self.root / "replies.json"
        self.calls = self.root / "calls.jsonl"
        self.registry_observations = self.root / "registry-observations.jsonl"
        self.push_observations = self.root / "push-observations.jsonl"
        self.replies = {}
        self.scratch_parent = self.root / "scratch"
        self.scratch_parent.mkdir(mode=0o700)

    def write_registry(self, serial=SERIAL, name="Public Restore Room", filename=None):
        registry = self.registry / (filename or (serial + ".json"))
        registry.write_text(json.dumps({
            "serial": serial, "name": name, "ip": "192.0.2.20",
            "agentPort": 8723, "token": TOKEN,
        }))
        registry.chmod(0o600)

    def write_apps(self, rows, minified=False):
        self.apps_file.write_text(json.dumps(
            {"ok": True, "apps": rows}, indent=None if minified else 2,
            separators=(",", ":") if minified else None))

    def run_restore(self, *modes, runtime=None, target=SERIAL, expected_serial=SERIAL):
        if self.calls.exists():
            self.calls.unlink()
        self.config.write_text(json.dumps(self.replies))
        env = {**os.environ, "PYTHONDONTWRITEBYTECODE": "1",
               "TMPDIR": str(self.scratch_parent),
               "FLEETCTL": str(self.cli), "IMMORTAL_FLEET_DIR": str(self.registry),
               "FAKE_RESTORE_CONFIG": str(self.config), "FAKE_RESTORE_CALLS": str(self.calls),
               "FAKE_ORIGINAL_REGISTRY": str(self.registry),
               "FAKE_RESTORE_REGISTRY_OBSERVATIONS": str(self.registry_observations),
               "FAKE_RESTORE_PUSH_OBSERVATIONS": str(self.push_observations)}
        env.pop("PYTHON3", None)
        if runtime is not None:
            env["PYTHON3"] = str(runtime)
        result = subprocess.run(
            ["/bin/bash", str(self.kit / "fleet-restore.sh"), target,
             "--backup", str(self.backup), *(modes or ("--apps",))],
            env=env, capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=10,
        )
        output = result.stdout + result.stderr
        self.assertNotIn(TOKEN, output)
        self.assertEqual(list(self.scratch_parent.iterdir()), [], "owned restore scratch must be removed")
        calls = [json.loads(line) for line in self.calls.read_text().splitlines()] if self.calls.exists() else []
        for args in calls:
            self.assertEqual(args[-2:], ["--device", expected_serial])
        return result.returncode, output, calls

    def assert_invalid_before_cli(self):
        code, output, calls = self.run_restore("--apps", "--prefs")
        self.assertNotEqual(code, 0, "invalid app plan must prevent all install/prefs operations")
        self.assertEqual(calls, [])
        self.assertNotIn("done.", output)

    def write_manifest(self, names, package="com.immortal.launcher"):
        lines = ["device:  " + SERIAL, "stamp:   public"]
        if package is not None:
            lines.append("package: " + package)
        lines.extend(["installed apps:", "shared_prefs:", *("  " + name for name in names)])
        (self.backup / "MANIFEST.txt").write_text("\n".join(lines) + "\n")

    def assert_invalid_preferences(self, *modes):
        code, output, calls = self.run_restore(*(modes or ("--apps", "--prefs")))
        self.assertNotEqual(code, 0, "invalid preference plan must prevent all operations")
        self.assertEqual(calls, [])
        self.assertNotIn("done.", output)

    def read_pushes(self):
        return [json.loads(line) for line in self.push_observations.read_text().splitlines()]

    def test_prefs_late_malformed_xml_prevents_earlier_install_and_push(self):
        (self.backup / "shared_prefs" / "z-late.xml").write_text("<map>")
        self.assert_invalid_preferences()

    def test_prefs_non_map_prevents_all_operations_even_in_dry_run(self):
        (self.backup / "shared_prefs" / "prefs.xml").write_text("<not-map/>")
        self.assert_invalid_preferences("--apps", "--prefs", "--dry-run")

    def test_prefs_manifest_missing_or_unlisted_xml_prevents_all_operations(self):
        for names in (["prefs.xml", "missing.xml"], ["other.xml"]):
            with self.subTest(names=names):
                self.write_manifest(names)
                self.assert_invalid_preferences()

    def test_prefs_manifest_invalid_inventory_and_package_prevent_all_operations(self):
        for text in (
            "package: com.immortal.launcher.debug\nshared_prefs:\n  prefs.xml\n",
            "package: com.immortal.launcher\npackage: com.immortal.launcher\nshared_prefs:\n  prefs.xml\n",
            "shared_prefs:\n  prefs.xml\nshared_prefs:\n  prefs.xml\n",
            "shared_prefs:\n  prefs.xml\n  prefs.xml\n",
            "shared_prefs:\n  ../prefs.xml\n",
            "device: public\n",
        ):
            with self.subTest(text=text):
                (self.backup / "MANIFEST.txt").write_text(text)
                self.assert_invalid_preferences()

    def test_prefs_xml_symlink_and_directory_prevent_all_operations(self):
        path = self.backup / "shared_prefs" / "z-late.xml"
        for kind in ("symlink", "directory"):
            with self.subTest(kind=kind):
                if kind == "symlink":
                    path.symlink_to(self.backup / "shared_prefs" / "prefs.xml")
                else:
                    path.mkdir()
                try:
                    self.assert_invalid_preferences()
                finally:
                    path.rmdir() if kind == "directory" else path.unlink()

    def test_prefs_missing_empty_or_linked_directory_prevents_all_operations(self):
        path = self.backup / "shared_prefs"
        saved = self.root / "saved-prefs"
        path.rename(saved)
        self.assert_invalid_preferences()
        path.mkdir()
        self.assert_invalid_preferences()
        path.rmdir()
        path.symlink_to(saved, target_is_directory=True)
        self.assert_invalid_preferences()

    def test_prefs_manifest_symlink_or_directory_is_not_absent_legacy(self):
        path = self.backup / "MANIFEST.txt"
        path.symlink_to(self.root / "missing-manifest")
        self.assert_invalid_preferences()
        path.unlink()
        path.mkdir()
        self.assert_invalid_preferences()

    def test_prefs_unsafe_xml_names_prevent_all_operations(self):
        for name in ("bad\nname.xml", "bad\\name.xml"):
            with self.subTest(name=name):
                path = self.backup / "shared_prefs" / name
                path.write_text("<map/>")
                try:
                    self.assert_invalid_preferences()
                finally:
                    path.unlink()

    def test_prefs_bytes_are_frozen_before_install_and_kept_private(self):
        path = self.backup / "shared_prefs" / "prefs.xml"
        original = path.read_bytes()
        self.replies["replace_preferences"] = {str(path): "<map/>"}
        code, output, calls = self.run_restore("--apps", "--prefs")
        self.assertEqual(code, 0, output)
        self.assertEqual([call[0] for call in calls], ["install", "apps", "push", "action"])
        pushed = self.read_pushes()[0]
        self.assertEqual(bytes.fromhex(pushed["bytes"]), original)
        self.assertEqual(pushed["file_mode"], 0o600)
        self.assertEqual(pushed["directory_mode"], 0o700)
        self.assertTrue(Path(pushed["source"]).is_relative_to(self.scratch_parent))

    def test_prefs_all_bytes_are_frozen_before_first_push(self):
        path = self.backup / "shared_prefs" / "z-late.xml"
        path.write_bytes(b'<?xml version="1.0"?><map><!--public--><string name="x">A&amp;B</string></map>\n')
        original = path.read_bytes()
        self.replies["replace_preferences_on_push"] = {str(path): "<map/>"}
        code, output, calls = self.run_restore("--prefs")
        self.assertEqual(code, 0, output)
        self.assertEqual([call[0] for call in calls], ["push", "push", "action"])
        self.assertEqual(bytes.fromhex(self.read_pushes()[1]["bytes"]), original)

    def test_prefs_manifestless_legacy_manifest_and_latest_link_keep_exact_bytes(self):
        original = (self.backup / "shared_prefs" / "prefs.xml").read_bytes()
        for kind in ("manual", "legacy", "current"):
            with self.subTest(kind=kind):
                if kind != "manual":
                    self.write_manifest(["prefs.xml"], None if kind == "legacy" else "com.immortal.launcher")
                if self.push_observations.exists():
                    self.push_observations.unlink()
                saved = self.backup
                link = self.root / "latest"
                link.symlink_to(saved, target_is_directory=True)
                self.backup = link
                code, output, calls = self.run_restore("--prefs")
                self.backup = saved
                link.unlink()
                self.assertEqual(code, 0, output)
                self.assertEqual([call[0] for call in calls], ["push", "action"])
                self.assertEqual(bytes.fromhex(self.read_pushes()[0]["bytes"]), original)

    def test_prefs_unicode_spaces_token_skip_and_dry_run_are_literal(self):
        name = " public café 'photo'.xml"
        (self.backup / "shared_prefs" / name).write_text("<map/>")
        (self.backup / "shared_prefs" / "fleet_agent.xml").write_text("old-token-not-xml")
        self.write_manifest([name, "prefs.xml", "fleet_agent.xml"])
        code, output, calls = self.run_restore("--prefs", "--dry-run")
        self.assertEqual(code, 0, output)
        self.assertEqual(calls, [])
        self.assertIn(name, output)
        self.assertNotIn("old-token-not-xml", output)
        code, output, calls = self.run_restore("--prefs")
        self.assertEqual(code, 0, output)
        pushes = [call for call in calls if call[0] == "push"]
        self.assertEqual([Path(call[1]).name for call in pushes], [name, "prefs.xml"])
        self.assertEqual(pushes[0][2], "/data/data/com.immortal.launcher/shared_prefs/" + name)

    def test_prefs_token_only_has_no_push_or_reboot_but_token_links_fail(self):
        path = self.backup / "shared_prefs" / "prefs.xml"
        path.unlink()
        token = self.backup / "shared_prefs" / "fleet_agent.xml"
        token.write_text("old-token-not-xml")
        code, output, calls = self.run_restore("--prefs")
        self.assertEqual(code, 0, output)
        self.assertEqual(calls, [])
        token.unlink()
        token.symlink_to(self.root / "missing-token")
        self.assert_invalid_preferences()

    def test_prefs_manifest_unicode_line_separators_remain_literal_filenames(self):
        for separator in ("\u0085", "\u2028", "\u2029"):
            with self.subTest(separator=separator):
                path = self.backup / "shared_prefs" / ("public" + separator + "photo.xml")
                path.write_text("<map/>")
                self.write_manifest(["prefs.xml", path.name])
                try:
                    code, output, calls = self.run_restore("--prefs")
                    self.assertEqual(code, 0, output)
                    self.assertEqual([Path(call[1]).name for call in calls if call[0] == "push"],
                                     ["prefs.xml", path.name])
                finally:
                    path.unlink()

    def test_arbitrary_field_order_and_pretty_or_minified_json_select_correct_app(self):
        for minified in (False, True):
            for installed_first in (False, True):
                with self.subTest(minified=minified, installed_first=installed_first):
                    if installed_first:
                        rows = [{"installed": False, "packageName": "org.wrong"},
                                {"installed": True, "packageName": "org.alpha"}]
                    else:
                        rows = [{"packageName": "org.wrong", "installed": False},
                                {"packageName": "org.alpha", "installed": True}]
                    self.write_apps(rows, minified=minified)
                    code, output, calls = self.run_restore()
                    self.assertEqual(code, 0, output)
                    self.assertEqual(calls, [
                        ["install", "org.alpha", "--device", SERIAL],
                        ["apps", "--device", SERIAL],
                    ])

    def test_markers_sorted_deduplicated_and_both_immortal_packages_skipped(self):
        self.write_apps([
            {"packageName": "org.gamma", "installed": 0},
            {"packageName": "org.beta", "installed": 123},
            {"packageName": "org.alpha", "installed": True},
            {"packageName": "org.alpha", "installed": 1},
            {"packageName": "org.skip", "installed": False},
            {"packageName": "com.immortal.launcher", "installed": True},
            {"packageName": "com.immortal.launcher.debug", "installed": 1},
        ])
        code, output, calls = self.run_restore()
        self.assertEqual(code, 0, output)
        self.assertEqual(calls, [
            ["install", "org.alpha", "--device", SERIAL], ["apps", "--device", SERIAL],
            ["install", "org.beta", "--device", SERIAL], ["apps", "--device", SERIAL],
            ["install", "org.gamma", "--device", SERIAL], ["apps", "--device", SERIAL],
        ])

    def test_empty_selected_list_is_success_without_any_cli_call(self):
        for rows in ([], [{"packageName": "org.skip", "installed": False}],
                     [{"packageName": "com.immortal.launcher", "installed": True},
                      {"packageName": "com.immortal.launcher.debug", "installed": True}]):
            with self.subTest(rows=rows):
                self.write_apps(rows)
                code, output, calls = self.run_restore()
                self.assertEqual(code, 0, output)
                self.assertEqual(calls, [])

    def test_malformed_or_nonliteral_success_apps_json_fails_before_all_cli(self):
        for body in (b"", b"not JSON", b"\xff", b"[]", b"null",
                     b'{"ok":false,"apps":[]}', b'{"ok":1,"apps":[]}',
                     b'{"ok":"true","apps":[]}', b'{"ok":true}', b'{"ok":true,"apps":{}}',
                     b'{"ok":false,"ok":true,"apps":[]}',
                     b'{"ok":true,"apps":[{}],"apps":[]}',
                     b'{"ok":true,"apps":[{"packageName":"org.alpha","installed":false,"installed":true}]}',
                     b'{"ok":true,"apps":[{"packageName":"org.wrong","packageName":"org.alpha","installed":true}]}',
                     b'{"ok":true,"apps":[],"metadata":{"label":1,"label":1}}'):
            with self.subTest(body=body):
                self.apps_file.write_bytes(body)
                self.assert_invalid_before_cli()

    def test_unsafe_packages_and_missing_or_invalid_markers_fail_before_all_cli(self):
        for row in (
            {"packageName": "org.alpha"},
            {"packageName": "org.alpha", "installed": "true"},
            {"packageName": "org.alpha", "installed": "1"},
            {"packageName": "org.alpha", "installed": -1},
            {"packageName": "org.alpha", "installed": None},
            {"packageName": "org.alpha", "installed": 1.5},
            {"packageName": "../escape", "installed": True},
            {"packageName": "org.alpha;echo public", "installed": True},
            {"packageName": "org.alpha/other", "installed": True},
            {"packageName": "", "installed": True},
        ):
            with self.subTest(row=row):
                # A valid first item proves the complete plan is checked before the first write.
                self.write_apps([{"packageName": "org.beta", "installed": True}, row])
                self.assert_invalid_before_cli()

    def test_missing_apps_file_fails_before_install_or_prefs(self):
        self.apps_file.unlink()
        self.assert_invalid_before_cli()

    def test_dry_run_prints_correct_sorted_plan_without_cli_calls(self):
        self.write_apps([
            {"installed": True, "packageName": "org.beta"},
            {"installed": True, "packageName": "org.alpha"},
            {"installed": True, "packageName": "org.alpha"},
            {"installed": True, "packageName": "com.immortal.launcher.debug"},
        ], minified=True)
        code, output, calls = self.run_restore("--apps", "--prefs", "--dry-run")
        self.assertEqual(code, 0, output)
        self.assertEqual(calls, [])
        self.assertIn("install org.alpha", output)
        self.assertIn("install org.beta", output)
        self.assertLess(output.index("install org.alpha"), output.index("install org.beta"))
        planned_commands = [shlex.split(line[4:]) for line in output.splitlines()
                            if line.startswith("  + ")]
        self.assertEqual([args for args in planned_commands if args[1] == "install"], [
            [str(self.cli), "install", "org.alpha", "--device", SERIAL],
            [str(self.cli), "install", "org.beta", "--device", SERIAL],
        ])
        self.assertNotIn("install com.immortal.launcher", output)
        self.assertIn("prefs.xml", output)

    def test_invalid_install_ack_or_cli_failure_stops_without_retry_or_prefs(self):
        valid = {"ok": True, "packageName": "org.alpha", "result": "installed"}
        for reply in (
            {"exit": 1, "body": valid, "stderr": TOKEN},
            {"body": {"ok": False, "token": TOKEN}},
            {"body": dict(valid, packageName="org.wrong")},
            {"body": "malformed " + TOKEN},
            {"body": ""},
            {"body": {"ok": True, "packageName": "org.alpha"}},
            {"body": dict(valid, ok=1)},
            {"body": dict(valid, ok="true")},
            {"body": dict(valid, result="downloaded")},
            {"body": []},
            {"body": '{"ok":false,"ok":true,"packageName":"org.alpha","result":"installed"}'},
            {"body": '{"ok":true,"packageName":"org.wrong","packageName":"org.alpha","result":"installed"}'},
            {"body": '{"ok":true,"packageName":"org.alpha","result":"failed","result":"installed"}'},
            {"body": '{"ok":true,"packageName":"org.alpha","result":"installed","metadata":{"label":1,"label":1}}'},
        ):
            with self.subTest(reply=reply):
                self.write_apps([{"packageName": "org.alpha", "installed": True},
                                 {"packageName": "org.beta", "installed": True}])
                self.replies = {"install": {"org.alpha": reply}}
                code, output, calls = self.run_restore("--apps", "--prefs")
                self.assertNotEqual(code, 0)
                self.assertEqual(calls, [["install", "org.alpha", "--device", SERIAL]])
                self.assertNotIn("done.", output)

    def test_valid_install_is_confirmed_by_exactly_one_same_device_apps_readback(self):
        code, output, calls = self.run_restore()
        self.assertEqual(code, 0, output)
        self.assertEqual(calls, [
            ["install", "org.alpha", "--device", SERIAL], ["apps", "--device", SERIAL],
        ])

    def test_unconfirmed_readback_stops_without_retry_next_app_or_prefs(self):
        for reply in (
            {"body": {"ok": True, "apps": [{"packageName": "org.alpha", "installed": False}]}},
            {"body": {"ok": True, "apps": []}},
            {"body": {"ok": True, "apps": [{"packageName": "org.wrong", "installed": True}]}},
            {"body": {"ok": True, "apps": [{"packageName": "org.alpha"}]}},
            {"body": {"ok": False, "token": TOKEN}},
            {"body": "malformed " + TOKEN},
            {"exit": 1},
            {"body": '{"ok":false,"ok":true,"apps":[{"packageName":"org.alpha","installed":true}]}'},
            {"body": '{"ok":true,"apps":[],"apps":[{"packageName":"org.alpha","installed":true}]}'},
            {"body": '{"ok":true,"apps":[{"packageName":"org.alpha","installed":false,"installed":true}]}'},
            {"body": '{"ok":true,"apps":[{"packageName":"org.wrong","packageName":"org.alpha","installed":true}]}'},
            {"body": '{"ok":true,"apps":[{"packageName":"org.alpha","installed":true}],"metadata":{"label":1,"label":1}}'},
        ):
            with self.subTest(reply=reply):
                self.write_apps([{"packageName": "org.alpha", "installed": True},
                                 {"packageName": "org.beta", "installed": True}])
                self.replies = {"readback": reply}
                code, output, calls = self.run_restore("--apps", "--prefs")
                self.assertNotEqual(code, 0)
                self.assertEqual(calls, [
                    ["install", "org.alpha", "--device", SERIAL], ["apps", "--device", SERIAL],
                ])
                self.assertNotIn("done.", output)

    def test_missing_explicit_python_runtime_fails_before_any_cli(self):
        for modes in (("--apps", "--prefs"), ("--prefs",), ("--config",)):
            with self.subTest(modes=modes):
                code, _, calls = self.run_restore(*modes, runtime=self.root / "absent-python")
                self.assertNotEqual(code, 0)
                self.assertEqual(calls, [])

    def test_explicit_python_runtime_path_is_used(self):
        marker = self.root / "runtime-used"
        runtime = self.root / "public-python"
        runtime.write_text("#!/usr/bin/env python3\nimport os, pathlib, sys\n"
                           f"pathlib.Path({str(marker)!r}).write_text('used')\n"
                           f"os.execv({sys.executable!r}, [{sys.executable!r}, *sys.argv[1:]])\n")
        runtime.chmod(0o700)
        code, output, calls = self.run_restore(runtime=runtime)
        self.assertEqual(code, 0, output)
        self.assertTrue(marker.exists())
        self.assertEqual(calls, [
            ["install", "org.alpha", "--device", SERIAL], ["apps", "--device", SERIAL],
        ])

    def assert_target_rejected(self, target):
        code, output, calls = self.run_restore("--apps", "--prefs", target=target)
        self.assertNotEqual(code, 0, "unsafe target must be rejected before any CLI operation")
        self.assertEqual(calls, [])
        self.assertNotIn("done.", output)

    def test_target_all_rejected_before_any_install_push_or_action(self):
        self.write_registry("public-living-room", "Public Living Room")
        for target in ("all", "ALL", "All"):
            for modes in (("--apps", "--prefs"), ("--prefs",), ("--config",),
                          ("--apps", "--prefs", "--dry-run")):
                with self.subTest(target=target, modes=modes):
                    code, output, calls = self.run_restore(*modes, target=target)
                    self.assertNotEqual(code, 0, output)
                    self.assertEqual(calls, [])
                    self.assertNotIn("done.", output)

    def test_target_ambiguous_friendly_names_ignore_ascii_case(self):
        self.write_registry("public-living-room", "PUBLIC RESTORE ROOM")
        for target in ("Public Restore Room", "pUbLiC rEsToRe RoOm"):
            with self.subTest(target=target):
                self.assert_target_rejected(target)

    def test_target_serial_matching_another_record_name_rejected(self):
        self.write_registry("public-living-room", SERIAL.upper())
        self.assert_target_rejected(SERIAL)

    def test_target_unique_alias_with_canonical_serial_collision_rejected(self):
        self.write_registry("public-living-room", SERIAL.upper())
        # The alias matches one record, but forwarding its serial would match two.
        self.assert_target_rejected("Public Restore Room")

    def test_target_duplicate_registry_key_cannot_hide_second_matching_serial(self):
        registry = self.registry / "public-other-room.json"
        # Fleetctl uses the first serial; a parser taking the last would miss fanout.
        registry.write_text(
            '{"token":' + json.dumps(TOKEN) + ',"serial":' + json.dumps(SERIAL)
            + ',"serial":"public-other-room","name":"Public Other Room"}')
        registry.chmod(0o600)
        self.assert_target_rejected(SERIAL)

    def test_target_malformed_additional_record_fails_closed(self):
        registry = self.registry / "public-other-room.json"
        for body in (b"not JSON", b"\xff", b'{"token":"public-token","serial":'):
            with self.subTest(body=body):
                registry.write_bytes(body)
                registry.chmod(0o600)
                self.assert_target_rejected(SERIAL)

    def test_target_empty_token_record_is_skipped_as_cli(self):
        registry = self.registry / "public-unpaired-room.json"
        registry.write_text(json.dumps({"serial": SERIAL, "name": SERIAL, "token": ""}))
        registry.chmod(0o600)
        code, output, calls = self.run_restore()
        self.assertEqual(code, 0, output)
        self.assertEqual(calls, [
            ["install", "org.alpha", "--device", SERIAL], ["apps", "--device", SERIAL],
        ])

    def test_target_registry_snapshot_is_private_stable_and_removed_after_external_change(self):
        self.write_registry("public-living-room", "Public Living Room")
        selected_path = self.registry / (SERIAL + ".json")
        selected = json.loads(selected_path.read_text())
        selected.update(model="Public Model", publicMetadata={"label": "keep full record"})
        selected_path.write_text(json.dumps(selected))
        self.replies = {"mutate_original_registry": True, "expected_registry_record": selected}
        code, output, calls = self.run_restore()
        self.assertEqual(code, 0, output)
        self.assertEqual(calls, [
            ["install", "org.alpha", "--device", SERIAL], ["apps", "--device", SERIAL],
        ])
        injected = json.loads((self.registry / "public-injected-room.json").read_text())
        self.assertEqual(injected["name"], SERIAL)
        self.assertEqual(json.loads(selected_path.read_text()), selected)
        observations = [json.loads(line) for line in
                        self.registry_observations.read_text().splitlines()]
        self.assertEqual([(item["command"], item["selector"]) for item in observations],
                         [("install", SERIAL), ("apps", SERIAL)])
        snapshot_paths = {Path(item["registry_path"]) for item in observations}
        self.assertEqual(len(snapshot_paths), 1)
        snapshot = snapshot_paths.pop()
        self.assertNotEqual(snapshot, self.registry)
        for item in observations:
            self.assertEqual(item["record_count"], 1)
            self.assertIs(item["selected_record_matches"], True)
            self.assertEqual(item["directory_mode"], 0o700)
            self.assertEqual(item["file_modes"], [0o600])
        self.assertFalse(snapshot.exists(), "private token snapshot must be removed on exit")
        self.assertTrue(self.registry.is_dir())

    def test_target_unknown_or_malformed_registry_rejected_before_cli(self):
        self.assert_target_rejected("Unknown Public Room")
        registry = self.registry / (SERIAL + ".json")
        for body in (
            b"not JSON", b"\xff", b"[]", b"null",
            json.dumps({"name": "Public Restore Room", "token": TOKEN}).encode(),
            json.dumps({"serial": 7, "name": "Public Restore Room", "token": TOKEN}).encode(),
            json.dumps({"serial": SERIAL, "name": "Public Restore Room"}).encode(),
        ):
            with self.subTest(body=body):
                registry.write_bytes(body)
                self.assert_target_rejected("Public Restore Room")

    def test_target_unsafe_or_special_canonical_serial_rejected(self):
        for serial in ("all", "ALL", "../outside", "--device", "public room", "room\nall", ""):
            with self.subTest(serial=serial):
                self.write_registry(serial, filename=SERIAL + ".json")
                self.assert_target_rejected("Public Restore Room")

    def test_target_unique_friendly_name_passes_canonical_serial_and_dry_run_is_local(self):
        self.write_registry("public-living-room", "Public Living Room")
        for dry in (False, True):
            with self.subTest(dry=dry):
                modes = ("--apps", "--dry-run") if dry else ("--apps",)
                code, output, calls = self.run_restore(
                    *modes, target="pUbLiC rEsToRe RoOm", expected_serial=SERIAL)
                self.assertEqual(code, 0, output)
                if dry:
                    self.assertEqual(calls, [])
                    self.assertIn("--device " + SERIAL, output)
                else:
                    self.assertEqual(calls, [
                        ["install", "org.alpha", "--device", SERIAL],
                        ["apps", "--device", SERIAL],
                    ])

    def test_target_name_matching_uses_ascii_case_only(self):
        self.write_registry(name="Public Caf\u00e9")
        self.assert_target_rejected("PUBLIC CAF\u00c9")
        code, output, calls = self.run_restore(target="PUBLIC Caf\u00e9")
        self.assertEqual(code, 0, output)
        self.assertEqual(calls, [
            ["install", "org.alpha", "--device", SERIAL], ["apps", "--device", SERIAL],
        ])

    def write_config(self, xml):
        path = self.backup / "shared_prefs" / "immortal_screensaver.xml"
        path.write_text(xml, encoding="utf-8")
        return path

    def test_config_entities_unicode_and_shell_text_are_literal_arguments(self):
        marker = self.root / "must-not-exist"
        folder = f'/storage/Public & Café <2026> "$(touch {marker})"'
        self.write_config(
            '<map><string name="source">default</string>\n'
            '<string name="folder_path">/storage/Public &amp; Café &lt;2026&gt; '
            f'&quot;$(touch {marker})&quot;</string>\n'
            '<string name="album_url">https://example.test/share?a=1&amp;b=2</string>\n'
            '<string name="calendar_url">https://example.test/feed.ics?a=1&amp;b=2</string>\n'
            '<string name="calendar_range">week</string></map>')
        code, output, calls = self.run_restore("--config")
        self.assertEqual(code, 0, output)
        self.assertEqual(calls, [
            ["screensaver", "set", "--source", "default", "--folder", folder,
             "--album-url", "https://example.test/share?a=1&b=2", "--device", SERIAL],
            ["calendar", "set", "--url", "https://example.test/feed.ics?a=1&b=2",
             "--range", "week", "--device", SERIAL],
        ])
        self.assertFalse(marker.exists())

    def test_config_minified_attribute_order_and_single_quotes_are_supported(self):
        self.write_config(
            "<map><string name='fit'>fit</string>"
            "<int value='20' name='interval_sec'/>"
            "<boolean value='false' name='shuffle'/>"
            "<boolean name='include_video' value='true'/>"
            "<string name='calendar_url'>https://example.test/feed.ics</string></map>")
        code, output, calls = self.run_restore("--config")
        self.assertEqual(code, 0, output)
        self.assertEqual(calls, [
            ["screensaver", "set", "--fit", "fit", "--interval", "20",
             "--shuffle", "false", "--videos", "true", "--device", SERIAL],
            ["calendar", "set", "--url", "https://example.test/feed.ics", "--device", SERIAL],
        ])

    def test_config_multiline_cdata_and_numeric_entities_preserve_values(self):
        self.write_config(
            '<map>\n<string name="folder_path"><![CDATA[/storage/First\nSecond & Café]]></string>\n'
            '<string name="album_url"><![CDATA[https://example.test/share?a=1&b=2]]></string>\n'
            '<string name="calendar_url">https://example.test/feed.ics?a=1&#38;b=2</string>\n'
            '</map>')
        code, output, calls = self.run_restore("--config")
        self.assertEqual(code, 0, output)
        self.assertEqual(calls, [
            ["screensaver", "set", "--folder", "/storage/First\nSecond & Café",
             "--album-url", "https://example.test/share?a=1&b=2", "--device", SERIAL],
            ["calendar", "set", "--url", "https://example.test/feed.ics?a=1&b=2",
             "--device", SERIAL],
        ])

    def test_config_invalid_xml_or_known_types_prevent_every_earlier_operation(self):
        for xml in (
            "", "<map>", "<other/>",
            '<map><string name="album_url">https://example.test/?a=1&b=2</string></map>',
            '<map><int name="folder_path" value="1"/></map>',
            '<map><string name="shuffle">true</string></map>',
            '<map><boolean name="shuffle" value="maybe"/></map>',
            '<map><int name="interval_sec" value="not-an-integer"/></map>',
            '<map><string name="fit">fit</string><string name="fit">cover</string></map>',
            '<map><string name="folder_path">First<string>Second</string></string></map>',
        ):
            with self.subTest(xml=xml):
                self.write_config(xml)
                code, output, calls = self.run_restore("--apps", "--prefs", "--config")
                self.assertNotEqual(code, 0)
                self.assertEqual(calls, [], "complete config preflight must precede all writes")
                self.assertNotIn("done.", output)

    def test_config_empty_missing_and_unsupported_source_keep_existing_subset_policy(self):
        for xml in (None, "<map/>",
                    '<map><string name="album_url"/><string name="calendar_url"></string>'
                    '<string name="source">immich</string>'
                    '<string name="immich_api_key">ignored synthetic credential</string></map>'):
            with self.subTest(xml=xml):
                path = self.backup / "shared_prefs" / "immortal_screensaver.xml"
                if xml is None:
                    path.unlink(missing_ok=True)
                else:
                    self.write_config(xml)
                code, output, calls = self.run_restore("--config")
                self.assertEqual(code, 0, output)
                self.assertEqual(calls, [])
                self.assertNotIn("ignored synthetic credential", output)

    def test_config_dry_run_is_decoded_and_has_no_cli_or_reboot(self):
        self.write_config(
            '<map><string name="album_url">https://example.test/share?a=1&amp;b=2</string>\n'
            '<string name="calendar_url">https://example.test/feed.ics?a=1&amp;b=2</string></map>')
        code, output, calls = self.run_restore("--config", "--dry-run")
        self.assertEqual(code, 0, output)
        self.assertEqual(calls, [])
        self.assertIn("https://example.test/share?a=1&b=2", output)
        self.assertIn("https://example.test/feed.ics?a=1&b=2", output)
        self.assertNotIn("&amp;", output)
        self.assertNotIn("reboot", output)

    def test_config_plan_is_frozen_before_earlier_app_commands(self):
        path = self.write_config(
            '<map><string name="calendar_url">https://example.test/original.ics</string></map>')
        self.replies = {
            "config_xml_path": str(path),
            "mutate_config_xml": '<map><string name="calendar_url">'
                                 'https://example.test/replaced.ics</string></map>',
        }
        code, output, calls = self.run_restore("--apps", "--config")
        self.assertEqual(code, 0, output)
        self.assertEqual(calls, [
            ["install", "org.alpha", "--device", SERIAL], ["apps", "--device", SERIAL],
            ["calendar", "set", "--url", "https://example.test/original.ics",
             "--device", SERIAL],
        ])
        self.assertIn("replaced.ics", path.read_text())

    def test_config_unconfirmed_reply_stops_without_later_call_or_done(self):
        xml = ('<map><string name="fit">fit</string>\n'
               '<string name="calendar_url">https://example.test/feed.ics</string></map>')
        for reply in (
            {"body": {"ok": False, "detail": TOKEN}},
            {"body": {"ok": 1}},
            {"body": {"ok": "true"}},
            {"body": {"ok": True, "error": "synthetic failure"}},
            {"body": '{"ok":false,"ok":true}'},
            {"body": "malformed " + TOKEN},
            {"exit": 1, "stderr": TOKEN},
        ):
            with self.subTest(reply=reply):
                self.write_config(xml)
                self.replies = {"screensaver": reply}
                code, output, calls = self.run_restore("--config")
                self.assertNotEqual(code, 0)
                self.assertEqual(calls, [
                    ["screensaver", "set", "--fit", "fit", "--device", SERIAL],
                ])
                self.assertNotIn("done.", output)

    def test_config_calendar_failure_reports_no_success_after_prior_screen_change(self):
        self.write_config(
            '<map><string name="fit">fit</string>\n'
            '<string name="calendar_url">https://example.test/feed.ics</string></map>')
        self.replies = {"calendar": {"body": {"ok": False, "detail": TOKEN}}}
        code, output, calls = self.run_restore("--config")
        self.assertNotEqual(code, 0)
        self.assertEqual([args[0] for args in calls], ["screensaver", "calendar"])
        self.assertNotIn("done.", output)

    def test_config_private_plans_and_frozen_registry_are_removed_after_use(self):
        self.write_config(
            '<map><string name="fit">fit</string>'
            '<string name="calendar_url">https://example.test/feed.ics</string></map>')
        self.replies = {
            "mutate_original_registry": True,
            "expected_registry_record": json.loads((self.registry / (SERIAL + ".json")).read_text()),
        }
        code, output, calls = self.run_restore("--config")
        self.assertEqual(code, 0, output)
        self.assertEqual([args[0] for args in calls], ["screensaver", "calendar"])
        observations = [json.loads(line) for line in self.registry_observations.read_text().splitlines()]
        self.assertEqual(len(observations), 2)
        self.assertTrue((self.registry / "public-injected-room.json").exists())
        for observation in observations:
            self.assertTrue(observation["selected_record_matches"])
            self.assertEqual(observation["record_count"], 1)
            self.assertEqual(observation["directory_mode"], 0o700)
            self.assertEqual(observation["scratch_mode"], 0o700)
            self.assertEqual(observation["file_modes"], [0o600])
            self.assertEqual(observation["plan_file_modes"], [0o600, 0o600])
            self.assertFalse(Path(observation["registry_path"]).exists())


if __name__ == "__main__":
    unittest.main()

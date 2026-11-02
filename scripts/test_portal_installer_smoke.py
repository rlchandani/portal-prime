#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Mocked subprocess boundaries; never runs adb, Fleet, or builds."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest

import portal_installer_smoke as smoke


APP = "com.immortal.launcher.debug"
TEST = APP + ".test"
COMPONENT = APP + "/com.immortal.launcher.InstallConfirmService"
SERVICE = (
    " * ServiceRecord{abc u0 " + COMPONENT + "}\n"
    "   binder=android.os.BinderProxy@123\n"
    "   received=true hasBound=true\n"
    "   ConnectionRecord{def u0 CR " + COMPONENT + ":@abc}\n"
)
ACCESS = " Bound services:\n Binding services:\n Enabled services:\n"
PRIVATE = "private-registry-token"


class Runner:
    def __init__(self):
        self.calls = []
        self.deployed = False
        self.deploy_result = 0
        self.before = {}
        self.after = {}
        self.private_dirs = []

    def __call__(self, command, **kwargs):
        self.calls.append((command, kwargs))
        if command[0] == sys.executable:
            self.deployed = True
            if self.deploy_result == "timeout":
                raise subprocess.TimeoutExpired(command, 135, output=PRIVATE)
            if self.deploy_result == "oserror":
                raise OSError(PRIVATE)
            return subprocess.CompletedProcess(command, self.deploy_result, PRIVATE, PRIVATE)
        if command[:3] != ["adb", "-s", "usb-serial"]:
            raise AssertionError("Unexpected device command")
        args = command[3:]
        state = self.after if self.deployed else self.before
        if state.get("read_error") == args[0]:
            raise subprocess.TimeoutExpired(command, 30, output=PRIVATE)
        if state.get("nonzero") == args[0]:
            return subprocess.CompletedProcess(command, 1, PRIVATE, PRIVATE)
        if args[:3] == ["shell", "pm", "path"]:
            pkg = args[3]
            output = state.get(pkg + "-path", "package:/data/app/" + pkg + "-abc/base.apk\n")
        elif args[0] == "pull":
            self.private_dirs.append(Path(args[2]).parent)
            if stat.S_IMODE(Path(args[2]).parent.stat().st_mode) != 0o700:
                raise AssertionError("APK temporary directory is not private")
            pkg = TEST if TEST in args[1] else APP
            if not state.get("skip_pull"):
                Path(args[2]).write_bytes(state.get(pkg, pkg.encode()))
            output = PRIVATE
        elif args[:4] == ["shell", "settings", "get", "secure"]:
            output = state.get(args[4], COMPONENT + "\n" if
                               args[4] == "enabled_accessibility_services" else "1\n")
        elif args == ["shell", "getprop", "ro.serialno"]:
            output = state.get("serial", "usb-serial\n")
        elif args == ["shell", "dumpsys", "accessibility"]:
            output = state.get("access", ACCESS)
        elif args == ["shell", "dumpsys", "activity", "services", COMPONENT]:
            output = state.get("service", SERVICE)
        else:
            raise AssertionError("Forbidden or unexpected command")
        return subprocess.CompletedProcess(command, 0, output, PRIVATE)


class InstallerSmokeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.fixture = Path(self.temp.name) / "original.apk"
        self.fixture.write_bytes(TEST.encode())
        self.registry = Path(self.temp.name) / "registry"
        self.registry.mkdir()
        (self.registry / "family.json").write_text(json.dumps({
            "name": "CurrentFamily", "serial": "usb-serial", "token": PRIVATE,
        }))
        self.args = [
            "--adb-serial", "usb-serial", "--device", "CurrentFamily",
            "--cli", "/fleetctl", "--registry", str(self.registry),
            "--fixture-apk", str(self.fixture),
            "--expected-fixture-sha256", hashlib.sha256(TEST.encode()).hexdigest(),
            "--expected-app-sha256", hashlib.sha256(APP.encode()).hexdigest(),
        ]
        self.runner = Runner()

    def invoke(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = smoke.main(self.args, runner=self.runner)
        self.assertEqual("", err.getvalue())
        self.assertNotIn(PRIVATE, out.getvalue())
        return code, json.loads(out.getvalue())

    def test_success_uses_real_helper_and_only_allowed_commands(self):
        code, result = self.invoke()
        self.assertEqual(0, code)
        self.assertEqual("passed", result["status"])
        deploy = [call for call in self.runner.calls if call[0][0] == sys.executable]
        self.assertEqual(1, len(deploy))
        self.assertEqual([
            sys.executable, str(Path(smoke.__file__).resolve().parents[1] /
                                "provisioning/room-fleet.py"),
            "--cli", "/fleetctl", "--registry", str(self.registry),
            "deploy", str(self.fixture), "--package", TEST, "--device", "CurrentFamily",
        ], deploy[0][0])
        self.assertEqual(135, deploy[0][1]["timeout"])
        for command, kwargs in self.runner.calls:
            self.assertTrue(kwargs["capture_output"])
            self.assertGreater(kwargs["timeout"], 0)
            self.assertLessEqual(kwargs["timeout"], 135)
            self.assertTrue(set(command).isdisjoint(
                {"instrument", "force-stop", "install", "uninstall", "put", "input",
                 "uiautomator", "logcat", "run-as", "start", "monkey"}))
        pulls = [command for command, _ in self.runner.calls if command[3:4] == ["pull"]]
        self.assertEqual(4, len(pulls))
        self.assertTrue(all(path.name == "base.apk" for path in map(
            lambda command: Path(command[4]), pulls)))
        self.assertTrue(all(not path.exists() for path in self.runner.private_dirs))

    def test_preflight_rejects_local_wrong_hash_before_any_device_command(self):
        self.fixture.write_bytes(b"different")
        self.assertEqual(1, self.invoke()[0])
        self.assertEqual([], self.runner.calls)

    def test_preflight_rejects_installed_hash_mismatch_or_missing_package(self):
        for pkg in (APP, TEST):
            for state in ({pkg: b"wrong"}, {pkg + "-path": ""}):
                with self.subTest(state=state):
                    self.runner = Runner()
                    self.runner.before = state
                    self.assertEqual(1, self.invoke()[0])
                    self.assertFalse(self.runner.deployed)

    def test_preflight_rejects_unsafe_or_multiple_apk_paths(self):
        for output in ("package:/data/user/0/private/base.apk\n",
                       "package:/data/app/" + TEST + "-abc/base.apk\npackage:/data/app/split.apk\n"):
            self.runner = Runner()
            self.runner.before[TEST + "-path"] = output
            self.assertEqual(1, self.invoke()[0])
            self.assertFalse(self.runner.deployed)

    def test_preflight_rejects_binding_stuck_even_with_connected_service(self):
        self.runner.before["access"] = ACCESS.replace(
            " Binding services:", " Binding services: " + APP + "/.OtherService")
        self.assertEqual(1, self.invoke()[0])
        self.assertFalse(self.runner.deployed)

    def test_preflight_requires_exact_connected_service_record(self):
        for service in ("", SERVICE.replace("InstallConfirmService", "InstallConfirmServiceExtra"),
                        SERVICE.replace("android.os.BinderProxy@123", "null"),
                        SERVICE.replace("received=true", "received=false"),
                        SERVICE.replace("hasBound=true", "hasBound=false"),
                        SERVICE.replace("ConnectionRecord{", "OtherRecord{"),
                        SERVICE.replace(" u0 CR ", " u0 DEAD CR "),
                        SERVICE.split("   ConnectionRecord")[0] +
                        " * ServiceRecord{123 u0 other/.Service}\n" +
                        SERVICE[SERVICE.index("   ConnectionRecord"):]):
            with self.subTest(service=service):
                self.runner = Runner()
                self.runner.before["service"] = service
                self.assertEqual(1, self.invoke()[0])
                self.assertFalse(self.runner.deployed)

    def test_nonzero_timeout_and_io_failure_always_run_postflight_and_fail(self):
        for outcome in (1, "timeout", "oserror"):
            with self.subTest(outcome=outcome):
                self.runner = Runner()
                self.runner.deploy_result = outcome
                code, result = self.invoke()
                self.assertEqual(1, code)
                self.assertEqual("failed", result["status"])
                self.assertEqual("observed_only" if outcome == "timeout" else "passed",
                                 result["postflight"])
                deploy_index = next(i for i, call in enumerate(self.runner.calls)
                                    if call[0][0] == sys.executable)
                self.assertEqual(9, len(self.runner.calls[deploy_index + 1:]))

    def test_postflight_detects_hash_settings_and_readiness_drift(self):
        for state in ({APP: b"changed"}, {TEST: b"changed"},
                      {"enabled_accessibility_services": COMPONENT + ":other/.Service\n"},
                      {"accessibility_enabled": "0\n"}, {"service": ""},
                      {"access": ACCESS.replace(" Binding services:",
                                               " Binding services: " + COMPONENT)}):
            with self.subTest(state=state):
                self.runner = Runner()
                self.runner.after = state
                code, result = self.invoke()
                self.assertEqual(1, code)
                self.assertEqual("failed", result["postflight"])

    def test_full_hash_detects_drift_beyond_first_streaming_chunk(self):
        original = b"a" * 131072 + b"original-tail"
        self.runner.before[APP] = original
        self.runner.after[APP] = original[:-1] + b"x"
        self.args[self.args.index("--expected-app-sha256") + 1] = hashlib.sha256(
            original).hexdigest()
        code, result = self.invoke()
        self.assertEqual(1, code)
        self.assertEqual("passed", result["preflight"])
        self.assertEqual("failed", result["postflight"])

    def test_other_package_binding_and_same_component_outside_block_are_allowed(self):
        self.runner.before["access"] = (
            " Bound services: " + COMPONENT + "\n"
            " Binding services: com.public.other/.Service\n"
            " Enabled services: " + COMPONENT + "\n")
        self.assertEqual(0, self.invoke()[0])

    def test_missing_binding_section_is_not_readiness(self):
        self.runner.before["access"] = " Bound services:\n Enabled services:\n"
        self.assertEqual(1, self.invoke()[0])
        self.assertFalse(self.runner.deployed)

    def test_preflight_adb_failures_do_not_deploy_or_leak_output(self):
        for state in ({"read_error": "shell"}, {"nonzero": "shell"},
                      {"read_error": "pull"}, {"skip_pull": True}):
            with self.subTest(state=state):
                self.runner = Runner()
                self.runner.before = state
                self.assertEqual(1, self.invoke()[0])
                self.assertFalse(self.runner.deployed)

    def test_postflight_read_failures_and_missing_pull_cannot_reuse_old_hash(self):
        for state in ({"read_error": "pull"}, {"nonzero": "shell"}, {"skip_pull": True}):
            with self.subTest(state=state):
                self.runner = Runner()
                self.runner.after = state
                code, result = self.invoke()
                self.assertEqual(1, code)
                self.assertEqual("passed", result["deploy"])
                self.assertEqual("failed", result["postflight"])

    def test_deploy_failure_and_postflight_drift_are_both_reported(self):
        self.runner.deploy_result = 1
        self.runner.after["accessibility_enabled"] = "0\n"
        code, result = self.invoke()
        self.assertEqual(1, code)
        self.assertEqual("failed", result["deploy"])
        self.assertEqual("failed", result["postflight"])
        self.assertEqual(2, len(result["failures"]))

    def test_invalid_arguments_and_all_target_are_sanitized(self):
        self.args += ["--unknown", PRIVATE]
        self.assertEqual(1, self.invoke()[0])
        self.assertEqual([], self.runner.calls)
        self.args = self.args[:-2]
        self.args[self.args.index("--device") + 1] = "all"
        self.assertEqual(1, self.invoke()[0])
        self.assertEqual([], self.runner.calls)

    def test_target_mismatch_never_deploys(self):
        self.runner.before["serial"] = "different-portal\n"
        code, result = self.invoke()
        self.assertEqual(1, code)
        self.assertIn("device_identity_mismatch", result["failures"])
        self.assertFalse(self.runner.deployed)

    def test_registry_missing_or_ambiguous_target_never_deploys(self):
        for payload in ({"name": "Other", "serial": "other"},
                        {"name": "CurrentFamily", "serial": "another-serial"}):
            with self.subTest(payload=payload):
                (self.registry / "other.json").write_text(json.dumps(payload))
                self.args[self.args.index("--device") + 1] = (
                    "Missing" if payload["name"] == "Other" else "CurrentFamily")
                self.runner = Runner()
                self.assertEqual(1, self.invoke()[0])
                self.assertFalse(self.runner.deployed)

    def test_postflight_checks_same_hardware_identity(self):
        self.runner.after["serial"] = "different-portal\n"
        code, result = self.invoke()
        self.assertEqual(1, code)
        self.assertEqual("failed", result["postflight"])

    def test_real_timeout_stops_descendant_before_late_write(self):
        marker = Path(self.temp.name) / "late-write"
        child_code = ("import time; from pathlib import Path; time.sleep(1.2); "
                      "Path(" + repr(str(marker)) + ").write_text('late')")
        parent_code = ("import subprocess,sys,time; subprocess.Popen("
                       "[sys.executable,'-c'," + repr(child_code) + "]); time.sleep(5)")
        with self.assertRaises(subprocess.TimeoutExpired):
            smoke.run_command([sys.executable, "-c", parent_code], capture_output=True,
                              text=True, errors="replace", check=False, timeout=0.5)
        import time
        time.sleep(1.3)
        self.assertFalse(marker.exists())


if __name__ == "__main__":
    unittest.main()

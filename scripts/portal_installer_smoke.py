#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Reinstall only an already-installed, byte-identical debug test fixture via Fleet.

ADB only reads dedicated APKs, secure settings, and service state. Child output
stays private; stdout contains one selected JSON summary. No instrumentation.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile


APP = "com.immortal.launcher.debug"
FIXTURE = APP + ".test"
COMPONENT = APP + "/com.immortal.launcher.InstallConfirmService"
HELPER = Path(__file__).resolve().parents[1] / "provisioning/room-fleet.py"


class SmokeError(Exception):
    """Only locally generated, credential-free failure codes."""


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise SmokeError("invalid_arguments")


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run_command(command, *, capture_output, check, timeout, **kwargs):
    """Stop the local helper and its descendants before timeout postflight."""
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          start_new_session=True, **kwargs) as child:
        try:
            stdout, stderr = child.communicate(timeout=timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            child.communicate()
            raise
        return subprocess.CompletedProcess(command, child.returncode, stdout, stderr)


def registered_serial(registry, device):
    """Project only identity fields; never expose registry contents or tokens."""
    try:
        root = Path(registry)
        if not root.is_dir():
            raise SmokeError("invalid_registry_target")
        matches = []
        for path in root.glob("*.json"):
            if path.is_symlink() or path.stat().st_size > 1024 * 1024:
                raise SmokeError("invalid_registry_target")
            data = json.loads(path.read_text(encoding="utf-8-sig"))
            if not isinstance(data, dict):
                raise SmokeError("invalid_registry_target")
            if device in (data.get("name"), data.get("serial")):
                matches.append(data.get("serial"))
        if (len(matches) != 1 or not isinstance(matches[0], str) or
                not re.fullmatch(r"[A-Za-z0-9_-]+", matches[0])):
            raise SmokeError("invalid_registry_target")
        return matches[0]
    except (OSError, ValueError):
        raise SmokeError("invalid_registry_target") from None


def connected(output):
    # A connection from another ServiceRecord cannot prove this service is bound.
    for block in re.split(r"(?=^[ \t]*\* ServiceRecord\{)", output, flags=re.M):
        if not re.match(r"[ \t]*\* ServiceRecord\{\w+ u0 " +
                        re.escape(COMPONENT) + r"\}", block):
            continue
        if (not re.search(r"binder=(?!null\b)\S+", block) or
                not re.search(r"\breceived=true\b", block) or
                not re.search(r"\bhasBound=true\b", block)):
            continue
        for record in re.findall(r"ConnectionRecord\{([^{}\n]+)\}", block):
            if (re.match(r"\w+ u0 ", record) and "DEAD" not in record.split() and
                    re.search(r" " + re.escape(COMPONENT) + r":@\w+$", record)):
                return True
    return False


def binding_clear(output):
    blocks = re.findall(r"^[ \t]*Binding services:(.*?)(?=^[ \t]*"
                        r"[A-Z][A-Za-z ]+:|\Z)", output, flags=re.M | re.S)
    return bool(blocks) and all(APP + "/" not in block for block in blocks)


def snapshot(args, directory, runner):
    def adb(*command):
        result = runner(["adb", "-s", args.adb_serial, *command],
                        capture_output=True, text=True, errors="replace",
                        check=False, timeout=30)
        if result.returncode:
            raise SmokeError("adb_read_failed")
        return result.stdout

    state = {"serial": adb("shell", "getprop", "ro.serialno").strip()}
    if state["serial"] != args.expected_device_serial:
        raise SmokeError("device_identity_mismatch")
    for package, key in ((APP, "app_sha256"), (FIXTURE, "fixture_sha256")):
        output = adb("shell", "pm", "path", package).strip()
        # Reject splits and arbitrary paths; only this package's installed base APK.
        match = re.fullmatch(r"package:(/data/app/(?:[A-Za-z0-9_=+~-]+/)?"
                             + re.escape(package) + r"-[A-Za-z0-9_=+~-]+/base\.apk)",
                             output)
        if not match:
            raise SmokeError("missing_or_unsupported_package")
        local = Path(directory) / (package + ".apk")
        local.unlink(missing_ok=True)
        adb("pull", match[1], str(local))
        state[key] = sha256(local)
    for setting in ("enabled_accessibility_services", "accessibility_enabled"):
        state[setting] = adb("shell", "settings", "get", "secure", setting)
    state["connected"] = connected(adb("shell", "dumpsys", "activity", "services", COMPONENT))
    state["binding_clear"] = binding_clear(adb("shell", "dumpsys", "accessibility"))
    return state


def validate(state, args, baseline=None):
    if (state["app_sha256"] != args.expected_app_sha256 or
            state["fixture_sha256"] != args.expected_fixture_sha256):
        raise SmokeError("installed_hash_mismatch")
    if not state["connected"] or not state["binding_clear"]:
        raise SmokeError("installer_not_ready")
    if baseline and any(state[key] != baseline[key] for key in
                        ("enabled_accessibility_services", "accessibility_enabled")):
        raise SmokeError("secure_settings_drift")


def main(argv=None, *, runner=None):
    runner = runner or run_command
    result = {"status": "failed", "preflight": "failed", "deploy": "not_run",
              "postflight": "not_run", "failures": []}
    phase = "preflight"
    try:
        parser = Parser(description=__doc__)
        for name in ("adb-serial", "device", "cli", "registry", "fixture-apk",
                     "expected-fixture-sha256", "expected-app-sha256"):
            parser.add_argument("--" + name, required=True)
        args = parser.parse_args(argv)
        if (args.device.casefold() == "all" or
                any(not value or "\n" in value or "\r" in value for value in vars(args).values())
                or any(not re.fullmatch("[a-f0-9]{64}", value) for value in
                       (args.expected_fixture_sha256, args.expected_app_sha256))):
            raise SmokeError("invalid_arguments")
        if sha256(args.fixture_apk) != args.expected_fixture_sha256:
            raise SmokeError("local_fixture_hash_mismatch")
        args.expected_device_serial = registered_serial(args.registry, args.device)
        with tempfile.TemporaryDirectory(prefix="portal-installer-smoke-") as directory:
            before = snapshot(args, directory, runner)
            validate(before, args)
            if sha256(args.fixture_apk) != args.expected_fixture_sha256:
                raise SmokeError("local_fixture_hash_mismatch")
            result["preflight"] = "passed"
            phase = "deploy"
            remote_completion_unknown = False
            try:
                child = runner([sys.executable, str(HELPER), "--cli", args.cli,
                                "--registry", args.registry, "deploy", args.fixture_apk,
                                "--package", FIXTURE, "--device", args.device],
                               capture_output=True, text=True, errors="replace",
                               check=False, timeout=135)
                if child.returncode:
                    raise SmokeError("deploy_nonzero")
                result["deploy"] = "passed"
            except (SmokeError, OSError, subprocess.TimeoutExpired) as error:
                result["deploy"] = "failed"
                remote_completion_unknown = isinstance(error, subprocess.TimeoutExpired)
                if remote_completion_unknown:
                    result["remoteCompletion"] = "unknown_after_timeout"
                result["failures"].append(str(error) if isinstance(error, SmokeError)
                                          else "deploy_timeout" if isinstance(
                                              error, subprocess.TimeoutExpired) else "deploy_io_failed")
            finally:
                phase = "postflight"
                validate(snapshot(args, directory, runner), args, before)
                result["postflight"] = "observed_only" if remote_completion_unknown else "passed"
    except (SmokeError, OSError, subprocess.TimeoutExpired) as error:
        result[phase] = "failed"
        result["failures"].append(str(error) if isinstance(error, SmokeError)
                                  else phase + "_verification_failed")
    if result["preflight"] == result["deploy"] == result["postflight"] == "passed":
        result["status"] = "passed"
    print(json.dumps(result, sort_keys=True))
    return int(result["status"] != "passed")


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Run the dedicated localhost artwork fixture on the parent's owned API 34 AVD.

Build/install and emulator lifecycle belong to the parent. This helper launches
only ArtworkCacheCheckActivity and reads only its sanitized receipt. No settings,
credentials, household data, audio, screenshots, logcat or external network.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time
import uuid

SERIAL = "emulator-5556"
AVD = "PortalMusic20261030"
PACKAGE = "com.immortal.launcher.debug"
COMPONENT = PACKAGE + "/com.immortal.launcher.ArtworkCacheCheckActivity"
RECEIPT = "files/artwork-cache-check.json"


class CheckFailure(Exception):
    """Messages are fixed local check codes, never device output."""


def require(condition, code):
    if not condition:
        raise CheckFailure(code)


def sanitized_receipt(raw, run_id):
    """Allowlist scalars and check codes; never forward arbitrary device strings."""
    require(len(raw) <= 32_768, "receipt_too_large")
    receipt = json.loads(raw)
    require(isinstance(receipt, dict), "receipt_shape")
    require(receipt.get("runId") == run_id, "receipt_run_id")
    require(receipt.get("complete") is True, "receipt_incomplete")
    require(type(receipt.get("success")) is bool, "receipt_success_type")
    require(receipt.get("api") == 34 and receipt.get("localhostOnly") is True
            and receipt.get("syntheticPngOnly") is True, "receipt_fixture_guard")
    allowed_checks = {
        "isolated_api34_base_application", "initial_warm_fetch_success",
        "warm_url_requested_once_before_cold_fetch",
        "cold_fetch_has_open_socket_and_held_body",
        "warm_fetch_completed_before_cold_body_release",
        "warm_fetch_before_release_has_same_bitmap_identity",
        "warm_fetch_eventually_has_same_bitmap_identity",
        "cold_fetch_eventually_success", "cold_bitmap_dimensions_at_most_384",
        "warm_url_one_network_request", "cold_url_one_network_request",
        "cold_server_sent_complete_png",
        "fixture_completed_without_coordinator_exception", "executors_terminated",
        "all_localhost_sockets_closed", "worker_exceptions_absent",
        "total_wait_budget_at_most_8_seconds",
    }
    checks = receipt.get("checks")
    require(isinstance(checks, list) and 1 <= len(checks) <= len(allowed_checks),
            "receipt_checks_shape")
    normalized_checks = []
    for check in checks:
        require(isinstance(check, dict) and check.get("name") in allowed_checks
                and type(check.get("passed")) is bool, "receipt_check_shape")
        normalized_checks.append({"name": check["name"], "passed": check["passed"]})
    require(len({check["name"] for check in normalized_checks}) == len(checks),
            "receipt_duplicate_check")
    passed = sum(check["passed"] for check in normalized_checks)
    require(receipt.get("passed") == passed and receipt.get("total") == len(checks),
            "receipt_counts")
    require(receipt["success"] == (passed == len(checks)), "receipt_success_consistency")
    if receipt["success"]:
        require(set(check["name"] for check in checks)
                == allowed_checks - {"fixture_completed_without_coordinator_exception"},
                "receipt_missing_acceptance_check")
    kinds = {"timeout", "interrupted", "io", "assertion", "unexpected"}
    stages = {"initial_warm_fetch", "cold_fetch", "cached_warm_fetch",
              "localhost_accept", "localhost_response"}
    errors = receipt.get("workerFailures")
    require(isinstance(errors, list) and len(errors) <= 16, "receipt_worker_failures")
    normalized_errors = []
    for error in errors:
        require(isinstance(error, dict) and error.get("stage") in stages
                and error.get("kind") in kinds, "receipt_worker_failure_shape")
        normalized_errors.append({"stage": error["stage"], "kind": error["kind"]})
    result = {
        "complete": True, "success": receipt["success"], "runId": run_id,
        "api": 34, "localhostOnly": True, "syntheticPngOnly": True,
        "passed": passed, "total": len(checks), "checks": normalized_checks,
        "workerFailures": normalized_errors,
    }
    for key in ("maxWaitMs", "warmWaitLimitMs", "sourceWidth", "sourceHeight",
                "pngBytes", "warmWaitMs", "coldWidth", "coldHeight", "warmRequests",
                "coldRequests", "elapsedMs"):
        value = receipt.get(key)
        if value is not None:
            require(type(value) is int and 0 <= value <= 1_000_000, "receipt_scalar")
            result[key] = value
    if "failure" in receipt:
        require(receipt["failure"] in kinds, "receipt_failure_kind")
        result["failure"] = receipt["failure"]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="Sanitized JSON report path")
    args = parser.parse_args()
    started = time.monotonic()
    deadline = started + 15
    run_id = uuid.uuid4().hex
    report = {"complete": True, "success": False, "serial": SERIAL, "avd": AVD, "api": 34}

    def adb(*command, cap=3):
        remaining = deadline - time.monotonic()
        require(remaining > 0, "helper_deadline")
        try:
            return subprocess.run(
                ["adb", "-s", SERIAL, *command], capture_output=True,
                timeout=min(cap, remaining), check=False,
            )
        except subprocess.TimeoutExpired:
            raise CheckFailure("adb_timeout") from None

    try:
        owner = adb("emu", "avd", "name")
        require(owner.returncode == 0 and owner.stdout.decode().strip().splitlines()
                == [AVD, "OK"], "owned_avd_guard")
        sdk = adb("shell", "getprop", "ro.build.version.sdk")
        require(sdk.returncode == 0 and sdk.stdout.strip() == b"34", "api34_guard")
        report["guardsPassed"] = True
        launch = adb("shell", "am", "start", "-W", "-n", COMPONENT,
                     "-f", "0x10008000", "--es", "run_id", run_id, cap=4)
        require(launch.returncode == 0 and b"Status: ok" in launch.stdout
                and b"Error:" not in launch.stdout, "dedicated_activity_launch")
        while time.monotonic() < deadline:
            receipt = adb("exec-out", "run-as", PACKAGE, "cat", RECEIPT, cap=1)
            if receipt.returncode == 0:
                try:
                    parsed = json.loads(receipt.stdout)
                except (ValueError, UnicodeDecodeError):
                    parsed = None
                if isinstance(parsed, dict) and parsed.get("runId") == run_id \
                        and parsed.get("complete") is True:
                    report.update(sanitized_receipt(receipt.stdout, run_id))
                    break
            time.sleep(min(0.15, max(0, deadline - time.monotonic())))
        else:
            raise CheckFailure("receipt_poll_deadline")
    except (Exception, KeyboardInterrupt) as error:
        report["success"] = False
        report["failure"] = str(error) if isinstance(error, CheckFailure) else "helper_exception"
    report["helperElapsedSeconds"] = round(time.monotonic() - started, 3)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"success": report["success"], "passed": report.get("passed", 0),
                      "total": report.get("total", 0)}))
    return 0 if report["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Run the synthetic local-artwork fixture on the parent's owned API 34 AVD.

Build/install and emulator lifecycle belong to the parent. This helper launches
only LocalArtworkCheckActivity and reads only its sanitized JSON receipt.
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
COMPONENT = PACKAGE + "/com.immortal.launcher.LocalArtworkCheckActivity"
RECEIPT = "files/local-artwork-check.json"
CHECKS = {
    "isolated_api34_base_application", "first_provider_held_after_first_byte",
    "second_fetch_queued_before_cancellation", "queued_future_cancelled",
    "cancelled_worker_returned_before_release", "two_other_fetches_queued_before_release",
    "one_provider_open_before_release", "cancelled_path_not_opened_before_release",
    "first_body_still_held_before_release", "first_decoded_at_most_384",
    "other_one_decoded_at_most_384", "other_two_decoded_at_most_384",
    "first_producer_sent_complete_png", "first_producer_finished",
    "cancelled_path_never_opened", "exactly_three_provider_opens",
    "fetch_workers_terminated", "provider_workers_terminated",
    "first_producer_terminated", "all_pipe_descriptors_closed",
    "worker_exceptions_absent", "total_wait_budget_at_most_8_seconds",
}
KINDS = {"timeout", "interrupted", "io", "assertion", "unexpected"}
STAGES = {
    "fetch_first", "fetch_cancelled", "fetch_other_one", "fetch_other_two",
    "provider_first", "provider_cancelled", "provider_other_one", "provider_other_two",
    "provider_close",
}
SCALARS = {
    "maxWaitMs", "sourceWidth", "sourceHeight", "pngBytes", "opensBeforeRelease",
    "firstWidth", "firstHeight", "other_oneWidth", "other_oneHeight",
    "other_twoWidth", "other_twoHeight", "providerOpens", "cancelledOpens", "elapsedMs",
}


class CheckFailure(Exception):
    """Messages are fixed local check codes, never device output."""


def require(condition, code):
    if not condition:
        raise CheckFailure(code)


def sanitized_receipt(raw, run_id):
    """Copy only fixed check names, fixed failure categories, and bounded scalars."""
    require(len(raw) <= 32_768, "receipt_too_large")
    receipt = json.loads(raw)
    require(isinstance(receipt, dict), "receipt_shape")
    require(receipt.get("runId") == run_id, "receipt_run_id")
    require(receipt.get("complete") is True, "receipt_incomplete")
    require(type(receipt.get("success")) is bool, "receipt_success_type")
    require(type(receipt.get("api")) is int and receipt["api"] == 34
            and receipt.get("syntheticProviderOnly") is True
            and receipt.get("syntheticPngOnly") is True, "receipt_fixture_guard")
    checks = receipt.get("checks")
    allowed = CHECKS | {"fixture_completed_without_coordinator_exception"}
    require(isinstance(checks, list) and 1 <= len(checks) <= len(allowed),
            "receipt_checks_shape")
    normalized_checks = []
    for check in checks:
        require(isinstance(check, dict) and isinstance(check.get("name"), str)
                and check["name"] in allowed and type(check.get("passed")) is bool,
                "receipt_check_shape")
        normalized_checks.append({"name": check["name"], "passed": check["passed"]})
    require(len({check["name"] for check in normalized_checks}) == len(checks),
            "receipt_duplicate_check")
    passed = sum(check["passed"] for check in normalized_checks)
    require(type(receipt.get("passed")) is int and receipt["passed"] == passed
            and type(receipt.get("total")) is int and receipt["total"] == len(checks),
            "receipt_counts")
    require(receipt["success"] == (passed == len(checks)), "receipt_success_consistency")
    if receipt["success"]:
        require({check["name"] for check in checks} == CHECKS,
                "receipt_missing_acceptance_check")
    errors = receipt.get("workerFailures")
    require(isinstance(errors, list) and len(errors) <= 32, "receipt_worker_failures")
    normalized_errors = []
    for error in errors:
        require(isinstance(error, dict) and isinstance(error.get("stage"), str)
                and error["stage"] in STAGES and isinstance(error.get("kind"), str)
                and error["kind"] in KINDS, "receipt_worker_failure_shape")
        normalized_errors.append({"stage": error["stage"], "kind": error["kind"]})
    result = {
        "complete": True, "success": receipt["success"], "runId": run_id,
        "api": 34, "syntheticProviderOnly": True, "syntheticPngOnly": True,
        "passed": passed, "total": len(checks), "checks": normalized_checks,
        "workerFailures": normalized_errors,
    }
    for key in SCALARS:
        value = receipt.get(key)
        if value is not None:
            require(type(value) is int and 0 <= value <= 1_000_000, "receipt_scalar")
            result[key] = value
    if "failure" in receipt:
        require(isinstance(receipt["failure"], str) and receipt["failure"] in KINDS,
                "receipt_failure_kind")
        result["failure"] = receipt["failure"]
    if receipt["success"]:
        require(not normalized_errors and "failure" not in receipt, "receipt_success_errors")
        require(receipt.get("maxWaitMs") == 8_000 and receipt.get("elapsedMs", 8_001) <= 8_000
                and receipt.get("sourceWidth") == 768 and receipt.get("sourceHeight") == 512,
                "receipt_success_bounds")
        require(receipt.get("opensBeforeRelease") == 1
                and receipt.get("providerOpens") == 3
                and receipt.get("cancelledOpens") == 0, "receipt_success_opens")
        for stage in ("first", "other_one", "other_two"):
            require(1 <= receipt.get(stage + "Width", 0) <= 384
                    and 1 <= receipt.get(stage + "Height", 0) <= 384,
                    "receipt_success_dimensions")
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
                require(len(receipt.stdout) <= 32_768, "receipt_too_large")
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

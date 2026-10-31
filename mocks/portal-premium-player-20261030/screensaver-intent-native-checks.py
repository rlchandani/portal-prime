#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Parent-owned native singleTask check; viewer taps may need Back to return."""
import argparse
import json
import os
import pathlib
import re
import subprocess
import tempfile
import time

SERIAL, AVD, PACKAGE = "emulator-5556", "PortalMusic20261030", "com.immortal.launcher.debug"
HOME = PACKAGE + "/com.immortal.launcher.HomeActivity"
PREVIEW = PACKAGE + "/com.immortal.launcher.PhotoFramePreviewActivity"
EXTRA = "launch_dismiss_app"  # PhotoFramePreviewActivity.EXTRA_LAUNCH_DISMISS_APP
EVENT = re.compile(r"onNewIntent \(reused instance\): launchDismiss=(true|false)")


class CheckFailure(Exception): pass


def require(condition, code):
    if not condition:
        raise CheckFailure(code)


class Driver:
    def __init__(self, private, started):
        self.private, self.deadline = private, started + 48
        self.final_deadline = started + 60
        self.sequence, self.guarded = 0, False
        self.report = {"passed": False, "serial": SERIAL, "avd": AVD, "api": 34,
                       "tap_exit": False, "back_return": False, "home_restored": False}

    def adb(self, *args):
        remaining = self.deadline - time.monotonic()
        require(remaining > 0, "whole_test_budget_exhausted")
        self.sequence += 1
        try:
            result = subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True,
                                    timeout=min(8, remaining), check=False)
        except subprocess.TimeoutExpired as error:
            self.save("timeout", error.stdout or b"", error.stderr or b"")
            raise CheckFailure("adb_command_timeout") from None
        self.save("command", result.stdout, result.stderr)
        require(result.returncode == 0, "adb_command_failed")
        return result.stdout.decode("utf-8", errors="replace")

    def save(self, kind, stdout, stderr):
        stem = f"{self.sequence:02d}-{kind}"
        (self.private / (stem + ".stdout")).write_bytes(stdout)
        (self.private / (stem + ".stderr")).write_bytes(stderr)

    def guard(self, digest):
        require(self.adb("get-serialno").strip() == SERIAL, "serial_guard_failed")
        require(self.adb("emu", "avd", "name").splitlines() == [AVD, "OK"],
                "avd_guard_failed")
        require(self.adb("shell", "getprop", "ro.build.version.sdk").strip() == "34",
                "api_guard_failed")
        paths = self.adb("shell", "pm", "path", PACKAGE).splitlines()
        require(len(paths) == 1 and
                re.fullmatch(r"package:/data/app/[A-Za-z0-9_./=+~-]+/base\.apk", paths[0]),
                "single_debug_apk_guard_failed")
        hashed = self.adb("exec-out", "sha256sum", paths[0][8:]).split()
        require(hashed and hashed[0].lower() == digest, "installed_apk_sha256_mismatch")
        self.guarded = True
        self.report["apk_guard"] = True
        self.marker()

    def marker(self):
        (self.private / "recovery.json").write_text(json.dumps({
            "serial": SERIAL, "avd": AVD, "component": HOME,
            "home_restored": self.report["home_restored"],
            "parent_owns_avd": True,
        }) + "\n")

    def records(self):
        text = self.adb("shell", "dumpsys", "activity", "activities")
        records = re.findall(r"(?m)^\s*\* Hist\s+#\d+: ActivityRecord\{([^}]+)\}", text)
        previews = [r.split()[0] for r in records if PREVIEW in r]
        resumed = re.findall(
            r"(?:mResumedActivity|topResumedActivity):?\s*=?\s*ActivityRecord\{([^}]+)\}", text)
        return previews, resumed

    def pid(self):
        value = self.adb("shell", "pidof", PACKAGE).strip()
        require(re.fullmatch(r"[1-9]\d*", value), "single_app_pid_required")
        return int(value)

    def logs(self, pid):
        return self.adb("logcat", "-d", "-v", "epoch", "--pid", str(pid),
                        "-s", "ImmortalFrame:I", "*:S")

    def events(self, text):
        return [(line, match[1]) for line in text.splitlines() if (match := EVENT.search(line))]

    def launch(self, component, flag=None):
        args = ["shell", "am", "start", "-W", "-n", component]
        if flag is not None: args += ["--ez", EXTRA, flag]
        output = self.adb(*args)
        require("Status: ok" in output and "Error:" not in output, "activity_start_failed")

    def preview_identity(self, token, pid):
        previews, resumed = self.records()
        require(previews == [token] and any(
            PREVIEW in r and r.split()[0] == token for r in resumed),
            "preview_record_or_focus_changed")
        require(self.pid() == pid, "app_pid_changed")

    def new_intent(self, flag, token, pid, before):
        self.launch(PREVIEW, flag)
        self.preview_identity(token, pid)
        after = self.events(self.logs(pid))
        require(after[:len(before)] == before and
                [value for _, value in after[len(before):]] == [flag],
                "on_new_intent_log_boundary_failed")
        self.report["on_new_intent_" + flag] = True
        return after

    def home(self):
        outer_deadline = self.deadline
        self.deadline = min(outer_deadline, time.monotonic() + 2)
        try:
            while time.monotonic() < self.deadline:
                previews, resumed = self.records()
                if not previews and any(HOME in r for r in resumed):
                    return
                time.sleep(min(.1, max(0, self.deadline - time.monotonic())))
            raise CheckFailure("home_return_failed")
        finally:
            self.deadline = outer_deadline

    def check(self):
        self.launch(HOME)
        previews, _ = self.records()
        require(not previews, "prior_preview_present_parent_recovery_required")
        self.launch(PREVIEW, "false")
        previews, resumed = self.records()
        require(len(previews) == 1 and any(PREVIEW in r for r in resumed),
                "initial_preview_not_resumed")
        token, pid = previews[0], self.pid()
        self.report.update({"activity_token": token, "pid": pid})
        before = self.events(self.logs(pid))
        before = self.new_intent("true", token, pid, before)
        self.new_intent("false", token, pid, before)
        self.report.update({"same_activity_record": True, "same_pid": True})
        # WindowManager's current geometry includes rotation, unlike wm size.
        screen = self.adb("shell", "dumpsys", "window", "displays")
        display = re.search(r"Display: mDisplayId=0\b(.*?)(?=\n\s*Display: mDisplayId=|\Z)",
                            screen, re.S)
        size = re.search(r"\bcur=(\d+)x(\d+)", display.group(1)) if display else None
        require(size is not None, "screen_geometry_unavailable")
        width, height = map(int, size.groups())
        require(200 <= width <= 8192 and 200 <= height <= 8192, "screen_geometry_invalid")
        log_before_tap = self.logs(pid)
        self.adb("shell", "input", "tap", str(width // 2), str(height // 2))
        time.sleep(min(.3, max(0, self.deadline - time.monotonic())))
        previews, _ = self.records()
        if previews:
            self.preview_identity(token, pid)
            self.report["viewer_retained_after_tap"] = True
            self.adb("shell", "input", "keyevent", "KEYCODE_BACK")
            self.home()
            self.report["back_return"] = True
        else:
            exits = lambda text: [line for line in text.splitlines()
                                  if "onExit (tap) -> finish(), launchDismiss=" in line]
            old, new = exits(log_before_tap), exits(self.logs(pid))
            require(new[:len(old)] == old and len(new) == len(old) + 1 and
                    new[-1].endswith("launchDismiss=false"), "tap_exit_log_boundary_failed")
            self.home()
            self.report["tap_exit"] = True
        require(self.pid() == pid, "return_app_pid_changed")
        self.report["passed"] = True


def main():
    started = time.monotonic()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--private-dir", required=True, type=pathlib.Path)
    parser.add_argument("--expected-apk-sha256", required=True)
    args = parser.parse_args()
    os.umask(0o077)
    driver = None
    try:
        require(re.fullmatch(r"[0-9a-fA-F]{64}", args.expected_apk_sha256),
                "expected_apk_sha256_invalid")
        private = args.private_dir.expanduser().resolve()
        require(not any((p / ".git").exists() for p in [private, *private.parents]),
                "private_dir_must_be_outside_checkout")
        private.mkdir(parents=True, mode=0o700, exist_ok=True)
        require(private.stat().st_uid == os.getuid() and
                private.stat().st_mode & 0o077 == 0, "private_dir_permissions_invalid")
        driver = Driver(pathlib.Path(tempfile.mkdtemp(prefix="screensaver-intent-", dir=private)), started)
        driver.guard(args.expected_apk_sha256.lower())
        driver.check()
    except Exception as error:
        if driver:
            driver.report["passed"] = False
            driver.report["failure"] = str(error) if isinstance(error, CheckFailure) else "internal_error"
    finally:
        if driver:
            if driver.guarded:
                driver.deadline = driver.final_deadline
                try:
                    driver.launch(HOME)
                    driver.home()
                    driver.report["home_restored"] = True
                except Exception:
                    driver.report["home_restored"] = False
                if not driver.report["home_restored"]:
                    driver.report.update({"passed": False, "failure": "final_home_recovery_failed"})
            driver.marker()
            (driver.private / "report.json").write_text(json.dumps(driver.report, indent=2) + "\n")
    receipt = {"passed": bool(driver and driver.report["passed"]),
               "home_restored": bool(driver and driver.report["home_restored"]),
               "tap_exit": bool(driver and driver.report["tap_exit"]),
               "back_return": bool(driver and driver.report["back_return"])}
    print(json.dumps(receipt, sort_keys=True))
    return 0 if receipt["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

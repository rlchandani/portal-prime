#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Bounded, explicit-device UX evidence. --self-test never invokes adb."""

import argparse
import csv
import datetime
import hashlib
import io
import json
import math
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import subprocess
import sys
import time
import unittest
from unittest.mock import patch


class OfflineTests(unittest.TestCase):
    """Hand-derived fixtures guard frame filtering, quantiles and safe commands."""

    def setUp(self):
        for name in ("parse_framestats", "parse_start", "parse_meminfo",
                     "parse_package", "start_args", "is_focused"):
            self.assertTrue(callable(globals().get(name)), f"{name} is not implemented")

    def test_legacy_chunks_flags_sentinels_and_duplicates(self):
        # Accepting flagged/unfinished frames or stopping at the first CSV chunk is a bug.
        fixture = """Window: private-window
---PROFILEDATA---
Flags,IntendedVsync,HandleInputStart,SyncQueued,SyncStart,SwapBuffers,FrameCompleted,
0,100000000,101000000,104000000,105000000,109000000,110000000,
1,200000000,201000000,204000000,205000000,209000000,250000000,
0,300000000,301000000,304000000,305000000,309000000,9223372036854775807,
0,0,0,0,0,0,0,
0,400000000,401000000,404000000,405000000,409000000,399000000,
bad,row,
---PROFILEDATA---
Window: another-private-window
---PROFILEDATA---
FrameCompleted,Flags,IntendedVsync,HandleInputStart,SyncQueued,SyncStart,SwapBuffers,
220000000,0,200000000,201000000,204000000,205000000,209000000,
220000000,0,200000000,201000000,204000000,205000000,209000000,
---PROFILEDATA---
"""
        result = parse_framestats(fixture, 60)
        self.assertEqual(result["drawn_frames"], 2)
        self.assertEqual(result["profile_chunks"], 2)
        self.assertEqual(result["excluded"]["flagged"], 1)
        self.assertEqual(result["excluded"]["invalid"], 4)
        self.assertEqual(result["excluded"]["duplicate"], 1)
        self.assertEqual(result["elapsed_ms"]["p50"], 15.0)
        self.assertEqual(result["elapsed_ms"]["p90"], 19.0)
        self.assertEqual(result["elapsed_ms"]["p95"], 19.5)
        self.assertEqual(result["elapsed_ms"]["p99"], 19.9)
        self.assertEqual(result["over_budget_frames"], 1)
        self.assertEqual(result["cpu_stage_elapsed_ms"]["p50"], 7.0)
        self.assertIsNone(result["deadline_miss_percent"])
        self.assertNotIn("private-window", str(result))

    def test_actual_deadline_cpu_and_same_vsync_different_windows(self):
        fixture = """Window: one
---PROFILEDATA---
Flags,FrameCompleted,IntendedVsync,FrameDeadline,CpuCompleted,FrameInterval,
0,120000000,100000000,125000000,115000000,25000000,
---PROFILEDATA---
Window: two
---PROFILEDATA---
Flags,FrameCompleted,IntendedVsync,FrameDeadline,CpuCompleted,FrameInterval,
0,124000000,100000000,123000000,116000000,25000000,
0,219000000,200000000,9223372036854775807,0,0,
---PROFILEDATA---
"""
        result = parse_framestats(fixture, 60)
        self.assertEqual(result["drawn_frames"], 3)
        self.assertEqual(result["frames_with_deadline"], 2)
        self.assertEqual(result["deadline_miss_frames"], 1)
        self.assertEqual(result["deadline_miss_percent"], 50.0)
        self.assertEqual(result["cpu_completion_elapsed_ms"]["p50"], 15.5)
        self.assertEqual(result["reported_frame_interval_ms"]["p50"], 25.0)

    def test_empty_is_unavailable_not_a_pass(self):
        result = parse_framestats("No process found", 60)
        self.assertEqual(result["drawn_frames"], 0)
        self.assertIsNone(result["elapsed_ms"]["p99"])
        self.assertIsNone(result["over_budget_percent"])
        self.assertEqual(result["assessment"], "insufficient_frames")

    def test_only_start_numbers_are_retained(self):
        result = parse_start("Status: ok\nLaunchState: COLD\nActivity: private\n"
                             "ThisTime: 121\nTotalTime: 140\nWaitTime: 150\nsecret")
        self.assertEqual(result, {"status": "ok", "launch_state": "cold",
                                 "this_time_ms": 121, "total_time_ms": 140,
                                 "wait_time_ms": 150})
        self.assertEqual(parse_start("Error: private details")["status"], "error")

    def test_pss_and_metadata_are_allowlisted(self):
        result = parse_meminfo("secret\n TOTAL 8192 30 12 4096 0 1 2\n"
                               " TOTAL PSS: 9000 TOTAL RSS: 16000 TOTAL SWAP PSS: 0")
        self.assertEqual(result["total_pss_kib"], 9000)
        self.assertEqual(parse_meminfo(" TOTAL 8192 30 12 4096")["total_pss_kib"], 8192)
        self.assertIsNone(parse_meminfo("No process found")["total_pss_kib"])
        self.assertEqual(parse_package("versionCode=42 minSdk=24\n"
                                       "versionName=0.4\n flags=[ DEBUGGABLE HAS_CODE ]\nsecret"),
                         {"version_code": 42, "version_name": "0.4", "debuggable": True})

    def test_private_components_use_run_as_user_zero(self):
        args = start_args("com.immortal.launcher.debug", "settings")
        self.assertEqual(args[:8], ["run-as", "com.immortal.launcher.debug", "am",
                                   "start", "--user", "0", "-W", "--activity-clear-top"])
        self.assertEqual(args[-1],
                         "com.immortal.launcher.debug/com.immortal.launcher.ImmortalSettingsActivity")
        self.assertNotIn("-S", args)
        with self.assertRaises(ValueError):
            start_args("com.immortal.launcher.debug", "lamp")

    def test_focus_rejects_foreign_or_unknown_activity(self):
        self.assertTrue(is_focused("mCurrentFocus=Window{abc u0 "
                                   "com.immortal.launcher.debug/com.immortal.launcher.StoreActivity}",
                                   "com.immortal.launcher.debug", "store"))
        self.assertFalse(is_focused("mCurrentFocus=Window{abc u0 other.app/.Main}",
                                    "com.immortal.launcher.debug", "store"))
        self.assertFalse(is_focused("mCurrentFocus=null", "com.immortal.launcher.debug", "home"))

    def test_package_only_window_title_uses_focused_app_but_never_foreign_overlay(self):
        text = ("mCurrentFocus = Window{abc u0 com.immortal.launcher.debug}\n"
                "mFocusedApp=AppWindowToken{abc token=ActivityRecord{def u0 "
                "com.immortal.launcher.debug/com.immortal.launcher.HomeActivity t12}}")
        self.assertTrue(is_focused(text, "com.immortal.launcher.debug", "home"))
        self.assertFalse(is_focused(text.replace("u0 com.immortal.launcher.debug}",
                                                "u0 com.android.permissioncontroller}"),
                                    "com.immortal.launcher.debug", "home"))
        self.assertFalse(is_focused(text.replace("Window{abc u0 com.immortal.launcher.debug}",
                                                "null"),
                                    "com.immortal.launcher.debug", "home"))
        # App focus alone cannot establish who receives injected input.
        self.assertFalse(is_focused(text.split("\n")[1], "com.immortal.launcher.debug", "home"))
        self.assertFalse(is_focused(text.replace("HomeActivity", "HelpActivity"),
                                    "com.immortal.launcher.debug", "home"))

    def test_adb_error_is_sanitized_and_serial_timeout_are_enforced(self):
        adb = Adb("/unused/fake-adb", "explicit-serial", 3)
        with patch.object(subprocess, "Popen") as spawn:
            process = spawn.return_value
            process.communicate.return_value = (b"private-token", b"private-ui")
            process.returncode = 1
            with self.assertRaises(ProbeError) as failure:
                adb.run(["shell", "get-state"], "state")
            self.assertNotIn("private", str(failure.exception))
            self.assertEqual(spawn.call_args.args[0][:3],
                             ["/unused/fake-adb", "-s", "explicit-serial"])
            self.assertEqual(process.communicate.call_args.kwargs["timeout"], 3)
        with self.assertRaises(ValueError):
            Adb("/unused", "serial", 31)

    def test_timeout_and_cancel_kill_the_child(self):
        for error in (subprocess.TimeoutExpired("fake", 2), KeyboardInterrupt()):
            with patch.object(subprocess, "Popen") as spawn, patch.object(os, "killpg") as kill:
                process = spawn.return_value
                process.pid = 123
                process.communicate.side_effect = error
                process.wait.return_value = 0
                with self.assertRaises(ProbeError if isinstance(error, subprocess.TimeoutExpired)
                                       else KeyboardInterrupt):
                    Adb("/unused", "serial", 2).run(["shell", "wm", "size"], "size")
                kill.assert_called_once_with(123, signal.SIGKILL)
                process.wait.assert_called_once_with(timeout=1)

    def test_viewport_and_no_home_swipe(self):
        self.assertEqual(parse_viewport("Display: mDisplayId=0 init=1920x1080 "
                                       "cur=1920x1080 app=1920x1080"), (1920, 1080))
        self.assertEqual(swipe_args("settings", (1920, 1080), 0),
                         ["input", "swipe", "1344", "702", "1344", "378", "220"])
        self.assertIsNone(swipe_args("home", (1920, 1080), 0))

    def test_collect_only_reads_one_buffer_without_launch_reset_or_input(self):
        args = arguments(["--collect-only", "--serial", "fixture"])
        calls = []

        def fake_run(_adb, command, operation, binary=False):
            calls.append(command)
            if command == ["get-state"]:
                return "device\n"
            remote = shlex.split(command[1])
            if remote[:2] == ["pm", "path"]:
                return "package:/private/app.apk\n"
            if remote[:2] == ["getprop", "ro.build.version.sdk"]:
                return "28\n"
            if remote[:2] == ["dumpsys", "package"]:
                return "versionCode=1\nversionName=fixture\nflags=[ DEBUGGABLE ]"
            if remote == ["dumpsys", "window"]:
                return "mCurrentFocus=Window{abc u0 com.immortal.launcher.debug/com.immortal.launcher.HomeActivity}"
            if remote[:2] == ["dumpsys", "gfxinfo"]:
                return "---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted,\n0,100000000,110000000,\n---PROFILEDATA---"
            if remote[:2] == ["dumpsys", "meminfo"]:
                return " TOTAL 8192 0 0 4096"
            self.fail("Unexpected command")

        report = {"samples": []}
        with patch.object(Adb, "run", fake_run):
            run_probe(Adb("/unused", "fixture", 1), args, report)
        self.assertEqual(report["samples"][0]["frames"]["drawn_frames"], 1)
        self.assertEqual(report["samples"][0]["memory"]["total_pss_kib"], 8192)
        remote_commands = [shlex.split(call[1]) for call in calls if call[0] == "shell"]
        self.assertEqual(sum(command[:2] == ["dumpsys", "gfxinfo"]
                             for command in remote_commands), 1)
        self.assertFalse(any("reset" in command or "am" in command or "input" in command
                             for command in remote_commands))

    def test_cli_requires_serial_and_bounds_timeout_before_any_adb(self):
        for extra in ([], ["--serial", "fixture", "--adb-timeout", "31"],
                      ["--serial", "fixture", "--refresh-hz", "nan"],
                      ["--serial", "fixture", "--package", "bad;command"]):
            with patch.object(sys, "stderr", io.StringIO()), self.assertRaises(SystemExit) as error:
                arguments(["--exercise"] + extra)
            self.assertEqual(error.exception.code, 2)

    def test_exercise_profiles_five_destinations_without_unsafe_actions(self):
        args = arguments(["--exercise", "--serial", "fixture", "--cycles", "1", "--swipes", "2"])
        current = "home"
        swipes = []

        def fake_run(_adb, command, operation, binary=False):
            nonlocal current
            if command == ["get-state"]:
                return "device"
            remote = shlex.split(command[1])
            if remote[:2] == ["pm", "path"]:
                return "package:/private/app.apk"
            if remote[:2] == ["dumpsys", "package"]:
                return "versionCode=1\nversionName=fixture\nflags=[ DEBUGGABLE ]"
            if remote == ["getprop", "ro.build.version.sdk"]:
                return "29"
            if remote == ["run-as", args.package, "id", "-u"]:
                return "10001"
            if remote == ["dumpsys", "window", "displays"]:
                return "cur=1920x1080"
            if "start" in remote:
                destination = remote[remote.index("-n") + 1]
                current = next(name for name in ACTIVITIES if component(args.package, name) == destination)
                self.assertEqual(remote, start_args(args.package, current))
                return "Status: ok\nLaunchState: WARM\nTotalTime: 100\nWaitTime: 110"
            if remote == ["dumpsys", "window"]:
                return f"mCurrentFocus=Window{{abc u0 {component(args.package, current)}}}"
            if remote == ["dumpsys", "gfxinfo", args.package, "reset"]:
                return "Graphics info reset"
            if remote == ["dumpsys", "gfxinfo", args.package, "framestats"]:
                return "---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted,\n0,100000000,110000000,\n---PROFILEDATA---"
            if remote == ["dumpsys", "meminfo", args.package]:
                return " TOTAL 8192 0 0 4096"
            if remote[:2] == ["input", "swipe"]:
                self.assertNotEqual(current, "home")
                self.assertEqual(remote[-1], "220")
                swipes.append(current)
                return ""
            self.fail("Unallowlisted command")

        report = {"samples": []}
        with patch.object(Adb, "run", fake_run), patch(__name__ + ".pause"):
            run_probe(Adb("/unused", "fixture", 1), args, report)
        self.assertEqual([sample["activity"] for sample in report["samples"]],
                         ["home", "settings", "tools", "store", "apps"])
        self.assertEqual(report["samples"][0]["injected_swipes"], 0)
        self.assertEqual(swipes, ["settings", "settings", "tools", "tools",
                                 "store", "store", "apps", "apps"])
        self.assertEqual(current, "home")
        self.assertTrue(all("navigation_frames" in sample and "interaction_frames" in sample
                            for sample in report["samples"]))


ACTIVITIES = {
    "home": "HomeActivity",
    "settings": "ImmortalSettingsActivity",
    "tools": "ToolsActivity",
    "store": "StoreActivity",
    "apps": "AppSwitcherActivity",
    "screensaver": "ScreensaverSettingsActivity",
}
MAX_TIMESTAMP = (1 << 63) - 1


def distribution(values):
    ordered = sorted(values)
    result = {"count": len(ordered)}
    for name, fraction in (("p50", .5), ("p90", .9), ("p95", .95), ("p99", .99)):
        if not ordered:
            result[name] = None
            continue
        position = (len(ordered) - 1) * fraction
        low = math.floor(position)
        high = math.ceil(position)
        result[name] = round(ordered[low] + (ordered[high] - ordered[low]) * (position - low), 4)
    result["max"] = round(ordered[-1], 4) if ordered else None
    return result


def timestamp(value):
    return isinstance(value, int) and 0 < value < MAX_TIMESTAMP


def parse_framestats(text, refresh_hz):
    """Parse all PROFILEDATA chunks by header, preserving distinct window frames."""
    elapsed, cpu, cpu_completion, intervals = [], [], [], []
    excluded = {"flagged": 0, "invalid": 0, "duplicate": 0}
    window, chunk, header, active = "default", 0, None, False
    seen = set()
    deadline_count = deadline_misses = 0
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("Window:"):
            # Used only in-memory for deduplication; never retained in evidence.
            window = line
        if line == "---PROFILEDATA---":
            active = not active
            header = None
            continue
        if not active or not line:
            continue
        columns = next(csv.reader([line]))
        columns = [item.strip() for item in columns]
        if columns and columns[-1] == "":
            columns.pop()
        if "Flags" in columns and "IntendedVsync" in columns and "FrameCompleted" in columns:
            header = columns
            chunk += 1
            continue
        if header is None:
            continue
        try:
            if len(columns) != len(header):
                raise ValueError()
            row = dict(zip(header, map(int, columns)))
            if row["Flags"] != 0:
                excluded["flagged"] += 1
                continue
            intended, completed = row["IntendedVsync"], row["FrameCompleted"]
            if not timestamp(intended) or not timestamp(completed) or completed <= intended:
                raise ValueError()
        except ValueError:
            excluded["invalid"] += 1
            continue
        identity = (window, intended, completed)
        if identity in seen:
            excluded["duplicate"] += 1
            continue
        seen.add(identity)
        elapsed.append((completed - intended) / 1_000_000)
        deadline = row.get("FrameDeadline")
        if timestamp(deadline) and deadline > intended:
            deadline_count += 1
            deadline_misses += int(completed > deadline)
        completion = row.get("CpuCompleted")
        if timestamp(completion) and intended <= completion <= completed:
            cpu_completion.append((completion - intended) / 1_000_000)
        # Legacy proxy: elapsed UI work + RenderThread work, excluding the sync queue gap.
        # These intervals include waits; gfxinfo does not expose actual CPU execution time.
        ui_start, queued = row.get("HandleInputStart"), row.get("SyncQueued")
        sync, swap = row.get("SyncStart"), row.get("SwapBuffers")
        if (all(timestamp(x) for x in (ui_start, queued, sync, swap))
                and intended <= ui_start <= queued <= sync <= swap <= completed):
            cpu.append((queued - ui_start + swap - sync) / 1_000_000)
        interval = row.get("FrameInterval")
        if timestamp(interval) and interval < 1_000_000_000:
            intervals.append(interval / 1_000_000)
    budget = 1000 / refresh_hz
    over = sum(item > budget for item in elapsed)
    return {
        "profile_chunks": chunk, "drawn_frames": len(elapsed), "excluded": excluded,
        "elapsed_ms": distribution(elapsed),
        "cpu_stage_elapsed_ms": distribution(cpu),
        "cpu_completion_elapsed_ms": distribution(cpu_completion),
        "reported_frame_interval_ms": distribution(intervals),
        "assumed_refresh_hz": refresh_hz, "budget_ms": round(budget, 6),
        "over_budget_frames": over,
        "over_budget_percent": round(100 * over / len(elapsed), 4) if elapsed else None,
        "frames_with_deadline": deadline_count, "deadline_miss_frames": deadline_misses,
        "deadline_miss_percent": round(100 * deadline_misses / deadline_count, 4)
        if deadline_count else None,
        "assessment": "insufficient_frames" if len(elapsed) < 120 else "review_required",
    }


def parse_start(text):
    error = bool(re.search(r"(?im)^\s*(?:Error:|Exception|SecurityException)", text))
    status = re.search(r"(?im)^\s*Status:\s*(\w+)", text)
    result = {"status": "error" if error else
              ("ok" if status and status[1].lower() == "ok" else "unavailable"),
              "launch_state": "unknown"}
    launch = re.search(r"(?im)^\s*LaunchState:\s*(COLD|WARM|HOT)\s*$", text)
    if launch:
        result["launch_state"] = launch[1].lower()
    for field, output in (("ThisTime", "this_time_ms"), ("TotalTime", "total_time_ms"),
                          ("WaitTime", "wait_time_ms")):
        value = re.search(rf"(?m)^\s*{field}:\s*(\d+)\s*$", text)
        result[output] = int(value[1]) if value else None
    return result


def parse_meminfo(text):
    value = re.search(r"TOTAL PSS:\s*(\d+)", text)
    if not value:
        value = re.search(r"(?m)^\s*TOTAL\s+(\d+)\s", text)
    return {"total_pss_kib": int(value[1]) if value else None}


def parse_package(text):
    code = re.search(r"\bversionCode=(\d+)", text)
    name = re.search(r"\bversionName=([a-zA-Z0-9_.+-]{1,80})(?:\s|$)", text)
    return {"version_code": int(code[1]) if code else None,
            "version_name": name[1] if name else None,
            "debuggable": bool(re.search(r"\bDEBUGGABLE\b", text))}


def component(package, activity):
    if activity not in ACTIVITIES:
        raise ValueError("Activity must be an allowlisted UX destination")
    return f"{package}/com.immortal.launcher.{ACTIVITIES[activity]}"


def start_args(package, activity):
    launcher = ["am"] if activity == "home" else ["run-as", package, "am"]
    intent = ["start", "--user", "0", "-W", "--activity-clear-top",
              "-n", component(package, activity)]
    if activity == "apps":
        intent += ["--ez", "approved_all_apps", "true"]
    return launcher + intent


def focus_fields(text):
    fields = {}
    for line in text.splitlines():
        match = re.search(r"\b(mCurrentFocus|mFocusedApp)\s*=\s*(.*)", line)
        if match and match[1] not in fields:
            fields[match[1]] = match[2]
    return fields


def activity_in_focus_field(field, package):
    if not re.search(r"\bu0\b", field):
        return None
    for name, cls in ACTIVITIES.items():
        pattern = re.escape(package) + r"/(?:com\.immortal\.launcher\.|\.)" + re.escape(cls)
        if re.search(pattern + r"(?=[\s}])", field):
            return name
    return None


def package_only_focus(field, package):
    # Allow mFocusedApp only when the input-focused window is precisely this package.
    # Never trust activity focus alone: a foreign/system dialog can own input.
    return bool(re.fullmatch(r"Window\{[^{}]*\bu0\s+" + re.escape(package) + r"\s*\}", field))


def focused_activity(text, package):
    fields = focus_fields(text)
    current = fields.get("mCurrentFocus", "")
    direct = activity_in_focus_field(current, package)
    if direct:
        return direct
    if package_only_focus(current, package):
        return activity_in_focus_field(fields.get("mFocusedApp", ""), package)
    return None


def focus_diagnostic(text, package, expected):
    fields = focus_fields(text)
    current = fields.get("mCurrentFocus", "")
    return {
        "expected_activity": expected,
        "current_focus_field_present": "mCurrentFocus" in fields,
        "current_focus_is_null": current == "null",
        "current_focus_package_only": package_only_focus(current, package),
        "current_focus_activity": activity_in_focus_field(current, package),
        "focused_app_field_present": "mFocusedApp" in fields,
        "focused_app_activity": activity_in_focus_field(fields.get("mFocusedApp", ""), package),
    }


def is_focused(text, package, activity):
    return focused_activity(text, package) == activity


def parse_viewport(text):
    match = re.search(r"\bcur=(\d+)x(\d+)", text)
    if match:
        width, height = map(int, match.groups())
        if 320 <= width <= 8192 and 320 <= height <= 8192:
            return width, height
    return None


def swipe_args(activity, viewport, index):
    if activity == "home":
        # Home can contain arbitrary hosted widgets/household controls; never inject there.
        return None
    if activity not in ACTIVITIES:
        raise ValueError("Unknown activity")
    width, height = viewport
    x, low, high = int(width * .70), int(height * .65), int(height * .35)
    start, end = (low, high) if index % 2 == 0 else (high, low)
    return ["input", "swipe", str(x), str(start), str(x), str(end), "220"]


class ProbeError(Exception):
    """Only fixed, sanitized diagnostic messages cross the evidence boundary."""

    def __init__(self, message, details=None):
        super().__init__(message)
        self.details = details


class Adb:
    def __init__(self, executable, serial, timeout):
        if not serial or not 0 < timeout <= 30:
            raise ValueError("An explicit serial and timeout in (0, 30] are required")
        self.prefix = [executable, "-s", serial]
        self.timeout = timeout

    def run(self, args, operation, binary=False):
        child = subprocess.Popen(self.prefix + args, stdout=subprocess.PIPE,
                                 stderr=subprocess.PIPE, start_new_session=True)
        try:
            out, _ = child.communicate(timeout=self.timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt) as error:
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            try:
                child.wait(timeout=1)
            except subprocess.TimeoutExpired:
                pass
            if isinstance(error, KeyboardInterrupt):
                raise
            raise ProbeError(f"{operation}: adb_timeout") from None
        if child.returncode:
            raise ProbeError(f"{operation}: adb_failed") from None
        return out if binary else out.decode("utf-8", errors="replace")

    def shell(self, args, operation):
        # adb's remote shell reparses arguments. Quote validated arguments even without shell=True.
        return self.run(["shell", shlex.join(args)], operation)


def pause(seconds):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        time.sleep(min(.1, max(0, end - time.monotonic())))


def private_write(path, data):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL |
                         getattr(os, "O_NOFOLLOW", 0), 0o600)
    with os.fdopen(descriptor, "wb") as handle:
        handle.write(data)


def screenshot(adb, directory, name):
    image = adb.run(["exec-out", "screencap", "-p"], "screenshot", binary=True)
    if not image.startswith(b"\x89PNG\r\n\x1a\n") or len(image) > 32 * 1024 * 1024:
        raise ProbeError("screenshot: invalid_png")
    private_write(directory / f"{name}.png", image)


def frames(adb, args):
    return parse_framestats(adb.shell(["dumpsys", "gfxinfo", args.package, "framestats"],
                                    "framestats"), args.refresh_hz)


def memory(adb, args):
    return parse_meminfo(adb.shell(["dumpsys", "meminfo", args.package], "meminfo"))


def require_focus(adb, args, activity):
    # Android 10 moves per-display focus into the full dump; the "windows"
    # subcommand can omit it. Keep the dump transient and retain only allowlisted facts.
    state = adb.shell(["dumpsys", "window"], "focus")
    if not is_focused(state, args.package, activity):
        raise ProbeError("focus: destination_not_foreground; exercise_stopped",
                         focus_diagnostic(state, args.package, activity))


def launch(adb, args, activity, report):
    result = parse_start(adb.shell(start_args(args.package, activity), "start"))
    report["last_start"] = {"activity": activity, **result}
    if result["status"] != "ok":
        raise ProbeError("start: activity_unavailable_or_denied")
    pause(args.settle_seconds)
    require_focus(adb, args, activity)
    return result


def reset_frames(adb, args):
    adb.shell(["dumpsys", "gfxinfo", args.package, "reset"], "reset")


def run_probe(adb, args, report):
    if adb.run(["get-state"], "connection").strip() != "device":
        raise ProbeError("connection: device_not_ready")
    installed = adb.shell(["pm", "path", "--user", "0", args.package], "package")
    if not any(line.startswith("package:") for line in installed.splitlines()):
        raise ProbeError("package: not_installed")
    report["package_info"] = parse_package(adb.shell(["dumpsys", "package", args.package],
                                                    "package_metadata"))
    sdk = adb.shell(["getprop", "ro.build.version.sdk"], "sdk").strip()
    report["android_sdk"] = int(sdk) if sdk.isdigit() else None
    if args.collect_only:
        focus = adb.shell(["dumpsys", "window"], "focus")
        sample = {"activity": focused_activity(focus, args.package) or "unknown",
                  "mode": "existing_ring_buffer", "frames": frames(adb, args),
                  "memory": memory(adb, args)}
        report["samples"].append(sample)
        if args.screenshot_dir:
            screenshot(adb, args.screenshot_dir, "collect")
            sample["screenshot_file"] = "collect.png"
        return
    if any(activity != "home" for activity in args.activities):
        if not report["package_info"]["debuggable"]:
            raise ProbeError("exercise: private_activities_require_run_as_debuggable_build")
        # Fail before navigation when run-as cannot execute on this firmware.
        uid = adb.shell(["run-as", args.package, "id", "-u"], "run_as").strip()
        if not uid.isdigit():
            raise ProbeError("exercise: run_as_unavailable")
    viewport = None
    if args.swipes:
        viewport = parse_viewport(adb.shell(["dumpsys", "window", "displays"], "viewport"))
        if not viewport:
            raise ProbeError("viewport: dimensions_unavailable; no_input_injected")
    report["viewport_px"] = list(viewport) if viewport else None
    # Preserve initial launch evidence separately; cold is reported only when AM reports COLD.
    report["initial_start"] = launch(adb, args, "home", report)
    for _ in range(args.warmup_rounds):
        for activity in args.activities:
            launch(adb, args, activity, report)
            launch(adb, args, "home", report)
    for cycle in range(1, args.cycles + 1):
        for activity in args.activities:
            reset_frames(adb, args)
            start = launch(adb, args, activity, report)
            sample = {"cycle": cycle, "activity": activity, "start": start,
                      "navigation_frames": frames(adb, args)}
            report["samples"].append(sample)
            reset_frames(adb, args)
            sample["injected_swipes"] = 0
            for index in range(args.swipes):
                gesture = swipe_args(activity, viewport, index)
                if gesture is None:
                    break
                require_focus(adb, args, activity)
                adb.shell(gesture, "swipe")
                sample["injected_swipes"] += 1
                pause(args.swipe_pause_seconds)
            pause(args.observe_seconds)
            require_focus(adb, args, activity)
            sample["interaction_frames"] = frames(adb, args)
            sample["interaction"] = "vertical_scroll" if sample["injected_swipes"] else "idle_only"
            sample["memory"] = memory(adb, args)
            if args.screenshot_dir:
                name = f"{cycle:02d}-{activity}"
                screenshot(adb, args.screenshot_dir, name)
                sample["screenshot_file"] = f"{name}.png"
            # Keep the task stack bounded; no BACK can fall through to a foreign application.
            launch(adb, args, "home", report)


def arguments(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--collect-only", action="store_true",
                      help="Read existing gfxinfo once; no reset, launch or input")
    mode.add_argument("--exercise", action="store_true", help="Warm and profile allowlisted destinations")
    mode.add_argument("--self-test", action="store_true", help="Offline fixtures; never runs adb")
    parser.add_argument("--serial", help="Required for device modes; never auto-selects a device")
    parser.add_argument("--adb", default="adb", help="Installed adb executable/path")
    parser.add_argument("--package", default="com.immortal.launcher.debug")
    parser.add_argument("--label", default="unlabelled", help="Baseline/candidate identifier; omit secrets")
    parser.add_argument("--apk", type=Path, help="Optional local APK to hash for artifact identity; never installs")
    parser.add_argument("--output", type=Path, help="New absolute JSON path, mode 0600; stdout also contains JSON")
    parser.add_argument("--screenshot-dir", type=Path, help="Optional absolute private directory (0700)")
    parser.add_argument("--activities", default="home,settings,tools,store,apps")
    parser.add_argument("--cycles", type=int, default=3)
    parser.add_argument("--warmup-rounds", type=int, default=1)
    parser.add_argument("--swipes", type=int, default=4, help="Alternating vertical scrolls; Home always idle")
    parser.add_argument("--settle-seconds", type=float, default=2)
    parser.add_argument("--observe-seconds", type=float, default=2)
    parser.add_argument("--swipe-pause-seconds", type=float, default=.3)
    parser.add_argument("--adb-timeout", type=float, default=30, help="Every adb call: 0 < timeout <= 30s")
    parser.add_argument("--refresh-hz", type=float, default=60, help="Assumed budget, not detected refresh")
    args = parser.parse_args(argv)
    if args.self_test:
        return args
    if not args.serial or args.serial.startswith("-") or any(c.isspace() for c in args.serial):
        parser.error("--serial must explicitly name one device without whitespace")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", args.package):
        parser.error("Invalid package identifier")
    args.activities = args.activities.split(",")
    if not args.activities or len(set(args.activities)) != len(args.activities) or any(
            activity not in ACTIVITIES for activity in args.activities):
        parser.error("--activities accepts a distinct comma-separated subset of home,settings,tools,store,apps,screensaver")
    for value, low, high, name in (
        (args.cycles, 1, 20, "cycles"), (args.warmup_rounds, 1, 5, "warmup-rounds"),
        (args.swipes, 0, 40, "swipes"), (args.settle_seconds, .1, 30, "settle-seconds"),
        (args.observe_seconds, 0, 30, "observe-seconds"),
        (args.swipe_pause_seconds, .05, 5, "swipe-pause-seconds"),
        (args.adb_timeout, .1, 30, "adb-timeout"), (args.refresh_hz, 1, 240, "refresh-hz"),
    ):
        if not math.isfinite(value) or not low <= value <= high:
            parser.error(f"--{name} must be in [{low}, {high}]")
    for path in (args.output, args.screenshot_dir):
        if path and not path.is_absolute():
            parser.error("Evidence paths must be absolute")
    if args.output and (not args.output.parent.is_dir() or args.output.exists()):
        parser.error("--output needs an existing parent and a new file name")
    if args.apk and not args.apk.is_file():
        parser.error("--apk must name an existing local file")
    return args


def main():
    args = arguments()
    if args.self_test:
        return self_test()
    report = {
        "schema_version": 1, "label": args.label, "package": args.package,
        "device_id_sha256_prefix": hashlib.sha256(args.serial.encode()).hexdigest()[:12],
        "captured_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "mode": "collect_only" if args.collect_only else "exercise",
        "probe_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "parameters": {key: getattr(args, key) for key in (
            "cycles", "warmup_rounds", "swipes", "settle_seconds", "observe_seconds",
            "swipe_pause_seconds", "adb_timeout", "refresh_hz")},
        "activities": args.activities, "samples": [], "errors": [],
        "limitations": [
            "gfxinfo ring buffers contain only rendered frames, not gestures or input-to-photon latency",
            "16.67ms at 60Hz is an elapsed-frame proxy; FrameDeadline is used only when supplied",
            "CPU stage/completion intervals are wall-clock proxies including waits, not CPU execution time",
            "No force-stop: cold/warm/hot classification is available only when am start reports LaunchState",
            "Home has arbitrary widgets: automated Home measurement is launch/idle, with no injected gestures",
            "Debug-build timings and missing/small samples cannot prove no perceptible lag",
            "Navigation frames are package-wide and can include both outgoing and incoming windows",
            "Opening Home/Store executes existing lifecycle hooks and read-only source/catalog fetches",
        ],
    }
    exit_code = 0
    def cancel(*_):
        raise KeyboardInterrupt()

    signal.signal(signal.SIGTERM, cancel)
    try:
        executable = shutil.which(args.adb)
        if not executable:
            raise ProbeError("setup: adb_not_installed")
        if args.apk:
            digest = hashlib.sha256()
            with args.apk.open("rb") as artifact:
                for block in iter(lambda: artifact.read(1024 * 1024), b""):
                    digest.update(block)
            report["operator_supplied_apk_sha256"] = digest.hexdigest()
        if args.screenshot_dir:
            args.screenshot_dir.mkdir(mode=0o700, parents=False, exist_ok=True)
            if args.screenshot_dir.is_symlink() or args.screenshot_dir.stat().st_mode & 0o077:
                raise ProbeError("setup: screenshot_directory_must_be_private_0700")
        run_probe(Adb(executable, args.serial, args.adb_timeout), args, report)
        report["status"] = "collected"
    except KeyboardInterrupt:
        report["status"] = "cancelled"
        exit_code = 130
    except (ProbeError, OSError) as error:
        report["status"] = "error"
        report["errors"].append(str(error) if isinstance(error, ProbeError) else "host_io_failed")
        if isinstance(error, ProbeError) and error.details is not None:
            report["failure_details"] = error.details
        exit_code = 1
    encoded = (json.dumps(report, indent=2, allow_nan=False) + "\n").encode()
    if args.output:
        try:
            private_write(args.output, encoded)
        except OSError:
            report["status"] = "error"
            report["errors"].append("output: private_file_write_failed")
            encoded = (json.dumps(report, indent=2, allow_nan=False) + "\n").encode()
            exit_code = 1
    sys.stdout.buffer.write(encoded)
    return exit_code


def self_test():
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(OfflineTests)
    return 0 if unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())

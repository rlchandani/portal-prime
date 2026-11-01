#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Bounded physical Family Area silent music fixture acceptance checks.

The parent must install the expected debug APK, start PortalUxInstrumentation
with command=observe, verify observerReady, and exclusively own that observer.
This script never starts/stops instrumentation or changes device configuration.
It launches only MusicDesignPreviewActivity, uses observed native node bounds,
and retains all raw evidence in a NEW directory outside Git.

The 300-second process budget reserves 20 seconds for owned staging-file cleanup
and reporting. Observation failure never restarts an activity or the observer.
Screenshots need manual pixel review: these checks do not accept audio, provider
behavior, speaker group control, production Home chrome/dock, actual photos, or
performance. The Photos fixture renders "Photo layout preview".
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import struct
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET


PACKAGE = "com.immortal.launcher.debug"
ACTIVITY = "com.immortal.launcher.MusicDesignPreviewActivity"
COMPONENT = f"{PACKAGE}/{ACTIVITY}"
PHYSICAL_SERIAL = "FAMILY_PORTAL_SERIAL"
WIDTH, HEIGHT, DENSITY, MIN_TARGET = 2160, 1440, 180, 54
CACHE = "cache/portal-ux-e2e"
TABS = ("Queue", "Lyrics", "Players")
TRANSPORT = ("Play", "Previous track", "Next track", "Track position")
FULL_CONTROLS = TRANSPORT + (
    "Minimize player", "Photos", "Music settings", "Close player",
) + TABS
LIMITATIONS = (
    "Silent generated fixture only; no audio or provider acceptance.",
    "Players tab retention and Family Area presentation only; no player/group control.",
    "Preview activity only; production Home header/dock integration untested.",
    "Photos layout uses a placeholder; actual/private photos untested.",
    "No performance acceptance or collector control.",
    "Bounds/semantics checks require parent manual screenshot pixel review.",
    "Parent owns observer lifecycle, snapshot cache cleanup, and device restoration.",
)


class VerificationFailure(RuntimeError):
    """A fixed diagnostic code, never raw device output."""


def require(condition, code):
    if not condition:
        raise VerificationFailure(code)


def bounds(node):
    return tuple(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))


def label_matches(node, label):
    return any(label in node.get(key, "").splitlines()
               for key in ("text", "content-desc"))


class Snapshot:
    """XML adapter preserves native parent nodes, including API29 tab wrappers."""

    def __init__(self, native, frame, window_id):
        self.root = ET.Element("hierarchy")
        self.frame = frame
        self.window_id = window_id
        self.observed_at = time.monotonic()
        self.count = 0
        self._convert(native, self.root)
        self.nodes = list(self.root.iter("node"))
        self.parents = {child: parent for parent in self.root.iter()
                        for child in parent}
        require(self.nodes, "native_snapshot_empty")

    def _convert(self, data, parent, depth=0):
        require(isinstance(data, dict) and depth <= 64, "native_tree_invalid")
        require(not data.get("truncated", False), "native_tree_truncated")
        if data.get("class") == "native-windows":
            children = data.get("children")
            require(isinstance(children, list) and children, "native_windows_empty")
            for child in children:
                self._convert(child, parent, depth + 1)
            return
        self.count += 1
        require(self.count <= 5000, "native_tree_too_large")
        box = data.get("bounds")
        require(isinstance(box, list) and len(box) == 4
                and all(type(value) is int for value in box), "native_bounds_invalid")
        for key in ("clickable", "enabled", "selected", "visible"):
            require(type(data.get(key)) is bool, "native_boolean_or_selected_missing")
        node = ET.SubElement(parent, "node", {
            "text": str(data.get("text", "")),
            "content-desc": str(data.get("description", "")),
            "class": str(data.get("class", "")),
            "bounds": f"[{box[0]},{box[1]}][{box[2]},{box[3]}]",
            **{key: str(data[key]).lower()
               for key in ("clickable", "enabled", "selected", "visible")},
            **{key: str(data.get(key, False)).lower()
               for key in ("checked", "checkable")},
            "state-description": str(data.get("stateDescription", "")),
        })
        children = data.get("children", [])
        require(isinstance(children, list), "native_children_invalid")
        for child in children:
            self._convert(child, node, depth + 1)

    def matches(self, label):
        return [node for node in self.nodes
                if node.get("visible") == "true" and label_matches(node, label)]

    def has(self, label):
        return bool(self.matches(label))

    def editable_fields(self, label):
        labels = self.matches(label)
        fields = [node for node in self.nodes if node.get("visible") == "true"
                  and node.get("class") == "android.widget.EditText"]
        matching = []
        for field in fields:
            left, top, right, bottom = bounds(field)
            if any(left <= (bounds(node)[0] + bounds(node)[2]) / 2 <= right
                   and top <= (bounds(node)[1] + bounds(node)[3]) / 2 <= bottom
                   for node in labels):
                matching.append(field)
        return matching

    def control(self, label, disabled=False):
        candidates = []
        matches = self.matches(label)
        if label in TABS:
            described = [node for node in matches if node.get("content-desc") == label]
            if described:
                matches = described
        for matched in matches:
            current = matched
            # API29 exposes interactive tab labels/wrappers as non-clickable.
            # Only these three known tabs may use their observed label bounds.
            if label in TABS:
                parent = self.parents.get(matched)
                candidates.append(parent if parent is not None and parent.tag == "node"
                                  and bounds(parent) == bounds(matched) else matched)
                continue
            while current is not None and current.tag == "node":
                if current.get("clickable") == "true":
                    candidates.append(current)
                    break
                if (current.get("class") == "android.widget.SeekBar"
                        or (disabled and current.get("content-desc") == label)):
                    candidates.append(current)
                    break
                current = self.parents.get(current)
        unique = list(dict.fromkeys(candidates))
        # Prefer the native node carrying the content description over its text.
        described = [node for node in unique
                     if node.get("content-desc") == label]
        if described:
            unique = described
        require(len(unique) == 1, "control_missing_or_ambiguous")
        node = unique[0]
        require(node.get("visible") == "true", "control_not_visible")
        return node

    def selected_tab(self):
        selected = []
        for label in TABS:
            nodes = self.matches(label)
            require(nodes, "tab_missing")
            # The API29 label and blank selection wrapper have identical bounds.
            # A selected Queue row farther up the tree must not select this tab.
            for node in nodes:
                box = bounds(node)
                current = node
                while current is not None and current.tag == "node":
                    if bounds(current) != box:
                        break
                    if current.get("selected") == "true":
                        selected.append(label)
                        break
                    current = self.parents.get(current)
                if label in selected:
                    break
        return selected

    def current_has(self, label):
        slider = self.control("Track position", disabled=True)
        left, top, right, _ = bounds(slider)
        return any(left - 8 <= bounds(node)[0] and bounds(node)[2] <= right + 8
                   and bounds(node)[3] <= top
                   for node in self.matches(label))

    def elapsed_seconds(self):
        left, _, right, bottom = bounds(self.control("Track position", disabled=True))
        times = []
        for node in self.nodes:
            value = node.get("text", "")
            if node.get("visible") != "true" or not re.fullmatch(r"\d+:\d{2}", value):
                continue
            x1, y1, x2, y2 = bounds(node)
            if left - 8 <= x1 and x2 <= right + 8 and bottom - 4 <= y1 <= bottom + 90:
                minutes, seconds = map(int, value.split(":"))
                times.append((x1, minutes * 60 + seconds))
        require(len(times) == 2, "elapsed_time_not_observed")
        times.sort()
        return times[0][1]


class Verifier:
    def __init__(self, args, adb_path):
        self.args = args
        self.adb_path = adb_path
        self.out = args.private_dir
        self.started = time.monotonic()
        self.hard_deadline = self.started + 300
        self.work_deadline = self.started + 280
        self.cleaning = False
        self.command_count = 0
        self.frame_count = 0
        self.frames = []
        self.checks = []
        self.ui_bugs = []
        self.case_count = 0
        self.stage = "preflight"
        self.remote_owned = False
        self.cache_temp_owned = False
        nonce = uuid.uuid4().hex[:12]
        self.request_prefix = f"family_music_{nonce}"
        self.remote_request = f"/sdcard/immortal-family-music-{nonce}-request.json"
        self.cache_temp = f"{CACHE}/.family-music-request-{nonce}.tmp"
        self.request_file = self.out / "observer-request.json"
        self.normal_title_heights = {}
        self.normal_queue_title_heights = {}
        self.report = {
            "success": False, "complete": False, "targetGuardPassed": False,
            "generatedSilentFixtureOnly": True, "apiLevel": 29,
            "physicalWidthPx": WIDTH, "physicalHeightPx": HEIGHT,
            "densityDpi": DENSITY, "minimumTargetPx": MIN_TARGET,
            "processBudgetSeconds": 300, "workBudgetSeconds": 280,
            "cleanupReserveSeconds": 20, "manualPixelReviewRequired": True,
            "manualPixelReviewCompleted": False, "acceptanceComplete": False,
            "audioAccepted": False, "providerAccepted": False,
            "groupControlAccepted": False, "productionHeaderDockAccepted": False,
            "actualPhotosAccepted": False, "performanceAccepted": False,
            "observerStartedByScript": False, "observerStoppedByScript": False,
            "globalSettingsWritten": False, "remoteXmlCreated": False,
            "observerCacheCleanupParentOwned": True, "failureCode": "",
            "cleanupSucceeded": True,
        }

    def remaining(self):
        deadline = self.hard_deadline if self.cleaning else self.work_deadline
        left = deadline - time.monotonic()
        require(left > 0, "process_budget_exhausted")
        return left

    def sleep(self, seconds):
        time.sleep(min(seconds, self.remaining()))

    def write_json(self, path, value):
        path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")

    def adb(self, *command, allow_failure=False, max_seconds=8):
        """Every command has a deadline; stdout/stderr never reach the console."""
        self.command_count += 1
        stem = self.out / f"command-{self.command_count:04d}"
        timeout = min(max_seconds, self.remaining())
        try:
            result = subprocess.run(
                [self.adb_path, "-s", self.args.serial, *command],
                stdin=subprocess.DEVNULL, capture_output=True, timeout=timeout,
                check=False,
            )
        except subprocess.TimeoutExpired as error:
            stem.with_suffix(".stdout").write_bytes(error.stdout or b"")
            stem.with_suffix(".stderr").write_bytes(error.stderr or b"")
            self.write_json(stem.with_suffix(".json"), {
                "command": list(command), "timedOut": True,
                "timeoutSeconds": round(timeout, 3),
            })
            raise VerificationFailure("adb_subprocess_timeout") from None
        stem.with_suffix(".stdout").write_bytes(result.stdout)
        stem.with_suffix(".stderr").write_bytes(result.stderr)
        self.write_json(stem.with_suffix(".json"), {
            "command": list(command), "exitCode": result.returncode,
            "timedOut": False,
        })
        require(allow_failure or result.returncode == 0, "adb_command_failed")
        return result

    def text(self, *command):
        return self.adb(*command).stdout.decode("utf-8", errors="replace").strip()

    def preflight(self):
        require(not self.args.serial.startswith("emulator-"), "emulator_transport_rejected")
        require(self.text("get-state") == "device", "transport_not_ready")
        require(self.text("shell", "getprop", "ro.serialno") == PHYSICAL_SERIAL,
                "physical_serial_guard_failed")
        require(self.text("shell", "getprop", "ro.build.version.sdk") == "29",
                "api_guard_failed")
        require(self.text("shell", "getprop", "ro.kernel.qemu") in ("", "0"),
                "physical_device_guard_failed")
        require(self.text("shell", "wm", "size") == "Physical size: 2160x1440",
                "physical_size_or_override_guard_failed")
        require(self.text("shell", "wm", "density") == "Physical density: 180",
                "density_or_override_guard_failed")
        paths = self.text("shell", "pm", "path", PACKAGE).splitlines()
        require(len(paths) == 1 and re.fullmatch(r"package:/[A-Za-z0-9_./=+-]+\.apk",
                                               paths[0]),
                "single_installed_debug_apk_required")
        apk_path = paths[0][8:]
        digest = self.text("shell", "sha256sum", apk_path).split()
        require(digest and re.fullmatch(r"[0-9a-f]{64}", digest[0])
                and digest[0] == self.args.expected_apk_sha256,
                "installed_apk_hash_guard_failed")
        require(self.adb("shell", "run-as", PACKAGE, "ls", "-ld", CACHE,
                         allow_failure=True).returncode == 0,
                "parent_observer_cache_missing")
        require(self.adb("shell", "ls", "-ld", self.remote_request,
                         allow_failure=True).returncode != 0,
                "remote_request_already_exists")
        require(self.adb("shell", "run-as", PACKAGE, "ls", "-ld", self.cache_temp,
                         allow_failure=True).returncode != 0,
                "cache_staging_already_exists")
        self.report.update(targetGuardPassed=True, installedApkSha256=digest[0])

    def foreground(self):
        """Check the actual focused window and its visible native surface."""
        raw = self.text("shell", "dumpsys", "window")
        match = re.search(r"mCurrentFocus=Window\{([0-9a-fA-F]+)\s+u\d+\s+([^\s}]+)", raw)
        require(match is not None, "focused_window_missing")
        window_id, component = match.groups()
        accepted = (COMPONENT, f"{PACKAGE}/.MusicDesignPreviewActivity")
        require(component in accepted, "fixture_not_foreground")
        focused_app = re.search(r"mFocusedApp=([^\n]+)", raw)
        require(focused_app and any(value in focused_app.group(1) for value in accepted),
                "fixture_not_focused_app")
        blocks = re.split(r"(?m)^\s*Window #\d+\s+", raw)[1:]
        actual = [block for block in blocks
                  if re.search(r"Window\{" + re.escape(window_id) + r"\s", block.split("\n")[0])]
        require(len(actual) == 1 and "mHasSurface=true" in actual[0]
                and "isReadyForDisplay()=true" in actual[0], "fixture_window_not_visible")
        return window_id

    def capture(self, case, command=None):
        """Atomic request; bounded polling tolerates ONLY incomplete response JSON."""
        self.stage = case
        window_id = self.foreground()
        self.frame_count += 1
        name = f"{self.request_prefix}_{self.frame_count:03d}_{case}"
        require(re.fullmatch(r"[A-Za-z0-9_-]+", name), "unsafe_observer_name")
        require(self.adb("shell", "run-as", PACKAGE, "ls", "-ld",
                         f"{CACHE}/request.json", allow_failure=True).returncode != 0,
                "observer_request_already_pending")
        if command is not None:
            require(isinstance(command, dict)
                    and command.get("command") == "musicSliderGesture",
                    "unsupported_native_capture_command")
        request = dict(command or {"command": "snapshot"})
        request["name"] = name
        self.write_json(self.request_file, request)
        self.remote_owned = True
        self.adb("push", str(self.request_file), self.remote_request)
        self.cache_temp_owned = True
        self.adb("shell", "run-as", PACKAGE, "cp", self.remote_request, self.cache_temp)
        self.adb("shell", "run-as", PACKAGE, "mv", self.cache_temp, f"{CACHE}/request.json")
        self.cache_temp_owned = False
        # A gesture includes input, four phase observations, and the final capture.
        # Keep its bounded allowance separate from ordinary screenshot requests.
        poll_deadline = min(time.monotonic() + (20 if command is not None else 10),
                            self.work_deadline)
        matched = False
        while time.monotonic() < poll_deadline:
            response = self.adb(
                "shell", "run-as", PACKAGE, "cat", f"{CACHE}/response.json",
                allow_failure=True, max_seconds=min(3, poll_deadline - time.monotonic()),
            )
            # Missing response or a concurrent File.writeText may yield empty,
            # partial, or remote-error text even when adb reports exit zero.
            try:
                value = json.loads(response.stdout)
            except (json.JSONDecodeError, UnicodeDecodeError):
                self.sleep(0.15)
                continue
            require(isinstance(value, dict), "observer_response_not_object")
            if value.get("name") != name:
                self.sleep(0.15)
                continue
            require(response.returncode == 0, "observer_matching_response_read_failed")
            require(value.get("success") is True, "observer_snapshot_failed")
            matched = True
            break
        require(matched, "observer_response_timeout")
        require(self.foreground() == window_id, "window_changed_during_capture")
        native_bytes = self.adb("exec-out", "run-as", PACKAGE, "cat",
                                f"{CACHE}/{name}.json").stdout
        try:
            native = json.loads(native_bytes)
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise VerificationFailure("completed_native_snapshot_invalid") from None
        png = self.adb("exec-out", "run-as", PACKAGE, "cat", f"{CACHE}/{name}.png").stdout
        require(len(png) >= 24 and png[:8] == b"\x89PNG\r\n\x1a\n"
                and png[12:16] == b"IHDR", "completed_native_png_missing_or_invalid")
        width, height = struct.unpack(">II", png[16:24])
        require((width, height) == (WIDTH, HEIGHT), "native_frame_dimensions_wrong")
        require(self.foreground() == window_id, "window_changed_during_frame_fetch")
        snapshot = Snapshot(native, self.frame_count, window_id)
        stem = self.out / f"{self.frame_count:03d}-{case}"
        stem.with_suffix(".json").write_bytes(native_bytes)
        stem.with_suffix(".png").write_bytes(png)
        ET.ElementTree(snapshot.root).write(stem.with_suffix(".xml"), encoding="utf-8",
                                            xml_declaration=True)
        self.frames.append({
            "frameNumber": self.frame_count, "case": case,
            "nativeJsonPath": str(stem.with_suffix(".json")),
            "xmlPath": str(stem.with_suffix(".xml")),
            "screenshotPath": str(stem.with_suffix(".png")),
            "observerCacheStem": f"{CACHE}/{name}",
            "widthPx": width, "heightPx": height, "nativeNodeCount": snapshot.count,
            "pngBytes": len(png), "pngSha256": hashlib.sha256(png).hexdigest(),
            "elapsedSeconds": round(time.monotonic() - self.started, 3),
        })
        return snapshot

    def observe(self, case, ready=None):
        """Only wait for the same fixture to render; never relaunch on timeout."""
        deadline = min(time.monotonic() + 12, self.work_deadline)
        snapshot = self.capture(case)
        while ready is not None and not ready(snapshot):
            require(time.monotonic() < deadline, "fixture_render_observation_timeout")
            self.sleep(0.2)
            snapshot = self.capture(case + "-render-wait")
        return snapshot

    def launch(self, case, theme, scenario="ready", font_scale=1.0):
        self.stage = case
        output = self.text("shell", "am", "start", "-W", "-n", COMPONENT,
                           "--es", "theme", theme, "--es", "scenario", scenario,
                           "--es", "screen", "player", "--ef", "font_scale", str(font_scale))
        require(not re.search(r"(?im)^\s*(?:Error|Exception)|Status:\s*(?!ok)\w+", output),
                "preview_launch_failed")
        return self.observe(case, lambda snapshot: snapshot.has("After the sun")
                            and snapshot.has("Track position") and snapshot.has("Play"))

    def check(self, case, name, passed, snapshot, **numbers):
        entry = {"case": case, "name": name, "passed": bool(passed),
                 "frameNumber": snapshot.frame, **numbers}
        self.checks.append(entry)
        if not passed:
            self.ui_bugs.append({**entry, "classification": "suspected_fixture_ui_bug",
                                 "requiresParentReview": True})

    def geometry(self, case, snapshot, labels):
        for label in labels:
            try:
                node = snapshot.control(label, disabled=True)
                left, top, right, bottom = bounds(node)
                if node.get("class") == "android.widget.SeekBar":
                    # Native bounds describe this build's measured slider layout.
                    # Geometry alone does not prove that an input point is admitted.
                    self.check(case, f"{label}: native slider bounds", (
                        0 <= left < right <= WIDTH and 0 <= top < bottom <= HEIGHT
                        and right - left >= MIN_TARGET), snapshot,
                        widthPx=right - left, nativeHeightPx=bottom - top,
                        touchInputVerified=False)
                    continue
                passed = (0 <= left < right <= WIDTH and 0 <= top < bottom <= HEIGHT
                          and right - left >= MIN_TARGET and bottom - top >= MIN_TARGET)
                self.check(case, f"{label}: observed target >=48dp", passed, snapshot,
                           leftPx=left, topPx=top, rightPx=right, bottomPx=bottom,
                           widthPx=right - left, heightPx=bottom - top)
            except VerificationFailure:
                self.check(case, f"{label}: native target observable", False, snapshot)

    def before_input(self, snapshot, node):
        require(time.monotonic() - snapshot.observed_at <= 20, "input_observation_stale")
        require(self.foreground() == snapshot.window_id, "input_window_changed")
        require(node.get("enabled") == "true" and node.get("visible") == "true",
                "input_control_disabled_or_invisible")
        left, top, right, bottom = bounds(node)
        require(0 <= left < right <= WIDTH and 0 <= top < bottom <= HEIGHT,
                "input_bounds_outside_physical_display")
        require(right - left >= MIN_TARGET and (
                    bottom - top >= MIN_TARGET
                    or node.get("class") == "android.widget.SeekBar"),
                "input_target_smaller_than_48dp")
        return left, top, right, bottom

    def tap(self, snapshot, label):
        node = snapshot.control(label)
        left, top, right, bottom = self.before_input(snapshot, node)
        require(label in TABS or node.get("clickable") == "true",
                "input_not_observed_click_target")
        self.adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def step(self, snapshot, label, case, ready=None):
        self.tap(snapshot, label)
        return self.observe(case, ready)

    def metadata(self, case, snapshot, title, artist, album, old_album=None):
        self.check(case, "current title/artist/album are coherent",
                   all(snapshot.current_has(label) for label in (title, artist, album))
                   and (old_album is None or not snapshot.current_has(old_album)), snapshot)

    def paused(self, case, snapshot):
        self.check(case, "paused transport semantics", snapshot.has("Paused")
                   and snapshot.has("Play") and not snapshot.has("Pause")
                   and snapshot.control("Play").get("enabled") == "true", snapshot)

    def title_height(self, snapshot):
        matches = snapshot.matches("After the sun")
        require(matches, "font_scale_title_missing")
        node = min(matches, key=lambda item: bounds(item)[0])
        box = bounds(node)
        return box[3] - box[1]

    def queue_title_height(self, snapshot):
        matches = [node for node in snapshot.matches("After the sun")
                   if bounds(node)[0] > WIDTH // 2]
        require(matches, "font_scale_queue_title_missing")
        box = bounds(matches[0])
        return box[3] - box[1]

    def ready_case(self, theme):
        case = f"{theme}-ready"
        snapshot = self.launch(case + "-full", theme)
        self.geometry(case, snapshot, FULL_CONTROLS)
        self.metadata(case, snapshot, "After the sun", "Sora Fields", "The quiet hours")
        self.normal_title_heights[theme] = self.title_height(snapshot)
        self.normal_queue_title_heights[theme] = self.queue_title_height(snapshot)
        snapshot = self.step(snapshot, "Play", case + "-playing",
                             lambda state: state.has("Pause"))
        self.check(case, "play changes native transport to Pause and Playing",
                   snapshot.has("Playing") and snapshot.control("Pause").get("enabled") == "true",
                   snapshot)
        snapshot = self.step(snapshot, "Pause", case + "-paused",
                             lambda state: state.has("Play"))
        self.paused(case, snapshot)
        snapshot = self.step(snapshot, "Next track", case + "-next")
        self.metadata(case, snapshot, "Blue distance", "North Atlantic", "North shore",
                      "The quiet hours")
        snapshot = self.step(snapshot, "Previous track", case + "-previous")
        self.metadata(case, snapshot, "After the sun", "Sora Fields", "The quiet hours",
                      "North shore")
        snapshot = self.step(snapshot, "Queue", case + "-queue")
        self.check(case, "Queue tab and six-track fixture", snapshot.selected_tab() == ["Queue"]
                   and snapshot.has("6 tracks"), snapshot)
        self.geometry(case, snapshot, ("Blue distance",))
        snapshot = self.step(snapshot, "Blue distance", case + "-queue-selection")
        self.metadata(case, snapshot, "Blue distance", "North Atlantic", "North shore",
                      "The quiet hours")
        self.paused(case, snapshot)
        row = snapshot.control("Blue distance")
        self.report[f"{theme}QueueNativeSelectedFlag"] = row.get("selected") == "true"
        self.check(case, "chosen queue row exposes its current paused marker",
                   any(label_matches(child, "Paused") for child in row.iter("node")),
                   snapshot)
        before = snapshot.elapsed_seconds()
        slider = snapshot.control("Track position")
        left, top, right, bottom = self.before_input(snapshot, slider)
        y = (top + bottom) // 2
        self.adb("shell", "input", "swipe", str(left + (right - left) // 4), str(y),
                 str(left + (right - left) * 3 // 4), str(y), "350")
        snapshot = self.observe(case + "-paused-seek")
        after = snapshot.elapsed_seconds()
        self.check(case, "observed slider drag seeks paused 257-second track",
                   before == 0 and 150 <= after <= 235, snapshot,
                   beforeSeconds=before, afterSeconds=after, durationSeconds=257)
        self.paused(case, snapshot)
        self.sleep(0.7)
        stable = self.observe(case + "-paused-seek-stable")
        self.check(case, "paused seek position remains stationary",
                   stable.elapsed_seconds() == after, stable, soughtSeconds=after)
        snapshot = self.step(stable, "Players", case + "-players")
        self.check(case, "Players tab and Family Area selected player",
                   snapshot.selected_tab() == ["Players"] and snapshot.has("Family Area")
                   and snapshot.has("Selected player") and snapshot.has("Selected"), snapshot)
        self.geometry(case, snapshot, ("Players", "Family Area volume"))
        snapshot = self.step(snapshot, "Minimize player", case + "-compact",
                             lambda state: state.has("Open player"))
        self.check(case, "compact retains title and paused transport",
                   snapshot.has("Blue distance") and snapshot.has("Play"), snapshot)
        self.geometry(case, snapshot, ("Open player", "Photos", "Play",
                                        "Previous track", "Next track"))
        snapshot = self.step(snapshot, "Open player", case + "-compact-return",
                             lambda state: state.has("Players"))
        self.check(case, "compact -> full retains selected Players tab",
                   snapshot.selected_tab() == ["Players"], snapshot)
        self.metadata(case, snapshot, "Blue distance", "North Atlantic", "North shore")
        self.paused(case, snapshot)
        # A failed retention assertion is recorded; select the known tab again
        # to independently test the Photos round trip without restarting.
        if snapshot.selected_tab() != ["Players"]:
            snapshot = self.step(snapshot, "Players", case + "-players-reselect")
        snapshot = self.step(snapshot, "Photos", case + "-photos",
                             lambda state: state.has("Full player"))
        self.check(case, "Photos fixture retains track and exposes its placeholder",
                   snapshot.has("Blue distance") and snapshot.has("Photo layout preview"),
                   snapshot)
        self.geometry(case, snapshot, ("Full player", "Play", "Previous track", "Next track"))
        snapshot = self.step(snapshot, "Full player", case + "-photos-return",
                             lambda state: state.has("Players"))
        self.check(case, "Photos -> full retains selected Players tab",
                   snapshot.selected_tab() == ["Players"], snapshot)
        self.metadata(case, snapshot, "Blue distance", "North Atlantic", "North shore")
        self.paused(case, snapshot)
        self.browse_case(theme, snapshot)
        self.case_count += 1

    def browse_case(self, theme, snapshot):
        case = f"{theme}-browse"
        snapshot = self.step(snapshot, "Playlists", case + "-playlists",
                             lambda state: state.has("Slow mornings"))
        self.check(case, "playlist grid contains three generated collections",
                   snapshot.has("3 playlists") and snapshot.has("After dark")
                   and snapshot.has("At home"), snapshot)
        self.geometry(case, snapshot, ("Back", "Now playing", "Slow mornings"))
        # Search is optional if the native editable control is not exposed.
        fields = snapshot.editable_fields("Search playlists")
        if len(fields) == 1:
            field = fields[0]
            left, top, right, bottom = self.before_input(snapshot, field)
            self.adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
            focused = self.observe(case + "-search-focused")
            fields = focused.editable_fields("Search playlists")
            require(len(fields) == 1, "search_field_not_observed_after_focus")
            self.before_input(focused, fields[0])
            # ASCII fixture text; input belongs to the observed search field.
            self.adb("shell", "input", "text", "Slow")
            snapshot = self.observe(case + "-search",
                                    lambda state: state.has("1 of 3 playlists"))
            self.check(case, "search filters generated playlists",
                       snapshot.has("Slow mornings") and snapshot.has("Clear search")
                       and not snapshot.has("After dark") and not snapshot.has("At home"),
                       snapshot)
            self.report[f"{theme}SearchExercised"] = True
        else:
            self.report[f"{theme}SearchExercised"] = False
            self.report[f"{theme}SearchLimit"] = "Native editable field not unambiguously exposed"
        snapshot = self.step(snapshot, "Slow mornings", case + "-collection",
                             lambda state: state.has("Tracks"))
        self.check(case, "playlist -> collection exposes generated tracks",
                   snapshot.has("Slow mornings") and snapshot.has("3 tracks")
                   and snapshot.has("Blue distance"), snapshot)
        self.geometry(case, snapshot, ("Back", "Now playing", "Blue distance"))
        snapshot = self.step(snapshot, "Blue distance", case + "-track-full",
                             lambda state: state.has("Track position"))
        self.metadata(case, snapshot, "Blue distance", "North Atlantic", "North shore")
        self.paused(case, snapshot)
        snapshot = self.step(snapshot, "Library", case + "-library",
                             lambda state: state.has("The quiet hours"))
        self.check(case, "library grid exposes six supplied albums",
                   snapshot.has("6 collections") and snapshot.has("North shore"), snapshot)
        self.geometry(case, snapshot, ("Back", "Now playing", "The quiet hours"))
        snapshot = self.step(snapshot, "The quiet hours", case + "-album",
                             lambda state: state.has("Tracks"))
        snapshot = self.step(snapshot, "After the sun", case + "-album-track-full",
                             lambda state: state.has("Track position"))
        self.metadata(case, snapshot, "After the sun", "Sora Fields", "The quiet hours")
        self.paused(case, snapshot)

    def offline_case(self, theme):
        case = f"{theme}-offline"
        snapshot = self.launch(case + "-full", theme, scenario="offline")
        self.check(case, "offline notice preserves last known fixture metadata",
                   snapshot.has("Player is offline") and snapshot.has("Last known track"),
                   snapshot)
        self.metadata(case, snapshot, "After the sun", "Sora Fields", "The quiet hours")
        for label in TRANSPORT + ("Queue", "Lyrics"):
            self.check(case, f"{label}: offline semantic disabled",
                       snapshot.control(label, disabled=True).get("enabled") == "false",
                       snapshot)
        self.geometry(case, snapshot, FULL_CONTROLS)
        self.check(case, "Players informative tab remains enabled",
                   snapshot.control("Players").get("enabled") == "true", snapshot)
        snapshot = self.step(snapshot, "Players", case + "-players")
        self.check(case, "offline Players tab renders Family Area",
                   snapshot.selected_tab() == ["Players"] and snapshot.has("Family Area"),
                   snapshot)
        self.check(case, "offline Family Area volume is semantically disabled",
                   snapshot.control("Family Area volume", disabled=True).get("enabled") == "false",
                   snapshot)
        snapshot = self.step(snapshot, "Playlists", case + "-playlists",
                             lambda state: state.has("Playlists unavailable"))
        self.check(case, "offline browse is unavailable without selectable fixture playlists",
                   snapshot.has("Playlists unavailable") and not snapshot.has("Slow mornings"),
                   snapshot)
        self.geometry(case, snapshot, ("Back", "Now playing"))
        self.case_count += 1

    def large_font_case(self, theme):
        case = f"{theme}-font-1_8"
        snapshot = self.launch(case + "-full", theme, font_scale=1.8)
        self.geometry(case, snapshot, FULL_CONTROLS)
        self.metadata(case, snapshot, "After the sun", "Sora Fields", "The quiet hours")
        normal_height = self.normal_title_heights[theme]
        large_height = self.title_height(snapshot)
        self.check(case, "activity-local 1.8 scale increases rendered title bounds",
                   normal_height > 0 and large_height > normal_height, snapshot,
                   normalTitleHeightPx=normal_height, largeTitleHeightPx=large_height)
        normal_queue_height = self.normal_queue_title_heights[theme]
        large_queue_height = self.queue_title_height(snapshot)
        self.check(case, "larger text increases the queue title bounds",
                   large_queue_height > normal_queue_height, snapshot,
                   normalQueueTitleHeightPx=normal_queue_height,
                   largeQueueTitleHeightPx=large_queue_height)
        snapshot = self.step(snapshot, "Playlists", case + "-playlists",
                             lambda state: state.has("Slow mornings"))
        self.check(case, "large font playlist page retains navigation and search",
                   snapshot.has("Back") and snapshot.has("Now playing")
                   and snapshot.has("Search playlists") and snapshot.has("Slow mornings"),
                   snapshot)
        self.geometry(case, snapshot, ("Back", "Now playing", "Slow mornings"))
        self.case_count += 1

    def run(self):
        self.preflight()
        if self.args.search_only:
            snapshot = self.launch("dark-search-start", "dark")
            self.browse_case("dark", snapshot)
            self.case_count += 1
            require(self.report.get("darkSearchExercised"), "search_not_exercised")
            self.report["complete"] = True
            return
        for theme in ("dark", "light"):
            self.ready_case(theme)
        for theme in ("dark", "light"):
            self.offline_case(theme)
        for theme in ("dark", "light"):
            self.large_font_case(theme)
        self.report["complete"] = True

    def cleanup(self):
        self.cleaning = True
        # Never remove request.json/response.json or parent-owned snapshots.
        # No remote XML is created by this observer-based implementation.
        commands = []
        if self.cache_temp_owned:
            commands.append(("shell", "run-as", PACKAGE, "rm", "-f", self.cache_temp))
        if self.remote_owned:
            commands.append(("shell", "rm", "-f", self.remote_request))
        for command in commands:
            try:
                result = self.adb(*command, allow_failure=True, max_seconds=5)
                if result.returncode != 0:
                    self.report["cleanupSucceeded"] = False
            except VerificationFailure:
                self.report["cleanupSucceeded"] = False
        self.report["ownedStagingFilesRemoved"] = self.report["cleanupSucceeded"]

    def finish(self):
        passed = sum(entry["passed"] for entry in self.checks)
        self.report.update(
            success=(self.report["complete"] and self.report["cleanupSucceeded"]
                     and not self.report["failureCode"] and passed == len(self.checks)),
            passed=passed, total=len(self.checks), completedCaseCount=self.case_count,
            nativeFrameCount=len(self.frames), attemptedFrameCount=self.frame_count,
            commandCount=self.command_count, suspectedUiBugCount=len(self.ui_bugs),
            elapsedSeconds=round(time.monotonic() - self.started, 3),
        )
        self.write_json(self.out / "checks.json", self.checks)
        self.write_json(self.out / "native-frames.json", self.frames)
        self.write_json(self.out / "suspected-ui-bugs.json", self.ui_bugs)
        self.write_json(self.out / "limitations.json", list(LIMITATIONS))
        self.write_json(self.out / "report.json", self.report)
        # Only scalar, sanitized values appear in the public terminal output.
        print(json.dumps({key: self.report[key] for key in (
            "success", "complete", "passed", "total", "completedCaseCount",
            "nativeFrameCount", "suspectedUiBugCount", "failureCode",
            "cleanupSucceeded", "elapsedSeconds",
        )}, sort_keys=True))
        return 0 if self.report["success"] else 1


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", required=True,
                        help="ADB transport; ro.serialno must independently match Family Area")
    parser.add_argument("--expected-apk-sha256", required=True,
                        help="SHA256 of the already installed, single debug APK")
    parser.add_argument("--private-dir", type=Path, required=True,
                        help="NEW absolute evidence directory outside every Git checkout")
    parser.add_argument("--search-only", action="store_true",
                        help="Only exercise physical search and browse navigation in dark theme")
    args = parser.parse_args(argv)
    if not re.fullmatch(r"[A-Za-z0-9_.:-]+", args.serial):
        parser.error("--serial must be a plain ADB transport identifier")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", args.expected_apk_sha256):
        parser.error("--expected-apk-sha256 must contain 64 hex digits")
    args.expected_apk_sha256 = args.expected_apk_sha256.lower()
    if not args.private_dir.is_absolute():
        parser.error("--private-dir must be absolute")
    if args.private_dir.exists() or args.private_dir.is_symlink():
        parser.error("--private-dir must be NEW")
    args.private_dir = args.private_dir.resolve()
    root = Path(__file__).resolve().parents[2]
    ancestors = (args.private_dir, *args.private_dir.parents)
    if (root in ancestors or ".git" in args.private_dir.parts
            or any((parent / ".git").exists() for parent in ancestors)
            or any((parent / "HEAD").is_file() and (parent / "objects").is_dir()
                   and (parent / "refs").is_dir() for parent in ancestors)):
        parser.error("--private-dir must be outside Git")
    return args


def main(argv=None):
    args = parse_args(argv)
    adb_path = shutil.which("adb")
    if adb_path is None:
        raise SystemExit("adb executable missing; no device commands attempted")
    os.umask(0o077)
    args.private_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
    verifier = Verifier(args, adb_path)

    def timeout_handler(*_):
        raise VerificationFailure("process_budget_exhausted")

    previous_handler = signal.signal(signal.SIGALRM, timeout_handler)
    signal.setitimer(signal.ITIMER_REAL, 280)
    try:
        verifier.run()
    except VerificationFailure as error:
        verifier.report["failureCode"] = str(error)
    except (KeyboardInterrupt, SystemExit):
        verifier.report["failureCode"] = "run_interrupted"
    except Exception:
        verifier.report["failureCode"] = "unexpected_script_failure"
    finally:
        # The absolute deadline does not move when entering cleanup.
        signal.setitimer(signal.ITIMER_REAL, max(0.001, verifier.hard_deadline - time.monotonic()))
        try:
            verifier.cleanup()
        except Exception:
            verifier.report["cleanupSucceeded"] = False
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous_handler)
        if verifier.report["failureCode"]:
            verifier.write_json(verifier.out / "script-failure.json", {
                "classification": "functional_script_failure",
                "code": verifier.report["failureCode"], "stage": verifier.stage,
                "lastCompletedFrame": len(verifier.frames),
                "lastCommandNumber": verifier.command_count,
                "observerRestarted": False, "activityRestartedOnTimeout": False,
            })
    return verifier.finish()


if __name__ == "__main__":
    raise SystemExit(main())

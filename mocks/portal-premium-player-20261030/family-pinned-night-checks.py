#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Physical API29 pinned-overlay night verifier; raw evidence is private.

Parent owns switching the active source to default (retaining all source fields),
recording content fades, restoring the source, and resuming the original Dream.
Requires the already installed optimized APK, an available unpinned service and
no performance collector. Never installs, builds, starts instrumentation, uses
UiAutomation, injects light, writes global settings, or clears/stops app tasks.
Main is bounded to 180 seconds with a separate 30-second restoration budget.
The optional second night tap runs AFTER the core disable/photo-return/tap case.
"""

import argparse
import hashlib
import io
import json
import math
import os
import pathlib
import re
import signal
import struct
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request


PKG = "com.immortal.launcher.debug"
FILES = "me.zhanghai.android.files"
PHYSICAL_SERIAL = "FAMILY_PORTAL_SERIAL"
APK_PREFIX = "6c1adacc"
WIDTH, HEIGHT = 2160, 1440
ROI = (604, 403, 1555, 950)
EPS = 1e-6
TEMP = dict(mode="wholePhoto", photo_motion=False, intervalSec=120,
            antiBurnIn=False, showNowPlaying=False, darkRoomClock=True,
            darkRoomLux=20, nightClockBrightness=3, nightClockTone="warm")


class CheckFailure(RuntimeError):
    """Fixed error codes; device data and exception text remain private."""


def require(condition, code):
    if not condition:
        raise CheckFailure(code)


def hardware_level(value):
    """Match Java float multiplication before Android 10's integer truncation."""
    require(math.isfinite(value) and 0 <= value <= 1, "effective_brightness_bounds")
    value32 = struct.unpack("!f", struct.pack("!f", value))[0]
    product32 = struct.unpack("!f", struct.pack("!f", value32 * 255))[0]
    return int(product32)


def required_intermediate_levels(start, target):
    """API29 truncates overrides to 0..255; adjacent levels need no interior level."""
    require(all(math.isfinite(level) and 0 <= level <= 1 for level in (start, target)),
            "effective_brightness_bounds")
    distance = abs(hardware_level(start) - hardware_level(target))
    return min(4, max(0, distance - 1))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise CheckFailure("http_redirect_rejected")


def window_blocks(text):
    return re.split(r"(?m)^\s*Window #\d+\s+", text)[1:]


def window_identity(block):
    match = re.search(r"Window\{([0-9a-fA-F]+)\s", block.splitlines()[0])
    require(match is not None, "native_window_identity_missing")
    session = re.search(r"mSession=Session\{[^ }\n]+\s+(\d+):", block)
    require(session is not None, "native_window_pid_missing")
    return match.group(1), int(session.group(1))


def visible_window(block):
    # API29 lacks isOnScreen in some dumps. An attached ready surface is
    # independent native evidence; an explicitly hidden surface still fails.
    if re.search(r"\b(?:isOnScreen|mHasSurface)=false\b", block):
        return False
    if re.search(r"\bmViewVisibility=(?!0x0\b)\S+", block):
        return False
    on_screen = re.search(r"\bisOnScreen(?:\(\))?=true\b", block)
    surface = re.search(r"\bmHasSurface=true\b", block)
    ready = re.search(r"\b(?:isReadyForDisplay|isReady)(?:\(\))?=true\b", block)
    return bool(on_screen or (surface and ready))


def attributes(block):
    require("mAttrs=" in block, "native_window_attributes_missing")
    return re.split(r"\n\s*m[A-Z]", block.split("mAttrs=", 1)[1], maxsplit=1)[0]


def component_names(component):
    package, activity = component.split("/", 1)
    full = package + activity if activity.startswith(".") else activity
    result = {package + "/" + full}
    if full.startswith(package + "."):
        result.add(package + "/" + full[len(package):])
    return result


class Verifier:
    def __init__(self, args):
        self.args, self.out = args, args.private_dir
        self.started = time.monotonic()
        self.deadline = self.started + 180
        self.seq = 0
        self.original = self.sources = self.original_pin = None
        self.changed = {}
        self.guarded = self.pin_attempted = self.files_attempted = False
        self.pin_window = self.overlay = self.files_identity = None
        self.files_component = None
        self.immortal_pid = None
        self.phase = "preflight"
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        self.result = dict(
            success=False, apiLevel=29, tapWhileNightClock=False,
            naturalBrightLightExitTested=False, opticalBacklightMeasured=False,
            panelFpsMeasured=False, contentFadeVideoVerified=False,
            clockTimeDateSemanticsVerified=False, rawEvidencePrivate=True,
            originalDreamResumeOwnedByParent=True, sourceRestorationOwnedByParent=True,
            limitations=[
                "Real dark-room readings; registered disable supplies the exit case.",
                "Window attributes measure brightness commands, not optical backlight or FPS.",
                "Parent separately reviews native time/date pixels and recorded content fades.",
                "Default-source native photo ROI only; household photos are not captured.",
                "Full captures, profiles, source fields, HTTP and native dumps stay private.",
            ],
        )

    def save(self, label, data):
        self.seq += 1
        payload = data if isinstance(data, bytes) else (
            json.dumps(data, indent=2, allow_nan=False) + "\n").encode()
        path = self.out / f"{self.seq:04d}-{label}"
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "wb") as output:
            output.write(payload)

    def remaining(self):
        left = self.deadline - time.monotonic()
        require(left > 0, "bounded_budget_exhausted")
        return left

    def pause(self, seconds):
        time.sleep(min(seconds, self.remaining()))

    def adb(self, *command):
        before = time.monotonic()
        try:
            proc = subprocess.run(
                ["adb", "-s", self.args.serial, *command],
                stdin=subprocess.DEVNULL, capture_output=True,
                timeout=min(4, self.remaining()), check=False,
            )
        except subprocess.TimeoutExpired as error:
            self.save("adb-timeout.stdout", error.stdout or b"")
            self.save("adb-timeout.stderr", error.stderr or b"")
            raise CheckFailure("adb_timeout") from None
        self.save("adb.stdout", proc.stdout)
        self.save("adb.stderr", proc.stderr)
        self.save("adb-command.json", dict(
            command=list(command), exitCode=proc.returncode,
            seconds=round(time.monotonic() - before, 6), phase=self.phase,
        ))
        require(proc.returncode == 0, "adb_failed")
        return proc.stdout

    def text(self, *command):
        return self.adb(*command).decode("utf-8", errors="replace").strip()

    def api(self, route, body=None):
        req = urllib.request.Request(
            self.conn["base"].rstrip("/") + route,
            data=None if body is None else json.dumps(body, allow_nan=False).encode(),
            headers={"Authorization": "Bearer " + self.conn["token"],
                     "Content-Type": "application/json"},
        )
        before = time.monotonic()
        try:
            with self.http.open(req, timeout=min(4, self.remaining())) as response:
                raw = response.read(4 * 1024 * 1024 + 1)
                status = response.status
        except urllib.error.HTTPError as error:
            self.save("http-error.raw", error.read(4 * 1024 * 1024))
            raise CheckFailure("http_rejected") from None
        self.save("http.raw", raw)
        self.save("http-request.json", dict(
            route=route, method=req.get_method(), status=status,
            seconds=round(time.monotonic() - before, 6), phase=self.phase,
        ))
        require(len(raw) <= 4 * 1024 * 1024, "http_response_too_large")
        data = json.loads(raw)
        require(isinstance(data, dict) and data.get("ok") is True, "api_not_ok")
        return data

    @staticmethod
    def controls(profile):
        result = {}
        for domain in profile["settings"]["domains"]:
            for control in domain["controls"]:
                if "value" in control:
                    key = (domain["id"], control["key"])
                    require(key not in result, "duplicate_registered_setting")
                    result[key] = control
        return result

    def profile(self):
        profile = self.api("/remote/settings/profile")
        require(profile.get("profileComplete") is True, "full_profile_required")
        require(len(self.controls(profile)) == 97, "full_97_setting_profile_required")
        return profile

    def no_collector(self):
        state = self.api("/perf/state")
        require(state.get("active") is False and state.get("collectorRunning") is False
                and state.get("activeListeners") == 0, "collector_must_be_inactive")
        self.result["collectorInactive"] = True

    def default_source(self):
        sources = self.api("/remote/sources")
        require(sources["sources"].get("source") == "default", "parent_default_source_required")
        require(self.sources is None or sources == self.sources, "source_fields_changed")
        return sources

    def validate_values(self, values):
        controls = self.controls(self.original)
        for key, value in values.items():
            control = controls.get(("screensaver", key))
            require(control is not None, "registered_setting_missing_" + key)
            for candidate in (value, control["value"]):
                valid = (
                    control["type"] == "bool" and type(candidate) is bool
                    or control["type"] == "int" and type(candidate) is int
                    and control["min"] <= candidate <= control["max"]
                    and (candidate - control["min"]) % control.get("step", 1) == 0
                    or control["type"] == "enum" and type(candidate) is str
                    and candidate in [option["value"] for option in control["options"]]
                )
                require(valid, "registry_constraint_failed_" + key)

    def apply(self, values, restoring=False, readback=True):
        self.validate_values(values)
        if not restoring:
            originals = self.controls(self.original)
            # Preserve originals BEFORE an ambiguous POST, using the first full
            # snapshot rather than a later temporary value.
            for key, value in values.items():
                original = originals[("screensaver", key)]["value"]
                if value != original:
                    self.changed.setdefault(key, original)
            self.save("settings-to-restore.json", self.changed)
        reply = self.api("/remote/settings", dict(domain="screensaver", values=values))
        require(set(reply.get("applied", [])) == set(values), "registry_apply_keys")
        if readback:
            self.readback(values)

    def readback(self, values):
        controls = self.controls(self.profile())
        require(all(controls[("screensaver", key)]["value"] == value
                    for key, value in values.items()), "registry_readback_failed")

    def dream_state(self):
        text = self.text("shell", "dumpsys", "dreams")
        match = re.search(r"(?m)^\s*mCurrentDream[^\n]*", text)
        require(match is not None, "dream_state_missing")
        return match.group(0), "null" not in match.group(0)

    def preflight(self):
        # Pillow is loaded only on actual execution; --help has no side effects.
        from PIL import Image
        self.Image = Image
        self.conn = json.loads(self.args.connection.read_text())
        base, token = self.conn.get("base"), self.conn.get("token")
        require(isinstance(base, str) and isinstance(token, str) and token
                and "\r" not in token and "\n" not in token, "connection_invalid")
        url = urllib.parse.urlsplit(base)
        require(url.scheme in ("http", "https") and url.hostname
                and not url.username and not url.password and url.path in ("", "/")
                and not url.query and not url.fragment, "connection_invalid")
        require(not self.args.serial.startswith("emulator-"), "physical_transport_required")
        require(self.text("get-state") == "device", "transport_not_ready")
        require(self.text("shell", "getprop", "ro.serialno") == PHYSICAL_SERIAL, "serial_guard")
        require(self.text("shell", "getprop", "ro.build.version.sdk") == "29", "sdk_guard")
        require(self.text("shell", "getprop", "ro.kernel.qemu") in ("", "0"), "physical_guard")
        require(self.text("shell", "wm", "size") == "Physical size: 2160x1440", "size_guard")
        require(self.text("shell", "wm", "density") == "Physical density: 180", "density_guard")
        paths = self.text("shell", "pm", "path", PKG).splitlines()
        require(len(paths) == 1
                and re.fullmatch(r"package:/[A-Za-z0-9_./=+~-]+\.apk", paths[0]),
                "single_installed_apk_required")
        self.apk_path = paths[0][8:]
        digest = self.text("shell", "sha256sum", self.apk_path).split()
        require(digest and digest[0] == self.args.expected_apk_sha256, "apk_hash_guard")
        require(self.api("/diag")["hardware"]["appApkPath"] == self.apk_path,
                "http_adb_apk_binding")
        self.immortal_pid = self.pid(PKG)
        self.original = self.profile()
        self.sources = self.default_source()
        require(len(self.sources["sources"]) == 22, "full_22_source_fields_required")
        self.original_pin = self.api("/remote/pin")
        self.save("original-profile.json", self.original)
        self.save("original-sources.json", self.sources)
        self.save("original-pin.json", self.original_pin)
        require(self.original_pin.get("available") is True
                and self.original_pin.get("pinned") is False
                and self.original_pin.get("covered") is False
                and self.original_pin.get("nativeCover") is False
                and self.original_pin.get("lost") is False
                and self.original_pin.get("expired") is False
                and self.original_pin.get("pendingCleanupCount") == 0
                and self.original_pin.get("failedCleanupCount") == 0,
                "available_unpinned_clean_service_required")
        self.no_collector()
        original_dream, was_dream = self.dream_state()
        self.save("original-dream.json", dict(state=original_dream, active=was_dream))
        self.result["originalDreamActive"] = was_dream
        self.validate_values(TEMP)
        self.validate_values({"nightClockBrightness": 15})
        self.guarded = True
        self.result.update(targetGuardPassed=True, installedApkSha256=digest[0],
                           originalSettingsCount=97, originalSourceFieldCount=len(self.sources["sources"]),
                           screenWidth=WIDTH, screenHeight=HEIGHT, densityDpi=180)
        if was_dream:
            require(PKG + "/com.immortal.launcher.PhotoDreamService" in original_dream,
                    "foreign_dream_must_not_be_dismissed")
            blocks = window_blocks(self.text("shell", "dumpsys", "window", "windows"))
            dreams = [block for block in blocks if "Sys2023:dream" in block.splitlines()[0]]
            require(len(dreams) == 1 and visible_window(dreams[0])
                    and re.search(r"\bpackage(?:Name)?=" + re.escape(PKG) + r"\b", dreams[0])
                    and window_identity(dreams[0])[1] == self.immortal_pid,
                    "original_dream_window_owner_guard")
            self.text("shell", "input", "tap", "1080", "720")
            self.pause(.5)
            require(not self.dream_state()[1], "original_dream_dismissal_failed")
            self.result["originalDreamDismissedForParentResume"] = True

    def pid(self, package):
        text = self.text("shell", "pidof", package)
        require(re.fullmatch(r"\d+", text) is not None, "single_process_required")
        return int(text)

    def files_state(self):
        text = self.text("shell", "dumpsys", "activity", "activities")
        resumed = re.findall(
            r"(?:mResumedActivity|topResumedActivity)\s*[:=]\s*ActivityRecord\{([^}\n]+)\}", text)
        own = set(record for record in resumed if FILES + "/" in record)
        require(len(own) == 1, "actual_files_resumed_activity_required")
        record = next(iter(own))
        blocks = window_blocks(self.text("shell", "dumpsys", "window", "windows"))
        candidates = [block for block in blocks
                      if any(component in block.splitlines()[0]
                             for component in component_names(self.files_component))
                      and re.search(r"\bpackage(?:Name)?=" + re.escape(FILES) + r"\b", block)]
        require(len(candidates) == 1, "one_files_window_required")
        window, window_pid = window_identity(candidates[0])
        pid = self.pid(FILES)
        require(window_pid == pid and visible_window(candidates[0]), "files_native_surface_required")
        return dict(activity=record, window=window, pid=pid)

    def pin_status(self, covered):
        state = self.api("/remote/pin")
        require(state.get("available") is True and state.get("pinned") is True
                and state.get("lost") is False and state.get("expired") is False
                and state.get("targetPackage") == FILES
                and state.get("targetWindowId") == self.pin_window
                and state.get("targetWindowPresent") is True
                and state.get("covered") is covered and state.get("nativeCover") is False
                and state.get("failedCleanupCount") == 0, "retained_files_pin_required")
        return state

    def pin_command(self, action):
        # POST may return 202 completion_unconfirmed. Read status afterwards;
        # never repeat a possibly admitted command.
        self.api("/remote/pin", {"action": action})
        stop = min(self.deadline, time.monotonic() + 5)
        while True:
            state = self.api("/remote/pin")
            done = (state.get("pinned") is True if action == "pin"
                    else state.get("covered") is True if action == "show"
                    else state.get("pinned") is False and state.get("covered") is False
                    and state.get("nativeCover") is False)
            if done:
                return state
            require(time.monotonic() < stop, "pin_command_completion_timeout")
            self.pause(.1)

    def overlay_state(self):
        before = time.monotonic()
        blocks = window_blocks(self.text("shell", "dumpsys", "window", "windows"))
        covers = [block for block in blocks
                  if re.search(r"\bpackage(?:Name)?=" + re.escape(PKG) + r"\b", block)
                  and "mAttrs=" in block
                  and re.search(r"\bty=(?:2032|ACCESSIBILITY_OVERLAY)\b", attributes(block))
                  and "(fillxfill)" in attributes(block) and visible_window(block)]
        require(len(covers) == 1, "one_native_fullscreen_accessibility_overlay_required")
        block = covers[0]
        identity, pid = window_identity(block)
        require(pid == self.immortal_pid, "overlay_owner_pid_changed")
        require(self.overlay in (None, (identity, pid)), "overlay_identity_changed")
        value = re.search(
            r"\b(?:mScreenBrightness|screenBrightness|sbrt)\s*=\s*(-?\d+(?:\.\d+)?)",
            attributes(block))
        level = float(value.group(1)) if value else -1.0
        require(math.isfinite(level) and (level == -1 or 0 <= level <= 1),
                "window_brightness_bounds")
        after = time.monotonic()
        return dict(seconds=round((before + after) / 2 - self.started, 6),
                    readDurationMs=round((after - before) * 1000, 3),
                    brightness=level, explicit=value is not None, identity=identity, pid=pid)

    def observer(self, night=None):
        diag = self.api("/diag")
        require(diag["hardware"]["appApkPath"] == self.apk_path, "http_apk_binding_changed")
        active = [state for state in diag["hardware"]["nightClockObservers"]
                  if state.get("registered") is True or state.get("requested") is True]
        require(len(active) == 1, "exactly_one_active_overlay_observer_required")
        state = active[0]
        lux = state.get("lux")
        require(state.get("host") and state["host"] != "PhotoDreamService"
                and state.get("registered") is True and state.get("requested") is True
                and state.get("sensorAvailable") is True
                and type(lux) in (float, int) and math.isfinite(lux) and 0 <= lux <= 20
                and state.get("readings", 0) > 0 and state.get("darkLux") == 20,
                "real_dark_overlay_sensor_required")
        require(night is None or state.get("night") is night, "observer_night_state_required")
        require(not self.dream_state()[1], "dream_collision")
        require(self.pid(PKG) == self.immortal_pid, "overlay_process_changed")
        self.save("overlay-observer.json", state)
        self.result["observedLux"] = lux
        return state

    def capture(self, label):
        self.default_source()
        raw = self.adb("exec-out", "screencap", "-p")
        self.save(label + ".png", raw)
        require(raw.startswith(b"\x89PNG\r\n\x1a\n"), "native_png_required")
        with self.Image.open(io.BytesIO(raw)) as image:
            require(image.format == "PNG" and image.size == (WIDTH, HEIGHT), "native_capture_size")
            return image.convert("RGB")

    def photo_baseline(self, seed):
        # Startup discovery may replace the immediate bundled fallback. Admit
        # only successive native captures with actual image content, before the
        # real 20-second dark settle can cover the photo.
        main_deadline = self.deadline
        self.deadline = min(main_deadline, time.monotonic() + 12,
                            self.started + seed["seconds"] + 17)
        previous = None
        attempt = 0
        try:
            while True:
                self.remaining()
                require(self.overlay_state()["brightness"] == -1,
                        "photo_baseline_must_precede_dark_brightness")
                self.observer(night=False)
                attempt += 1
                photo = self.capture(f"photo-baseline-{attempt:02d}").crop(ROI)
                require(self.overlay_state()["brightness"] == -1,
                        "photo_baseline_must_stay_at_default")
                self.remaining()
                if photo.convert("L").entropy() >= 3:
                    if previous is not None and photo.tobytes() == previous.tobytes():
                        self.observer(night=False)
                        self.remaining()
                        self.result["baselineCaptureCount"] = attempt
                        return photo
                    previous = photo
                else:
                    previous = None
                self.pause(.3)
        finally:
            self.deadline = main_deadline

    def ramp(self, name, seed, target, direction, seconds=9, requested_at=None):
        system = int(self.text("shell", "settings", "get", "system", "screen_brightness"))
        require(0 <= system <= 255, "system_brightness_bounds")
        effective_start = seed["brightness"] if seed["brightness"] >= 0 else system / 255
        effective_target = target if target >= 0 else system / 255
        minimum_intermediates = required_intermediate_levels(effective_start, effective_target)
        samples = [seed]
        self.save(name + "-start.json", seed)
        stop = min(self.deadline, time.monotonic() + seconds)
        stable_since = None
        try:
            while time.monotonic() < stop:
                sample = self.overlay_state()
                samples.append(sample)
                if abs(sample["brightness"] - target) <= EPS:
                    if stable_since is None:
                        stable_since = time.monotonic()
                    if time.monotonic() - stable_since >= .3:
                        break
                else:
                    stable_since = None
                self.pause(.04)
            require(stable_since is not None and time.monotonic() - stable_since >= .3,
                    name + "_finite_completion_required")
            positive = [sample for sample in samples if sample["brightness"] >= 0]
            values = [sample["brightness"] for sample in positive]
            require(len(values) >= 5, name + "_several_explicit_samples_required")
            require(all(direction * (b - a) >= -EPS for a, b in zip(values, values[1:])),
                    name + "_monotonic_required")
            if target == -1:
                require(abs(seed["brightness"] - .03) <= EPS, name + "_starts_at_003")
                first_default = next(index for index, sample in enumerate(samples)
                                     if sample["brightness"] == -1)
                require(all(sample["brightness"] == -1 for sample in samples[first_default:]),
                        name + "_default_must_stay_restored")
                end = samples[first_default]
                moved = next((sample for sample in positive
                              if sample["brightness"] > .03 + EPS), None)
                intermediate = [value for value in values if value > .03 + EPS]
            else:
                first_explicit = next(index for index, sample in enumerate(samples)
                                      if sample["brightness"] >= 0)
                require(all(sample["brightness"] >= 0 and sample["explicit"]
                            for sample in samples[first_explicit:]),
                        name + "_override_must_remain_explicit")
                require(samples[-1]["explicit"]
                        and abs(samples[-1]["brightness"] - target) <= EPS, name + "_exact_target")
                end = next(sample for sample in positive if abs(sample["brightness"] - target) <= EPS)
                moved = (positive[0] if seed["brightness"] == -1 else
                         next(sample for sample in positive
                              if abs(sample["brightness"] - seed["brightness"]) > EPS))
                intermediate = [value for value in values if abs(value - target) > EPS
                                and abs(value - seed["brightness"]) > EPS]
            distinct = len({round(value, 6) for value in intermediate})
            low, high = sorted((hardware_level(effective_start), hardware_level(effective_target)))
            interior_levels = {hardware_level(value) for value in values
                               if low < hardware_level(value) < high}
            gaps = [b["seconds"] - a["seconds"] for a, b in zip(samples, samples[1:])]
            require(moved is not None or requested_at is not None and minimum_intermediates == 0,
                    name + "_visible_step_or_request_required")
            duration = end["seconds"] - moved["seconds"] if moved is not None else None
            progression_start = moved["seconds"] if moved is not None else requested_at
            progression = [sample for sample in samples
                           if progression_start <= sample["seconds"] <= end["seconds"]]
            progression_gaps = [b["seconds"] - a["seconds"]
                                for a, b in zip(progression, progression[1:])]
            jumps = [abs(b - a) for a, b in zip(values, values[1:])]
            span = max(values) - min(values)
            receipt = dict(sampleCount=len(samples), intermediateCount=distinct,
                           requiredIntermediateCount=minimum_intermediates,
                           hardwareInteriorCount=len(interior_levels),
                           effectiveStart=effective_start, effectiveTarget=effective_target,
                           progressionSeconds=round(duration, 6) if duration is not None else None,
                           largestSampleGapSeconds=round(max(gaps, default=0), 6),
                           largestProgressionGapSeconds=round(max(progression_gaps, default=0), 6),
                           largestObservedStep=round(max(jumps, default=0), 6),
                           initialBrightness=seed["brightness"], finalBrightness=samples[-1]["brightness"])
            # A coarse step can appear late in a perfectly continuous logical ramp.
            # For live settings, measure from the actual request, not its first visible step.
            checked_duration = duration
            if requested_at is not None:
                checked_duration = end["seconds"] - requested_at
                receipt["requestToEndpointSeconds"] = round(checked_duration, 6)
            self.result[name] = receipt
            require(len(interior_levels) >= minimum_intermediates
                    and 1.8 <= checked_duration <= 4 + max(progression_gaps, default=0)
                    and max(jumps, default=0) <= max(.015, span * .45),
                    name + "_gradual_three_second_progression_required")
            return samples[-1]
        finally:
            self.save(name + "-samples.json", samples)

    def show_cover(self):
        self.no_collector()
        self.default_source()
        require(not self.dream_state()[1], "dream_collision_before_cover")
        self.overlay = None
        self.pin_command("show")
        self.pin_status(True)
        seed = self.overlay_state()
        self.overlay = (seed["identity"], seed["pid"])
        require(seed["brightness"] == -1, "overlay_initial_system_default_required")
        return seed

    def retention(self, covered):
        self.pin_status(covered)
        require(self.files_state() == self.files_identity, "files_activity_window_pid_changed")
        require(self.pid(PKG) == self.immortal_pid, "overlay_process_changed")

    def tap_reveal(self, night=False):
        self.retention(True)
        if night:
            require(abs(self.overlay_state()["brightness"] - .03) <= EPS, "night_tap_target_required")
            self.observer(night=True)
        # Exactly one admitted center tap per cover; polling issues no extra taps.
        self.text("shell", "input", "tap", "1080", "720")
        stop = min(self.deadline, time.monotonic() + 5)
        while True:
            after = self.api("/remote/pin")
            if (after.get("covered") is False and after.get("pendingCleanupCount") == 0
                    and after.get("revealPending") is False):
                break
            require(time.monotonic() < stop, "one_tap_reveal_timeout")
            self.pause(.1)
        self.retention(False)
        blocks = window_blocks(self.text("shell", "dumpsys", "window", "windows"))
        require(not any(re.search(r"\bpackage(?:Name)?=" + re.escape(PKG) + r"\b", block)
                        and "mAttrs=" in block
                        and re.search(r"\bty=(?:2032|ACCESSIBILITY_OVERLAY)\b", attributes(block))
                        and "(fillxfill)" in attributes(block) for block in blocks),
                "native_cover_not_retired")
        self.result["nightTapReveal" if night else "oneTapReveal"] = True
        if night:
            self.result["tapWhileNightClock"] = True

    def run(self):
        self.preflight()
        self.phase = "configure"
        self.apply(TEMP)
        resolved = self.text("shell", "cmd", "package", "resolve-activity", "--brief",
                             "-a", "android.intent.action.MAIN",
                             "-c", "android.intent.category.LAUNCHER", FILES)
        components = [line for line in resolved.splitlines()
                      if re.fullmatch(re.escape(FILES) + r"/[A-Za-z0-9_.$]+", line)]
        require(len(components) == 1, "installed_files_launcher_resolution_required")
        component = components[0]
        package, activity = component.split("/", 1)
        self.files_component = package + "/" + (package + activity if activity.startswith(".") else activity)
        self.files_attempted = True
        launched = self.text("shell", "am", "start", "-W", "-n", component)
        require("Error" not in launched and "Exception" not in launched, "files_launch_failed")
        self.pause(.4)
        self.files_identity = self.files_state()
        before = self.api("/remote/pin")
        require(before.get("available") is True and before.get("pinned") is False
                and before.get("foregroundPackage") == FILES, "files_pin_precondition")
        self.pin_attempted = True
        pin = self.pin_command("pin")
        target = pin.get("targetWindowId")
        require(type(target) is int and target >= 0, "actual_pin_session_window_required")
        self.pin_window = target
        self.pin_status(False)
        self.phase = "dark-entry"
        seed = self.show_cover()
        # Native source startup must finish before freezing a baseline. Two direct
        # captures prove stability; screenrecord/video pixels are never substitutes.
        self.pause(1)
        photo = self.photo_baseline(seed)
        self.result["photoRoiBounds"] = list(ROI)
        self.result["photoRoiSha256"] = hashlib.sha256(photo.tobytes()).hexdigest()
        seed = self.ramp("darkEntry", seed, .03, -1, seconds=35)
        self.observer(night=True)
        self.retention(True)
        clock = self.capture("settled-night").crop(ROI)
        pixels = list(clock.getdata())
        black = sum(pixel == (0, 0, 0) for pixel in pixels)
        warm = sum(pixel == (185, 159, 135) for pixel in pixels)
        self.result.update(clockBlackFraction=round(black / len(pixels), 6), clockWarmPixels=warm)
        require(black / len(pixels) > .7 and warm > 50, "black_clock_with_visible_warm_ink_required")
        self.result["liveBrightnessRampsExecuted"] = False
        if self.args.live_brightness and self.remaining() > 55:
            for name, level, target, direction in (
                    ("liveUp", 15, .15, 1), ("liveDown", 3, .03, -1)):
                self.phase = name
                seed = self.overlay_state()
                requested_at = time.monotonic() - self.started
                self.apply({"nightClockBrightness": level}, readback=False)
                seed = self.ramp(name, seed, target, direction, requested_at=requested_at)
                self.readback({"nightClockBrightness": level})
            self.result["liveBrightnessRampsExecuted"] = True
        self.phase = "registered-disable"
        seed = self.overlay_state()
        requested_at = time.monotonic() - self.started
        self.apply({"darkRoomClock": False}, readback=False)
        self.ramp("registeredDisable", seed, -1, 1, requested_at=requested_at)
        self.readback({"darkRoomClock": False})
        returned = self.capture("returned-photo").crop(ROI)
        require(photo.tobytes() == returned.tobytes(), "same_native_photo_roi_required")
        self.result["samePhotoRoi"] = True
        self.retention(True)
        self.phase = "one-tap-reveal"
        self.tap_reveal()
        if self.args.tap_in_night:
            self.phase = "second-night-tap"
            require(self.remaining() > 45, "second_night_case_budget_required")
            self.apply({"darkRoomClock": True})
            seed = self.show_cover()
            self.ramp("secondDarkEntry", seed, .03, -1, seconds=35)
            self.observer(night=True)
            self.capture("second-settled-night")
            self.tap_reveal(night=True)
        self.result["sameFilesActivityWindowAndPid"] = True
        self.result["success"] = True

    def cleanup(self):
        self.phase = "cleanup"
        self.deadline = time.monotonic() + 30
        self.result["modifiedSettingsCount"] = len(self.changed)
        steps = []
        if self.pin_attempted:
            def unpin():
                state = self.api("/remote/pin")
                # A timed-out pin POST still belongs to this run if its actual
                # target is Files. Never unpin a replacement owned by someone else.
                require(not (state.get("pinned") or state.get("covered") or state.get("nativeCover"))
                        or state.get("targetPackage") == FILES
                        and self.pin_window in (None, state.get("targetWindowId")),
                        "cleanup_foreign_pin_rejected")
                after = self.pin_command("unpin")
                require(after.get("pinned") is False and after.get("covered") is False
                        and after.get("nativeCover") is False
                        and after.get("failedCleanupCount") == 0, "cleanup_unpin_failed")
                stop = min(self.deadline, time.monotonic() + 3)
                while after.get("pendingCleanupCount") != 0:
                    require(time.monotonic() < stop, "cleanup_overlay_retirement_timeout")
                    self.pause(.1)
                    after = self.api("/remote/pin")
                require(after.get("failedCleanupCount") == 0, "cleanup_retirement_failed")
            steps.append(("unpinRestored", unpin))
        if self.changed:
            steps.append(("settingsRestored", lambda: self.apply(self.changed, restoring=True)))
        if self.guarded and self.files_attempted:
            def home():
                # Return Home only when our resolved Files Activity/window/PID
                # still owns the foreground. No Back, force-stop or task clear.
                current = self.files_state()
                require(self.files_identity is None or current == self.files_identity,
                        "cleanup_files_identity_changed")
                # Family API29 exposes mCurrentFocus only in the full dump.
                focus = self.text("shell", "dumpsys", "window")
                focused = re.search(r"mCurrentFocus=Window\{([^}\n]+)\}", focus)
                require(focused is not None
                        and any(component in focused.group(1)
                                for component in component_names(self.files_component)),
                        "cleanup_own_files_foreground_required")
                launched = self.text("shell", "am", "start", "-n",
                                     PKG + "/com.immortal.launcher.HomeActivity")
                require("Error" not in launched and "Exception" not in launched,
                        "cleanup_home_launch_failed")
                self.pause(.3)
                activity = self.text("shell", "dumpsys", "activity", "activities")
                require(re.search(r"(?:mResumedActivity|topResumedActivity)[^\n]*"
                                  + re.escape(PKG) + r"/(?:com\.immortal\.launcher\.|\.?)HomeActivity",
                                  activity), "cleanup_immortal_home_required")
                require(not self.dream_state()[1], "cleanup_dream_reappeared")
            steps.append(("homeSafelyReturned", home))
        if self.original is not None:
            def compare():
                before, after = self.controls(self.original), self.controls(self.profile())
                mismatch = sum(key not in after or after[key]["value"] != control["value"]
                               for key, control in before.items())
                sources = self.api("/remote/sources")
                self.result.update(settingsCompared=len(before), settingsMismatchCount=mismatch,
                                   sourceFieldsCompared=len(self.sources["sources"]),
                                   sourcesExact=sources == self.sources)
                require(set(before) == set(after) and mismatch == 0
                        and sources == self.sources, "full_profile_sources_restore_compare")
            # A preflight failure must not mask missing source/pin prerequisites.
            if self.sources is not None:
                steps.append(("profileAndSourcesRestored", compare))
        if self.guarded:
            steps.append(("collectorInactiveAfter", self.no_collector))
        for name, action in steps:
            try:
                action()
                self.result[name] = True
            except Exception as error:
                self.result[name] = False
                self.result[name + "FailureType"] = type(error).__name__
                self.save(name + "-failure-private.txt", str(error).encode())
                self.result["success"] = False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--connection", type=pathlib.Path, required=True,
                        help="Private authenticated JSON containing base and token")
    parser.add_argument("--serial", required=True, help="ADB transport; physical ro.serialno is separately guarded")
    parser.add_argument("--expected-apk-sha256", required=True,
                        help="Full installed optimized APK SHA256 (6c1adacc...)")
    parser.add_argument("--private-dir", type=pathlib.Path, required=True,
                        help="NEW owner-only evidence directory outside all Git checkouts")
    parser.add_argument("--tap-in-night", action="store_true", help="Add a second night cover and center-tap case")
    parser.add_argument("--live-brightness", action="store_true",
                        help="Add registered 3 -> 15 -> 3 ramps if the main budget permits")
    args = parser.parse_args()
    args.expected_apk_sha256 = args.expected_apk_sha256.lower()
    if not re.fullmatch(r"[0-9a-f]{64}", args.expected_apk_sha256):
        parser.error("--expected-apk-sha256 must contain 64 hex digits")
    if not args.expected_apk_sha256.startswith(APK_PREFIX):
        parser.error("--expected-apk-sha256 must identify the installed optimized 6c1adacc APK")
    if not args.serial or args.serial.startswith("-") or any(char.isspace() for char in args.serial):
        parser.error("--serial must be one explicit ADB transport")
    os.umask(0o077)
    root = pathlib.Path(__file__).resolve().parents[2]
    args.private_dir = args.private_dir.expanduser().resolve()
    if (args.private_dir == root or root in args.private_dir.parents
            or any((ancestor / ".git").exists() for ancestor in (args.private_dir, *args.private_dir.parents))):
        parser.error("--private-dir must be outside Git")
    try:
        args.private_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
    except OSError:
        parser.error("--private-dir must be NEW and writable")
    verifier = Verifier(args)

    def bounded_timeout(*_):
        raise TimeoutError("bounded_timeout")

    signal.signal(signal.SIGALRM, bounded_timeout)
    signal.setitimer(signal.ITIMER_REAL, 180)
    try:
        verifier.run()
    except BaseException as error:
        verifier.result.update(success=False, failureType=type(error).__name__, failurePhase=verifier.phase)
        verifier.save("failure-private.txt", str(error).encode())
    finally:
        signal.setitimer(signal.ITIMER_REAL, 30)
        try:
            verifier.cleanup()
        except BaseException as error:
            verifier.result.update(success=False, cleanupFailureType=type(error).__name__)
            verifier.save("cleanup-failure-private.txt", str(error).encode())
        signal.setitimer(signal.ITIMER_REAL, 0)
        verifier.result["elapsedSeconds"] = round(time.monotonic() - verifier.started, 3)
        fd = os.open(verifier.out / "verification.json",
                     os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "w") as output:
            json.dump(verifier.result, output, indent=2, allow_nan=False)
            output.write("\n")
    print("PASS" if verifier.result["success"] else "FAIL (private evidence retained)")
    print("Private evidence:", verifier.out)
    return 0 if verifier.result["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

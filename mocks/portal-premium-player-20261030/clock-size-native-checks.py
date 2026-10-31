#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Measure digital clock size variants on the parent's owned API 34 AVD.

No APK installation, emulator lifecycle, source changes or direct preference
writes. Screenshots stay private; checks.json/stdout contain scalar measurements
only. The parent owns the AVD and complete backup, and reviews graphics personally.
Requires adb and Pillow. Test deadline: 150 seconds; including cleanup: 180 seconds.

Registry rollback checks live API values, not durable preference writes. The
parent must restore its exact private preference backup with the process stopped,
sync it and verify file bytes; immediate force-stop can retire asynchronous writes.
"""
import argparse
import base64
import io
import json
import os
import pathlib
import re
import signal
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops

SERIAL, AVD, PACKAGE = "emulator-5556", "PortalMusic20261030", "com.immortal.launcher.debug"
PREVIEW = PACKAGE + "/com.immortal.launcher.PhotoFramePreviewActivity"
TEST_SECONDS, TOTAL_SECONDS = 150, 180
STYLES = {
    "big": ("sans-serif-light", 200, (280, 380, 460)),
    "bold": ("Bebas Neue", 400, (320, 430, 500)),
}
SIZES = (("landscape", (2160, 1440)), ("portrait", (1440, 2160)))
DESIRED = {
    "enabled": True, "mode": "wholePhoto", "photo_motion": False,
    "intervalSec": 600, "darkRoomClock": False, "antiBurnIn": False,
    "showNowPlaying": False, "idleSleepMin": 0, "overnightEnabled": False,
}


class CheckFailure(Exception):
    """Contains only a caller-defined, sanitized check name."""


def require(condition, name):
    if not condition:
        raise CheckFailure(name)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None  # Credentials may only be sent to the guarded localhost endpoint.


def face_b64(style, scale):
    font, weight, _ = STYLES[style]
    face = {
        "name": "Native clock size check",
        "clock": {
            "mode": "digital", "font": font, "fontWeight": weight,
            "color": "#ff00ff", "opacity": 1, "gradient": None,
            "position": "middle-center", "format": "24h", "leadingZero": True,
            "sizeScale": scale, "showDate": False, "showSeconds": False,
            "showAmPm": False, "shadow": "none", "separator": "colon",
            "animateDigits": False,
        },
        "battery": {"enabled": False}, "weather": {"enabled": False},
        "nowPlaying": {"enabled": False}, "caption": {"enabled": False},
    }
    return base64.b64encode(json.dumps(face, separators=(",", ":")).encode()).decode()


def display_values(raw, label, pattern):
    """Keep physical and optional override values distinct for exact restoration."""
    lines = raw.decode().strip().splitlines()
    physical, override = None, None
    for line in lines:
        match = re.fullmatch(r"(Physical|Override) " + label + r": (" + pattern + r")", line)
        require(match is not None, "unrecognized_wm_" + label)
        if match[1] == "Physical":
            require(physical is None, "duplicate_physical_" + label)
            physical = match[2]
        else:
            require(override is None, "duplicate_override_" + label)
            override = match[2]
    require(physical is not None, "missing_physical_" + label)
    return physical, override


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--connection", type=pathlib.Path, required=True)
    parser.add_argument("--private-dir", type=pathlib.Path, required=True)
    parser.add_argument("--expected-apk-sha256", required=True)
    args = parser.parse_args()
    require(args.private_dir.is_absolute() and args.private_dir.is_dir(),
            "existing_absolute_private_directory_required")
    private = args.private_dir.resolve()
    root = pathlib.Path(__file__).resolve().parents[2]
    require(not private.is_relative_to(root), "private_directory_inside_worktree")
    require(not any((parent / ".git").exists() for parent in (private, *private.parents)),
            "private_directory_inside_repository")
    require(re.fullmatch(r"[0-9a-f]{64}", args.expected_apk_sha256) is not None,
            "invalid_expected_apk_sha256")
    # Never print the connection, token, raw API responses, hierarchy or adb output.
    connection = json.loads(args.connection.read_text())
    url = urllib.parse.urlsplit(connection["base"])
    require(url.scheme == "http" and url.hostname == "127.0.0.1" and url.port
            and not url.username and not url.password and url.path in ("", "/")
            and not url.query and not url.fragment
            and isinstance(connection.get("token"), str) and connection["token"],
            "private_localhost_connection_required")
    base = connection["base"].rstrip("/")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    started = time.monotonic()
    deadline = started + TEST_SECONDS
    ui_path = "/sdcard/portal-clock-size-native-" + uuid.uuid4().hex + ".xml"
    guarded = settings_touched = size_touched = xml_touched = False
    original_settings = {}
    original_size = original_density = None
    report = {
        "passed": False, "serial": SERIAL, "avd": AVD, "api": 34,
        "debugApkSha256": args.expected_apk_sha256, "physicalPortalVerified": False,
        "testDeadlineSeconds": TEST_SECONDS, "totalDeadlineSeconds": TOTAL_SECONDS,
        "minimumGrowthPercent": 5, "requiredGlyphMarginPx": 1,
        "measurements": [], "comparisons": [], "cleanup": {},
    }

    def remaining(cap=8):
        value = deadline - time.monotonic()
        require(value > 0, "bounded_runtime_exhausted")
        return min(cap, value)

    def pause(seconds):
        time.sleep(min(seconds, remaining(seconds)))
        remaining()

    def adb(*command, cap=8):
        result = subprocess.run(["adb", "-s", SERIAL, *command], capture_output=True,
                                timeout=remaining(cap))
        require(result.returncode == 0, "owned_adb_command_failed")
        return result.stdout

    def api(route, values=None, cap=4):
        body = None if values is None else json.dumps(
            {"domain": "screensaver", "values": values}).encode()
        request = urllib.request.Request(base + route, data=body, headers={
            "Authorization": "Bearer " + connection["token"],
            "Content-Type": "application/json",
        })
        with opener.open(request, timeout=remaining(cap)) as response:
            raw = response.read(1024 * 1024 + 1)
        require(len(raw) <= 1024 * 1024, "registry_response_too_large")
        result = json.loads(raw)
        require(isinstance(result, dict) and result.get("ok"), "registry_request_rejected")
        return result

    def controls(cap=4):
        profile = api("/remote/settings/profile", cap=cap)
        require(profile.get("profileComplete"), "complete_registry_profile_required")
        domains = [d for d in profile["settings"]["domains"] if d["id"] == "screensaver"]
        require(len(domains) == 1, "screensaver_registry_domain_required")
        return {c["key"]: c["value"] for c in domains[0]["controls"] if "value" in c}

    def default_source():
        require(api("/remote/sources")["sources"]["source"] == "default",
                "default_photo_source_required")

    def verify_owner():
        require(adb("emu", "avd", "name").decode().splitlines() == [AVD, "OK"],
                "owned_avd_identity_mismatch")
        require(adb("shell", "getprop", "ro.build.version.sdk").strip() == b"34",
                "owned_api34_required")

    def private_write(name, content):
        # Do not follow output symlinks; private images and sanitized report are owner-readable.
        fd = os.open(private / name, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "wb") as output:
            os.fchmod(output.fileno(), 0o600)
            output.write(content)

    def launch(style, scale):
        verify_owner()
        default_source()
        adb("shell", "am", "force-stop", PACKAGE)
        # PhotoFramePreviewActivity is singleTask: a new process must consume each face extra.
        adb("shell", "am", "start", "-W", "-n", PREVIEW,
            "--ez", "launch_dismiss_app", "false", "--es", "face_b64", face_b64(style, scale))
        pause(1.5)
        # FleetAgentService starts with the app; allow bounded startup after each force-stop.
        ready_by = min(deadline, time.monotonic() + 4)
        while True:
            try:
                default_source()
                break
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                require(time.monotonic() < ready_by, "preview_registry_restart_timed_out")
                pause(0.2)

    def measure(orientation, dimensions, style, scale):
        nonlocal xml_touched
        xml_touched = True  # Mark before a dump which might write and then time out.
        # Remove stale output before every dump, so a failed dump cannot reuse a prior size.
        adb("shell", "rm", "-f", ui_path)
        adb("shell", "uiautomator", "dump", "--compressed", ui_path)
        raw = adb("exec-out", "cat", ui_path)
        nodes = [node for node in ET.fromstring(raw).iter("node")
                 if node.get("package") == PACKAGE
                 and node.get("content-desc", "").startswith("Clock ")]
        require(len(nodes) == 1, "unique_accessible_clock_required")
        require(re.fullmatch(r"Clock (?:[01]\d|2[0-3]):[0-5]\d",
                             nodes[0].get("content-desc", "")) is not None,
                "clock_24h_accessibility_label_required")
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", nodes[0].get("bounds", ""))
        require(match is not None, "clock_bounds_required")
        left, top, right, bottom = bounds = tuple(map(int, match.groups()))
        width, height = dimensions
        require(0 <= left < right <= width and 0 <= top < bottom <= height,
                "clock_view_outside_screen")
        default_source()  # Check again immediately before acquiring any pixels.
        png = adb("exec-out", "screencap", "-p")
        with Image.open(io.BytesIO(png)) as image:
            require(image.format == "PNG" and image.size == dimensions,
                    "screenshot_geometry_mismatch")
            red, green, blue = image.crop(bounds).convert("RGB").split()
        private_write(f"{orientation}-{style}-{scale}.png", png)
        # Photo pixels elsewhere are irrelevant: the mask covers only the measured clock View.
        mask = ImageChops.multiply(
            ImageChops.multiply(red.point(lambda p: 255 if p > 180 else 0),
                                blue.point(lambda p: 255 if p > 180 else 0)),
            green.point(lambda p: 255 if p < 100 else 0),
        )
        area, glyph = mask.histogram()[255], mask.getbbox()
        view_width, view_height = right - left, bottom - top
        row = {
            "orientation": orientation, "screenSizePx": list(dimensions), "densityDpi": 240,
            "style": style, "sizeScale": scale, "clockBoundsPx": list(bounds),
            "viewWidthPx": view_width, "viewHeightPx": view_height,
            "glyphAreaPx": area, "glyphHeightPx": 0, "glyphBoundsPx": None,
            "glyphMarginPx": None,
        }
        if glyph is not None:
            x1, y1, x2, y2 = glyph  # Pillow boxes are half-open, matching XML bounds.
            row.update({
                "glyphHeightPx": y2 - y1,
                "glyphBoundsPx": [left + x1, top + y1, left + x2, top + y2],
                "glyphMarginPx": min(x1, y1, view_width - x2, view_height - y2),
            })
        report["measurements"].append(row)
        require(area > 0 and glyph is not None, "positive_magenta_glyph_area_required")
        require(row["glyphMarginPx"] >= 1, "glyph_bounds_must_be_strictly_inside_clock")
        remaining()
        return row

    def budget_expired(signum, frame):
        raise CheckFailure("bounded_runtime_exhausted")

    def terminated(signum, frame):
        raise CheckFailure("interrupted")

    old_alarm = signal.signal(signal.SIGALRM, budget_expired)
    old_term = signal.signal(signal.SIGTERM, terminated)
    signal.setitimer(signal.ITIMER_REAL, remaining(TEST_SECONDS))
    try:
        verify_owner()
        installed = adb("shell", "pm", "path", PACKAGE).decode().strip().splitlines()
        require(len(installed) == 1 and installed[0].startswith("package:/")
                and installed[0].endswith("/base.apk"), "single_debug_base_apk_required")
        hashes = adb("exec-out", "sha256sum", installed[0][8:]).decode().split()
        require(hashes and hashes[0] == args.expected_apk_sha256, "installed_apk_hash_mismatch")
        # The supplied HTTP port must actually forward to this AVD, before any API access.
        forwards = [line.split() for line in adb("forward", "--list").decode().splitlines()]
        matching = [row for row in forwards if len(row) == 3 and row[1] == f"tcp:{url.port}"]
        require(len(matching) == 1 and matching[0][0] == SERIAL
                and re.fullmatch(r"tcp:\d+", matching[0][2]) is not None,
                "localhost_forward_must_target_owned_avd")
        default_source()
        original_size = display_values(adb("shell", "wm", "size"), "size", r"\d+x\d+")
        original_density = display_values(adb("shell", "wm", "density"), "density", r"\d+")
        require(int(original_density[1] or original_density[0]) == 240,
                "effective_density_240_required")
        before = controls()
        require(all(key in before for key in DESIRED), "required_registry_values_missing")
        original_settings = {key: before[key] for key in DESIRED}
        guarded = True
        report["guardsPassed"] = True
        settings_touched = True  # Save all originals before a potentially ambiguous POST.
        require(set(api("/remote/settings", DESIRED)["applied"]) == set(DESIRED),
                "registry_batch_incomplete")
        after = controls()
        require(all(type(after[key]) is type(value) and after[key] == value
                    for key, value in DESIRED.items()), "registry_configuration_readback_failed")
        for orientation, dimensions in SIZES:
            verify_owner()
            default_source()
            size_touched = True
            target = "x".join(map(str, dimensions))
            adb("shell", "wm", "size", target)
            pause(0.5)
            current = display_values(adb("shell", "wm", "size"), "size", r"\d+x\d+")
            require((current[1] or current[0]) == target, "requested_wm_size_not_applied")
            require(display_values(adb("shell", "wm", "density"), "density", r"\d+")
                    == original_density, "density_must_remain_unchanged")
            for style, (_, _, scales) in STYLES.items():
                previous = None
                for scale in scales:
                    launch(style, scale)
                    row = measure(orientation, dimensions, style, scale)
                    if previous is not None:
                        width_grew = row["viewWidthPx"] * 100 >= previous["viewWidthPx"] * 105
                        height_grew = row["glyphHeightPx"] * 100 >= previous["glyphHeightPx"] * 105
                        report["comparisons"].append({
                            "orientation": orientation, "style": style,
                            "fromScale": previous["sizeScale"], "toScale": scale,
                            "viewWidthGrowthPercent": round(
                                (row["viewWidthPx"] / previous["viewWidthPx"] - 1) * 100, 3),
                            "glyphHeightGrowthPercent": round(
                                (row["glyphHeightPx"] / previous["glyphHeightPx"] - 1) * 100, 3),
                            "viewWidthGrewAtLeast5Percent": width_grew,
                            "glyphHeightGrewAtLeast5Percent": height_grew,
                        })
                        require(width_grew, "clock_view_width_growth_below_5_percent")
                        require(height_grew, "magenta_glyph_height_growth_below_5_percent")
                    previous = row
        require(len(report["measurements"]) == 12 and len(report["comparisons"]) == 8,
                "complete_size_matrix_required")
        report["passed"] = True
    except (Exception, KeyboardInterrupt) as error:
        report["failure"] = str(error) if isinstance(error, CheckFailure) else type(error).__name__
    finally:
        deadline = started + TOTAL_SECONDS

        def cleanup_step(name, seconds, action):
            nonlocal deadline
            total_deadline = deadline
            deadline = min(total_deadline, time.monotonic() + seconds)
            try:
                action()
                report["cleanup"][name] = True
            except (Exception, KeyboardInterrupt) as error:
                report["cleanup"][name] = False
                report.setdefault("cleanupFailures", {})[name] = (
                    str(error) if isinstance(error, CheckFailure) else type(error).__name__)
                report["passed"] = False
            finally:
                deadline = total_deadline

        def restore_size():
            verify_owner()
            adb("shell", "wm", "size", original_size[1] or "reset", cap=3)
            require(display_values(adb("shell", "wm", "size", cap=2), "size", r"\d+x\d+")
                    == original_size, "original_wm_size_not_restored")
            require(display_values(adb("shell", "wm", "density", cap=2), "density", r"\d+")
                    == original_density, "original_density_not_preserved")

        def restore_settings():
            verify_owner()
            try:
                controls(cap=1)
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                # A failed launch may have left the package stopped; restart only to restore API values.
                adb("shell", "am", "start", "-W", "-n", PREVIEW,
                    "--ez", "launch_dismiss_app", "false",
                    "--es", "face_b64", face_b64("big", 280), cap=3)
            while True:
                try:
                    controls(cap=1)
                    break
                except (urllib.error.URLError, TimeoutError, ConnectionError):
                    pause(0.2)
            require(set(api("/remote/settings", original_settings, cap=2)["applied"])
                    == set(original_settings), "registry_restore_batch_incomplete")
            restored = controls(cap=2)
            require(all(type(restored[key]) is type(value) and restored[key] == value
                        for key, value in original_settings.items()),
                    "original_registry_values_not_restored")

        def stop_package():
            verify_owner()
            adb("shell", "am", "force-stop", PACKAGE, cap=3)
            result = subprocess.run(["adb", "-s", SERIAL, "shell", "pidof", PACKAGE],
                                    capture_output=True, timeout=remaining(2))
            require(result.returncode == 1 and not result.stdout.strip(),
                    "debug_package_must_be_stopped")

        def remove_xml():
            verify_owner()
            adb("shell", "rm", "-f", ui_path, cap=2)
            adb("shell", "test", "!", "-e", ui_path, cap=2)

        try:
            signal.setitimer(signal.ITIMER_REAL, max(0.001, deadline - time.monotonic()))
            # Keep rollback steps independent: failure of one must not skip the others.
            if guarded:
                if size_touched:
                    cleanup_step("originalWmSizeAndDensityRestored", 7, restore_size)
                if settings_touched:
                    cleanup_step("originalRegistryValuesRestored", 14, restore_settings)
                cleanup_step("packageStopped", 5, stop_package)
                if xml_touched:
                    cleanup_step("ownXmlRemoved", 4, remove_xml)
        except (Exception, KeyboardInterrupt) as error:
            report["passed"] = False
            report["cleanupFailure"] = (
                str(error) if isinstance(error, CheckFailure) else type(error).__name__)
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
            signal.signal(signal.SIGALRM, old_alarm)
            signal.signal(signal.SIGTERM, old_term)
            report["elapsedSeconds"] = round(time.monotonic() - started, 3)
            private_write("checks.json", (json.dumps(report, indent=2) + "\n").encode())
            print(json.dumps(report, indent=2), flush=True)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

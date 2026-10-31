#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Native content fade plus window-brightness/retention checks on the owned API34 AVD.

Raw video and connection stay private. The published receipt contains only
default-photo luminance samples from a region above the clock text. Recording
observes rendered content; it cannot measure physical backlight brightness.
"""
import argparse
import importlib.util
import json
import pathlib
import subprocess
import sys
import time
import uuid

sys.dont_write_bytecode = True
BASE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("night_brightness", BASE / "night-brightness-native-checks.py")
brightness = importlib.util.module_from_spec(spec)
spec.loader.exec_module(brightness)


class Driver(brightness.Driver):
    def __init__(self, connection, private):
        super().__init__(connection, 180, BASE.parents[1] / "app/build/outputs/apk/debug/app-debug.apk", False)
        self.out = BASE / "evidence/native-night-crossfade"
        self.private = private
        self.recording = None
        self.remote = "/sdcard/portal-night-fade-" + uuid.uuid4().hex + ".mp4"

    def collect(self, *args, **kwargs):
        if self.recording is None:
            self.verify_owner()
            self.recording = subprocess.Popen(
                ["adb", "-s", brightness.retained.SERIAL, "shell", "screenrecord",
                 "--size", "1080x720", "--bit-rate", "4000000", "--time-limit", "45", self.remote],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return super().collect(*args, **kwargs)

    def verify_pixels(self):
        self.recording.wait(timeout=48)
        self.require("owned_native_recording_completed", self.recording.returncode == 0)
        video = self.private / "transition.mp4"
        self.adb("pull", self.remote, str(video))
        self.adb("shell", "rm", self.remote, mutate=True)
        result = subprocess.run(
            ["/opt/homebrew/bin/ffmpeg", "-v", "error", "-i", str(video),
             "-vf", "fps=12,crop=450:50:300:170,scale=1:1",
             "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
            capture_output=True, check=True, timeout=15)
        raw = result.stdout
        samples = [sum(raw[i:i + 3]) / 3 for i in range(0, len(raw), 3)]
        self.require("native_content_has_sufficient_frames", len(samples) >= 120)
        base = sum(samples[:12]) / 12
        self.require("default_photo_probe_has_visible_detail", base >= 10)
        normalized = [value / base for value in samples]
        dark = [i for i, value in enumerate(normalized) if value < .01]
        self.require("clock_cover_reaches_black_outside_text", bool(dark))
        first_dark, last_dark = dark[0], dark[-1]
        # Stop at the retained-photo endpoint; later Done/Home navigation is outside this fade.
        returned = next((i for i in range(last_dark + 1, len(normalized))
                         if normalized[i] >= .95), None)
        self.require("retained_photo_reaches_original_rendered_level", returned is not None)
        down = [(i, v) for i, v in enumerate(normalized[:first_dark]) if .1 < v < .9]
        up = [(i, normalized[i]) for i in range(last_dark + 1, returned + 1)
              if .1 < normalized[i] < .9]
        self.require("photos_to_clock_has_many_blended_native_frames", len(down) >= 12)
        self.require("clock_to_photos_has_many_blended_native_frames", len(up) >= 12)
        self.require("photos_to_clock_gradual_content_duration", (down[-1][0] - down[0][0]) / 12 >= 1.5)
        self.require("clock_to_photos_gradual_content_duration", (up[-1][0] - up[0][0]) / 12 >= 1.5)
        self.require("photo_dim_has_no_intermediate_flash",
                     all(b[1] - a[1] <= .04 for a, b in zip(down, down[1:])))
        self.require("photo_return_has_no_intermediate_black_flash",
                     all(a[1] - b[1] <= .04 for a, b in zip(up, up[1:])))
        self.observations["renderedContentFade"] = {
            "sampleFps": 12, "downBlendedFrames": len(down), "upBlendedFrames": len(up),
            "downIntermediateSeconds": round((down[-1][0] - down[0][0]) / 12, 3),
            "upIntermediateSeconds": round((up[-1][0] - up[0][0]) / 12, 3),
            "probeBoundsAt1080x720": [300, 170, 750, 220],
            "normalizedLuminanceSamples": [round(v, 4) for v in normalized],
            "physicalPanelMeasured": False,
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--connection", type=pathlib.Path, required=True)
    parser.add_argument("--private-output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.private_output.mkdir(parents=True, exist_ok=True)
    connection = json.loads(args.connection.read_text())
    if not connection["base"].startswith("http://127.0.0.1:"):
        raise ValueError("Only the owned emulator private localhost connection is allowed")
    driver = Driver(connection, args.private_output)
    try:
        driver.run()
        driver.verify_pixels()
    except Exception as error:
        driver.failure = str(error) if isinstance(error, brightness.CheckFailure) else type(error).__name__
    finally:
        driver.cleanup()
        if driver.recording is not None:
            driver.recording.wait(timeout=48)
            driver.adb("shell", "rm", "-f", driver.remote, mutate=True)
    return driver.publish()


if __name__ == "__main__":
    raise SystemExit(main())

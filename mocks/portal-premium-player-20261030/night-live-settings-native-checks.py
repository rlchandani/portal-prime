#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Live clock controls through the registered API, on the explicitly owned API 34 AVD."""
import argparse
import importlib.util
import json
import pathlib
import sys
import time
import urllib.parse

sys.dont_write_bytecode = True
BASE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("retained_night", BASE / "night-retention-native-checks.py")
retained = importlib.util.module_from_spec(spec)
spec.loader.exec_module(retained)


class Driver(retained.Driver):
    def __init__(self, connection):
        super().__init__(connection, 180)
        self.out = BASE / "evidence/native-night-live-settings"

    def update(self, values):
        controls = self.profile()
        for key in values:
            self.changed.setdefault(key, controls[key]["value"])
        result = self.api("/remote/settings", values)
        self.require("live_batch_" + "_".join(values),
                     set(result.get("applied", [])) == set(values))
        self.require("live_readback_" + "_".join(values),
                     all(self.profile()[key]["value"] == value for key, value in values.items()))
        self.wait_until(time.monotonic() + .5)

    def demand_clock(self, name, baseline):
        super().demand_clock(name, baseline)
        if name != "paused-night":
            return
        events_before = len(self.events()["scene"])
        self.update({"nightClockBrightness": 5, "nightClockTone": "red", "darkRoomLux": 4})
        nodes = self.ui()
        self.require("live_threshold_keeps_existing_clock",
                     len(self.clock_nodes(nodes)) == 1)
        window = self.same_owner("live-night", baseline)
        self.require("live_window_brightness_005",
                     window["brightnessExplicit"] and abs(window["brightness"] - .05) < 1e-6)
        image = self.screenshot("live-red-clock")
        self.require("existing_clock_displays_exact_red_palette",
                     sum(pixel == (166, 85, 72) for pixel in image.getdata()) > 50)
        self.require("live_controls_do_not_prepare_another_photo",
                     len(self.events()["scene"]) == events_before)
        self.update({"nightClockBrightness": 3, "nightClockTone": "warm", "darkRoomLux": 3})
        super().demand_clock("live-restored-clock", baseline)
        image = self.screenshot("live-warm-clock")
        self.require("existing_clock_displays_exact_warm_palette",
                     sum(pixel == (185, 159, 135) for pixel in image.getdata()) > 50)

    def toggle_case(self):
        self.progress("LIVE_DISABLE_ENABLE")
        case = self.cases["liveDisableEnable"] = {"executed": True, "success": False}
        self.configure(5)
        self.light(500)
        self.launch_device_epoch = float(self.adb("shell", "date", "+%s.%N").decode().strip())
        self.launch()
        self.dismiss_immersive_education()
        self.settle_startup(0)
        self.tap_node("Pause", self.toolbar("Pause"))
        self.wait_until(time.monotonic() + 2)
        photo_hash = self.capture_baseline("toggle-before")
        baseline = self.identities()
        scene_count = len(self.events()["scene"])
        self.update({"darkRoomClock": False})
        self.light(0)
        self.wait_until(time.monotonic() + 6)
        self.demand_photo("toggle-disabled-dark", baseline, photo_hash)
        observers = self.api("/diag")["hardware"]["nightClockObservers"]
        self.require("disabled_clock_has_no_registered_light_observer",
                     not any(observer["registered"] for observer in observers))
        self.update({"darkRoomClock": True})
        self.wait_until(time.monotonic() + 22)
        self.demand_clock("toggle-enabled-night", baseline)
        self.update({"darkRoomClock": False})
        self.demand_photo("toggle-disabled-visible-clock", baseline, photo_hash)
        self.require("disabling_clock_keeps_USER_PAUSED",
                     any(node.get("text") == "Resume" for node in self.ui()))
        self.light(500)
        self.update({"darkRoomClock": True})
        self.wait_until(time.monotonic() + 6)
        self.demand_photo("toggle-reenabled-bright", baseline, photo_hash)
        self.require("all_live_toggles_keep_the_same_photo_scene",
                     len(self.events()["scene"]) == scene_count)
        case["success"] = True
        case["retainedPhotoRoiSha256"] = photo_hash
        self.tap_node("finish_live_toggle_case", self.toolbar("Done"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--connection", type=pathlib.Path, required=True)
    parser.add_argument("--toggle-only", action="store_true")
    args = parser.parse_args()
    connection = json.loads(args.connection.read_text())
    url = urllib.parse.urlparse(connection["base"])
    if url.hostname != "127.0.0.1" or url.scheme != "http" or not connection.get("token"):
        raise ValueError("A private owned-emulator localhost connection is required")
    driver = Driver(connection)
    if args.toggle_only:
        driver.out = BASE / "evidence/native-night-live-toggle"
    try:
        driver.preflight()
        driver.finish_prior_preview()
        if args.toggle_only:
            driver.toggle_case()
        else:
            driver.pause_case()
    except Exception as error:
        driver.failure = str(error) if isinstance(error, retained.CheckFailure) else type(error).__name__
    finally:
        driver.cleanup()
    return driver.publish()


if __name__ == "__main__":
    raise SystemExit(main())

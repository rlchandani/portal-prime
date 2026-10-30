#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository root.
"""Generated, silent player layout/metadata regressions on the owned AVD only."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--serial", default="emulator-5556")
args = parser.parse_args()
started = time.monotonic()
out = Path(__file__).resolve().parent / "evidence" / "music-continuity-native"
out.mkdir(parents=True, exist_ok=True)
checks = []
component = "com.immortal.launcher.debug/com.immortal.launcher.MusicDesignPreviewActivity"


def adb(*command, binary=False):
    if time.monotonic() - started > 120:
        raise TimeoutError("Music continuity check exceeded its two-minute deadline")
    result = subprocess.run(
        ["adb", "-s", args.serial, *command],
        capture_output=True, check=True, timeout=15,
    )
    return result.stdout if binary else result.stdout.decode()


assert args.serial == "emulator-5556"
assert adb("emu", "avd", "name").splitlines()[0] == "PortalMusic20261030"


def capture(name):
    adb("shell", "uiautomator", "dump", "/sdcard/music-continuity-check.xml")
    xml = adb("shell", "cat", "/sdcard/music-continuity-check.xml")
    (out / f"{name}.xml").write_text(xml)
    (out / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    return list(ET.fromstring(xml).iter("node"))


def launch(name, theme):
    adb("shell", "am", "force-stop", "com.immortal.launcher.debug")
    adb("shell", "am", "start", "-W", "-n", component, "--es", "theme", theme)
    time.sleep(0.5)
    return capture(name)


def node(nodes, label):
    return next(n for n in nodes
                if n.get("text") == label or n.get("content-desc") == label)


def tap(nodes, label):
    box = tuple(map(int, re.findall(r"\d+", node(nodes, label).get("bounds"))))
    left, top, right, bottom = box
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def selected_tab(nodes):
    parents = {child: parent for parent in nodes for child in parent}
    selected = []
    for label in ("Queue", "Lyrics", "Players"):
        current = node(nodes, label)
        while not any(child.get("text") == label for child in current):
            current = parents[current]
        if current.get("selected") == "true":
            selected.append(label)
    return selected


def demand(name, condition):
    checks.append({"name": name, "passed": bool(condition)})
    print("PASS" if condition else "FAIL", name, flush=True)


for theme in ("dark", "light"):
    nodes = launch(f"{theme}-start", theme)
    tap(nodes, "Players")
    nodes = capture(f"{theme}-players")
    demand(f"{theme}: Players selected before layout change",
           selected_tab(nodes) == ["Players"])
    tap(nodes, "Minimize player")
    nodes = capture(f"{theme}-compact")
    demand(f"{theme}: compact player retains current track",
           any(n.get("text") == "After the sun" for n in nodes))
    tap(nodes, "Open player")
    nodes = capture(f"{theme}-compact-return")
    demand(f"{theme}: compact → full retains Players selection",
           selected_tab(nodes) == ["Players"])
    demand(f"{theme}: compact → full retains track and paused transport",
           any(n.get("text") == "After the sun" for n in nodes)
           and node(nodes, "Play").get("enabled") == "true")
    # Independently establish Players again, even when the preceding RED failed.
    tap(nodes, "Players")
    nodes = capture(f"{theme}-before-photos")
    tap(nodes, "Photos")
    nodes = capture(f"{theme}-photos")
    demand(f"{theme}: photo presentation retains current track",
           any(n.get("text") == "After the sun" for n in nodes))
    tap(nodes, "Full player")
    nodes = capture(f"{theme}-photos-return")
    demand(f"{theme}: photos → full retains Players selection",
           selected_tab(nodes) == ["Players"])
    demand(f"{theme}: photos → full retains track and paused transport",
           any(n.get("text") == "After the sun" for n in nodes)
           and node(nodes, "Play").get("enabled") == "true")
    tap(nodes, "Queue")
    nodes = capture(f"{theme}-queue-return")
    demand(f"{theme}: Queue remains selectable after both layout round trips",
           selected_tab(nodes) == ["Queue"]
           and any(n.get("text") == "6 tracks" for n in nodes))
    tap(nodes, "Blue distance")
    nodes = capture(f"{theme}-metadata")
    demand(f"{theme}: selected track has its own album and artist",
           any(n.get("text") == "North shore" for n in nodes)
           and any(n.get("text") == "North Atlantic" for n in nodes)
           and not any(n.get("text") == "The quiet hours" for n in nodes))

passed = sum(c["passed"] for c in checks)
report = {
    "complete": True, "success": passed == len(checks),
    "passed": passed, "total": len(checks), "checks": checks,
    "elapsedSeconds": round(time.monotonic() - started, 3),
    "avd": "PortalMusic20261030", "api": 34,
    "generatedSilentFixtureOnly": True,
    "physicalPortalUiVerified": False, "physicalPortalPerformanceMeasured": False,
    "audioPlaybackVerified": False,
}
(out / "checks.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps({k: report[k] for k in ("success", "passed", "total", "elapsedSeconds")}))
raise SystemExit(0 if report["success"] else 1)

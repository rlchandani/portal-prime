#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository root.
"""Exercise the production background video owner with an original silent debug clip."""
import json
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

from PIL import Image

serial = "emulator-5556"
out = pathlib.Path(__file__).resolve().parent / "evidence" / "video-native"
out.mkdir(parents=True, exist_ok=True)
started = time.monotonic()
checks = []
read_position_point = None


def adb(*args, binary=False):
    assert time.monotonic() - started < 120, "Two-minute video acceptance deadline"
    result = subprocess.run(["adb", "-s", serial, *args], check=True,
                            capture_output=True, timeout=15)
    return result.stdout if binary else result.stdout.decode()


assert adb("emu", "avd", "name").splitlines()[0] == "PortalMusic20261030"


def capture(name):
    global read_position_point
    if read_position_point is not None:
        adb("shell", "input", "tap", *map(str, read_position_point))
    adb("shell", "uiautomator", "dump", "/sdcard/video-design-check.xml")
    xml = adb("shell", "cat", "/sdcard/video-design-check.xml")
    (out / f"{name}.xml").write_text(xml)
    nodes = list(ET.fromstring(xml).iter("node"))
    button = next(n for n in nodes if n.get("text", "").casefold() == "read position")
    left, top, right, bottom = map(int, re.findall(r"\d+", button.get("bounds")))
    read_position_point = ((left + right) // 2, (top + bottom) // 2)
    return nodes


def text(nodes, prefix):
    return next(n.get("text") for n in nodes if n.get("text", "").startswith(prefix))


def position(nodes):
    return int(text(nodes, "Video position: ").split(": ")[1])

def wait_position(name, predicate):
    # Native seek/start finish asynchronously; an on-demand label is only a snapshot.
    for attempt in range(3):
        nodes = capture(f"{name}-{attempt}")
        if predicate(position(nodes)):
            return nodes
    raise AssertionError(f"{name}: native position did not reach the expected state")


def tap(nodes, label):
    node = next(n for n in nodes if n.get("text", "").casefold() == label.casefold())
    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def demand(label, condition):
    assert condition, label
    checks.append(label)
    print("PASS", label, flush=True)


adb("shell", "am", "force-stop", "com.immortal.launcher.debug")
adb("shell", "am", "start", "-W", "-n",
    "com.immortal.launcher.debug/com.immortal.launcher.VideoDesignPreviewActivity")
time.sleep(0.7)
nodes = capture("ready")
demand("Production native player prepares the 1280x720 silent clip",
       text(nodes, "Video ready:").startswith("Video ready: 1280x720; preparations: 1"))
nodes = capture("playing")
demand("Native playback advances its cached position", position(nodes) > 0)
(out / "ready.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
image = Image.open(out / "ready.png").convert("RGB")
demand("Video Surface renders pixels below the controls",
       # Software FFmpeg and Android's codec use different video-range/color conversion.
       # The black root and dark controls remain far outside this fixture's color range.
       all(abs(actual - expected) <= 24 for actual, expected in
           zip(image.getpixel((image.width // 2, image.height // 2)), (54, 80, 95))))
tap(nodes, "Pause")
nodes = capture("paused")
p = position(nodes)
time.sleep(0.6)
nodes = capture("paused-retained")
demand("Pause retains the actual playback position", abs(position(nodes) - p) <= 250)
tap(nodes, "Seek")
nodes = wait_position("seek-paused", lambda value: abs(value - 3000) <= 250)
demand("Seek completes while paused and updates the cached position",
       abs(position(nodes) - 3000) <= 250)
p = position(nodes)
tap(nodes, "Resume")
nodes = wait_position("resumed", lambda value: value > p + 400)
demand("Resume advances from the retained seek position", position(nodes) > p + 400)
tap(nodes, "Stop")
nodes = capture("stopped")
demand("Stop clears cached position", position(nodes) == 0)
tap(nodes, "Replace rapidly")
nodes = capture("replaced")
demand("100 replacements accept only the current clip's prepared callback",
       text(nodes, "Video ready:").endswith("preparations: 2"))
nodes = wait_position("replacement-playing", lambda value: value > 0)
demand("The final replacement plays", position(nodes) > 0)
tap(nodes, "Close")
nodes = capture("closed")
time.sleep(0.6)
nodes = capture("closed-retained")
demand("Close rejects late playback callbacks", text(nodes, "Video closed") == "Video closed")
demand("Closed playback clears the cached position", position(nodes) == 0)
(out / "checks.json").write_text(json.dumps({
    "complete": True, "passed": len(checks), "checks": checks,
    "elapsedSeconds": round(time.monotonic() - started, 2),
    "avd": "PortalMusic20261030", "physicalPortalPerformanceMeasured": False,
    "householdMediaUsed": False,
}, indent=2) + "\n")

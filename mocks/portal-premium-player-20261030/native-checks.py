#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository root.
"""Silent native UI acceptance on the dedicated test AVD; never a household Portal."""
import argparse
import json
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument("--serial", default="emulator-5556")
args = parser.parse_args()
started = time.monotonic()
out = pathlib.Path(__file__).resolve().parent / "evidence" / "native"
out.mkdir(parents=True, exist_ok=True)
checks = []
component = "com.immortal.launcher.debug/com.immortal.launcher.MusicDesignPreviewActivity"


def adb(*command, binary=False):
    if time.monotonic() - started > 240:
        raise TimeoutError("Native visual check exceeded its four-minute deadline")
    result = subprocess.run(
        ["adb", "-s", args.serial, *command], capture_output=True, check=True, timeout=20
    )
    return result.stdout if binary else result.stdout.decode()


assert args.serial.startswith("emulator-")
assert adb("emu", "avd", "name").splitlines()[0] == "PortalMusic20261030"


def launch(name, *extras):
    adb("shell", "am", "force-stop", "com.immortal.launcher.debug")
    adb("shell", "am", "start", "-W", "-n", component, *extras)
    time.sleep(0.7)
    return capture(name)


def capture(name):
    adb("shell", "uiautomator", "dump", "/sdcard/music-design-check.xml")
    xml = adb("shell", "cat", "/sdcard/music-design-check.xml")
    (out / f"{name}.xml").write_text(xml)
    (out / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    return list(ET.fromstring(xml).iter("node"))


def node(nodes, label):
    return next(n for n in nodes if n.get("content-desc") == label or n.get("text") == label)


def bounds(n):
    return tuple(map(int, re.findall(r"\d+", n.get("bounds"))))


def tap(nodes, label):
    left, top, right, bottom = bounds(node(nodes, label))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def demand(name, condition):
    assert condition, name
    checks.append(name)
    (out / "checks.json").write_text(json.dumps({
        "passed": len(checks), "checks": checks, "complete": False,
        "avd": "PortalMusic20261030", "physicalPortalPerformanceMeasured": False,
    }, indent=2) + "\n")
    print("PASS", name, flush=True)


nodes = launch("full-dark")
demand("Native sample and six-track queue render", node(nodes, "6 tracks") is not None)
for label in ("Play", "Previous track", "Next track", "Minimize player", "Close player"):
    left, top, right, bottom = bounds(node(nodes, label))
    demand(f"{label} has a 48dp touch target", min(right - left, bottom - top) >= 72)
left, top, right, bottom = bounds(node(nodes, "Play"))
image = Image.open(out / "full-dark.png").convert("RGB")
demand("Play circle bottom is visible, not clipped",
       min(image.getpixel(((left + right) // 2, bottom - 8))) >= 180)
tap(nodes, "Blue distance")
nodes = capture("queue-selected")
demand("Queue selection updates hero metadata and remains paused",
       sum(n.get("text") == "Blue distance" for n in nodes) >= 2
       and node(nodes, "Play").get("enabled") == "true")
tap(nodes, "Minimize player")
nodes = capture("compact")
demand("Compact retains selected track and transport", node(nodes, "Blue distance") is not None
       and node(nodes, "Next track").get("enabled") == "true")
tap(nodes, "Open player")
nodes = capture("expanded")
demand("Expand retains selected track", sum(n.get("text") == "Blue distance" for n in nodes) >= 2)
tap(nodes, "Players")
nodes = capture("speakers")
demand("Speakers pane replaces queue inside the same player",
       node(nodes, "Your speakers") is not None and node(nodes, "Family Area") is not None)
nodes = launch("offline", "--es", "scenario", "offline")
demand("Offline transport is disabled", all(node(nodes, label).get("enabled") == "false"
       for label in ("Play", "Previous track", "Next track")))
nodes = launch("full-light", "--es", "theme", "light")
demand("Light palette renders the same controls", node(nodes, "Play").get("enabled") == "true")
nodes = launch("large-font", "--ef", "font_scale", "1.8")
left, top, right, bottom = bounds(node(nodes, "Play"))
image = Image.open(out / "large-font.png").convert("RGB")
demand("Large-font transport remains visible",
       bottom <= image.height and min(image.getpixel(((left + right) // 2, bottom - 8))) >= 180)
nodes = launch("night-warm", "--es", "screen", "night")
demand("Actual native clock includes time and weekday",
       any(re.match(r"\d{1,2}:\d{2}, ", n.get("content-desc", "")) and
           any(day in n.get("content-desc", "") for day in
               ("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"))
           for n in nodes))
launch("night-red", "--es", "screen", "night", "--es", "tone", "red")
nodes = launch("gallery-print", "--es", "screen", "gallery")
demand("Actual Gallery stage renders the new style", node(nodes, "Gallery print") is not None)
image = Image.open(out / "gallery-print.png").convert("RGB")
demand("Gallery matte and retained photo differ", image.getpixel((32, 32)) == (28, 27, 25)
       and image.getpixel((image.width // 2, image.height // 2)) != (28, 27, 25))
nodes = launch("library", "--es", "screen", "library")
demand("Native library reports six collections and renders the first row",
       node(nodes, "6 collections") is not None and
       all(node(nodes, title) is not None for title in
           ("The quiet hours", "North shore", "Open windows", "Small hours", "Open road")))
adb("shell", "input", "swipe", "1000", "1250", "1000", "500", "350")
nodes = capture("library-scrolled")
demand("Lazy library reveals its sixth supplied album on scroll",
       node(nodes, "Quiet bloom") is not None)
adb("shell", "input", "swipe", "1000", "500", "1000", "1250", "350")
nodes = capture("library-top")
tap(nodes, "The quiet hours")
nodes = capture("album-detail")
demand("Album detail includes its supplied track and optional play action",
       node(nodes, "After the sun") is not None and node(nodes, "Play").get("enabled") == "true")
tap(nodes, "Back")
nodes = capture("library-return")
demand("Album back returns to library in the same activity", node(nodes, "6 collections") is not None)
nodes = launch("playlists", "--es", "screen", "playlists")
demand("Native playlists present three supplied collections",
       all(node(nodes, title) is not None for title in ("Slow mornings", "After dark", "At home")))
tap(nodes, "Slow mornings")
nodes = capture("playlist-detail")
demand("Playlist detail presents its three supplied tracks",
       all(node(nodes, title) is not None for title in ("After the sun", "Blue distance", "The long way home")))
tap(nodes, "Blue distance")
nodes = capture("collection-selected-player")
demand("Collection selection returns to player and preserves the selected track",
       sum(n.get("text") == "Blue distance" for n in nodes) >= 2
       and node(nodes, "Play").get("enabled") == "true")
nodes = launch("library-empty", "--es", "screen", "library", "--es", "scenario", "setup")
demand("Unconfigured library shows an honest empty state", node(nodes, "Your library is empty") is not None)
nodes = launch("playlists-offline", "--es", "screen", "playlists", "--es", "scenario", "offline")
demand("Unavailable playlists show status without fabricated cards",
       node(nodes, "Playlists unavailable") is not None and not any(n.get("text") == "Slow mornings" for n in nodes))
nodes = launch("library-large-font", "--es", "screen", "library", "--ef", "font_scale", "1.8")
demand("Large-font library retains visible navigation", all(bounds(node(nodes, label))[3] <= 1440
       for label in ("Back", "Now playing")))
tap(nodes, "The quiet hours")
nodes = capture("album-large-font")
demand("Large-font album detail retains navigation and collection title",
       node(nodes, "The quiet hours") is not None and bounds(node(nodes, "Back"))[3] <= 1440)
nodes = launch("playlists-light", "--es", "screen", "playlists", "--es", "theme", "light")
demand("Light playlists retain their supplied metadata", node(nodes, "After dark") is not None)
adb("shell", "am", "force-stop", "com.immortal.launcher.debug")
(out / "checks.json").write_text(json.dumps({
    "passed": len(checks), "checks": checks, "complete": True, "serial": args.serial,
    "avd": "PortalMusic20261030", "api": 34, "viewport": "2160x1440", "densityDpi": 240,
    "durationSeconds": round(time.monotonic() - started, 2),
    "data": "debug-only silent fixtures and original local artwork",
    "physicalPortalPerformanceMeasured": False, "audioMeasured": False,
    "physicalLightSensorTransitionsMeasured": False,
}, indent=2) + "\n")

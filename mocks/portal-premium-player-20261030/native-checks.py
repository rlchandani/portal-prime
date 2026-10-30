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

from PIL import Image, ImageStat

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


def button_node(nodes, label):
    described = node(nodes, label)
    if described.get("clickable") == "true":
        return described
    # Text buttons expose their label in a child View. Enabled state belongs to
    # the clickable ancestor, which has the same complete touch-target bounds.
    return next(n for n in nodes if n.get("clickable") == "true"
                and n.get("bounds") == described.get("bounds"))


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


def side_panel_tabs(nodes):
    """Resolve labeled tab controls without assuming that availability implies selection."""
    labels = ("Queue", "Lyrics", "Players")
    parents = {child: parent for parent in nodes for child in parent}
    tabs = {}
    for label in labels:
        current = node(nodes, label)
        # Compose exposes the label inside its tab owner. A selected Role.Tab
        # can deliberately have clickable=false in Android accessibility, so
        # resolve the owner by its labeled children, rather than clickability.
        while not any(child.get("text") == label for child in current):
            assert current in parents, f"No tab control for {label}"
            current = parents[current]
        tabs[label] = current
    return tabs


def side_panel_segments(name, nodes, selected, *, offline=False, pixels=True):
    labels = ("Queue", "Lyrics", "Players")
    tabs = side_panel_tabs(nodes)
    demand(f"{name}: exactly one tab is semantically selected ({selected})",
           [label for label in labels if tabs[label].get("selected") == "true"] == [selected])
    # Selection is independent of availability: Queue remains current when offline.
    demand(f"{name}: Queue/Lyrics/Players retain their enabled states",
           [tabs[label].get("enabled") for label in labels]
           == ["false" if offline else "true", "false", "true"])
    if not pixels:
        return
    side_panel_segment_pixels(name, nodes, tabs, selected)


def side_panel_segment_pixels(name, nodes, tabs, selected):
    """Compare a saved native render with its shared container, in either palette.

    Reads saved pixels without issuing device commands. Keeping these assertions
    separate also lets previous renders reveal visual defects despite older XML
    selection semantics; the caller records verdicts through demand.
    """
    labels = ("Queue", "Lyrics", "Players")
    containers = [n for n in nodes if all(tab in set(n.iter("node")) for tab in tabs.values())]
    container = min(containers, key=lambda n:
                    (bounds(n)[2] - bounds(n)[0]) * (bounds(n)[3] - bounds(n)[1]))
    boxes = [bounds(tabs[label]) for label in labels]
    left, top, right, bottom = bounds(container)
    demand(f"{name}: labeled tabs share one horizontal container",
           all(left <= b[0] < b[2] <= right and top <= b[1] < b[3] <= bottom
               for b in boxes)
           and len({(b[1], b[3]) for b in boxes}) == 1
           and all(a[2] < b[0] for a, b in zip(boxes, boxes[1:])))

    with Image.open(out / f"{name}.png") as screenshot:
        image = screenshot.convert("RGB")

    def patch(box):
        x1, y1, x2, y2 = box
        assert 0 <= x1 < x2 <= image.width and 0 <= y1 < y2 <= image.height, (
            f"{name}: invalid background sampling patch {box}")
        crop = image.crop(box)
        color = tuple(ImageStat.Stat(crop).median)
        noise = max(max(abs(a - b) for a, b in zip(pixel, color))
                    for pixel in crop.getdata())
        return color, noise

    def distance(a, b):
        return max(abs(x - y) for x, y in zip(a, b))

    # The central strips BETWEEN the labeled tab bounds belong to the actual
    # common container. Inset from their edges to avoid button antialiasing.
    references = []
    for a, b in zip(boxes, boxes[1:]):
        height = a[3] - a[1]
        references.append(patch((a[2] + 1, a[1] + round(height * .35),
                                 b[0] - 1, a[1] + round(height * .65))))
    background = references[0][0]
    noise = max(sample[1] for sample in references)
    demand(f"{name}: common-container reference patches agree",
           distance(background, references[1][0]) <= 2 and noise <= 2)

    interiors = {}
    for label, box in zip(labels, boxes):
        x1, y1, x2, y2 = box
        width, height = x2 - x1, y2 - y1
        text_boxes = [bounds(n) for n in tabs[label].iter("node")
                      if n.get("text") == label]
        assert text_boxes, f"{name}: missing text bounds for {label}"
        samples = []
        # Wide interior patches on BOTH sides, midway down the button: far from
        # rounded corners and the centered label. Guard against label overlap.
        for start, end in ((.18, .26), (.74, .82)):
            region = (x1 + round(width * start), y1 + round(height * .4),
                      x1 + round(width * end), y1 + round(height * .6))
            margin = round(height * .12)
            assert all(region[2] <= text[0] - margin or region[0] >= text[2] + margin
                       for text in text_boxes), f"{name}: {label} patch approaches text"
            samples.append(patch(region))
        interiors[label] = samples
    contrast = min(distance(color, background) for color, _ in interiors[selected])
    tolerance = max(2, noise * 2, contrast * .12)
    demand(f"{name}: selected {selected} has one distinct interior fill",
           contrast >= max(6, noise * 4)
           and all(spread <= 2 for color, spread in interiors[selected])
           and distance(interiors[selected][0][0], interiors[selected][1][0]) <= 2)
    for label in labels:
        if label != selected:
            demand(f"{name}: unselected {label} interiors match the common container",
                   all(distance(color, background) <= tolerance and spread <= 2
                       for color, spread in interiors[label]))


nodes = launch("full-dark")
demand("Native sample and six-track queue render", node(nodes, "6 tracks") is not None)
side_panel_segments("full-dark", nodes, "Queue")
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
demand("Selected queue track retains its artist separately from the paused status",
       sum(n.get("text") == "North Atlantic" for n in nodes) >= 2
       and node(nodes, "Paused") is not None)
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
side_panel_segments("speakers", nodes, "Players")
demand("Selected speaker is explicit and redundant selection is disabled",
       node(nodes, "Selected player") is not None and button_node(nodes, "Selected").get("enabled") == "false")
demand("Unavailable group actions are explained instead of displaying a disabled Join button",
       not any(n.get("text") == "Join group" for n in nodes)
       and node(nodes, "Group controls apply to other players.") is not None)
tap(nodes, "Queue")
nodes = capture("queue-return")
side_panel_segments("queue-return", nodes, "Queue")
demand("Returning from Players retains the selected queue track and paused transport",
       node(nodes, "6 tracks") is not None
       and sum(n.get("text") == "Blue distance" for n in nodes) >= 2
       and node(nodes, "Play").get("enabled") == "true")
nodes = launch("speaker-groups", "--es", "scenario", "speaker-groups")
tap(nodes, "Players")
nodes = capture("speaker-groups")
demand("Compatible speaker offers an enabled group action",
       node(nodes, "Kitchen") is not None and button_node(nodes, "Join group").get("enabled") == "true")
tap(nodes, "Use player")
nodes = capture("speaker-selected")
demand("Speaker selection updates the same native chooser without opening another page",
       node(nodes, "Your speakers") is not None and node(nodes, "Selected player") is not None
       and sum(n.get("text") == "Selected" for n in nodes) == 1
       and bounds(node(nodes, "Selected"))[1] >= bounds(node(nodes, "Kitchen"))[3]
       and bounds(node(nodes, "Family Area"))[1] < bounds(node(nodes, "Kitchen"))[1])
nodes = launch("speakers-large-font", "--es", "scenario", "speaker-groups", "--ef", "font_scale", "1.8")
tap(nodes, "Players")
nodes = capture("speakers-large-font")
pane = next(n for n in nodes if n.get("scrollable") == "true"
            and any(child.get("text") in ("Family Area", "Kitchen") for child in n.iter("node")))
left, top, right, bottom = bounds(pane)
adb("shell", "input", "swipe", str((left + right) // 2), str(bottom - 24),
    str((left + right) // 2), str(max(top + 24, bottom - 324)), "350")
nodes = capture("speakers-large-font")
use = bounds(button_node(nodes, "Use player"))
join = bounds(button_node(nodes, "Join group"))
demand("Larger-text speaker actions stack with full-width 48dp touch targets",
       use[0] == join[0] and use[2] == join[2] and join[1] >= use[3]
       and use[3] - use[1] >= 72 and join[3] - join[1] >= 72)
nodes = launch("offline", "--es", "scenario", "offline")
demand("Offline transport is disabled", all(node(nodes, label).get("enabled") == "false"
       for label in ("Play", "Previous track", "Next track")))
side_panel_segments("offline", nodes, "Queue", offline=True, pixels=False)
tap(nodes, "Lyrics")
nodes = capture("offline")
side_panel_segments("offline after disabled Lyrics", nodes, "Queue", offline=True, pixels=False)
demand("Disabled Lyrics cannot replace the unavailable current Queue tab",
       node(nodes, "Queue unavailable") is not None)
nodes = launch("full-light", "--es", "theme", "light")
demand("Light palette renders the same controls", node(nodes, "Play").get("enabled") == "true")
side_panel_segments("full-light", nodes, "Queue")
tap(nodes, "Players")
nodes = capture("speakers-light")
side_panel_segments("speakers-light", nodes, "Players")
demand("Light Players tab opens the native speakers pane", node(nodes, "Your speakers") is not None)
tap(nodes, "Queue")
nodes = capture("queue-return-light")
side_panel_segments("queue-return-light", nodes, "Queue")
demand("Light Queue return restores the native six-track queue", node(nodes, "6 tracks") is not None)
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
nodes = launch("library-search", "--es", "screen", "library")
tap(nodes, "Search library")
nodes = capture("library-keyboard")
demand("Library search remains visible with keyboard focus",
       any(n.get("class") == "android.widget.EditText" and n.get("focused") == "true"
           and bounds(n) == bounds(node(nodes, "Search library")) for n in nodes)
       and bounds(node(nodes, "Search library"))[3] <= 720)
adb("shell", "input", "text", "north")
adb("shell", "input", "keyevent", "66")
nodes = capture("library-search")
demand("Native search filters supplied collection and artist metadata",
       node(nodes, "1 of 6 collections") is not None and node(nodes, "North shore") is not None
       and not any(n.get("text") == "The quiet hours" for n in nodes))
tap(nodes, "North shore")
nodes = capture("search-album-detail")
demand("Search result opens its complete supplied collection",
       node(nodes, "Blue distance") is not None)
tap(nodes, "Back")
nodes = capture("search-return")
demand("Collection back preserves the search query and results",
       node(nodes, "1 of 6 collections") is not None and node(nodes, "north") is not None)
left, top, right, bottom = bounds(node(nodes, "Clear search"))
demand("Clear search has a 48dp touch target", min(right - left, bottom - top) >= 72)
tap(nodes, "Clear search")
nodes = capture("search-cleared")
demand("Clearing search restores all supplied collections", node(nodes, "6 collections") is not None)
tap(nodes, "Search library")
adb("shell", "input", "text", "long")
adb("shell", "input", "keyevent", "66")
nodes = capture("search-track")
demand("Nested track search explains why its collection matched",
       node(nodes, "Open road") is not None and
       any("Includes The long way home" in n.get("text", "") for n in nodes))
tap(nodes, "Clear search")
tap(nodes, "Search library")
adb("shell", "input", "text", "zzzz")
adb("shell", "input", "keyevent", "66")
nodes = capture("search-empty")
demand("Unmatched search shows zero results without fabricated cards",
       node(nodes, "0 of 6 collections") is not None and node(nodes, "No matching collections") is not None)
tap(nodes, "Playlists")
nodes = capture("playlists-query-independent")
demand("Library and playlist search drafts remain separate", node(nodes, "3 playlists") is not None
       and not any(n.get("text") == "zzzz" for n in nodes))
nodes = launch("offline-search", "--es", "screen", "playlists", "--es", "scenario", "offline")
tap(nodes, "Search playlists")
adb("shell", "input", "text", "zzzz")
adb("shell", "input", "keyevent", "66")
nodes = capture("offline-search")
demand("Unmatched query cannot mask provider unavailability",
       node(nodes, "Playlists unavailable") is not None
       and not any(n.get("text") == "No matching playlists" for n in nodes))
nodes = launch("gallery-spread", "--es", "screen", "spread")
image = Image.open(out / "gallery-spread.png").convert("RGB")
demand("Native Gallery spread displays two whole-photo panes and a matte gap",
       node(nodes, "Gallery spread") is not None and image.getpixel((1080, 720)) == (28, 27, 25)
       and image.getpixel((540, 720)) != (28, 27, 25)
       and image.getpixel((1620, 720)) != (28, 27, 25))
nodes = launch("gallery-spread-landscape", "--es", "screen", "spread", "--es", "scenario", "landscape")
image = Image.open(out / "gallery-spread-landscape.png").convert("RGB")
demand("Gallery spread single-photo fallback preserves a whole landscape",
       node(nodes, "Gallery spread") is not None and image.getpixel((1080, 720)) != (28, 27, 25)
       and image.getpixel((32, 32)) == (28, 27, 25))
for scenario in ("cancel", "save"):
    launch(f"gallery-spread-{scenario}", "--es", "screen", "spread", "--es", "scenario", scenario)
    image = Image.open(out / f"gallery-spread-{scenario}.png").convert("RGB")
    demand(f"Gallery spread restores its whole pair after edit {scenario}",
           image.getpixel((1080, 720)) == (28, 27, 25)
           and image.getpixel((540, 720)) != (28, 27, 25)
           and image.getpixel((1620, 720)) != (28, 27, 25))
launch("gallery-spread-overlays", "--es", "screen", "spread", "--es", "scenario", "overlays")
image = Image.open(out / "gallery-spread-overlays.png").convert("RGB")
demand("Overlay change during a dissolve reserves space while retaining both photos",
       image.getpixel((540, 250)) == (28, 27, 25) and image.getpixel((1620, 250)) == (28, 27, 25)
       and image.getpixel((540, 1000)) != (28, 27, 25)
       and image.getpixel((1620, 1000)) != (28, 27, 25))
nodes = launch("gallery-spread-controls", "--es", "screen", "spread", "--ez", "controls", "true")
demand("Gallery spread omits crop editing that cannot affect its whole-photo layout",
       not any(n.get("text") == "Adjust" for n in nodes))
demand("Gallery spread retains navigation, pause and exit controls",
       all(node(nodes, label).get("enabled") == "true" for label in ("Previous", "Pause", "Next", "Done")))
tap(nodes, "Pause")
nodes = capture("gallery-spread-paused")
demand("Gallery spread pause still reaches its viewer controls",
       node(nodes, "Resume") is not None and node(nodes, "Paused") is not None)
nodes = launch("gallery-print-controls", "--es", "screen", "gallery", "--ez", "controls", "true")
demand("Gallery print retains its functioning crop editor",
       node(nodes, "Adjust").get("enabled") == "true")
tap(nodes, "Adjust")
nodes = capture("gallery-print-editor")
demand("Gallery print Adjust opens the native position editor",
       node(nodes, "Photo zoom") is not None and node(nodes, "Cancel") is not None
       and node(nodes, "Save position") is not None)
tap(nodes, "Cancel")
nodes = capture("gallery-print-controls-return")
demand("Gallery print Cancel restores viewer controls",
       node(nodes, "Adjust").get("enabled") == "true")
adb("shell", "am", "force-stop", "com.immortal.launcher.debug")
(out / "checks.json").write_text(json.dumps({
    "passed": len(checks), "checks": checks, "complete": True, "serial": args.serial,
    "avd": "PortalMusic20261030", "api": 34, "viewport": "2160x1440", "densityDpi": 240,
    "durationSeconds": round(time.monotonic() - started, 2),
    "data": "debug-only silent fixtures and original local artwork",
    "physicalPortalPerformanceMeasured": False, "audioMeasured": False,
    "physicalLightSensorTransitionsMeasured": False,
}, indent=2) + "\n")

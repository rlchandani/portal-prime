"""Device safety rules, enforced in code.

- No force-stop, reboot, pm clear/uninstall, kill, `svc power` or `logcat -c` - ever.
- No screencap/screenrecord through the generic shell. The only capture path is
  `screenshot_allowed()` + Device.capture_png(), and it refuses unless the scenario allows captures
  AND the resumed activity is on the allow-list (PhotoFramePreviewActivity only while the
  generated test source is active, so household photos can never be captured).
- BACK only on a resumed, non-Home Hearthpane activity (never on Jarvis or another app).
"""

import re

from . import PACKAGE

FORBIDDEN = re.compile(
    r"screencap|screenrecord|force-stop|\breboot\b|\bpm\s+(clear|uninstall)|\bkill(all)?\b|svc\s+power|"
    r"\blogcat\b[^;|&]*\s(-c|--clear)\b|\brm\s+-rf\s+/(?!data/local/tmp/perf-bench)")

SCREENSHOT_ACTIVITIES = frozenset({"ImmortalSettingsActivity", "CallsActivity", "StoreActivity"})
TEST_SOURCE_ACTIVITY = "PhotoFramePreviewActivity"
HOME_ACTIVITY = "HomeActivity"


class SafetyRefusal(Exception):
    """A device action the harness refuses by rule."""


def check_command(command):
    if FORBIDDEN.search(command):
        raise SafetyRefusal(f"adb command refused by the harness safety rules: {command[:80]}")


def split_component(component):
    """('com.pkg', 'ShortActivity') from 'com.pkg/com.pkg.ShortActivity' or 'com.pkg/.ShortActivity'."""
    if "/" not in (component or ""):
        return "", ""
    pkg, act = component.split("/", 1)
    return pkg, act.rsplit(".", 1)[-1]


def screenshot_allowed(scenario_allows, resumed_component, test_source_active):
    """(allowed, reason). Pure so the rule is unit-tested without a device."""
    if not scenario_allows:
        return False, "this scenario never takes screenshots"
    pkg, act = split_component(resumed_component)
    if pkg != PACKAGE:
        return False, f"resumed activity is not Hearthpane ({resumed_component or 'none'})"
    if act in SCREENSHOT_ACTIVITIES:
        return True, act
    if act == TEST_SOURCE_ACTIVITY:
        return (True, act) if test_source_active else (False, "photo frame without the generated test source")
    return False, f"{act} is not on the screenshot allow-list"


def back_allowed(resumed_component):
    pkg, act = split_component(resumed_component)
    if pkg != PACKAGE:
        return False, f"BACK refused: {resumed_component or 'nothing'} is not a Hearthpane screen"
    if act == HOME_ACTIVITY:
        return False, "BACK refused on Home"
    return True, act

"""adb helpers with the device safety rules built in.

- Never screenshots, never `am force-stop`, never reboots: those commands are refused here.
- uiautomator only on a resumed, non-Home Hearthpane screen and only with no alert overlay up.
- No Back key at all (a Back from a Jarvis card is never allowed; HOME is how we leave a screen).
"""

import re
import subprocess

from . import JARVIS_PACKAGE, PACKAGE
from .lock import guard
from .model import SafetyRefusal
from . import uiparse

FORBIDDEN = re.compile(r"screencap|screenrecord|force-stop|\breboot\b|keyevent\s+(4|KEYCODE_BACK)\b|"
                       r"\bpm\s+(clear|uninstall)|\bkill\b|svc\s+power")
HOME_ACTIVITY = f"{PACKAGE}/com.immortal.launcher.HomeActivity"
UI_DUMP_PATH = "/sdcard/smoke-ui.xml"
KEY_HOME = 3


def connected_serials():
    try:
        p = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=15)
    except (OSError, subprocess.TimeoutExpired):
        return frozenset()
    rows = (line.split() for line in p.stdout.splitlines()[1:])
    return frozenset(r[0] for r in rows if len(r) >= 2 and r[1] == "device")


class Adb:
    def __init__(self, locks, redactor):
        self.locks = locks
        self.redactor = redactor

    def shell(self, device, command, timeout=30):
        """(rc, stdout). [command] is one remote shell string."""
        guard(self.locks, device.serial)
        if FORBIDDEN.search(command):
            raise SafetyRefusal("adb command refused by the suite's safety rules")
        try:
            p = subprocess.run(["adb", "-s", device.serial, "shell", command], capture_output=True,
                               text=True, timeout=timeout, errors="replace")
        except subprocess.TimeoutExpired:
            return 124, ""
        return p.returncode, p.stdout

    def resumed_activity(self, device):
        _, out = self.shell(device, "dumpsys activity activities | grep mResumedActivity")
        m = re.search(r"ActivityRecord\{\S+ \S+ (\S+)", out)
        return m.group(1) if m else ""

    def wakefulness(self, device):
        _, out = self.shell(device, "dumpsys power | grep mWakefulness=")
        m = re.search(r"mWakefulness=(\w+)", out)
        return m.group(1) if m else ""

    def windows(self, device):
        _, out = self.shell(device, "dumpsys window windows", timeout=40)
        return out

    def visible_overlays(self, device):
        """Hearthpane overlay/panel windows that are drawn now (banners, takeovers, cards)."""
        _, out = self.shell(device, "dumpsys window windows", timeout=40)
        return uiparse.visible_overlays(out, (PACKAGE, JARVIS_PACKAGE))

    def pid(self, device, package=PACKAGE):
        _, out = self.shell(device, f"pidof {package}")
        return out.strip()

    def logcat(self, device, lines):
        _, out = self.shell(device, f"logcat -d -t {int(lines)}", timeout=60)
        return out.splitlines()

    def home(self, device):
        self.shell(device, f"input keyevent {KEY_HOME}")

    def tap(self, device, x, y):
        self.shell(device, f"input tap {int(x)} {int(y)}")

    def ui_nodes(self, device, expected_activity):
        """uiautomator text of [expected_activity], refused unless it is resumed and no overlay shows."""
        resumed = self.resumed_activity(device)
        if not resumed.endswith(expected_activity) or resumed == HOME_ACTIVITY:
            raise SafetyRefusal(f"uiautomator refused: {expected_activity} is not resumed")
        if self.visible_overlays(device):
            raise SafetyRefusal("uiautomator refused: an alert overlay is showing")
        rc, out = self.shell(device, f"uiautomator dump {UI_DUMP_PATH} >/dev/null && cat {UI_DUMP_PATH}; "
                                     f"rm -f {UI_DUMP_PATH}", timeout=45)
        return uiparse.nodes(out) if rc == 0 else []

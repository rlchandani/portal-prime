"""One Portal: adb + fleetctl, each command behind the lock guard and the safety filter."""

import json
import os
import re

from . import HOME_COMPONENT, PACKAGE
from .proc import Runner
from .safety import SafetyRefusal, back_allowed, check_command, screenshot_allowed

TMP_PREFIX = "/data/local/tmp/perf-bench"
KEYS = {"BACK": "KEYCODE_BACK", "HOME": "KEYCODE_HOME", "WAKEUP": "KEYCODE_WAKEUP"}
HOME_INTENT = "am start -a android.intent.action.MAIN -c android.intent.category.HOME"


class DeviceError(Exception):
    pass


class Device:
    def __init__(self, serial, lock, fleetctl, runner=None, log=None):
        self.serial = serial
        self.lock = lock
        self.fleetctl = fleetctl
        self.runner = runner or Runner()
        self.log = log or (lambda msg: None)

    # ---- raw channels -------------------------------------------------------------------------
    def shell(self, command, timeout=40):
        self.lock.check()
        check_command(command)
        p = self.runner.run(["adb", "-s", self.serial, "shell", command], timeout=timeout)
        return p.out if p.rc == 0 else p.out or ""

    def shell_rc(self, command, timeout=40):
        self.lock.check()
        check_command(command)
        return self.runner.run(["adb", "-s", self.serial, "shell", command], timeout=timeout)

    def adb(self, args, timeout=60):
        self.lock.check()
        check_command(" ".join(args))
        return self.runner.run(["adb", "-s", self.serial, *args], timeout=timeout)

    def exec_out(self, command, timeout=60):
        self.lock.check()
        check_command(command)
        return self.runner.run_bytes(["adb", "-s", self.serial, "exec-out", command], timeout=timeout)

    def fleet(self, args, timeout=40):
        self.lock.check()
        cwd = os.path.dirname(self.fleetctl) or None
        return self.runner.run([self.fleetctl, *args, "--device", self.serial], timeout=timeout, cwd=cwd)

    def action(self, body, timeout=40):
        """POST /action; the parsed reply dict, or {} (fleet replies here never carry secrets)."""
        p = self.fleet(["raw", "POST", "/action", json.dumps(body)], timeout=timeout)
        try:
            reply = json.loads(p.out.strip() or "{}")
        except ValueError:
            reply = {}
        return reply if isinstance(reply, dict) else {}

    # ---- state ---------------------------------------------------------------------------------
    def connected(self):
        p = self.runner.run(["adb", "devices"], timeout=15)  # host-side listing, touches no device
        return any(line.split()[:2] == [self.serial, "device"] for line in p.out.splitlines()[1:])

    def resumed(self):
        out = self.shell("dumpsys activity activities | grep -m1 mResumedActivity")
        m = re.search(r"ActivityRecord\{\S+ \S+ (\S+)", out)
        if not m:
            return ""
        comp = m.group(1)
        pkg, act = comp.split("/", 1) if "/" in comp else (comp, "")
        return f"{pkg}/{pkg}{act}" if act.startswith(".") else comp

    def resumed_short(self):
        return self.resumed().rsplit(".", 1)[-1].rsplit("/", 1)[-1]

    def wakefulness(self):
        m = re.search(r"mWakefulness=(\w+)", self.shell("dumpsys power | grep mWakefulness="))
        return m.group(1) if m else ""

    def home_resolved(self):
        out = self.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN "
                         "-c android.intent.category.HOME")
        lines = [x.strip() for x in out.splitlines() if x.strip()]
        return lines[-1] if lines else ""

    def pid(self):
        return self.shell(f"pidof {PACKAGE}").strip().split(" ")[0]

    def installed_sha(self):
        out = self.shell(f"pm path {PACKAGE}")
        paths = [x.split(":", 1)[1].strip() for x in out.splitlines() if x.startswith("package:")]
        base = next((p for p in paths if p.endswith("/base.apk")), paths[0] if paths else "")
        if not base:
            return ""
        m = re.match(r"([0-9a-f]{64})", self.shell(f"sha256sum {base}", timeout=60).strip())
        return m.group(1) if m else ""

    def version_name(self):
        m = re.search(r"versionName=(\S+)", self.shell(f"dumpsys package {PACKAGE} | grep -m1 versionName"))
        return m.group(1) if m else ""

    def uptime_s(self):
        try:
            return float(self.shell("cat /proc/uptime").split()[0])
        except (IndexError, ValueError):
            return 0.0

    def music_volume(self):
        m = re.search(r"volume is (\d+)", self.shell("media volume --stream 3 --get"))
        return int(m.group(1)) if m else None

    def set_music_volume(self, value):
        self.shell(f"media volume --stream 3 --set {int(value)}")

    def setting(self, namespace, key):
        v = self.shell(f"settings get {namespace} {key}").strip()
        return None if v in ("", "null") else v

    def put_setting(self, namespace, key, value):
        self.shell(f"settings put {namespace} {key} {value}")

    # ---- input ---------------------------------------------------------------------------------
    def tap(self, x, y):
        self.shell(f"input tap {int(x)} {int(y)}")

    def key(self, name, prechecked=False):
        """[prechecked]: the caller verified BACK is allowed just before (outside a measured window)."""
        if name == "BACK" and not prechecked:
            ok, why = back_allowed(self.resumed())
            if not ok:
                raise SafetyRefusal(why)
        self.shell(f"input keyevent {KEYS[name]}")

    def swipe(self, x1, y1, x2, y2, ms=180):
        self.shell(f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {int(ms)}")

    def go_home(self):
        """Home as the default launcher and on top (wakes a dream first; never taps the screen)."""
        self.shell(f"cmd package set-home-activity {HOME_COMPONENT}")
        if self.wakefulness() == "Dreaming":
            self.shell("input keyevent KEYCODE_WAKEUP")
            self.runner.sleep(1)
        self.shell(HOME_INTENT)

    # ---- measurement ---------------------------------------------------------------------------
    def gfx_reset(self):
        self.shell(f"dumpsys gfxinfo {PACKAGE} reset >/dev/null")

    def framestats(self):
        return self.shell(f"dumpsys gfxinfo {PACKAGE} framestats", timeout=30)

    def meminfo(self):
        pid = self.pid()
        return self.shell(f"dumpsys meminfo {pid}", timeout=60) if pid else ""

    def ui_dump(self):
        path = f"{TMP_PREFIX}-ui.xml"
        return self.shell(f"uiautomator dump {path} >/dev/null 2>&1 && cat {path}; rm -f {path}", timeout=45)

    def capture_png(self, scenario_allows, test_source_active):
        """The only screenshot path. Returns PNG bytes; raises SafetyRefusal unless allowed."""
        ok, why = screenshot_allowed(scenario_allows, self.resumed(), test_source_active)
        if not ok:
            raise SafetyRefusal(f"screenshot refused: {why}")
        self.lock.check()
        rc, data = self.runner.run_bytes(["adb", "-s", self.serial, "exec-out", "screencap", "-p"], timeout=30)
        if rc != 0 or not data.startswith(b"\x89PNG"):
            raise DeviceError("screencap failed")
        return data

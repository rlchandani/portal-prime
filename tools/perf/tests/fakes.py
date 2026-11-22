"""A fake adb/fleetctl world: records every command and answers like a Portal would."""

import json
import re

from perflib import HOME_COMPONENT
from perflib.builds import Build
from perflib.proc import Proc

SERIAL = "TESTSERIAL0001"
OFFICIAL_SHA = "a" * 64
BRANCH_SHA = "b" * 64


class FakePortal:
    TRACKED = frozenset({"screen_off_timeout"})
    relaunches_home = True  # False: a HOME intent never brings the Home window back

    def __init__(self, sha=BRANCH_SHA, music=3, top=HOME_COMPONENT, fail=()):
        self.state = {"sha": sha, "music": music, "top": top, "dev": True, "wm": "2160x1440"}
        self.calls = []        # every argv
        self.shells = []       # adb shell command strings, in order
        self.fleet_calls = []  # fleet argument lists
        self.fail = set(fail)  # substrings of shell commands that should "fail" (raise via rc)
        self.slept = 0.0
        self.clock = 1000.0
        self.pending_install_sha = sha
        # settings the harness may read/write: (namespace, key) -> value; absent = "null"
        self.settings = {("system", "screen_off_timeout"): "60000"}
        # gfxinfo lists a Home window once a HOME intent has (re)created it after an install
        self.home_window = True
        self.home_intents = 0

    # Runner protocol --------------------------------------------------------------------------
    def run(self, argv, timeout=60, cwd=None):
        self.calls.append(list(argv))
        if argv[:2] == ["adb", "devices"]:
            return Proc(0, f"List of devices attached\n{SERIAL}\tdevice\n")
        if argv[0] == "adb" and argv[3] == "shell":
            return self._shell(argv[4])
        if argv[0] == "adb" and argv[3] in ("install", "install-multiple"):
            apk = argv[-1] if argv[3] == "install" else argv[-2]
            self.state["sha"] = OFFICIAL_SHA if "official" in apk else BRANCH_SHA
            self.home_window = False  # the install kills the app; the stale Home record stays "resumed"
            return Proc(0, "Performing Streamed Install\nSuccess\n")
        if argv[0].endswith("fleetctl"):
            return self._fleet(argv[1:-2])
        return Proc(0, "")

    def run_bytes(self, argv, timeout=60):
        self.calls.append(list(argv))
        return 0, b"\x89PNG fake"

    def sleep(self, s):
        self.slept += s
        self.clock += s

    def now(self):
        self.clock += 0.5
        return self.clock

    # Simulation -------------------------------------------------------------------------------
    def _shell(self, cmd):
        self.shells.append(cmd)
        for bad in self.fail:
            if bad in cmd:
                raise RuntimeError(f"simulated failure: {bad}")
        s = self.state
        if "mResumedActivity" in cmd:
            return Proc(0, f"  mResumedActivity: ActivityRecord{{1a2b u0 {s['top']} t42}}\n")
        if cmd.startswith("pm path"):
            return Proc(0, "package:/data/app/com.immortal.launcher.debug-1/base.apk\n")
        if cmd.startswith("sha256sum"):
            return Proc(0, f"{s['sha']}  /data/app/com.immortal.launcher.debug-1/base.apk\n")
        if "media volume --stream 3 --get" in cmd:
            return Proc(0, f"[v] will get volume\n[v] volume is {s['music']} in range [0..15]\n")
        m = re.search(r"media volume --stream 3 --set (\d+)", cmd)
        if m:
            s["music"] = int(m.group(1))
            return Proc(0, "")
        if "resolve-activity" in cmd:
            return Proc(0, "priority=0 preferredOrder=0\n" + HOME_COMPONENT + "\n")
        if "android.intent.category.HOME" in cmd and cmd.startswith("am start"):
            s["top"] = HOME_COMPONENT
            self.home_intents += 1
            self.home_window = self.relaunches_home
            return Proc(0, "Starting: Intent")
        if cmd.endswith("framestats") and "gfxinfo" in cmd:
            window = f"\t{HOME_COMPONENT}/android.view.ViewRootImpl@1 (visibility=0)\n" if self.home_window else ""
            return Proc(0, "** Graphics info for pid 4242 [com.immortal.launcher.debug] **\n" + window)
        if "mWakefulness" in cmd:
            return Proc(0, "  mWakefulness=Awake\n")
        m = re.fullmatch(r"settings (get|put|delete) (\w+) (\w+)(?: (\S+))?", cmd)
        if m and (m.group(2), m.group(3)) in self.settings or m and m.group(3) in self.TRACKED:
            verb, ns, key, value = m.groups()
            if verb == "put":
                self.settings[(ns, key)] = value
            elif verb == "delete":
                self.settings.pop((ns, key), None)
            return Proc(0, (self.settings.get((ns, key), "null") + "\n") if verb == "get" else "")
        if cmd.startswith("settings get"):
            return Proc(0, "1\n" if "accelerometer" in cmd else "0\n")
        if cmd.startswith("wm size reset"):
            s["wm"] = "2160x1440"
            return Proc(0, "")
        if cmd == "wm size; wm density":
            return Proc(0, f"Physical size: {s['wm']}\nPhysical density: 240\n")
        if cmd == "cat /proc/uptime":
            return Proc(0, f"{self.clock:.2f} 100.0\n")
        if "versionName" in cmd:
            return Proc(0, "    versionName=1.326\n")
        if cmd.startswith("pidof"):
            return Proc(0, "4242\n")
        return Proc(0, "")

    def _fleet(self, args):
        self.fleet_calls.append(list(args))
        if args[:1] == ["dev"]:
            self.state["dev"] = args[1] == "on"
            return Proc(0, "ok")
        if args[:1] == ["info"]:
            return Proc(0, json.dumps({"devMode": self.state["dev"], "versionName": "1.326"}))
        if args[:3] == ["raw", "POST", "/action"]:
            body = json.loads(args[3])
            if body["action"] == "open_control_center":
                self.state["top"] = "com.immortal.launcher.debug/com.immortal.launcher.ControlCenterActivity"
            reply = {"ok": True, "action": body["action"]}
            if body["action"] == "screensaver_test_source":
                reply["active"] = bool(body.get("enabled"))
            return Proc(0, json.dumps(reply))
        return Proc(0, "{}")

    def press_back(self):
        """Hook: BACK from CC returns Home in this simulation."""
        self.state["top"] = HOME_COMPONENT


class BackAwarePortal(FakePortal):
    def _shell(self, cmd):
        if "KEYCODE_BACK" in cmd:
            self.shells.append(cmd)
            self.press_back()
            return Proc(0, "")
        return super()._shell(cmd)


class FakeOfficial:
    def __init__(self, path="/cache/official/hearthpane.apk"):
        self.build = Build("O", "official", path, None, OFFICIAL_SHA, "1.326", "hearthpane-v1.326")
        self.calls = 0

    def latest(self, label="O"):
        self.calls += 1
        return self.build


class OpenLock:
    """A lock that is always ours (tests that are not about locking)."""
    tag = "perf-bench test"
    acquired_at = None

    def check(self):
        return None

    def owned(self):
        return True

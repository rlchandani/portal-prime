"""Runs one scenario on one Portal for every build, A/B interleaved, and records every sample."""

import os
import re
from dataclasses import dataclass
from typing import Optional

from . import PACKAGE, framestats, meminfo, uidump
from .builds import install
from .device import TMP_PREFIX
from .safety import SafetyRefusal, back_allowed

SETTLE_AFTER_INSTALL_S = 25
MEMINFO_AFTER_S = 5
POLL_GAP_S = 0.2
TAB_LABELS = frozenset({"Home", "Apps", "Tools"})
SIMPLEPERF_HZ = 4000
ATRACE_BUFFER_KB = 16384


class ScenarioError(Exception):
    pass


@dataclass(frozen=True)
class Options:
    reps: int = 3
    interleave: bool = True
    meminfo: bool = False
    atrace: bool = False
    simpleperf: bool = False
    screenshots: bool = False
    poll_s: Optional[float] = None  # overrides a poll step's window (e.g. a shorter household run)
    settle_s: float = SETTLE_AFTER_INSTALL_S


def build_order(labels, reps, interleave):
    """A,B,B,A,A,B... when interleaved (each build gets [reps] reps); otherwise all A then all B."""
    if not interleave:
        return [label for label in labels for _ in range(reps)]
    order = []
    for i in range(reps):
        order.extend(labels if i % 2 == 0 else list(reversed(labels)))
    return order


class Session:
    def __init__(self, device, name, scenario, builds, options, out_dir, log=print):
        self.d = device
        self.name = name
        self.sc = scenario
        self.builds = {b.label: b for b in builds}
        self.labels = [b.label for b in builds]
        self.opts = options
        self.out = out_dir
        self.log = log
        self.samples = []
        self.memory = []
        self.audits = []
        self.screenshots = []
        self.installs = []
        self.targets = {}
        self.test_source_active = False
        self._screen = None
        self._back_checked = False
        os.makedirs(os.path.join(out_dir, "raw"), exist_ok=True)

    # ---- top level -----------------------------------------------------------------------------
    def run(self):
        reps_done = {label: 0 for label in self.labels}
        current = None
        for label in build_order(self.labels, self.opts.reps, self.opts.interleave):
            if label != current:
                self._install(label)
                current = label
                if not self.sc.get("audit"):
                    self.log(f"[{label}] warm-up rep (unmeasured)")
                    self._rep(label, 0, measured=False)
            reps_done[label] += 1
            self.log(f"[{label}] rep {reps_done[label]}/{self.opts.reps}")
            self._rep(label, reps_done[label], measured=True)

    def _install(self, label):
        build = self.builds[label]
        self.log(f"[{label}] install {os.path.basename(build.apk)} (sha {build.sha256[:8]}...)")
        sha = install(self.d, build, self.log)
        self.installs.append({"build": label, "sha256": sha, "verified": sha == build.sha256,
                              "versionName": self.d.version_name()})
        self.d.runner.sleep(self.opts.settle_s)
        self._ensure_start()
        self.targets = {}
        self._screen = None
        self._actions(self.sc.get("session", []))

    # ---- reps and steps ------------------------------------------------------------------------
    def _rep(self, label, rep, measured):
        self._ensure_start()
        if measured and self.opts.meminfo:
            self._mem(label, rep, "before")
        self._actions(self.sc.get("setup", []))
        if self.sc.get("audit"):
            self._audit(label, rep)
        else:
            for _ in range(self.sc.get("warmup", 0)):
                for step in self.sc["steps"]:
                    self._step(step, label, rep, None)
            for i in range(1, (self.sc.get("inner", 1) if measured else 1) + 1):
                for step in self.sc["steps"]:
                    self._step(step, label, rep, i if measured else None)
        self._actions(self.sc.get("teardown", []))
        if measured and self.opts.meminfo:
            self.d.runner.sleep(MEMINFO_AFTER_S)
            self._mem(label, rep, "after")

    def _step(self, step, label, rep, n):
        measured = n is not None
        self._prepare(step["do"])
        tag = f"{label}-r{rep}-{step['name']}-{n}" if measured else None
        if "poll" in step:
            self._poll_step(step, label, rep, n, tag)
        else:
            self.d.gfx_reset()
            self._trace_start(tag)
            self._actions(step["do"])
            self.d.runner.sleep(step["wait"])
            text = self.d.framestats()
            self._trace_stop(tag)
            if measured:
                flags = tuple(step.get("flags", (0,)))
                frames = framestats.parse_frames(text, flags=flags)
                self._sample(label, rep, step["name"], n, framestats.summarize(frames),
                             framestats.parse_hwui(text), self._save(tag + ".framestats.txt", text),
                             extra={"flags": list(flags)})
        self._expect(step.get("expect"), step["name"])
        if measured:
            self._actions(step.get("after", []), once_key=f"{label}-{step['name']}")

    def _poll_step(self, step, label, rep, n, tag):
        measured = n is not None
        window = (self.opts.poll_s or step["poll"]) if measured else step.get("warmup_poll", step["poll"])
        self.d.gfx_reset()
        t0 = self.d.uptime_s()
        self._trace_start(tag)
        self._actions(step["do"])
        end = self.d.runner.now() + window
        chunks = []
        while self.d.runner.now() < end:
            chunks.append(self.d.framestats())
            self.d.runner.sleep(POLL_GAP_S)
        self._trace_stop(tag)
        t1 = self.d.uptime_s()
        if not measured:
            return
        text = "\n".join(chunks)
        raw = self._save(tag + ".framestats.txt", text)
        frames = framestats.parse_frames(text)
        beats = []
        if step.get("beats"):
            log = self.d.shell("logcat -d -v monotonic -s ImmortalEngine:I", timeout=60)
            self._save(tag + ".logcat.txt", "\n".join(x for x in log.splitlines() if "photo_" in x))
            beats = framestats.parse_beats(log, since_s=t0 - 1.0, until_s=t1 + 1.0)
        if beats:
            for t, mode, fr in framestats.beat_windows(frames, beats, skip_first=step.get("skip_first", True)):
                self._sample(label, rep, step["name"], n, framestats.summarize(fr), None, raw,
                             extra={"mode": mode, "beat_s": round(t - t0, 2)})
        else:
            self._sample(label, rep, step["name"], n, framestats.summarize(frames), None, raw,
                         extra={"mode": None, "note": "no photo_beat lines; whole window" if step.get("beats") else ""})

    def _sample(self, label, rep, step, n, summary, hwui, raw, extra=None):
        rec = {"build": label, "rep": rep, "step": step, "pass": n, "summary": summary, "raw": raw}
        if hwui is not None:
            rec["hwui"] = {"janky_pct": hwui.janky_pct, "p90": hwui.p90, "p99": hwui.p99, "frames": hwui.frames}
        if extra:
            rec.update(extra)
        self.samples.append(rec)
        s = summary
        self.log(f"   {step}#{n}{' ' + extra['mode'] if extra and extra.get('mode') else ''}: frames={s['frames']} "
                 f">32={s['over32']} >60={s['over60']} worst={s['worst']} p90={s['p90']}")

    # ---- actions -------------------------------------------------------------------------------
    def _actions(self, actions, once_key=None):
        for verb, arg in actions:
            if verb == "screenshot":
                self._screenshot(arg, once_key or arg)
                continue
            handler = getattr(self, f"_a_{verb}", None)
            if handler is None:
                raise ScenarioError(f"unknown action {verb}")
            handler(arg)

    def _prepare(self, actions):
        """Locates tap targets and checks BACK BEFORE the gfxinfo reset, so neither a uiautomator dump
        nor a dumpsys lands inside a measured window."""
        if ("key", "BACK") in actions:
            ok, why = back_allowed(self.d.resumed())
            if not ok:
                raise SafetyRefusal(why)
            self._back_checked = True
        need = [arg for verb, arg in actions if verb in ("tap_label", "tap_desc") and arg not in self.targets]
        if need:
            self._locate_all(need)

    def _locate_all(self, labels):
        nodes = uidump.parse(self.d.ui_dump())
        wanted = set(labels) | (TAB_LABELS if TAB_LABELS & set(labels) else set())
        for label in wanted:
            exact = label in TAB_LABELS or label == "Settings"
            node = uidump.find(nodes, label, exact=exact, prefer="bottom" if label in TAB_LABELS else "top")
            if node is not None:
                self.targets[label] = node.center
        missing = [x for x in labels if x not in self.targets]
        if missing:
            raise ScenarioError(f"could not find {missing} on {self.d.resumed_short()} (uiautomator dump)")

    def _a_locate(self, label):
        if label not in self.targets:
            self._locate_all([label])

    def _a_tap_label(self, label):
        self._a_locate(label)
        self.d.tap(*self.targets[label])

    _a_tap_desc = _a_tap_label

    def _a_fleet(self, body):
        reply = self.d.action(body)
        if reply.get("ok") is not True:
            raise ScenarioError(f"fleet {body.get('action')} failed: {str(reply)[:160]}")

    def _a_key(self, name):
        prechecked, self._back_checked = self._back_checked and name == "BACK", False
        self.d.key(name, prechecked=prechecked)

    def _a_sleep(self, seconds):
        self.d.runner.sleep(seconds)

    def _a_home(self, _):
        self.d.go_home()
        self.d.runner.sleep(3)

    def _a_dream(self, _):
        self.d.shell("am start -n com.android.systemui/.Somnambulator")

    def _a_expect(self, activity):
        self._expect(activity, "setup/teardown")

    def _a_test_source(self, mode):
        if mode is None:
            self.d.action({"action": "screensaver_test_source", "enabled": False})
            self.test_source_active = False
            return
        reply = self.d.action({"action": "screensaver_test_source", "enabled": True, "minutes": 10,
                               "mode": mode, "intervalSec": 15})
        if reply.get("active") is not True:
            raise ScenarioError(f"test source did not start: {str(reply)[:160]}")
        self.test_source_active = True

    def _a_orientation(self, request):
        self._a_fleet({"action": "orientation", "request": request})

    def _a_mini(self, orientation):
        size = {"portrait": "800x1280", "landscape": "1280x800"}[orientation]
        self.d.put_setting("system", "accelerometer_rotation", "0")
        self.d.put_setting("system", "user_rotation", "0")
        self.d.shell(f"wm size {size}; wm density 160")
        self.d.runner.sleep(4)
        self.targets = {}
        self._screen = None
        self._ensure_start()

    def _a_swipe_left(self, _):
        w, h = self._screen_size()
        self.d.swipe(w * 0.79, h / 2, w * 0.23, h / 2, 180)

    def _screen_size(self):
        if self._screen is None:
            out = self.d.shell("wm size")
            m = re.search(r"Override size: (\d+)x(\d+)", out) or re.search(r"Physical size: (\d+)x(\d+)", out)
            self._screen = (int(m.group(1)), int(m.group(2))) if m else (2160, 1440)
        return self._screen

    def _screenshot(self, name, once_key):
        if not self.opts.screenshots or any(s.get("key") == once_key for s in self.screenshots):
            return
        try:
            data = self.d.capture_png(self.sc.get("screenshots", False), self.test_source_active)
        except SafetyRefusal as e:
            self.screenshots.append({"key": once_key, "refused": str(e)})
            self.log(f"   {e}")
            return
        path = os.path.join(self.out, f"{once_key}-{name}.png")
        with open(path, "wb") as f:
            f.write(data)
        self.screenshots.append({"key": once_key, "file": os.path.basename(path)})

    # ---- checks / captures ---------------------------------------------------------------------
    def _ensure_start(self):
        start = self.sc.get("start", "HomeActivity")
        if self.d.resumed_short() != start:
            self.d.go_home()
            self.d.runner.sleep(4)
        self._expect(start, "start")

    def _expect(self, activity, where):
        if activity is None:
            return
        top = self.d.resumed_short()
        if top != activity:
            raise ScenarioError(f"{where}: expected {activity} on top, found {top or 'nothing'}; aborting")

    def _mem(self, label, rep, when):
        text = self.d.meminfo()
        self.memory.append({"build": label, "rep": rep, "when": when, **meminfo.parse(text)})

    def _audit(self, label, rep):
        for screen in self.sc["audit"]:
            self._open_screen(screen)
            self.d.runner.sleep(3)
            top = self.d.resumed_short()
            nodes = uidump.parse(self.d.ui_dump())
            found = uidump.overflow(nodes)
            self.audits.append({"build": label, "rep": rep, "screen": screen, "top": top, "nodes": len(nodes),
                                "screen_size": list(uidump.screen_size(nodes)), "findings": found})
            self.log(f"   {screen} ({top}): {len(nodes)} nodes, {len(found)} flagged")
            self.d.go_home()
            self.d.runner.sleep(3)
            if screen in ("apps", "tools"):
                self._a_tap_label("Home")
                self.d.runner.sleep(2)

    def _open_screen(self, screen):
        if screen in ("home", "apps", "tools"):
            self._ensure_start()
            self._a_tap_label(screen.capitalize())
        elif screen == "control_center":
            self._a_fleet({"action": "open_control_center", "screen": "map"})
        else:
            self._a_fleet({"action": "open_screen", "screen": screen})

    def _trace_start(self, tag):
        if tag is None:
            return
        if self.opts.atrace:
            self.d.shell(f"atrace --async_start -b {ATRACE_BUFFER_KB} -a {PACKAGE} gfx view >/dev/null 2>&1")
        if self.opts.simpleperf:
            self.d.shell(f"simpleperf record --app {PACKAGE} -g -f {SIMPLEPERF_HZ} --duration 2 "
                         f"-o {TMP_PREFIX}.data >/dev/null 2>&1 &")

    def _trace_stop(self, tag):
        if tag is None:
            return
        if self.opts.atrace:
            self.d.shell(f"atrace --async_stop -o {TMP_PREFIX}.trace >/dev/null 2>&1", timeout=90)
            rc, data = self.d.exec_out(f"cat {TMP_PREFIX}.trace", timeout=90)
            if rc == 0:
                self._save_bytes(tag + ".atrace.txt", data)
            self.d.shell(f"rm -f {TMP_PREFIX}.trace")
        if self.opts.simpleperf:
            self.d.runner.sleep(1)
            text = self.d.shell(f"simpleperf report-sample --show-callchain -i {TMP_PREFIX}.data 2>/dev/null",
                                timeout=120)
            self._save(tag + ".simpleperf.txt", text)
            self.d.shell(f"rm -f {TMP_PREFIX}.data")


    def _save(self, name, text):
        rel = os.path.join("raw", name)
        with open(os.path.join(self.out, rel), "w", encoding="utf-8") as f:
            f.write(text)
        return rel

    def _save_bytes(self, name, data):
        with open(os.path.join(self.out, "raw", name), "wb") as f:
            f.write(data)

    def result_parts(self):
        return {"samples": self.samples, "memory": self.memory, "audits": self.audits,
                "screenshots": self.screenshots, "installs": self.installs}

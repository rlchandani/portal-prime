"""Runs one scenario on one Portal for every build, A/B interleaved, and records every sample."""

import os
import re
from dataclasses import dataclass
from typing import Optional, Tuple

from . import PACKAGE, awake, framestats, meminfo, uidump
from .builds import install
from .device import TMP_PREFIX
from .safety import SafetyRefusal, back_allowed

SETTLE_AFTER_INSTALL_S = 25
HOME_RELAUNCH_S = 4
HOME_WINDOW = "/com.immortal.launcher.HomeActivity/android.view.ViewRootImpl"
MEMINFO_AFTER_S = 5
POLL_GAP_S = 0.2
TAB_LABELS = frozenset({"Home", "Apps", "Tools"})
SIMPLEPERF_HZ = 4000
# simpleperf needs a moment to start recording; without it a step's first ~200 ms (a Control
# Center open's first frame) were never sampled.
SIMPLEPERF_START_S = 0.6
ATRACE_BUFFER_KB = 16384
ATRACE_CATEGORIES = ("gfx", "view")
ATRACE_BUFFER_KB_RANGE = (1024, 262144)
ATRACE_MAX_CATEGORIES = 24
_ATRACE_CATEGORY = re.compile(r"^[a-z][a-z0-9_]{0,31}$")


def parse_atrace_categories(text):
    """'gfx,view webview' -> ('gfx', 'view', 'webview'). Each name is a plain atrace category
    (lower-case letters, digits, '_'), because it is put into an adb shell command line."""
    names = tuple(n for n in re.split(r"[,\s]+", (text or "").strip()) if n)
    if not names:
        raise ValueError("--atrace-categories: give at least one category")
    if len(names) > ATRACE_MAX_CATEGORIES:
        raise ValueError(f"--atrace-categories: at most {ATRACE_MAX_CATEGORIES} categories")
    for name in names:
        if not _ATRACE_CATEGORY.match(name):
            raise ValueError(f"--atrace-categories: not an atrace category name: {name!r}")
    return tuple(dict.fromkeys(names))


def check_atrace_buffer_kb(value):
    lo, hi = ATRACE_BUFFER_KB_RANGE
    if isinstance(value, bool) or not isinstance(value, int) or not lo <= value <= hi:
        raise ValueError(f"--atrace-buffer-kb: an integer from {lo} to {hi}")
    return value


def atrace_start_command(categories, buffer_kb):
    """The atrace start line for a step; inputs are re-validated so a hand-written job cannot inject."""
    names = parse_atrace_categories(" ".join(categories))
    kb = check_atrace_buffer_kb(buffer_kb)
    return f"atrace --async_start -b {kb} -a {PACKAGE} {' '.join(names)} >/dev/null 2>&1"


class ScenarioError(Exception):
    pass


@dataclass(frozen=True)
class Options:
    reps: int = 3
    interleave: bool = True
    meminfo: bool = False
    atrace: bool = False
    atrace_categories: Tuple[str, ...] = ATRACE_CATEGORIES
    atrace_buffer_kb: int = ATRACE_BUFFER_KB
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
        # Tap targets per fleet orientation (the tab bar moves when the screen turns); [targets] is the
        # current orientation's dict. None = whatever the screen was before any orientation request.
        self._target_sets = {None: self.targets}
        self._orientation = None
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
        self._relaunch_home()
        self._ensure_start()
        self._forget_targets()
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
        self._prepare(step["do"], after=step.get("pre", ()))
        awake.nudge(self.d)  # outside the window: keeps the idle frame / dream off Home
        tag = f"{label}-r{rep}-{step['name']}-{n}" if measured else None
        if "poll" in step:
            self._poll_step(step, label, rep, n, tag)
        else:
            extra = {}
            if step.get("pre"):
                # Unmeasured lead-in (e.g. a turn); the window opens [pre_wait] s after it returned.
                self._actions(step["pre"])
                turned = self.d.runner.now()
                self.d.runner.sleep(step.get("pre_wait", 0))
            self.d.gfx_reset()
            self._trace_start(tag)
            if step.get("pre"):
                extra["do_after_s"] = round(self.d.runner.now() - turned, 2)
            self._actions(step["do"])
            self.d.runner.sleep(step["wait"])
            text = self.d.framestats()
            self._trace_stop(tag)
            if measured:
                flags = tuple(step.get("flags", (0,)))
                frames = framestats.parse_frames(text, flags=flags)
                extra["flags"] = list(flags)
                if step.get("frame_ms"):
                    extra["frame_ms"] = [round(f.total_ms, 1) for f in frames]
                self._sample(label, rep, step["name"], n, framestats.summarize(frames),
                             framestats.parse_hwui(text), self._save(tag + ".framestats.txt", text),
                             extra=extra)
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

    def _prepare(self, actions, after=()):
        """Locates tap targets and checks BACK BEFORE the gfxinfo reset, so neither a uiautomator dump
        nor a dumpsys lands inside a measured window. With [after] (a step's lead-in that turns the
        screen) the taps happen in the orientation it turns to: their targets must already be known
        (located in setup), because there is no time for a dump between the turn and the tap."""
        turn = next((arg for verb, arg in after if verb == "orientation"), None)
        if turn is not None:
            known = self._target_sets.get(turn, {})
            missing = [arg for verb, arg in actions if verb in ("tap_label", "tap_desc") and arg not in known]
            if missing:
                raise ScenarioError(f"{missing} not located for {turn} (locate them in setup after turning)")
            return
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
        self._orientation = request if request in ("portrait", "landscape") else None
        self.targets = self._target_sets.setdefault(self._orientation, {})

    def _forget_targets(self):
        self.targets = {}
        self._target_sets = {None: self.targets}
        self._orientation = None

    def _a_mini(self, orientation):
        size = {"portrait": "800x1280", "landscape": "1280x800"}[orientation]
        self.d.put_setting("system", "accelerometer_rotation", "0")
        self.d.put_setting("system", "user_rotation", "0")
        self.d.shell(f"wm size {size}; wm density 160")
        self.d.runner.sleep(4)
        self._forget_targets()
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

    def _relaunch_home(self):
        """The install kills the app, but the activity stack keeps a "resumed" HomeActivity record. The
        process comes back as a service only, so that record looks fine to `mResumedActivity` while no
        Home window exists: 0 frames, empty uiautomator dumps. A HOME intent recreates it; the check is
        that this process really has a Home window (gfxinfo lists it)."""
        if self.sc.get("start", "HomeActivity") != "HomeActivity":
            return
        for _ in range(2):
            self.d.go_home()
            self.d.runner.sleep(HOME_RELAUNCH_S)
            if HOME_WINDOW in self.d.framestats():
                return
        raise ScenarioError("after the install no HomeActivity window is drawn by the app process; aborting")

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
            text = self.d.ui_dump()
            # Kept for accessibility diffs between builds (node text, order, flags, bounds).
            self._save(f"{label}-r{rep}-{screen}.uidump.txt", text)
            nodes = uidump.parse(text)
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
            self.d.shell(atrace_start_command(self.opts.atrace_categories, self.opts.atrace_buffer_kb))
        if self.opts.simpleperf:
            self.d.shell(f"simpleperf record --app {PACKAGE} -g -f {SIMPLEPERF_HZ} --duration 2 "
                         f"-o {TMP_PREFIX}.data >/dev/null 2>&1 &")
            self.d.runner.sleep(SIMPLEPERF_START_S)

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

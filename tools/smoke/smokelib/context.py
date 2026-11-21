"""Shared run state: tools, the Portals this run holds, cached facts and the cleanup stack."""

import sys
import threading
import time
import traceback
from dataclasses import dataclass, field
from typing import Any, Mapping

from .lock import LockGuardError
from .model import FAIL, SKIP, Result, SafetyRefusal


@dataclass(frozen=True)
class Options:
    mode: str  # "quick" | "full"
    screensaver_device: str
    screensaver_mode: str
    screensaver_seconds: int
    sync_wait_s: int
    verbose: bool


@dataclass(frozen=True)
class Facts:
    """What the read-only first pass learned about one Portal."""
    info: Mapping[str, Any] = field(default_factory=dict)
    apk_path: str = ""
    install_ms: int = 0
    install_local: str = ""
    adb: bool = False
    pid: str = ""
    utc_offset_s: int = 0
    ui_initial: str = ""  # "home" | "frame" | "Dreaming" once a UI check prepared the Portal


class Context:
    def __init__(self, options, fleet, adb, redactor, release):
        self.options = options
        self.fleet = fleet
        self.adb = adb
        self.redactor = redactor
        self.release = release
        self.devices = ()  # the Portals whose lock this run holds
        self._facts = {}
        self._cleanups = ()
        self._mutex = threading.Lock()
        self.started = time.monotonic()

    # --- facts --------------------------------------------------------------------------
    def facts(self, device):
        with self._mutex:
            return self._facts.get(device.serial, Facts())

    def set_facts(self, device, facts):
        with self._mutex:
            self._facts = {**self._facts, device.serial: facts}

    def adb_devices(self):
        return tuple(d for d in self.devices if self.facts(d).adb)

    # --- cleanup ------------------------------------------------------------------------
    def on_cleanup(self, label, fn):
        """Registers an undo step; returns a token to drop it once undone normally."""
        entry = (label, fn, object())
        with self._mutex:
            self._cleanups = self._cleanups + (entry,)
        return entry[2]

    def done(self, token):
        with self._mutex:
            self._cleanups = tuple(c for c in self._cleanups if c[2] is not token)

    def run_cleanups(self):
        """Runs every pending undo, newest first. Returns [(label, ok, error)]."""
        with self._mutex:
            pending, self._cleanups = self._cleanups, ()
        report = []
        for label, fn, _ in reversed(pending):
            try:
                fn()
                report.append((label, True, ""))
            except Exception as e:  # keep undoing the rest whatever one step does
                report.append((label, False, self.redactor.text(f"{type(e).__name__}: {e}")))
        return report

    # --- output -------------------------------------------------------------------------
    def log(self, msg):
        if self.options.verbose:
            elapsed = time.monotonic() - self.started
            print(f"  [{elapsed:6.1f}s] {self.redactor.text(msg)}", file=sys.stderr, flush=True)


def run_check(ctx, device_name, check, fn):
    """Runs [fn] -> [Result]; turns exceptions into a FAIL (or SKIP for a safety refusal)."""
    t0 = time.monotonic()
    ctx.log(f"{device_name}: {check}")
    try:
        out = list(fn())
    except SafetyRefusal as e:
        out = [Result(device_name, check, SKIP, str(e))]
    except LockGuardError as e:
        out = [Result(device_name, check, FAIL, str(e))]
    except Exception as e:
        ctx.log(traceback.format_exc(limit=3))
        out = [Result(device_name, check, FAIL, ctx.redactor.text(f"error {type(e).__name__}: {e}"))]
    dt = round(time.monotonic() - t0, 2)
    for r in out:
        ctx.log(f"{r.device}: {r.check} {r.status} {r.detail[:90]}")
    return [Result(r.device, r.check, r.status, ctx.redactor.text(r.detail), ctx.redactor.data(dict(r.data)), dt)
            for r in out]

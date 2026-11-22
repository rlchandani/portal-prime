"""Parsing `dumpsys gfxinfo <pkg> framestats` and summarising frames.

A frame's time is FrameCompleted - IntendedVsync (ms); only Flags == 0 rows count (the survey's
rule; flagged rows are first/layout-changed or skipped frames). Rows from repeated polls are
de-duplicated by (window, IntendedVsync). UI = SyncQueued - IntendedVsync, RT = SwapBuffers - SyncStart.
"""

import re
from dataclasses import dataclass

JANK_MS = 16.7
SLOW_MS = 32.0
FROZEN_MS = 60.0

_WINDOW = re.compile(r"^Window: ?(.*)$")
_VIEWROOT = re.compile(r"^(\S*)/android\.view\.ViewRootImpl@\w+")
_JANKY = re.compile(r"^Janky frames: (\d+) \(([\d.]+)%\)")
_PCT = re.compile(r"^(\d+)th percentile: (\d+)ms")
_TOTAL = re.compile(r"^Total frames rendered: (\d+)")


@dataclass(frozen=True)
class Frame:
    window: str
    vsync_ns: int
    total_ms: float
    ui_ms: float
    rt_ms: float


@dataclass(frozen=True)
class Hwui:
    """The process-level HWUI summary at the top of the dump (all launcher windows)."""
    frames: int = 0
    janky: int = 0
    janky_pct: float = 0.0
    p50: int = 0
    p90: int = 0
    p95: int = 0
    p99: int = 0


def short_window(name):
    name = (name or "").strip()
    return name.rsplit("/", 1)[-1].rsplit(".", 1)[-1] if name else "?"


def parse_hwui(text):
    vals = {}
    for line in text.splitlines():
        t = line.strip()
        if t.startswith("Window:") or t.startswith("Profile data in ms"):
            break
        m = _TOTAL.match(t)
        if m:
            vals["frames"] = int(m.group(1))
        m = _JANKY.match(t)
        if m:
            vals["janky"], vals["janky_pct"] = int(m.group(1)), float(m.group(2))
        m = _PCT.match(t)
        if m and m.group(1) in ("50", "90", "95", "99"):
            vals["p" + m.group(1)] = int(m.group(2))
    return Hwui(**vals)


def parse_frames(text, all_flags=False):
    """Frames (sorted by vsync, de-duplicated) from one or many concatenated framestats dumps."""
    seen = {}
    window, header, inside = None, None, False
    for line in text.splitlines():
        t = line.strip()
        m = _WINDOW.match(t)
        if m:
            window, header, inside = m.group(1).strip() or "?", None, False
            continue
        m = _VIEWROOT.match(t)
        if m and not inside:
            window = m.group(1) or "?"
            continue
        if t.startswith("---PROFILEDATA---"):
            inside, header = not inside, None
            continue
        if not inside:
            continue
        parts = t.rstrip(",").split(",")
        if parts[0] == "Flags":
            header = parts
            continue
        if header is None or len(parts) < len(header) or not parts[0].isdigit():
            continue
        try:
            row = dict(zip(header, (int(x) for x in parts[:len(header)])))
        except ValueError:
            continue
        if not all_flags and row.get("Flags", 1) != 0:
            continue
        iv, fc = row.get("IntendedVsync", 0), row.get("FrameCompleted", 0)
        if iv <= 0 or fc <= iv:
            continue
        seen[(window, iv)] = Frame(
            window=window or "?",
            vsync_ns=iv,
            total_ms=(fc - iv) / 1e6,
            ui_ms=max(0, row.get("SyncQueued", iv) - iv) / 1e6,
            rt_ms=max(0, row.get("SwapBuffers", 0) - row.get("SyncStart", 0)) / 1e6,
        )
    return sorted(seen.values(), key=lambda f: (f.vsync_ns, f.window))


def percentile(values, p):
    """Nearest-rank on the sorted values (the survey scripts' rule)."""
    if not values:
        return 0.0
    s = sorted(values)
    return s[min(len(s) - 1, max(0, int(round(p / 100.0 * (len(s) - 1)))))]


def summarize(frames, window=None):
    """Metrics for [frames] (optionally only windows whose name contains [window])."""
    fr = [f for f in frames if window is None or window in f.window]
    tot = [f.total_ms for f in fr]
    if not tot:
        return {"frames": 0, "over16": 0, "over32": 0, "over60": 0, "worst": 0.0, "p50": 0.0, "p90": 0.0,
                "p99": 0.0, "janky_pct": 0.0, "sum_over32": 0.0, "span_ms": 0.0, "windows": {}}
    windows = {}
    for f in fr:
        key = short_window(f.window)
        windows[key] = windows.get(key, 0) + 1
    span = (max(f.vsync_ns + f.total_ms * 1e6 for f in fr) - fr[0].vsync_ns) / 1e6
    over16 = sum(1 for x in tot if x > JANK_MS)
    return {
        "frames": len(tot),
        "over16": over16,
        "over32": sum(1 for x in tot if x > SLOW_MS),
        "over60": sum(1 for x in tot if x > FROZEN_MS),
        "worst": round(max(tot), 1),
        "p50": round(percentile(tot, 50), 1),
        "p90": round(percentile(tot, 90), 1),
        "p99": round(percentile(tot, 99), 1),
        "janky_pct": round(100.0 * over16 / len(tot), 1),
        "sum_over32": round(sum(x for x in tot if x > SLOW_MS), 1),
        "span_ms": round(span, 1),
        "windows": windows,
    }


def frames_between(frames, start_s, end_s):
    """Frames whose IntendedVsync (CLOCK_MONOTONIC) lies in [start_s, end_s] seconds."""
    return [f for f in frames if start_s <= f.vsync_ns / 1e9 <= end_s]


_BEAT = re.compile(r"^\s*(\d+\.\d+)\s.*photo_beat (\w+)")


def parse_beats(logcat_text, since_s=0.0, until_s=None):
    """[(monotonic seconds, mode)] from `logcat -v monotonic` lines carrying the engine's photo_beat."""
    out = []
    for line in logcat_text.splitlines():
        m = _BEAT.match(line)
        if not m:
            continue
        t = float(m.group(1))
        if t >= since_s and (until_s is None or t <= until_s):
            out.append((t, m.group(2)))
    return sorted(out)


def beat_windows(frames, beats, pre_s=0.5, post_s=3.0, skip_first=True):
    """One entry per beat: (t, mode, frames in [t - pre, t + post]); the first beat is the start."""
    chosen = beats[1:] if skip_first else beats
    return [(t, mode, frames_between(frames, t - pre_s, t + post_s)) for t, mode in chosen]

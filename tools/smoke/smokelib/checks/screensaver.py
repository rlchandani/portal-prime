"""Screensaver: the operator test source (generated scenes only) with one pinned mode, then off."""

import time

from .. import PACKAGE, logscan
from ..model import SKIP, Result, result
from .common import body, leave_screen, logs_since, mark, prepare_ui

INTERVAL_S = 5
SOURCE_MINUTES = 2  # the test source also expires by itself
FRAME = "PhotoFramePreviewActivity"
ACTIVE_LINE = ("ImmortalPhotoFrame", "screensaver test source active: generated scenes only")
SCENE_LINE = ("ImmortalSlideshow", "ready native scene")


def _source(ctx, device, enabled, mode=None):
    req = {"action": "screensaver_test_source", "enabled": enabled}
    if enabled:
        req = {**req, "minutes": SOURCE_MINUTES, "mode": mode, "intervalSec": INTERVAL_S}
    return body(ctx.fleet.action(device, req))


def test_source(ctx, device):
    opts = ctx.options
    facts = ctx.facts(device)
    if facts.adb:
        ready, why = prepare_ui(ctx, device)
        if not ready:
            return [Result(device.name, "screensaver.test_source", SKIP, f"Portal not idle ({why})")]
    t0 = mark(ctx, device)
    undo = ctx.on_cleanup(f"{device.name}: screensaver test source off", lambda: _source(ctx, device, False))
    on = _source(ctx, device, True, opts.screensaver_mode)
    if not on.get("available"):
        _source(ctx, device, False)
        ctx.done(undo)
        return [Result(device.name, "screensaver.test_source", SKIP, "test source unavailable in this build")]
    time.sleep(1)
    frame_up = ctx.adb.resumed_activity(device).endswith(FRAME) if facts.adb else None
    time.sleep(max(0, opts.screensaver_seconds - 1))
    off = _source(ctx, device, False)
    ctx.done(undo)
    time.sleep(2)
    after = ctx.adb.resumed_activity(device) if facts.adb else ""
    if after.endswith(FRAME):
        leave_screen(ctx, device)
        after = ctx.adb.resumed_activity(device)
    window = logs_since(ctx, device, t0, lines=8000)
    return [evaluate(device, opts, on, off, window, frame_up, after, facts, ctx.adb.pid(device) if facts.adb else "")]


def evaluate(device, opts, on, off, window, frame_up, after, facts, pid_after):
    scenes = len(logscan.find(window, *SCENE_LINE))
    want_scenes = max(2, opts.screensaver_seconds // INTERVAL_S)
    pinned = f"{opts.screensaver_mode}@{INTERVAL_S}s"
    crashed = logscan.crashes(window, PACKAGE)
    problems = [p for p, bad in (
        (f"enable reply active={on.get('active')} pin={on.get('pin')}", not (on.get("active") and on.get("pin") == pinned)),
        ("no 'test source active' line", not logscan.has(window, *ACTIVE_LINE)),
        (f"{scenes} scenes in {opts.screensaver_seconds}s (want ≥{want_scenes})", scenes < want_scenes),
        ("photo frame never came to front", frame_up is False),
        (f"off reply active={off.get('active')}", off.get("active") is not False),
        (f"crash: {crashed[:1]}", bool(crashed)),
        (f"launcher pid changed {facts.pid}->{pid_after}", facts.adb and facts.pid and pid_after != facts.pid),
        (f"after off: {after.split('/')[-1]} in front", facts.adb and not after.endswith("HomeActivity")),
    ) if bad]
    detail = "; ".join(problems) or f"{pinned}: active -> {scenes} scenes -> off, Home back, no crash"
    return result(device.name, "screensaver.test_source", not problems, detail, {"scenes": scenes})

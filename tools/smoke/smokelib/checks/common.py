"""Helpers shared by the checks."""

import dataclasses
import time

from .. import logscan

FULL_LOG_LINES = 20000
MARK_LOG_LINES = 20
POLL_S = 1.0


def logs(ctx, device, lines=FULL_LOG_LINES):
    raw = ctx.adb.logcat(device, lines) if ctx.facts(device).adb else ctx.fleet.logcat(device, lines)
    return logscan.parse(raw)


def mark(ctx, device):
    """The newest logcat timestamp now; later lines are 'after the mark'."""
    return logscan.last_ts(logs(ctx, device, MARK_LOG_LINES))


def logs_since(ctx, device, ts, lines=4000):
    return logscan.since(logs(ctx, device, lines), ts)


def wait_for_log(ctx, device, ts, tag, needle, timeout_s):
    """Polls logcat after [ts] for a [tag] line containing [needle]; the match or None."""
    deadline = time.monotonic() + timeout_s
    while True:
        hits = logscan.find(logs_since(ctx, device, ts), tag, needle)
        if hits or time.monotonic() >= deadline:
            return hits[0] if hits else None
        time.sleep(POLL_S)


def body(reply):
    return reply.body if isinstance(reply.body, dict) else {}


DREAMING = "Dreaming"
FRAME = "PhotoFramePreviewActivity"
WAKE_STEPS = 3
SETTLE_S = 1.5
DREAM_START = "am start -n com.android.systemui/.Somnambulator"


def _front(ctx, device):
    return ctx.adb.wakefulness(device), ctx.adb.resumed_activity(device)


def prepare_ui(ctx, device):
    """(ok, why). UI checks need Hearthpane Home in front. From the screensaver we go Home with HOME
    (dream -> its photo-frame continuation -> Home) and put the dream back after the run; anything
    else in front (an app, a call, Jarvis, an overlay) means the Portal is busy and we leave it."""
    facts = ctx.facts(device)
    if facts.ui_initial:
        wake, resumed = _front(ctx, device)
        return resumed.endswith("HomeActivity"), f"now {resumed.split('/')[-1] or wake}"
    if ctx.adb.visible_overlays(device):
        return False, "an alert overlay is showing"
    wake, resumed = _front(ctx, device)
    if wake != DREAMING and not resumed.endswith(("HomeActivity", FRAME)):
        return False, f"busy: {resumed.split('/')[-1] or wake} in front"
    initial = DREAMING if wake == DREAMING else ("frame" if resumed.endswith(FRAME) else "home")
    ctx.set_facts(device, dataclasses.replace(facts, ui_initial=initial))
    if initial == DREAMING:
        ctx.on_cleanup(f"{device.name}: restart its screensaver", lambda: restore_dream(ctx, device))
    for _ in range(WAKE_STEPS):
        if resumed.endswith("HomeActivity") and wake != DREAMING:
            return True, f"was {initial}"
        ctx.adb.home(device)
        time.sleep(SETTLE_S)
        wake, resumed = _front(ctx, device)
    return False, f"could not reach Home ({resumed.split('/')[-1] or wake})"


def restore_dream(ctx, device):
    """Back to the screensaver the run found (Home first, so nothing we opened is left under it)."""
    if not ctx.adb.resumed_activity(device).endswith("HomeActivity"):
        ctx.adb.home(device)
        time.sleep(SETTLE_S)
    ctx.adb.shell(device, DREAM_START)


def leave_screen(ctx, device):
    """HOME (never Back), then confirm Hearthpane Home is in front."""
    ctx.adb.home(device)
    time.sleep(SETTLE_S)
    return ctx.adb.resumed_activity(device).endswith("HomeActivity")


def wait_resumed(ctx, device, activity, timeout_s=8):
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if ctx.adb.resumed_activity(device).endswith(activity):
            return True
        time.sleep(0.5)
    return False

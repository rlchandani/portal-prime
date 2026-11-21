"""Calls room roster and Jarvis (versions + the Hearthpane tool provider's caller check)."""

import time

from .. import JARVIS_PACKAGE, PACKAGE, uiparse
from ..model import FLEET, FAIL, PASS, SKIP, Result, result, skip
from .common import leave_screen, mark, prepare_ui, wait_for_log, wait_resumed

SCREEN_SETTLE_S = 3
CALLS = "CallsActivity"
RETRY_S = 4
PROVIDER = f"content://{PACKAGE}.jarvis-tools"


def roster(ctx, device, all_devices):
    """Calls > Rooms lists every other Portal of the registry as Available."""
    if not ctx.facts(device).adb:
        return [Result(device.name, "calls.roster", SKIP, "needs adb (the roster is only on screen)")]
    ready, why = prepare_ui(ctx, device)
    if not ready:
        return [Result(device.name, "calls.roster", SKIP, f"Portal not idle ({why})")]
    others = [d.name for d in all_devices if d.serial != device.serial]
    undo = ctx.on_cleanup(f"{device.name}: leave Calls", lambda: leave_screen(ctx, device))
    try:
        opened = ctx.fleet.action(device, {"action": "open_screen", "screen": "calls"}).ok
        if not (opened and wait_resumed(ctx, device, CALLS)):
            return [Result(device.name, "calls.roster", FAIL, "Calls did not open")]
        time.sleep(SCREEN_SETTLE_S)
        seen = uiparse.roster(ctx.adb.ui_nodes(device, CALLS), others)
        if any(v != "Available" for v in seen.values()):
            time.sleep(RETRY_S)  # opening Calls asks the rooms who's around; give them a beat
            seen = uiparse.roster(ctx.adb.ui_nodes(device, CALLS), others)
    finally:
        leave_screen(ctx, device)
        ctx.done(undo)
    ok = all(v == "Available" for v in seen.values())
    detail = ", ".join(f"{n}: {v or 'missing'}" for n, v in seen.items())
    return [result(device.name, "calls.roster", ok, detail, {"roster": seen})]


def jarvis_versions(ctx):
    codes = {}
    for d in ctx.devices:
        app = next((a for a in ctx.facts(d).info.get("apps") or [] if a.get("packageName") == JARVIS_PACKAGE), {})
        codes[d.name] = app.get("installed")
    if len(codes) < 2:
        return [skip(FLEET, "jarvis.version", "needs two or more reachable Portals")]
    values = set(codes.values())
    ok = len(values) == 1 and None not in values
    detail = f"versionCode {next(iter(values))} everywhere" if ok else ", ".join(f"{k}={v}" for k, v in codes.items())
    return [result(FLEET, "jarvis.version", ok, detail, {"versions": codes})]


def jarvis_provider(ctx, device):
    """A shell-uid call to the tool provider must be refused (it answers Jarvis's uid only)."""
    if not ctx.facts(device).adb:
        return [Result(device.name, "jarvis.provider", SKIP, "needs adb (a call from the shell uid)")]
    t0 = mark(ctx, device)
    _, out = ctx.adb.shell(device, f"content call --uri {PROVIDER} --method privacy")
    refused_log = wait_for_log(ctx, device, t0, "HearthpaneTools", "refused call privacy from [com.android.shell]", 5)
    ok = "Result: null" in out and refused_log is not None
    detail = ("shell call refused (Result: null, logged)" if ok else
              f"reply {out.strip()[:60]!r}; refusal {'logged' if refused_log else 'not logged'}")
    return [Result(device.name, "jarvis.provider", PASS if ok else FAIL, detail)]

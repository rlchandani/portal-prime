"""Control Center: Live + entities, the cc_debug dry run with a fake state, the banner preview."""

import time

from .. import logscan, uiparse
from ..model import FAIL, PASS, SKIP, Result, result
from .common import body, leave_screen, logs, mark, prepare_ui, wait_for_log, wait_resumed

BANNER_TAG = "HearthpaneBanner"
PREVIEW_CHIP = "Washer done"  # info severity: a top banner, never a sound (CcBannerPreview)
PREVIEW_TIMEOUT_S = 8.0       # CcBannerPreview.TIMEOUT_MS
LOG_WAIT_S = 8
CC = "ControlCenterActivity"


def _debug(ctx, device, extra=None):
    r = ctx.fleet.action(device, {"action": "cc_debug", **(extra or {})})
    return str(body(r).get("result", "")) if r.ok else f"error {r.text[:80]}"


def _fake_round(ctx, device, t0):
    """Dry run on, a fake leak on then off, dry run off. Every step has an undo until it is undone."""
    undo_dry = ctx.on_cleanup(f"{device.name}: cc_debug dryRun off", lambda: _debug(ctx, device, {"dryRun": False}))
    try:
        _debug(ctx, device, {"dryRun": True})
        fake = {"slug": "smoke_" + time.strftime("%H%M%S"), "state": "on", "deviceClass": "moisture",
                "name": "Smoke test"}
        off = {"fakeState": {**fake, "state": "off"}}
        undo_fake = ctx.on_cleanup(f"{device.name}: fake state off", lambda: _debug(ctx, device, off))
        try:
            on_reply = _debug(ctx, device, {"fakeState": fake})
            decision = wait_for_log(ctx, device, t0, BANNER_TAG, "dry-run banner critical class=moisture", LOG_WAIT_S)
        finally:
            off_reply = _debug(ctx, device, off)
            ctx.done(undo_fake)
    finally:
        _debug(ctx, device, {"dryRun": False})
        ctx.done(undo_dry)
    entity = f"binary_sensor.fake_{fake['slug']}"
    return {"on": on_reply, "off": off_reply, "decision": decision,
            "accepted": on_reply.startswith(f"fakeState {entity}=on"),
            "cleared": off_reply.startswith(f"fakeState {entity}=off")}


def dry_run(ctx, device):
    """cc.live + cc.dry_run: dry run on, a fake leak through the alert path, decision logged, all undone."""
    before = logscan.parse_kv(_debug(ctx, device))
    since = ctx.facts(device).install_local
    entities, seen_at = logscan.registry_entities(logscan.since(logs(ctx, device), since) if since else ())
    t0 = mark(ctx, device)
    run = _fake_round(ctx, device, t0)
    after_text = _debug(ctx, device)
    after = logscan.parse_kv(after_text)
    shown = logscan.find(logscan.since(logs(ctx, device, 4000), t0), BANNER_TAG, "alert shown")
    phase = "live" if run["accepted"] else (run["on"].split("not live (")[-1].rstrip(")") if "not live" in run["on"] else "?")
    restored = after.get("dryRun") == "false" and after.get("haUnreachable") == "false" and \
        after.get("testPlan") == before.get("testPlan", "false")
    problems = [p for p, bad in (
        (f"found dryRun={before.get('dryRun')} before the run", before.get("dryRun") != "false"),
        (f"fakeState: {run['on'][:80]}", not run["accepted"]),
        ("no dry-run banner decision logged", run["decision"] is None),
        (f"fake off: {run['off'][:60]}", not run["cleared"]),
        ("a real banner was shown", bool(shown)),
        (f"not restored: {after_text[:80]}", not restored),
    ) if bad]
    decision = run["decision"].msg.split("dry-run banner ")[-1] if run["decision"] else ""
    live_ok = run["accepted"] and (entities or 0) > 0
    live_detail = f"phase {phase}; {entities if entities is not None else '?'} entities" + \
        (f" (registry {seen_at[6:]})" if seen_at else " (no registry line since install)")
    return [
        Result(device.name, "cc.live", PASS if live_ok else FAIL, live_detail, {"entities": entities}),
        result(device.name, "cc.dry_run", not problems,
               "; ".join(problems) or f"decision logged: {decision}; dry run off again",
               {"before": before, "after": after}),
    ]


def banner_preview(ctx, device):
    """CC Alerts > 'Washer done' preview chip: drawn, logged, gone after its timeout."""
    if not ctx.facts(device).adb:
        return [Result(device.name, "cc.banner_preview", SKIP, "needs adb (a tap on the Alerts screen)")]
    ready, why = prepare_ui(ctx, device)
    if not ready:
        return [Result(device.name, "cc.banner_preview", SKIP, f"Portal not idle ({why})")]
    undo = ctx.on_cleanup(f"{device.name}: leave Control Center", lambda: leave_screen(ctx, device))
    try:
        opened = ctx.fleet.action(device, {"action": "open_control_center", "screen": "alerts"}).ok
        if not (opened and wait_resumed(ctx, device, CC)):
            return [Result(device.name, "cc.banner_preview", FAIL, "Control Center Alerts did not open")]
        time.sleep(1.5)  # first composition
        return [_preview_round(ctx, device)]
    finally:
        home = leave_screen(ctx, device)
        ctx.done(undo)
        if not home:
            ctx.log(f"{device.name}: Home not back in front after Control Center")


def _preview_round(ctx, device):
    nodes = ctx.adb.ui_nodes(device, CC)
    chip = uiparse.by_desc(nodes, PREVIEW_CHIP)
    ha_live = uiparse.by_desc(nodes, "Home Assistant: Live") is not None
    if chip is None:
        return Result(device.name, "cc.banner_preview", FAIL, f"'{PREVIEW_CHIP}' preview chip not found")
    t0 = mark(ctx, device)
    base = uiparse.count_visible_windows(ctx.adb.windows(device), CC)
    ctx.adb.tap(device, *chip.center)
    shown_log = wait_for_log(ctx, device, t0, BANNER_TAG, "banner preview info host=", LOG_WAIT_S)
    shown = uiparse.count_visible_windows(ctx.adb.windows(device), CC)
    time.sleep(PREVIEW_TIMEOUT_S + 1.5)
    cleared = uiparse.count_visible_windows(ctx.adb.windows(device), CC)
    ok = shown_log is not None and shown > base and cleared == base
    host = shown_log.msg.split("host=")[-1] if shown_log else "?"
    return result(device.name, "cc.banner_preview", ok,
                  f"shown (host={host}, windows {base}->{shown}) and cleared ({cleared}); "
                  f"header says HA {'Live' if ha_live else 'NOT Live'}")

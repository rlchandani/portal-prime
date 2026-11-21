"""Leave-as-found verification, after every undo step has run."""

from .. import logscan
from ..model import result
from .common import DREAMING, body


def verify(ctx, device):
    problems = []
    r = ctx.fleet.action(device, {"action": "cc_debug"})
    kv = logscan.parse_kv(str(body(r).get("result", "")))
    if kv.get("dryRun") != "false" or kv.get("haUnreachable") != "false" or kv.get("testPlan") != "false":
        problems.append(f"cc_debug {body(r).get('result', r.text[:60])}")
    info = body(ctx.fleet.info(device))
    if info.get("devMode") is not False:
        problems.append(f"devMode={info.get('devMode')}")
    if ctx.facts(device).adb:
        overlays = ctx.adb.visible_overlays(device)
        if overlays:
            problems.append(f"overlay still visible ({overlays[0][0]})")
        problems += _front_problems(ctx, device)
    return [result(device.name, "restore.state", not problems,
                   "; ".join(problems) or "dry run off, test plan off, dev mode off" +
                   (", front screen as found, no overlay" if ctx.facts(device).adb else ""))]


def _front_problems(ctx, device):
    """The screensaver again if the run found it dreaming, otherwise Hearthpane Home."""
    initial = ctx.facts(device).ui_initial
    if not initial:
        return []
    if initial == DREAMING:
        wake = ctx.adb.wakefulness(device)
        return [] if wake == DREAMING else [f"screensaver not restarted ({wake})"]
    front = ctx.adb.resumed_activity(device)
    return [] if front.endswith("HomeActivity") else [f"{front.split('/')[-1] or 'nothing'} in front"]

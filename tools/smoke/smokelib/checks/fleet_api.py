"""Read-only fleet routes: settings sync, OTA status, the rotation hook."""

from ..model import FLEET, FAIL, Result, result, skip
from .common import body

SANE_OTA = {"up_to_date", "idle"}


def sync_status(ctx, device):
    r = ctx.fleet.action(device, {"action": "settings_sync", "op": "status"})
    s = body(r).get("sync") or {}
    ok = r.ok and s.get("available") is True and s.get("paused") is False and s.get("phase") == "SYNCED"
    peers = ", ".join(p.get("name", "?") for p in s.get("peers") or [])
    detail = f"{s.get('phase')} digest {s.get('digest')} paused={s.get('paused')} peers: {peers or 'none'}"
    return [result(device.name, "sync.status", ok, detail if r.ok else r.text[:120],
                   {"digest": s.get("digest"), "phase": s.get("phase")})]


def sync_digests(ctx, status_results):
    """Settings-sync digest identical on every Portal this run reached."""
    digests = {r.device: r.data.get("digest") for r in status_results if r.check == "sync.status"}
    if len(digests) < 2:
        return [skip(FLEET, "sync.digest", "needs two or more reachable Portals")]
    values = set(digests.values())
    ok = len(values) == 1 and None not in values
    detail = f"all {next(iter(values))}" if ok else "; ".join(f"{d}={v}" for d, v in digests.items())
    return [result(FLEET, "sync.digest", ok, detail, {"digests": digests})]


def settings_sync_route(ctx, device):
    r = ctx.fleet.action(device, {"action": "settings_sync", "op": "status"})
    return [result(device.name, "api.settings_sync", r.ok and "digest" in (body(r).get("sync") or {}),
                   "answers with a digest" if r.ok else r.text[:120])]


def ota_status(ctx, device):
    """GET /update/ota only (never a check or install): sane status, no error, no retry pending."""
    r = ctx.fleet.raw(device, "GET", "/update/ota")
    ota = body(r).get("ota") or {}
    installed = ota.get("installed") or {}
    out = [result(device.name, "api.ota", r.ok and bool(installed), "answers" if r.ok else r.text[:120])]
    if not r.ok:
        return out + [Result(device.name, "ota.status", FAIL, "OTA status unreadable")]
    problems = []
    if ota.get("status") not in SANE_OTA:
        problems.append(f"status={ota.get('status')}")
    for key in ("retry", "error", "waitingFor"):
        if ota.get(key) is not None:
            problems.append(f"{key}={ota.get(key)}")
    if ota.get("devMode") is not False:
        problems.append(f"devMode={ota.get('devMode')}")
    if installed.get("versionCode") != ctx.release.version_code:
        problems.append(f"installed {installed.get('versionCode')}")
    detail = "; ".join(problems) or f"{ota.get('status')}, retry null, error null"
    return out + [result(device.name, "ota.status", not problems, detail)]


def rotation_hook(ctx, device):
    """`orientation` with no request only reports (it forces nothing)."""
    r = ctx.fleet.action(device, {"action": "orientation"})
    res = body(r).get("result") or {}
    ok = r.ok and res.get("request") == "" and "listening" in res and "forced" in res
    detail = (f"forced={res.get('forced')} settled={res.get('settled')} listening={res.get('listening')}"
              if r.ok else r.text[:120])
    return [result(device.name, "rotation.hook", ok, detail, {"result": res})]

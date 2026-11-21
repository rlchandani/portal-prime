"""Orchestration: locks first, read-only passes in parallel, device-changing checks one at a time,
then every undo step, the leave-as-found check and the lock release — whatever happened."""

import os
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

from . import adb as adb_mod, registry, release as release_mod
from .context import Context, run_check
from .fleet import Fleet
from .lock import MAX_HOLD_S, Locks
from .model import FAIL, Result
from .redact import Redactor
from .checks import calls_jarvis, control_center, fleet_api, reminders, restore, screensaver, version

PARALLEL = 3
STOP_STARTING_AFTER_S = MAX_HOLD_S - 5 * 60


def _each(ctx, steps):
    """[steps]: (check, fn(ctx, device)) run per held Portal, Portals in parallel, steps in order."""
    def per_device(device):
        out = []
        for check, fn in steps:
            out += run_check(ctx, device.name, check, lambda: fn(ctx, device))
        return out
    with ThreadPoolExecutor(max_workers=PARALLEL) as pool:
        return [r for rs in pool.map(per_device, ctx.devices) for r in rs]


def _quick(ctx, adb_serials):
    results = _each(ctx, [
        ("facts", lambda c, d: version.gather_facts(c, d, adb_serials)),
        ("version.installed", version.installed_version),
        ("version.apk_sha", version.apk_sha),
        ("health.fleet", version.fleet_health),
        ("health.dev_mode", version.dev_mode),
        ("sync.status", fleet_api.sync_status),
    ])
    return results + run_check(ctx, "fleet", "sync.digest", lambda: fleet_api.sync_digests(ctx, results))


def _full_read_only(ctx):
    results = _each(ctx, [
        ("health.crashes", version.crashes),
        ("api.settings_sync", fleet_api.settings_sync_route),
        ("ota.status", fleet_api.ota_status),
        ("rotation.hook", fleet_api.rotation_hook),
        ("jarvis.provider", calls_jarvis.jarvis_provider),
    ])
    return results + run_check(ctx, "fleet", "jarvis.version", lambda: calls_jarvis.jarvis_versions(ctx))


def _full_changing(ctx, all_devices):
    """One Portal at a time. Banner preview runs before the fake alert so Alerts never lists it."""
    results = []
    for d in ctx.devices:
        if time.monotonic() - ctx.started > STOP_STARTING_AFTER_S:
            results.append(Result(d.name, "time", FAIL, "lock-hold budget reached; remaining checks not started"))
            continue
        results += run_check(ctx, d.name, "cc.banner_preview", lambda: control_center.banner_preview(ctx, d))
        results += run_check(ctx, d.name, "cc.dry_run", lambda: control_center.dry_run(ctx, d))
        results += run_check(ctx, d.name, "calls.roster", lambda: calls_jarvis.roster(ctx, d, all_devices))
        if d.name.lower() == ctx.options.screensaver_device.lower():
            results += run_check(ctx, d.name, "screensaver.test_source", lambda: screensaver.test_source(ctx, d))
    return results + run_check(ctx, "fleet", "reminders.sync", lambda: reminders.sync(ctx))


def run(options, fleetctl, report_root, wanted, release_repo):
    redactor = Redactor()
    started = datetime.now()
    stamp = started.strftime("%Y%m%d-%H%M%S")
    locks = Locks(f"smoke-suite pid={os.getpid()} {started.isoformat(timespec='seconds')}")
    all_devices = registry.load(fleetctl)
    for d in all_devices:
        redactor.add(d.token)
        redactor.add(d.host)
    chosen = registry.select(all_devices, wanted)
    rel = release_mod.latest(release_repo)
    ctx = Context(options, Fleet(fleetctl, locks, redactor), adb_mod.Adb(locks, redactor), redactor, rel)
    results, lock_report = [], {}
    held = []
    for d in chosen:
        ok, holder = locks.acquire(d.serial)
        if ok:
            held.append(d)
        else:
            results.append(Result(d.name, "lock", FAIL, f"locked by {redactor.text(holder)} - not touched"))
            lock_report[d.name] = f"locked by {redactor.text(holder)}"
    ctx.devices = tuple(held)
    cleanups = []
    try:
        if held:
            results += _quick(ctx, adb_mod.connected_serials())
            if options.mode == "full":
                results += _full_read_only(ctx)
                results += _full_changing(ctx, all_devices)
    finally:
        cleanups = ctx.run_cleanups()
        if options.mode == "full":
            for d in held:
                results += run_check(ctx, d.name, "restore.state", lambda: restore.verify(ctx, d))
        for serial, released in locks.release_all().items():
            name = next(d.name for d in held if d.serial == serial)
            lock_report[name] = "held, released" if released else "held; owner file changed, NOT removed"
    results = [r for r in results if r.check != "facts" or r.status != "PASS"]
    payload = {
        "startedAt": started.isoformat(timespec="seconds"), "mode": options.mode,
        "seconds": round(time.monotonic() - ctx.started, 1),
        "release": {"tag": rel.tag, "versionCode": rel.version_code, "versionName": rel.version_name,
                    "sha256": rel.sha256},
        "devices": [{"name": d.name, "id": d.short, "model": d.model, "adb": ctx.facts(d).adb,
                     "lock": lock_report.get(d.name, "")} for d in chosen],
        "cleanups": [{"step": s, "ok": ok, "error": e} for s, ok, e in cleanups],
    }
    return results, payload, os.path.join(report_root, stamp), [d.name for d in chosen]

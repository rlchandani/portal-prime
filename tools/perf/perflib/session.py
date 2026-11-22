"""One benchmark session: lock -> preflight -> scenario -> restore (always) -> result -> release."""

import os
import signal
import time
import traceback

from . import notes, report, scenarios
from .builds import BuildError, OfficialSource, local_build
from .device import Device
from .engine import Options, ScenarioError, Session, build_order
from .lock import LockError

APK_LABELS = "ABCDEFGH"
_SIGNALS = (signal.SIGTERM, signal.SIGINT, signal.SIGHUP)


class Interrupted(BaseException):
    """Raised from a signal handler so the session unwinds through restore."""


def _raise_interrupted(signum, _frame):
    raise Interrupted(f"signal {signum}")


def resolve_builds(apk_specs, official_flag, official_source):
    builds = []
    if official_flag:
        builds.append(official_source.latest("O"))
    for i, spec in enumerate(apk_specs):
        builds.append(local_build(APK_LABELS[i], spec))
    if not builds:
        raise BuildError("give --official and/or one or more --apk")
    return builds


def preflight(device, priors, log):
    """Checks the Portal and records into [priors] what restore must put back (before changing
    anything, so an interrupt mid-preflight still restores them). Then lowers STREAM_MUSIC to 0."""
    if not device.connected():
        raise ScenarioError(f"{device.serial} is not connected over adb")
    priors.update({
        "top": device.resumed(),
        "wakefulness": device.wakefulness(),
        "music_volume": device.music_volume(),
        "accelerometer_rotation": device.setting("system", "accelerometer_rotation"),
        "user_rotation": device.setting("system", "user_rotation"),
        "installed_sha256": device.installed_sha(),
        "version_name": device.version_name(),
    })
    log(f"preflight: top={priors['top']} wake={priors['wakefulness']} music={priors['music_volume']} "
        f"version={priors['version_name']}")
    p = device.fleet(["dev", "on"])
    if p.rc != 0:
        raise ScenarioError(f"fleetctl dev on failed (rc {p.rc})")
    if priors["music_volume"] is not None:
        device.set_music_volume(0)
    device.go_home()
    device.runner.sleep(3)


def run_session(*, serial, scenario_name, builds, options, out_dir, lock, fleetctl, official, runner=None,
                log=print, own_lock=True, job_id=None):
    """Returns (result dict, result.json path). The lock is held (or checked) by the caller's [lock]."""
    scenario = scenarios.get(scenario_name)
    device = Device(serial, lock, fleetctl, runner=runner, log=log)
    started = time.strftime("%Y-%m-%d %H:%M:%S %Z")
    result = {
        "schema": report.SCHEMA, "job": job_id, "scenario": scenario_name, "device": serial,
        "summary": scenario["summary"], "screenshots_allowed": scenario["screenshots"],
        "builds": [b.as_dict() for b in builds], "reps": options.reps, "interleave": options.interleave,
        "order": build_order([b.label for b in builds], options.reps, options.interleave),
        "options": {k: getattr(options, k) for k in ("meminfo", "atrace", "simpleperf", "screenshots", "poll_s")},
        "started": started, "status": "running", "lock_owner": lock.tag,
    }
    previous = {s: signal.getsignal(s) for s in _SIGNALS}
    for s in _SIGNALS:
        signal.signal(s, _raise_interrupted)
    session = None
    path = None
    priors = {}  # filled by preflight; restore reads it (music volume, rotation)
    try:
        lock.check()
        preflight(device, priors, log)
        session = Session(device, scenario_name, scenario, builds, options, out_dir, log)
        session.run()
        result["status"] = "ok"
    except Interrupted as e:
        result.update(status="interrupted", error=str(e))
        log(f"interrupted ({e}); restoring")
    except LockError as e:
        result.update(status="lock-lost", error=str(e))
        log(f"LOCK LOST: {e}; no further device commands")
    except Exception as e:  # noqa: BLE001 - any failure still restores the device
        result.update(status="error", error=f"{type(e).__name__}: {e}")
        log(f"error: {e}")
        with open(os.path.join(out_dir, "traceback.txt"), "w", encoding="utf-8") as f:
            f.write(traceback.format_exc())
    finally:
        for s in _SIGNALS:
            signal.signal(s, signal.SIG_IGN)  # restore must finish; the watchdog has a hard kill
        try:
            result["priors"] = {k: v for k, v in priors.items() if k != "installed_sha256"}
            if session is not None:
                result.update(session.result_parts())
            if result["status"] == "lock-lost":
                result["restore"] = {"ok": False, "steps": [], "final": {}, "skipped": "lock not ours"}
            else:
                log("restoring the Portal")
                from .restore import restore
                result["restore"] = restore(device, official, priors, log)
                log(f"restore {'OK' if result['restore']['ok'] else 'INCOMPLETE'}")
            result["ended"] = time.strftime("%Y-%m-%d %H:%M:%S %Z")
            path = report.write(out_dir, result)
            if own_lock:
                notes.append(serial, notes.line(result, lock.tag, started_epoch=lock.acquired_at))
        finally:
            for s, h in previous.items():
                signal.signal(s, h)
    return result, path


def default_out_dir(root, scenario_name):
    safe = scenario_name.replace(":", "_").replace("/", "_")
    return os.path.join(root, f"{time.strftime('%Y%m%d-%H%M%S')}-{safe}")


__all__ = ["Interrupted", "Options", "OfficialSource", "resolve_builds", "run_session", "default_out_dir"]

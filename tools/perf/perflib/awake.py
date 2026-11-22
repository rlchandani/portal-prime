"""Keeping the Portal awake for a whole job, and putting the exact prior state back.

Since 1.329 Home's idle timer (IdleFrameTimer) opens the photo frame (PhotoFramePreviewActivity) 10 s
before the system screen timeout, and the system itself starts PhotoDreamService at the timeout. Both
come up during a scenario's untouched waits (fleet orientation, sleeps) and cover Home: rotation
windows render 0 frames, `expect HomeActivity` aborts, the tab targets are not found.

For the job:
  1. `screen_off_timeout` (Settings.System) -> "never". The idle timer treats >= a day as never
     (IdleFrameTiming.idleDelayMs) and the system never times out, so neither the frame nor the dream starts.
     (`stay_on_while_plugged_in`, what `svc power stayon` writes, is not used: on Family the Portal's own
     services put it back to 0 within seconds, and the harness never runs `svc power`.)
  2. A no-op key (left Shift) before every step: system user activity + Home's onUserInteraction, so a
     timer armed before step 1 (Home reads the timeout only when it resumes) restarts its count.

Restore writes each prior back exactly: the prior value, or `settings delete` when it was unset. Only
keys recorded in the priors are touched; the priors are on disk (priors.json) before anything changes,
so a worker can restore after a crashed child. Settings.Secure is never read or written here.
"""

import json
import os

NEVER_MS = "2147483647"  # Integer.MAX_VALUE, Android's own "never"
NUDGE_KEY = "KEYCODE_SHIFT_LEFT"
PRIORS_FILE = "priors.json"

# (priors key, settings namespace, settings key, value for the job). Never "secure".
SETTINGS = (
    ("screen_off_timeout", "system", "screen_off_timeout", NEVER_MS),
)
_ALLOWED_NAMESPACES = frozenset({"system", "global"})


def _check(namespace):
    if namespace not in _ALLOWED_NAMESPACES:
        raise ValueError(f"keep-awake only touches system/global settings, not {namespace}")


def save_priors(out_dir, priors):
    """priors.json next to the result, written before each change so a crash can still be undone."""
    if not out_dir:
        return None
    path = os.path.join(out_dir, PRIORS_FILE)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump({k: v for k, v in priors.items() if k != "installed_sha256"}, f, indent=1)
    os.replace(tmp, path)
    return path


def load_priors(out_dir):
    path = os.path.join(out_dir or "", PRIORS_FILE)
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def hold(device, priors, out_dir=None, log=print):
    """Records each prior (in [priors] and on disk) and only then changes it."""
    for key, namespace, name, value in SETTINGS:
        _check(namespace)
        priors[key] = device.setting(namespace, name)
        save_priors(out_dir, priors)
        device.put_setting(namespace, name, value)
    log("keep-awake: " + ", ".join(f"{k} {priors[k]} -> {v}" for k, _, _, v in SETTINGS))


def release(device, priors):
    """Puts back every recorded prior exactly; returns a detail string. Raises if one did not stick."""
    done, problems = [], []
    for key, namespace, name, _ in SETTINGS:
        if key not in priors:
            done.append(f"{key} untouched")
            continue
        _check(namespace)
        prior = priors[key]
        if prior is None:
            device.shell(f"settings delete {namespace} {name}")
        else:
            device.put_setting(namespace, name, prior)
        now = device.setting(namespace, name)
        done.append(f"{key} -> {prior if prior is not None else 'unset'}")
        if now != prior:
            problems.append(f"{key} is {now}, prior {prior}")
    if problems:
        raise RuntimeError("; ".join(problems))
    return ", ".join(done)


def nudge(device):
    """One no-op key: resets the system's and Home's idle counts. Never a tap, never on a measured window."""
    device.shell(f"input keyevent {NUDGE_KEY}")

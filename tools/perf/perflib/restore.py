"""Putting a Portal back exactly as the fleet expects it - always, even after an error.

Order (each step runs even when an earlier one failed; each is recorded):
  1. stop any atrace/simpleperf capture and remove our /data/local/tmp files
  2. wm size reset + wm density reset
  3. auto-rotate back (accelerometer_rotation / user_rotation to their prior values, default 1 / 0)
  4. the latest OFFICIAL release installed (gh release download, sha256 verified on the Mac and on device)
  5. fleet: orientation forget, screensaver test source off, cc_debug cleared (retried while the app restarts)
  6. STREAM_MUSIC back to its prior value
  7. Home as the default and on top (set-home-activity + HOME intent; a dream is woken, never tapped)
  8. fleetctl dev off
  9. final check: on-device sha == release manifest, Home resumed + default, dev mode off
"""

import json
import re

from . import HOME_COMPONENT
from .builds import install
from .device import TMP_PREFIX

FLEET_RETRIES = 8
FLEET_RETRY_S = 5
SETTLE_AFTER_INSTALL_S = 20

FLEET_CLEANUP = (
    ("orientation forget", {"action": "orientation", "request": "forget"}),
    ("screensaver test source off", {"action": "screensaver_test_source", "enabled": False}),
    ("cc_debug cleared", {"action": "cc_debug", "dryRun": False, "testPlan": "clear", "haUnreachable": False}),
)


def _step(steps, name, fn, log):
    try:
        detail = fn()
        steps.append({"step": name, "ok": True, "detail": detail or ""})
    except BaseException as e:  # noqa: BLE001 - restore must continue past anything, even a signal
        if isinstance(e, (SystemExit,)):
            raise
        steps.append({"step": name, "ok": False, "detail": f"{type(e).__name__}: {e}"[:300]})
        log(f"  restore step failed: {name}: {e}")


def _fleet_with_retry(device, body, sleep):
    for attempt in range(FLEET_RETRIES):
        reply = device.action(body)
        if reply.get("ok") is True or (body["action"] == "screensaver_test_source" and reply.get("active") is False):
            return reply
        if attempt + 1 < FLEET_RETRIES:
            sleep(FLEET_RETRY_S)
    raise RuntimeError(f"fleet {body['action']} did not confirm: {json.dumps(reply)[:160]}")


def restore(device, official, priors, log=print):
    """Returns {"ok": bool, "steps": [...], "final": {...}}. [priors] comes from the preflight."""
    steps = []
    sleep = device.runner.sleep
    final = {}

    def stop_captures():
        device.shell("atrace --async_stop -o /dev/null >/dev/null 2>&1; "
                     f"rm -f {TMP_PREFIX}-*", timeout=60)
        return "atrace stopped, temp files removed"

    def wm_reset():
        device.shell("wm size reset; wm density reset")
        return device.shell("wm size; wm density").strip().replace("\n", "; ")

    def rotation():
        accel = priors.get("accelerometer_rotation") or "1"
        user = priors.get("user_rotation") or "0"
        device.put_setting("system", "accelerometer_rotation", accel)
        device.put_setting("system", "user_rotation", user)
        return f"accel={accel} user={user}"

    def official_install():
        build = official.latest()
        final["official"] = {"tag": build.tag, "versionName": build.version_name, "sha256": build.sha256}
        if device.installed_sha() == build.sha256:
            return f"official {build.version_name} already installed (sha {build.sha256[:8]}...)"
        install(device, build, log)
        sleep(SETTLE_AFTER_INSTALL_S)
        return f"installed official {build.version_name} (sha {build.sha256[:8]}... verified)"

    def fleet_cleanup(body):
        return lambda: json.dumps(_fleet_with_retry(device, body, sleep))[:160]

    def music():
        prior = priors.get("music_volume")
        if prior is None:
            return "unchanged (prior volume unknown, never lowered)"
        device.set_music_volume(prior)
        return f"STREAM_MUSIC -> {prior} (now {device.music_volume()})"

    def home():
        device.go_home()
        sleep(3)
        if device.resumed() != HOME_COMPONENT:
            device.go_home()
            sleep(4)
        top, default = device.resumed(), device.home_resolved()
        if top != HOME_COMPONENT or default != HOME_COMPONENT:
            raise RuntimeError(f"Home not restored: top={top} default={default}")
        return "HomeActivity on top + default Home"

    def dev_off():
        p = device.fleet(["dev", "off"])
        if p.rc != 0:
            raise RuntimeError(f"fleetctl dev off rc {p.rc}")
        return "dev off"

    def verify():
        sha = device.installed_sha()
        final["device_sha256"] = sha
        final["top"] = device.resumed()
        final["home_default"] = device.home_resolved()
        info = device.fleet(["info"]).out
        m = re.search(r'"devMode"\s*:\s*(true|false)', info)
        final["dev_mode"] = m.group(1) if m else "unknown"
        want = final.get("official", {}).get("sha256")
        problems = []
        if not want or sha != want:
            problems.append(f"device sha {sha[:8]} != official {str(want)[:8]}")
        if final["top"] != HOME_COMPONENT:
            problems.append(f"top is {final['top']}")
        if final["dev_mode"] != "false":
            problems.append(f"devMode={final['dev_mode']}")
        if problems:
            raise RuntimeError("; ".join(problems))
        return f"official sha {sha[:8]}... = release json, Home on top, dev off"

    _step(steps, "stop captures", stop_captures, log)
    _step(steps, "wm reset", wm_reset, log)
    _step(steps, "auto-rotate", rotation, log)
    _step(steps, "official release", official_install, log)
    for name, body in FLEET_CLEANUP:
        _step(steps, name, fleet_cleanup(body), log)
    _step(steps, "music volume", music, log)
    _step(steps, "home", home, log)
    _step(steps, "dev off", dev_off, log)
    _step(steps, "verify", verify, log)
    return {"ok": all(s["ok"] for s in steps), "steps": steps, "final": final}

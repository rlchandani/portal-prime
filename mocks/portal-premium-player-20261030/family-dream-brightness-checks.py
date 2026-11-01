#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Physical API29 Dream verifier; all evidence stays in a NEW private directory.

Requires an already installed APK and authenticated {base,token} connection.
No install, global settings writes, force-stop, task clearing or sensor injection.
Cleanup restores settings and leaves Home without an active Dream. Main owns
restoration of the earlier natural night session and records video separately.
"""
import argparse
import json
import os
import pathlib
import re
import signal
import subprocess
import time
import urllib.error, urllib.parse, urllib.request
PKG = "com.immortal.launcher.debug"; COMPONENT = PKG + "/com.immortal.launcher.PhotoDreamService"
TEMP = dict(mode="wholePhoto", photo_motion=False, intervalSec=120, antiBurnIn=False, showNowPlaying=False, darkRoomClock=True, darkRoomLux=20, nightClockBrightness=3)
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise RuntimeError("redirect_rejected")
class Verifier:
    def __init__(self, args):
        self.args, self.out = args, args.private_dir; self.started = time.monotonic(); self.deadline = self.started + 150; self.seq, self.changed, self.original, self.sources = 0, {}, None, None; self.guarded, self.was_dream, self.identity = False, False, None
        self.result = dict(success=False, naturalBrightLightExitTested=False, opticalBacklightMeasured=False, panelFpsMeasured=False, limitations="Room ~5lux is below minimum 10lux bright threshold; natural " "bright exit NOT tested. Registered disable uses the same exit fade. " "Sysfs backlight unreadable; window attributes do not prove optical output " "or FPS. Photo ROI comparison omitted. Original night session restoration owned by main.")
        self.http = urllib.request.build_opener(NoRedirect)
    def require(self, condition, label):
        if not condition:
            raise RuntimeError(label)
    def save(self, label, data):
        self.seq += 1
        p = self.out / f"{self.seq:04d}-{label}"; p.write_bytes(data if isinstance(data, bytes) else json.dumps(data, indent=2).encode()); p.chmod(0o600)
    def budget(self):
        left = self.deadline - time.monotonic(); self.require(left > 0, "budget_exhausted"); return min(4, left)
    def adb(self, *cmd):
        p = subprocess.run(["adb", "-s", self.args.serial, *cmd], capture_output=True, timeout=self.budget()); self.save("adb.raw", p.stdout + b"\nSTDERR\n" + p.stderr); self.require(p.returncode == 0, "adb_failed"); return p.stdout.decode(errors="replace")
    def api(self, path, values=None):
        body = None if values is None else json.dumps( dict(domain="screensaver", values=values)).encode()
        req = urllib.request.Request(self.conn["base"].rstrip("/") + path, body, {"Authorization": "Bearer " + self.conn["token"], "Content-Type": "application/json"})
        try:
            with self.http.open(req, timeout=self.budget()) as response:
                raw = response.read()
        except urllib.error.HTTPError as error:
            self.save("http-error.raw", error.read()); raise RuntimeError("http_rejected") from None
        self.save("http.json", raw); data = json.loads(raw); self.require(data.get("ok") is True, "api_not_ok"); return data
    def profile(self):
        p = self.api("/remote/settings/profile"); self.require(p.get("profileComplete") is True, "incomplete_profile"); return p
    @staticmethod
    def controls(p):
        return {(d["id"], c["key"]): c for d in p["settings"]["domains"] for c in d["controls"]}
    def apply(self, values, restoring=False, validate_only=False):
        controls = self.controls(self.profile())
        for key, value in values.items():
            c = controls[("screensaver", key)]
            for candidate in (value, c["value"]):
                valid = (c["type"] == "bool" and type(candidate) is bool or c["type"] == "int" and type(candidate) is int and c["min"] <= candidate <= c["max"] and (candidate - c["min"]) % c.get("step", 1) == 0 or c["type"] == "enum" and candidate in [o["value"] for o in c["options"]])
                self.require(valid, "invalid_registry_value_" + key)
        if validate_only: return
        if not restoring:
            for key in values:
                self.changed.setdefault(key, controls[("screensaver", key)]["value"])
        self.require(set(self.api("/remote/settings", values)["applied"]) == set(values), "apply_keys"); after = self.controls(self.profile()); self.require(all(after[("screensaver", k)]["value"] == v for k, v in values.items()), "readback")
    def window(self):
        text = self.adb("shell", "dumpsys", "window", "windows"); blocks = re.split(r"(?m)^\s*Window #\d+ ", text)[1:]; own = [b for b in blocks if "Sys2023:dream" in b.split("\n", 1)[0]]; self.require(len(own) == 1, "one_actual_dream_window_required")
        b = own[0]; self.require(re.search(r"\bpackage(?:Name)?=" + re.escape(PKG) + r"\b", b), "dream_owner"); self.require("mHasSurface=true" in b and "mViewVisibility=0x0" in b, "visible_dream"); identity = re.search(r"Window\{([^ }]+)", b).group(1)
        pid = int(re.search(r"mSession=Session\{[^ }]+\s+(\d+):", b).group(1)); self.require(str(pid) == self.adb("shell", "pidof", PKG).strip(), "same_owner_pid"); self.require(self.identity in (None, (identity, pid)), "dream_identity_changed")
        attrs = re.split(r"\n\s*m[A-Z]", b.split("mAttrs=", 1)[1], maxsplit=1)[0]; brightness = re.search(r"\bsbrt=(-?\d+(?:\.\d+)?)", attrs); return time.monotonic() - self.started, float(brightness.group(1)) if brightness else -1, identity, pid
    def dream(self):
        text = self.adb("shell", "dumpsys", "dreams"); return bool(re.search(r"mCurrentDream[^\n]*" + re.escape(COMPONENT), text))
    def observer(self):
        states = self.api("/diag")["hardware"]["nightClockObservers"]; states = [s for s in states if s["host"] == "PhotoDreamService" and s["registered"]]
        self.require(len(states) == 1 and states[0]["sensorAvailable"] and states[0]["requested"] and type(states[0]["lux"]) in (float, int) and 0 <= states[0]["lux"] <= 20 and states[0]["readings"] > 0, "real_dark_sensor_required"); return states[0]
    def ramp(self, name, target, direction, seed):
        samples, stop = [seed[:2]], time.monotonic() + 8
        while time.monotonic() < stop:
            sample = self.window(); samples.append(sample[:2])
            if abs(sample[1] - target) < 1e-5:
                break
            time.sleep(min(.04, self.budget()))
        self.save(name + ".json", samples); positive = [(t, v) for t, v in samples if v >= 0]; values = [v for _, v in positive]; distinct = len({round(v, 5) for v in values if v != target and v != seed[1]})
        gaps = [b[0] - a[0] for a, b in zip(samples[1:], samples[2:])]; moved = next((t for t, v in positive if v != seed[1]), samples[0][0]); span = samples[-1][0] - moved; self.result[name + "IntermediateCount"] = distinct
        self.result[name + "SpanSeconds"] = round(span, 3)
        self.require(samples[-1][1] == target and distinct >= 4 and all(0 <= v <= 1 for v in values) and all(direction * (b - a) >= -1e-5 for a, b in zip(values, values[1:])) and 1.8 <= span <= 4 + max(gaps, default=0), name + "_ramp_failed")
        self.require(self.dream(), "same_active_dream"); return sample
    def capture(self, name):
        p = subprocess.run(["adb", "-s", self.args.serial, "exec-out", "screencap", "-p"], capture_output=True, timeout=self.budget()); self.save(name + ".png", p.stdout); self.save("capture-stderr.raw", p.stderr)
        self.require(p.returncode == 0 and p.stdout.startswith(b"\x89PNG"), "screenshot_failed")
    def run(self):
        self.conn = json.loads(self.args.connection.read_text()); u = urllib.parse.urlsplit(self.conn["base"])
        self.require(u.scheme in ("http", "https") and u.hostname and not u.username and not u.password and u.path in ("", "/") and not u.query and not u.fragment and isinstance(self.conn["token"], str) and self.conn["token"], "connection_invalid")
        self.require(self.adb("shell", "getprop", "ro.serialno").strip() == "FAMILY_PORTAL_SERIAL", "serial_guard"); self.require(self.adb("shell", "getprop", "ro.build.version.sdk").strip() == "29", "sdk_guard"); size = self.adb("shell", "wm", "size")
        self.require("Physical size: 2160x1440" in size and "Override size:" not in size, "display_guard"); paths = self.adb("shell", "pm", "path", PKG).strip().splitlines()
        self.require(len(paths) == 1 and paths[0].startswith("package:"), "single_apk_required"); apk = paths[0][8:]; digest = self.adb("shell", "sha256sum", apk).split()[0]; self.require(digest == self.args.expected_apk_sha256.lower(), "apk_hash_guard")
        self.require(self.api("/diag")["hardware"]["appApkPath"] == apk, "http_adb_binding"); self.guarded = True; self.original, self.sources = self.profile(), self.api("/remote/sources"); self.save("original-profile.json", self.original)
        self.save("original-sources.json", self.sources); self.was_dream = self.dream()
        self.apply(TEMP, validate_only=True); self.apply({"nightClockBrightness": 15}, validate_only=True)
        self.result.update(targetGuardPassed=True, apiLevel=29, apkSha256=digest, originalDreamActive=self.was_dream)
        if self.was_dream:
            self.window(); self.adb("shell", "input", "tap", "1080", "720")
            time.sleep(.5)
            self.require(not self.dream(), "prior_dream_exit")
        self.apply(TEMP)
        # Portal's shell lacks WRITE_DREAM_STATE; SystemUI owns the normal Dream entry.
        start = self.adb("shell", "am", "start", "-n", "com.android.systemui/.Somnambulator")
        self.require("Error" not in start and "Exception" not in start, "system_dream_entry")
        ready_until = time.monotonic() + 6
        while not self.dream() and time.monotonic() < ready_until:
            time.sleep(.2)
        self.require(self.dream(), "actual_immortal_dream")
        time.sleep(.3)
        seed = self.window(); self.identity = seed[2:]
        self.require(seed[1] == -1, "initial_system_default")
        dwell_start = time.monotonic(); self.observer()
        self.capture("before-dark")
        time.sleep(max(0, dwell_start + 17 - time.monotonic()))
        seed = self.ramp("darkEntry", .03, -1, seed)
        self.require(self.observer()["night"], "actual_night")
        self.capture("night")
        for name, value, target, direction in (("liveUp", 15, .15, 1), ("liveDown", 3, .03, -1)):
            self.apply({"nightClockBrightness": value})
            seed = self.ramp(name, target, direction, seed)
        self.apply({"darkRoomClock": False})
        self.ramp("registeredDisable", -1, 1, seed)
        self.capture("after-return")
        self.adb("shell", "input", "tap", "1080", "720")
        time.sleep(1)
        self.require(not self.dream(), "dream_reappeared")
        self.require(re.search(r"(?:mResumedActivity|topResumedActivity)[^\n]*" + re.escape(PKG + "/com.immortal.launcher.HomeActivity"), self.adb("shell", "dumpsys", "activity", "activities")), "home_required")
        self.result["success"] = True
    def cleanup(self):
        self.deadline = time.monotonic() + 25; self.result["modifiedSettingsCount"] = len(self.changed)
        def leave():
            if self.dream():
                self.window()
                self.adb("shell", "input", "tap", "1080", "720")
                time.sleep(.3)
                self.require(not self.dream(), "cleanup_dream_exit")
        steps = [("temporaryDreamExited", leave)] if self.changed else []
        steps += [("settingsRestored", lambda: self.apply(self.changed, restoring=True))] if self.changed else []
        if self.original:
            def compare():
                before, after = self.controls(self.original), self.controls(self.profile())
                self.result["profileCompareCount"] = len(before)
                self.result["profileMismatchCount"] = sum( after.get(k, {}).get("value") != c.get("value") for k, c in before.items())
                sources = self.api("/remote/sources")
                self.result["sourceCompareCount"] = len(self.sources["sources"])
                self.result["sourcesExact"] = sources == self.sources; self.result["sourceMismatchCount"] = sum(sources.get("sources", {}).get(k) != v for k, v in self.sources["sources"].items())
                self.require(self.result["profileMismatchCount"] == 0 and sources == self.sources, "restore_compare")
            steps.append(("profileAndSourcesRestored", compare))
        if self.guarded and self.changed:
            def home():
                leave(); time.sleep(1)
                self.require(not self.dream(), "cleanup_dream_reappeared")
                self.require(re.search(r"(?:mResumedActivity|topResumedActivity)[^\n]*" + re.escape(PKG + "/com.immortal.launcher.HomeActivity"), self.adb("shell", "dumpsys", "activity", "activities")), "cleanup_home_required")
            steps.append(("homeWithoutDream", home))
        for name, action in steps:
            try:
                action()
                self.result[name] = True
            except Exception as error:
                self.result[name] = False
                self.result[name + "Error"] = type(error).__name__
                self.result["success"] = False
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--connection", type=pathlib.Path, required=True)
    parser.add_argument("--serial", required=True, help="ADB transport serial; ro.serialno separately guarded")
    parser.add_argument("--expected-apk-sha256", required=True)
    parser.add_argument("--private-dir", type=pathlib.Path, required=True, help="NEW directory outside repository")
    args = parser.parse_args()
    parser.error("expected SHA256 must be 64 hex digits") if not re.fullmatch( r"[0-9a-fA-F]{64}", args.expected_apk_sha256) else None
    os.umask(0o077)
    root = pathlib.Path(__file__).resolve().parents[2]
    args.private_dir = args.private_dir.resolve()
    if args.private_dir == root or root in args.private_dir.parents or any((p / ".git").exists() for p in args.private_dir.parents):
        parser.error("--private-dir must be outside this repository")
    args.private_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
    v = Verifier(args)
    def timeout(*_):
        raise TimeoutError("bounded_timeout")
    signal.signal(signal.SIGALRM, timeout)
    signal.setitimer(signal.ITIMER_REAL, 150)
    try:
        v.run()
    except BaseException as error:
        v.result.update(success=False, failureType=type(error).__name__)
        v.save("failure-private.txt", str(error).encode())
    finally:
        signal.setitimer(signal.ITIMER_REAL, 25)
        try:
            v.cleanup()
        except BaseException as error:
            v.result.update(success=False, cleanupFailureType=type(error).__name__)
        signal.setitimer(signal.ITIMER_REAL, 0)
        v.result["elapsedSeconds"] = round(time.monotonic() - v.started, 3)
        (v.out / "verification.json").write_text(json.dumps(v.result, indent=2) + "\n")
    print("PASS" if v.result["success"] else "FAIL (private verification.json and raw traces retained)")
    return 0 if v.result["success"] else 1
if __name__ == "__main__":
    raise SystemExit(main())

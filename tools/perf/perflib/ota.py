"""The `ota-update` scenario: one production-path wireless update, timed phase by phase.

1. The Portal must be one official release behind. If it already runs the latest, the previous
   official release is installed with the harness's plain `adb install -r` (plus `-d` when Android
   refuses the downgrade). If that is refused too, the run stops as "not-behind": never an uninstall.
2. Developer mode off (the production state; the bench's preflight turned it on), then exactly what
   production does: fleet `POST /update/ota {"action":"check"}`, then `{"action":"install"}`.
3. Polled until Home is back: fleet `GET /update/ota` status while the old process lives, `pidof`,
   `dumpsys package` versionCode, the resumed activity. Then one `logcat -d -v epoch` dump (never
   cleared) gives the exact HearthpaneOta / installer / am_proc_* / Displayed / resume times.
4. Before/after snapshots: uptime + boot id (reboot?), pids of the launcher, Jarvis, SystemUI and
   system_server (what restarted?).

The session's restore then leaves the official latest installed and Home on top, as for every scenario.
"""

import json
import os

from . import PACKAGE, otaphases
from .builds import BuildError, install
from .engine import ScenarioError

OTA_PATH = "/update/ota"
POLL_GAP_S = 0.5
INSTALL_DEADLINE_S = 600
CHECK_PENDING_S = 90
HOME_GRACE_S = 90
BUSY_RETRIES = 20
BUSY_RETRY_S = 3
SETTLE_AFTER_PREVIOUS_S = 25
AFTER_HOME_S = 3
FLEET_BACK_RETRIES = 12
FLEET_BACK_S = 5

SNAPSHOT_COMMAND = ("cat /proc/uptime; cat /proc/sys/kernel/random/boot_id; "
                    f"for p in {' '.join(otaphases.WATCHED)}; do echo \"pid $p=$(pidof $p)\"; done; "
                    f"dumpsys package {PACKAGE} | grep -E 'versionCode=|versionName=|lastUpdateTime='")
POLL_COMMAND = f"echo \"pid=$(pidof {PACKAGE})\"; dumpsys package {PACKAGE} | grep -m1 versionCode="
LOGCAT_COMMAND = f"logcat -d -v epoch -b main,system,events | grep -E '{otaphases.LOGCAT_PATTERN}'"


class OtaRun:
    """Duck-types engine.Session for session.run_session: run(), status, result_parts()."""

    def __init__(self, device, builds, out_dir, log=print, settle_s=SETTLE_AFTER_PREVIOUS_S,
                 poll_gap_s=POLL_GAP_S, deadline_s=INSTALL_DEADLINE_S):
        self.d = device
        self.prev = next(b for b in builds if b.label == "P")
        self.latest = next(b for b in builds if b.label == "O")
        self.out = out_dir
        self.log = log
        self.settle_s = settle_s
        self.gap = poll_gap_s
        self.deadline_s = deadline_s
        self.status = "running"
        self.installs = []
        self.ota = {"mode": None, "outcome": None, "to": self._ver(self.latest)}
        self.polls = {}
        self.mac_marks = []
        self.statuses = []
        os.makedirs(os.path.join(out_dir, "raw"), exist_ok=True)

    @staticmethod
    def _ver(build):
        return {"tag": build.tag, "versionName": build.version_name, "versionCode": build.version_code,
                "sha256": build.sha256}

    def result_parts(self):
        return {"ota": self.ota, "installs": self.installs, "samples": []}

    # ---- top level -----------------------------------------------------------------------------
    def run(self):
        before = self._snapshot()
        code = before.get("versionCode") or 0
        if code and code < self.latest.version_code:
            self.ota.update(mode="natural", **{"from": {"versionName": before["versionName"], "versionCode": code}})
            self.log(f"naturally behind: {before['versionName']} ({code}) < latest {self.latest.version_name}")
        elif not self._install_previous():
            return
        else:
            before = self._snapshot()
        self.ota["before"] = before
        self._measure(before)

    def _install_previous(self):
        self.log(f"up to date; installing the previous official {self.prev.tag} to measure an update")
        try:
            sha = install(self.d, self.prev, self.log)
        except BuildError as e:
            self.status = "not-behind"
            self.ota.update(mode="forced", outcome="not-behind",
                            note=f"previous release could not be installed without an uninstall ({e}); not run")
            self.log(f"not behind: {e}")
            return False
        self.installs.append({"build": "P", "sha256": sha, "verified": sha == self.prev.sha256,
                              "versionName": self.d.version_name()})
        self.ota.update(mode="forced", **{"from": self._ver(self.prev)})
        self.d.runner.sleep(self.settle_s)
        self.d.go_home()
        self.d.runner.sleep(3)
        return True

    # ---- the update ----------------------------------------------------------------------------
    def _measure(self, before):
        p = self.d.fleet(["dev", "off"])
        if p.rc != 0:
            raise ScenarioError(f"fleetctl dev off failed (rc {p.rc})")
        self.d.runner.sleep(2)
        old_pid = before["pids"].get(PACKAGE, "")
        self._mark("check-sent")
        self.polls["check_sent"] = self.mac_marks[-1][1]
        reply = self._check()
        status = (reply.get("ota") or {}).get("status")
        available = (reply.get("ota") or {}).get("available") or {}
        self.ota["check_reply"] = reply.get("ota")
        if status == "up_to_date":
            self.status = "not-behind"
            self.ota.update(outcome="not-behind", note="check answered up_to_date; nothing to install")
            return
        if status != "available" or not available:
            raise ScenarioError(f"check answered {status}: {json.dumps(reply)[:200]}")
        self.ota["size_bytes"] = available.get("size")
        self._mark("install-sent")
        self.polls["install_sent"] = self.mac_marks[-1][1]
        reply = self._post({"action": "install"})
        if reply.get("ok") is not True:
            raise ScenarioError(f"install was not started: {json.dumps(reply)[:200]}")
        self._wait_until_home(old_pid, int(available.get("versionCode") or self.latest.version_code))
        self._finish()

    def _check(self):
        reply = self._post({"action": "check"})
        end = self.d.runner.now() + CHECK_PENDING_S
        while reply.get("pending") and self.d.runner.now() < end:
            self.d.runner.sleep(1)
            reply = self.d.raw_json("GET", OTA_PATH)
            if (reply.get("ota") or {}).get("status") != "checking":
                break
        self.polls["check_reply"] = self.d.runner.now()
        return reply

    def _post(self, body):
        for _ in range(BUSY_RETRIES):
            reply = self.d.raw_json("POST", OTA_PATH, body)
            if not reply:
                raise ScenarioError(f"{body['action']}: fleet {OTA_PATH} gave no JSON reply")
            if reply.get("error") == "dev_mode":
                raise ScenarioError("developer mode is still on; the updater refuses (not forcing)")
            if reply.get("error") != "busy":
                return reply
            self.log(f"  updater busy (a scheduled run?); retrying {body['action']} in {BUSY_RETRY_S}s")
            self.d.runner.sleep(BUSY_RETRY_S)
        raise ScenarioError(f"updater stayed busy; {body['action']} not sent")

    def _wait_until_home(self, old_pid, target_code):
        now = self.d.runner.now
        end, home_by = now() + self.deadline_s, None
        polls = self.polls
        while now() < end:
            t = now()
            pid, code = otaphases.parse_poll(self.d.shell(POLL_COMMAND))
            if pid != old_pid and "pid_gone" not in polls:
                polls["pid_gone"] = t
            if pid and pid != old_pid and "pid_new" not in polls:
                polls["pid_new"] = t
                home_by = t + HOME_GRACE_S
                self.log(f"  new launcher pid {pid} (was {old_pid})")
            if code == target_code and "version_new" not in polls:
                polls["version_new"] = t
                self.log(f"  versionCode {code} installed")
            if pid == old_pid:
                self._status_poll(t)
                if self.statuses and self.statuses[-1][1] == "failed":
                    raise ScenarioError(f"update failed: {json.dumps(self.ota.get('last_status'))[:200]}")
            elif pid and self.d.resumed_short() == "HomeActivity" and "home_top" not in polls:
                polls["home_top"] = now()
            if "home_top" in polls and "version_new" in polls:
                self.d.runner.sleep(AFTER_HOME_S)  # lets the Displayed / resume lines land
                return
            if home_by is not None and t > home_by:
                raise ScenarioError(f"new launcher process up but Home not resumed within {HOME_GRACE_S}s")
            self.d.runner.sleep(self.gap)
        raise ScenarioError(f"update not finished within {self.deadline_s}s")

    def _status_poll(self, t):
        reply = self.d.raw_json("GET", OTA_PATH, timeout=15)
        state = reply.get("ota") or {}
        status = state.get("status")
        if not status:
            return
        self.ota["last_status"] = state
        if not self.statuses or self.statuses[-1][1] != status:
            self.statuses.append((t, status))
            self.log(f"  status {status}")
        key = f"status_{status}"
        if key not in self.polls:
            self.polls[key] = t

    def _mark(self, name):
        before = self.d.runner.now()
        self.d.shell(f"log -t {otaphases.MARK_TAG} {name}")
        self.mac_marks.append((name.replace("-", "_"), before, self.d.runner.now()))

    # ---- after ---------------------------------------------------------------------------------
    def _finish(self):
        state = {}
        for _ in range(FLEET_BACK_RETRIES):  # the fleet server restarts with the new process
            state = self.d.raw_json("GET", OTA_PATH, timeout=15).get("ota") or {}
            if state:
                break
            self.d.runner.sleep(FLEET_BACK_S)
        after = self._snapshot()
        text = self.d.shell(LOGCAT_COMMAND, timeout=90)
        with open(os.path.join(self.out, "raw", "ota.logcat.txt"), "w", encoding="utf-8") as f:
            f.write(text)
        log_marks, extra = otaphases.markers(otaphases.parse_logcat(text))
        offset = otaphases.clock_offset(log_marks, self.mac_marks)
        events = otaphases.timeline(log_marks, self.polls, offset)
        pm = otaphases.local_epoch(after.get("lastUpdateTime"))
        zero = events.get("check_sent", {}).get("epoch")
        self.ota.update(
            outcome="updated" if after.get("versionCode") == self.latest.version_code else "not updated",
            after=after, after_ota=state, logcat=extra, clock_offset_s=None if offset is None else round(offset, 3),
            statuses=[{"t_mac": round(t - self.polls["check_sent"], 2), "status": s} for t, s in self.statuses],
            events=events, phases=otaphases.phases(events), totals=otaphases.totals(events),
            download=otaphases.download(events, self.ota.get("size_bytes")),
            restarts=otaphases.restarts(self.ota["before"], after, extra.get("assistant_starts", [])),
            pm_last_update_t=round(pm - zero, 2) if pm is not None and zero is not None else None)
        self.status = "ok" if self.ota["outcome"] == "updated" else "error"
        for row in self.ota["phases"] + self.ota["totals"]:
            self.log(f"  {row.get('phase') or row.get('total')}: {row['seconds']} s")

    def _snapshot(self):
        return otaphases.parse_snapshot(self.d.shell(SNAPSHOT_COMMAND))

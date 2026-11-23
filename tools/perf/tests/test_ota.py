"""The ota-update scenario: logcat/poll parsing, the phase table, and the run against a fake Portal."""

import json
import tempfile
import unittest
from dataclasses import replace

from perflib import HOME_COMPONENT, PACKAGE, otaphases, report
from perflib.builds import Build, previous_official_tag
from perflib.device import Device
from perflib.engine import Options
from perflib.ota import LOGCAT_COMMAND, POLL_COMMAND, SNAPSHOT_COMMAND, OtaRun
from perflib.proc import Proc
from perflib.safety import FORBIDDEN
from perflib.session import resolve_builds, run_session
from tests.fakes import OFFICIAL_SHA, SERIAL, FakePortal, OpenLock

PREV_SHA = "c" * 64
PREV = Build("P", "official", "/cache/official/hearthpane-v1.326/hearthpane.apk", None, PREV_SHA, "1.326",
             "hearthpane-v1.326", 1326)
LATEST = Build("O", "official", "/cache/official/hearthpane-v1.327/hearthpane.apk", None, OFFICIAL_SHA, "1.327",
               "hearthpane-v1.327", 1327)
BOOT = "0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b"
OFFSET = 100.0  # device clock = Mac clock + 100 s in the fake


def log_line(epoch, tag, msg, pid=4242, level="I"):
    return f"{epoch:.3f}  {pid}  {pid} {level} {tag}: {msg}"


def sample_log(t0):
    """A device logcat for one update, check request at device time t0 (plus noise to ignore)."""
    return "\n".join([
        log_line(t0 - 60, "am_proc_start", f"[0,4242,10123,{PACKAGE},top-activity,{{x}}]", pid=900),  # downgrade
        log_line(t0 - 50, otaphases.OTA_TAG, "update available: 1.327 (1327), installed 1326"),  # an older check
        log_line(t0, otaphases.MARK_TAG, "check-sent", pid=7000),
        log_line(t0 + 0.9, otaphases.OTA_TAG, "update available: 1.327 (1327), installed 1326"),
        log_line(t0 + 2.0, otaphases.MARK_TAG, "install-sent", pid=7001),
        log_line(t0 + 2.8, otaphases.OTA_TAG, "update available: 1.327 (1327), installed 1326"),
        log_line(t0 + 11.8, otaphases.OTA_TAG, "installing 1.327 (1327)"),
        log_line(t0 + 12.0, "ImmortalFleet", f"committed install of {PACKAGE} (session 77, 51854 KiB); waiting"),
        log_line(t0 + 15.5, "ActivityManager", f"Force stopping {PACKAGE} appid=10123 user=0: installPackageLI",
                 pid=900),
        log_line(t0 + 15.6, "am_proc_died", f"[0,4242,{PACKAGE},0,2]", pid=900),
        log_line(t0 + 16.1, "am_proc_start", f"[0,5151,10123,{PACKAGE},top-activity,{{x}}]", pid=900),
        log_line(t0 + 18.4, "ActivityTaskManager", f"Displayed {PACKAGE}/{otaphases.HOME_CLASS}: +2s210ms", pid=900),
        log_line(t0 + 18.5, "wm_on_resume_called", f"[123,{otaphases.HOME_CLASS},RESUME_ACTIVITY]", pid=5151),
        "--------- beginning of events",
    ])


class ParsingTest(unittest.TestCase):
    def test_markers_follow_their_prerequisites(self):
        found, extra = otaphases.markers(otaphases.parse_logcat(sample_log(5000.0)))
        self.assertEqual(found["check_sent"], 5000.0)
        self.assertEqual(found["manifest_check"], 5000.9)   # not the check 50 s earlier
        self.assertEqual(found["manifest_install"], 5002.8)
        self.assertEqual(found["verified"], 5011.8)
        self.assertEqual(found["committed"], 5012.0)
        self.assertEqual(found["old_stopped"], 5015.5)
        self.assertEqual(found["old_died"], 5015.6)
        self.assertEqual(found["new_started"], 5016.1)      # not the downgrade's process start
        self.assertEqual(found["home_displayed"], 5018.4)
        self.assertEqual(found["home_resumed"], 5018.5)
        self.assertEqual(extra["new_pid"], 5151)
        self.assertEqual(extra["available"], {"versionName": "1.327", "versionCode": 1327})
        self.assertEqual(extra["displayed"], "+2s210ms")
        self.assertEqual(extra["assistant_starts"], [])

    def test_meta_home_gap_and_hearthpane_restore_are_marked(self):
        t0 = 5000.0
        extra_lines = "\n".join([
            log_line(t0 + 15.7, "am_on_resume_called", f"[0,{otaphases.STOCK_HOME_CLASS},RESUME_ACTIVITY]", pid=2318),
            log_line(t0 + 16.3, otaphases.RESTORE_TAG, "package replaced: restoring Home (start 1, accepted=true)",
                     pid=5151),
            log_line(t0 + 17.9, otaphases.RESTORE_TAG, "package replaced: RESTORED after 1 Home start(s)", pid=5151),
        ])
        found, extra = otaphases.markers(otaphases.parse_logcat(sample_log(t0) + "\n" + extra_lines))
        self.assertEqual(found["stock_resumed"], 5015.7)
        self.assertEqual(found["home_restore"], 5016.3)
        self.assertEqual(extra["replaced_home"], "package replaced: RESTORED after 1 Home start(s)")
        events = otaphases.timeline(found, {}, None)
        gap = next(p for p in otaphases.phases(events) if p["from"] == "stock_home_resumed")
        self.assertEqual(gap["seconds"], 2.8)  # Meta's Home on top until ours resumed

    def test_no_meta_gap_without_a_stock_resume(self):
        found, _ = otaphases.markers(otaphases.parse_logcat(sample_log(5000.0)))
        self.assertNotIn("stock_resumed", found)
        self.assertNotIn("home_restore", found)

    def test_device_filter_keeps_the_restore_lines(self):
        self.assertIn(otaphases.RESTORE_TAG, otaphases.LOGCAT_PATTERN)

    def test_missing_lines_fall_back_to_polls_on_the_device_clock(self):
        found, _ = otaphases.markers(otaphases.parse_logcat(
            log_line(10.0, otaphases.MARK_TAG, "check-sent") + "\n" + log_line(12.0, otaphases.MARK_TAG, "install-sent")))
        offset = otaphases.clock_offset(found, [("check_sent", 9.8 - OFFSET, 10.2 - OFFSET)])
        self.assertAlmostEqual(offset, OFFSET)
        polls = {"check_reply": 1.0 - OFFSET + 10.0, "status_downloading": 13.0 - OFFSET, "pid_new": 30.0 - OFFSET}
        ev = otaphases.timeline(found, polls, offset)
        self.assertEqual(ev["download_start"], {"epoch": 13.0, "source": "poll (first seen)", "t": 3.0})
        self.assertEqual(ev["new_process"]["t"], 20.0)
        self.assertEqual(ev["install_sent"]["source"], "logcat")
        self.assertNotIn("home_first_frame", ev)

    def test_phases_totals_and_throughput(self):
        found, _ = otaphases.markers(otaphases.parse_logcat(sample_log(5000.0)))
        ev = otaphases.timeline(found, {}, None)
        by = {p["phase"]: p["seconds"] for p in otaphases.phases(ev)}
        self.assertEqual(by["download + integrity check"], 9.0)
        self.assertEqual(by["old process gone -> new process"], 0.5)
        self.assertEqual(by["new process -> Home first frame"], 2.3)
        self.assertIsNone(by["verified -> package replaced"])  # poll-only event, no polls given
        tot = {t["total"]: t["seconds"] for t in otaphases.totals(ev)}
        self.assertEqual(tot["whole update: check -> Home first frame"], 18.4)
        self.assertEqual(tot["launcher down: old process gone -> Home first frame"], 2.8)
        dl = otaphases.download(ev, 54_000_000)
        self.assertEqual(dl["mb_per_s"], 6.0)

    def test_snapshot_poll_and_package(self):
        text = (f"12345.67 9999.0\n{BOOT}\npid {PACKAGE}=4242\npid com.portal.assistant=3131\n"
                "pid com.android.systemui=1200\npid system_server=900 \n"
                "    versionCode=1326 minSdk=28 targetSdk=34\n    versionName=1.326\n"
                "    lastUpdateTime=2026-11-22 12:03:04\n")
        snap = otaphases.parse_snapshot(text)
        self.assertEqual(snap["uptime_s"], 12345.67)
        self.assertEqual(snap["boot_id"], BOOT)
        self.assertEqual(snap["pids"][PACKAGE], "4242")
        self.assertEqual(snap["pids"]["system_server"], "900")
        self.assertEqual((snap["versionCode"], snap["versionName"], snap["lastUpdateTime"]),
                         (1326, "1.326", "2026-11-22 12:03:04"))
        self.assertEqual(otaphases.parse_poll("pid=\n    versionCode=1327 minSdk=28\n"), ("", 1327))
        self.assertEqual(otaphases.parse_poll("pid=5151 5152\n"), ("5151", None))

    def test_restarts(self):
        pids = {PACKAGE: "4242", otaphases.ASSISTANT: "3131", "com.android.systemui": "1200", "system_server": "900"}
        before = {"uptime_s": 100.0, "boot_id": BOOT, "pids": pids}
        after = {"uptime_s": 160.0, "boot_id": BOOT, "pids": {**pids, PACKAGE: "5151"}}
        r = otaphases.restarts(before, after, [])
        self.assertFalse(r["rebooted"])
        self.assertTrue(r["processes"][PACKAGE]["restarted"])
        self.assertFalse(r["processes"][otaphases.ASSISTANT]["restarted"])
        self.assertTrue(otaphases.restarts(before, {**after, "uptime_s": 20.0}, [])["rebooted"])

    def test_previous_official_tag_skips_prereleases_and_drafts(self):
        listing = [
            {"tagName": "v4", "isPrerelease": True, "isDraft": False, "publishedAt": "2026-11-22T20:00:00Z"},
            {"tagName": "v3", "isPrerelease": False, "isDraft": False, "publishedAt": "2026-11-22T18:00:00Z"},
            {"tagName": "v2b", "isPrerelease": False, "isDraft": True, "publishedAt": "2026-11-22T17:00:00Z"},
            {"tagName": "v2", "isPrerelease": False, "isDraft": False, "publishedAt": "2026-11-22T14:00:00Z"},
        ]
        self.assertEqual(previous_official_tag(listing, "v3"), "v2")
        self.assertIsNone(previous_official_tag(listing, "v2"))
        self.assertIsNone(previous_official_tag(listing, "nope"))


class OtaPortal(FakePortal):
    """Simulates an update: the install POST starts it, the launcher dies and restarts a few polls later."""

    def __init__(self, code=1327, refuse_downgrade=False, assistant_restarts=False, behind_reply=True):
        super().__init__(sha=OFFICIAL_SHA if code == 1327 else PREV_SHA)
        self.state.update(code=code, pid="4242", dev=True)
        self.refuse_downgrade = refuse_downgrade
        self.assistant_restarts = assistant_restarts
        self.behind_reply = behind_reply
        self.marks = {}
        self.install_at = None
        self.gets = 0
        self.posts = []

    def dev_now(self):
        return self.clock + OFFSET

    def run(self, argv, timeout=60, cwd=None):
        if argv[0] == "adb" and argv[3:4] == ["install"]:
            self.calls.append(list(argv))
            if "-d" not in argv and "v1.326" in argv[-1]:
                return Proc(1, "", "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]")
            if self.refuse_downgrade:
                return Proc(1, "", "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]")
            prev = "v1.326" in argv[-1]
            self.state.update(sha=PREV_SHA if prev else OFFICIAL_SHA, code=1326 if prev else 1327, pid="4300")
            return Proc(0, "Success\n")
        return super().run(argv, timeout, cwd)

    def _shell(self, cmd):
        s = self.state
        if cmd == SNAPSHOT_COMMAND:
            self.shells.append(cmd)
            jarvis = "3999" if self.assistant_restarts and self.install_at else "3131"
            return Proc(0, f"{self.clock:.2f} 1.0\n{BOOT}\npid {PACKAGE}={s['pid']}\npid com.portal.assistant={jarvis}\n"
                           "pid com.android.systemui=1200\npid system_server=900\n"
                           f"    versionCode={s['code']} minSdk=28\n    versionName=1.{s['code'] - 1000}\n"
                           "    lastUpdateTime=2026-11-22 12:03:04\n")
        if cmd == POLL_COMMAND:
            self.shells.append(cmd)
            if self.install_at is not None:
                age = self.dev_now() - self.install_at
                if age > 14:
                    s.update(code=1327, sha=OFFICIAL_SHA, pid="" if age < 16 else "5151")
            return Proc(0, f"pid={s['pid']}\n    versionCode={s['code']} minSdk=28\n")
        if cmd.startswith(f"log -t {otaphases.MARK_TAG} "):
            self.shells.append(cmd)
            self.marks[cmd.split()[-1]] = self.dev_now()
            return Proc(0, "")
        if cmd == LOGCAT_COMMAND:
            self.shells.append(cmd)
            return Proc(0, sample_log(self.marks["check-sent"]))
        return super()._shell(cmd)

    def _fleet(self, args):
        if args[:3] in (["raw", "POST", "/update/ota"], ["raw", "GET", "/update/ota"]):
            self.fleet_calls.append(list(args))
            if self.state["dev"]:
                return Proc(1, json.dumps({"ok": False, "error": "dev_mode"}))
            if args[1] == "POST":
                body = json.loads(args[3])
                self.posts.append(body)
                if body["action"] == "install":
                    self.install_at = self.dev_now()
                    return Proc(0, json.dumps({"ok": True, "status": "started", "ota": {"status": "checking"}}))
                if not self.behind_reply:
                    return Proc(0, json.dumps({"ok": True, "ota": {"status": "up_to_date", "available": None}}))
                return Proc(0, json.dumps({"ok": True, "ota": {"status": "available", "available": {
                    "versionCode": 1327, "versionName": "1.327", "size": 53098744}}}))
            self.gets += 1
            status = "downloading" if self.gets < 3 else "installing"
            if self.install_at and self.dev_now() - self.install_at > 16:
                status = "idle"
            return Proc(0, json.dumps({"ok": True, "ota": {"status": status}}))
        return super()._fleet(args)


class TwoReleases:
    def latest(self, label="O"):
        return replace(LATEST, label=label)

    def previous(self, label="P"):
        return PREV


class OtaRunTest(unittest.TestCase):
    def _run(self, portal):
        d = Device(SERIAL, OpenLock(), "/x/fleetctl", runner=portal)
        run = OtaRun(d, [PREV, LATEST], tempfile.mkdtemp(), log=lambda m: None, settle_s=0)
        run.run()
        return run

    def assert_safe(self, portal):
        for argv in portal.calls:
            self.assertIsNone(FORBIDDEN.search(" ".join(argv)), argv)
            self.assertNotIn("uninstall", " ".join(argv))

    def test_forced_update_is_timed_phase_by_phase(self):
        portal = OtaPortal(code=1327)
        run = self._run(portal)
        self.assertEqual(run.status, "ok", run.ota)
        ota = run.ota
        self.assertEqual((ota["mode"], ota["outcome"]), ("forced", "updated"))
        self.assertEqual(ota["from"]["versionCode"], 1326)
        installs = [c for c in portal.calls if c[3:4] == ["install"]]
        self.assertEqual(installs[-1], ["adb", "-s", SERIAL, "install", "-r", "-d", PREV.apk])  # -d only on refusal
        self.assertEqual(portal.posts, [{"action": "check"}, {"action": "install"}])  # production order, no force
        self.assertLess(portal.fleet_calls.index(["dev", "off"]),
                        next(i for i, c in enumerate(portal.fleet_calls) if c[:2] == ["raw", "POST"]))
        by = {p["phase"]: p["seconds"] for p in ota["phases"]}
        self.assertEqual(by["download + integrity check"], 9.0)
        self.assertEqual(ota["events"]["home_first_frame"]["source"], "logcat")
        self.assertEqual(ota["events"]["package_replaced"]["source"], "poll (first seen)")
        self.assertGreater(ota["events"]["package_replaced"]["t"], 2.0)
        self.assertAlmostEqual(ota["clock_offset_s"], OFFSET, delta=0.5)
        self.assertEqual(ota["download"]["bytes"], 53098744)
        r = ota["restarts"]
        self.assertFalse(r["rebooted"])
        self.assertTrue(r["processes"][PACKAGE]["restarted"])
        self.assertFalse(r["processes"][otaphases.ASSISTANT]["restarted"])
        self.assert_safe(portal)
        md = otaphases.markdown(ota)
        self.assertIn("| download + integrity check | download_start -> verified | 9.00 |", md)
        self.assertIn("Reboot: no", md)

    def test_naturally_behind_installs_nothing(self):
        portal = OtaPortal(code=1326)
        run = self._run(portal)
        self.assertEqual((run.status, run.ota["mode"]), ("ok", "natural"))
        self.assertFalse([c for c in portal.calls if c[3:4] == ["install"]])

    def test_jarvis_restart_is_reported(self):
        run = self._run(OtaPortal(code=1326, assistant_restarts=True))
        self.assertTrue(run.ota["restarts"]["processes"][otaphases.ASSISTANT]["restarted"])

    def test_refused_downgrade_is_not_behind_and_never_uninstalls(self):
        portal = OtaPortal(code=1327, refuse_downgrade=True)
        run = self._run(portal)
        self.assertEqual((run.status, run.ota["outcome"]), ("not-behind", "not-behind"))
        self.assertFalse(portal.posts)
        self.assert_safe(portal)

    def test_up_to_date_check_reply_is_not_behind(self):
        run = self._run(OtaPortal(code=1326, behind_reply=False))
        self.assertEqual(run.status, "not-behind")
        self.assertEqual(run.ota["outcome"], "not-behind")


class OtaSessionTest(unittest.TestCase):
    def test_session_runs_ota_then_restores_official(self):
        portal = OtaPortal(code=1327)
        official = TwoReleases()
        out = tempfile.mkdtemp()
        result, path = run_session(serial=SERIAL, scenario_name="ota-update", builds=[PREV, LATEST],
                                   options=Options(reps=1, settle_s=0), out_dir=out, lock=OpenLock(),
                                   fleetctl="/x/fleetctl", official=official, runner=portal, log=lambda m: None,
                                   own_lock=False)
        self.assertEqual(result["status"], "ok", result.get("error"))
        self.assertTrue(result["restore"]["ok"], result["restore"])
        self.assertEqual(portal.state["sha"], OFFICIAL_SHA)
        self.assertEqual(portal.state["top"], HOME_COMPONENT)
        md = report.markdown(report.load(path))
        self.assertIn("## OTA update (forced): 1.326 (1326) -> 1.327 (1327)", md)
        self.assertNotIn("## Frames", md)

    def test_resolve_builds_for_ota(self):
        builds = resolve_builds([], False, TwoReleases(), scenario_name="ota-update")
        self.assertEqual([b.label for b in builds], ["P", "O"])
        with self.assertRaises(Exception):
            resolve_builds(["/x.apk"], False, TwoReleases(), scenario_name="ota-update")


if __name__ == "__main__":
    unittest.main()

"""Keep-awake for a whole job (no idle frame / dream over Home) and its exact restore, crash included."""

import json
import os
import tempfile
import unittest

from perflib import awake
from perflib.device import Device
from perflib.engine import Options
from perflib.restore import restore
from perflib.safety import check_command
from perflib.session import preflight, run_session
from tests.fakes import BRANCH_SHA, OFFICIAL_SHA, SERIAL, FakeOfficial, FakePortal, OpenLock
from tests.test_engine import HomeTabsPortal

TIMEOUT = ("system", "screen_off_timeout")
NUDGE = f"input keyevent {awake.NUDGE_KEY}"


def device_for(portal):
    return Device(SERIAL, OpenLock(), "/x/fleetctl", runner=portal)


class PreflightTest(unittest.TestCase):
    def test_records_each_prior_on_disk_before_changing_it(self):
        out = tempfile.mkdtemp()
        seen_on_disk = []

        class Watching(FakePortal):
            def _shell(self, cmd):
                if cmd.startswith("settings put system screen_off_timeout"):
                    seen_on_disk.append(awake.load_priors(out).get("screen_off_timeout"))
                return super()._shell(cmd)

        portal = Watching(sha=OFFICIAL_SHA)
        priors = {}
        preflight(device_for(portal), priors, lambda m: None, out)
        self.assertEqual(seen_on_disk, ["60000"])
        self.assertEqual(priors["screen_off_timeout"], "60000")
        self.assertEqual(portal.settings[TIMEOUT], awake.NEVER_MS)
        on_disk = awake.load_priors(out)
        self.assertEqual(on_disk["screen_off_timeout"], "60000")
        self.assertNotIn("installed_sha256", on_disk)

    def test_unset_prior_is_recorded_as_none(self):
        portal = FakePortal(sha=OFFICIAL_SHA)
        del portal.settings[TIMEOUT]
        priors = {}
        preflight(device_for(portal), priors, lambda m: None, tempfile.mkdtemp())
        self.assertIn("screen_off_timeout", priors)
        self.assertIsNone(priors["screen_off_timeout"])

    def test_never_counts_as_never_for_the_idle_timer(self):
        # IdleFrameTiming.idleDelayMs: >= one day means the screen never times out -> no idle frame.
        self.assertGreaterEqual(int(awake.NEVER_MS), 24 * 60 * 60_000)


class ReleaseTest(unittest.TestCase):
    def _restore(self, portal, priors):
        return restore(device_for(portal), FakeOfficial(), priors, log=lambda m: None)

    def test_restore_puts_the_exact_priors_back(self):
        portal = FakePortal(sha=BRANCH_SHA)
        portal.settings[TIMEOUT] = awake.NEVER_MS
        r = self._restore(portal, {"music_volume": 3, "screen_off_timeout": "45000"})
        self.assertTrue(r["ok"], r)
        self.assertEqual(portal.settings[TIMEOUT], "45000")

    def test_unset_prior_is_deleted_not_written(self):
        portal = FakePortal(sha=BRANCH_SHA)
        portal.settings[TIMEOUT] = awake.NEVER_MS
        r = self._restore(portal, {"screen_off_timeout": None})
        self.assertTrue(r["ok"], r)
        self.assertNotIn(TIMEOUT, portal.settings)
        self.assertIn("settings delete system screen_off_timeout", portal.shells)

    def test_unknown_prior_is_left_untouched(self):
        portal = FakePortal(sha=BRANCH_SHA)
        r = self._restore(portal, {"music_volume": 3})
        self.assertTrue(r["ok"], r)
        self.assertFalse([c for c in portal.shells if "screen_off_timeout" in c])

    def test_a_value_that_does_not_stick_fails_the_step(self):
        class Stubborn(FakePortal):
            def _shell(self, cmd):
                if cmd.startswith("settings put system screen_off_timeout"):
                    self.shells.append(cmd)
                    from perflib.proc import Proc
                    return Proc(0, "")
                return super()._shell(cmd)

        portal = Stubborn(sha=BRANCH_SHA)
        portal.settings[TIMEOUT] = awake.NEVER_MS
        r = self._restore(portal, {"screen_off_timeout": "60000"})
        step = next(s for s in r["steps"] if s["step"] == "keep-awake off")
        self.assertFalse(step["ok"])
        self.assertFalse(r["ok"])

    def test_only_system_and_global_namespaces(self):
        self.assertLessEqual({ns for _, ns, _, _ in awake.SETTINGS}, {"system", "global"})
        with self.assertRaises(ValueError):
            awake._check("secure")


class SessionTest(unittest.TestCase):
    def _run(self, portal, name="home-tabs"):
        out = tempfile.mkdtemp()
        result, _ = run_session(serial=SERIAL, scenario_name=name, builds=[FakeOfficial().latest()],
                                options=Options(reps=1, settle_s=0), out_dir=out, lock=OpenLock(),
                                fleetctl="/x/fleetctl", official=FakeOfficial(), runner=portal,
                                log=lambda m: None, own_lock=False)
        return result, out

    def test_job_holds_awake_then_restores_exactly(self):
        portal = HomeTabsPortal(sha=OFFICIAL_SHA)
        result, out = self._run(portal)
        self.assertEqual(result["status"], "ok", result.get("error"))
        self.assertTrue(result["restore"]["ok"], result["restore"])
        self.assertEqual(portal.settings[TIMEOUT], "60000")
        self.assertEqual(result["priors"]["screen_off_timeout"], "60000")
        self.assertTrue(os.path.isfile(os.path.join(out, awake.PRIORS_FILE)))
        sh = portal.shells
        put = sh.index(f"settings put system screen_off_timeout {awake.NEVER_MS}")
        back = sh.index("settings put system screen_off_timeout 60000")
        self.assertTrue(all(put < i < back for i, c in enumerate(sh) if c.startswith("input tap")))
        self.assertFalse([c for c in sh if "settings" in c and " secure " in c])

    def test_failed_scenario_still_restores_keep_awake(self):
        portal = FakePortal(sha=OFFICIAL_SHA)  # no uiautomator dump: home-tabs cannot find its tabs
        result, _ = self._run(portal)
        self.assertEqual(result["status"], "error")
        self.assertIn("uiautomator", result["error"])
        self.assertTrue(result["restore"]["ok"], result["restore"])
        self.assertEqual(portal.settings[TIMEOUT], "60000")

    def test_nudge_before_every_step_and_never_inside_a_window(self):
        portal = HomeTabsPortal(sha=OFFICIAL_SHA)
        result, _ = self._run(portal)
        self.assertEqual(result["status"], "ok", result.get("error"))
        inside = False
        nudges = 0
        for cmd in portal.shells:
            if cmd.endswith("reset >/dev/null"):
                inside = True
            elif "framestats" in cmd:
                inside = False
            elif cmd == NUDGE:
                nudges += 1
                self.assertFalse(inside, "nudge inside a measured window")
        windows = sum(1 for c in portal.shells if c.endswith("reset >/dev/null"))
        self.assertEqual(nudges, windows)  # one per step, warm-up steps included
        check_command(NUDGE)  # the key is not refused by the safety filter

    def test_worker_can_restore_from_priors_file_alone(self):
        out = tempfile.mkdtemp()
        preflight(device_for(FakePortal(sha=OFFICIAL_SHA)), {}, lambda m: None, out)
        # the child is gone (no result.json): the worker restores from priors.json
        portal = FakePortal(sha=BRANCH_SHA)
        portal.settings[TIMEOUT] = awake.NEVER_MS
        r = restore(device_for(portal), FakeOfficial(), awake.load_priors(out), log=lambda m: None)
        self.assertTrue(r["ok"], r)
        self.assertEqual(portal.settings[TIMEOUT], "60000")
        with open(os.path.join(out, awake.PRIORS_FILE), encoding="utf-8") as f:
            self.assertEqual(json.load(f)["music_volume"], 3)

    def test_load_priors_missing_or_bad_is_empty(self):
        out = tempfile.mkdtemp()
        self.assertEqual(awake.load_priors(out), {})
        with open(os.path.join(out, awake.PRIORS_FILE), "w", encoding="utf-8") as f:
            f.write("[1, 2")
        self.assertEqual(awake.load_priors(out), {})


if __name__ == "__main__":
    unittest.main()

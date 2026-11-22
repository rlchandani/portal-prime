"""The restore sequence (fake adb/fleet) and that a failing scenario still restores."""

import json
import os
import tempfile
import unittest
from unittest import mock

from perflib import HOME_COMPONENT, notes
from perflib.builds import Build
from perflib.device import Device
from perflib.engine import Options
from perflib.restore import restore
from perflib.session import run_session
from tests.fakes import BRANCH_SHA, OFFICIAL_SHA, SERIAL, BackAwarePortal, FakeOfficial, FakePortal, OpenLock

CC = "com.immortal.launcher.debug/com.immortal.launcher.ControlCenterActivity"


def index_of(seq, pred):
    return next(i for i, x in enumerate(seq) if pred(x))


class RestoreSequenceTest(unittest.TestCase):
    def setUp(self):
        self.portal = FakePortal(sha=BRANCH_SHA, music=0, top=CC)
        self.device = Device(SERIAL, OpenLock(), "/x/fleetctl", runner=self.portal)
        self.official = FakeOfficial("/cache/official/hearthpane.apk")
        self.priors = {"music_volume": 3, "accelerometer_rotation": "1", "user_rotation": "0"}

    def test_full_sequence_in_order(self):
        r = restore(self.device, self.official, self.priors, log=lambda m: None)
        self.assertTrue(r["ok"], r)
        self.assertEqual([s["step"] for s in r["steps"]],
                         ["stop captures", "wm reset", "auto-rotate", "official release", "orientation forget",
                          "screensaver test source off", "cc_debug cleared", "music volume", "keep-awake off", "home", "dev off",
                          "verify"])
        sh = self.portal.shells
        self.assertIn("wm size reset; wm density reset", sh)
        self.assertIn("settings put system accelerometer_rotation 1", sh)
        self.assertIn("settings put system user_rotation 0", sh)
        self.assertIn("media volume --stream 3 --set 3", sh)
        self.assertIn(f"cmd package set-home-activity {HOME_COMPONENT}", sh)
        installs = [c for c in self.portal.calls if c[:4] == ["adb", "-s", SERIAL, "install"]]
        self.assertEqual(installs, [["adb", "-s", SERIAL, "install", "-r", "/cache/official/hearthpane.apk"]])
        actions = [json.loads(c[3]) for c in self.portal.fleet_calls if c[:1] == ["raw"]]
        self.assertEqual(actions, [{"action": "orientation", "request": "forget"},
                                   {"action": "screensaver_test_source", "enabled": False},
                                   {"action": "cc_debug", "dryRun": False, "testPlan": "clear", "haUnreachable": False}])
        self.assertIn(["dev", "off"], self.portal.fleet_calls)
        self.assertEqual(self.portal.state["sha"], OFFICIAL_SHA)
        self.assertEqual(self.portal.state["top"], HOME_COMPONENT)
        self.assertFalse(self.portal.state["dev"])
        self.assertEqual(self.portal.state["music"], 3)
        self.assertEqual(r["final"]["device_sha256"], OFFICIAL_SHA)
        # wm/rotation before the install; Home after the fleet cleanup; dev off last but the check.
        i_wm = index_of(sh, lambda c: c.startswith("wm size reset"))
        i_home = index_of(sh, lambda c: c.startswith("cmd package set-home-activity"))
        self.assertLess(i_wm, i_home)

    def test_no_force_stop_and_no_screenshot_ever(self):
        restore(self.device, self.official, self.priors, log=lambda m: None)
        flat = [" ".join(c) for c in self.portal.calls]
        self.assertFalse(any("force-stop" in c or "screencap" in c or "uninstall" in c for c in flat))

    def test_official_already_installed_is_not_reinstalled(self):
        self.portal.state["sha"] = OFFICIAL_SHA
        r = restore(self.device, self.official, self.priors, log=lambda m: None)
        self.assertTrue(r["ok"])
        self.assertFalse(any(c[3:4] == ["install"] for c in self.portal.calls if c[0] == "adb"))

    def test_every_step_runs_even_after_a_failure(self):
        self.portal.fail = {"wm size reset"}
        r = restore(self.device, self.official, self.priors, log=lambda m: None)
        self.assertFalse(r["ok"])
        failed = [s["step"] for s in r["steps"] if not s["ok"]]
        self.assertEqual(failed, ["wm reset"])
        self.assertIn(["dev", "off"], self.portal.fleet_calls)
        self.assertEqual(self.portal.state["music"], 3)

    def test_unknown_prior_volume_is_left_alone(self):
        r = restore(self.device, self.official, {}, log=lambda m: None)
        self.assertFalse(any("--set" in c for c in self.portal.shells if "media volume" in c))
        self.assertIn("unchanged", next(s for s in r["steps"] if s["step"] == "music volume")["detail"])


class SessionTest(unittest.TestCase):
    def setUp(self):
        self.out = tempfile.mkdtemp()
        self.notes = os.path.join(self.out, "notes.txt")
        patcher = mock.patch.object(notes, "notes_file", lambda serial: self.notes)
        patcher.start()
        self.addCleanup(patcher.stop)

    def _run(self, portal, scenario="cc-open", reps=2):
        official = FakeOfficial("/cache/official/hearthpane.apk")
        return run_session(serial=SERIAL, scenario_name=scenario, builds=[official.latest()],
                           options=Options(reps=reps, settle_s=0), out_dir=self.out, lock=OpenLock(),
                           fleetctl="/x/fleetctl", official=official, runner=portal, log=lambda m: None)

    def test_cc_open_runs_measures_and_restores(self):
        portal = BackAwarePortal(sha=OFFICIAL_SHA, music=3)
        result, path = self._run(portal)
        self.assertEqual(result["status"], "ok", result.get("error"))
        self.assertTrue(result["restore"]["ok"])
        self.assertEqual(sorted({s["step"] for s in result["samples"]}), ["back", "open"])
        self.assertEqual(len(result["samples"]), 4)  # 2 reps x (open, back); the warm-up rep is unmeasured
        self.assertTrue(os.path.isfile(path) and os.path.isfile(os.path.join(self.out, "result.md")))
        self.assertIn(["dev", "on"], portal.fleet_calls)
        self.assertEqual(portal.fleet_calls[-2:], [["dev", "off"], ["info"]])
        self.assertEqual(portal.state["music"], 3)
        self.assertIn("media volume --stream 3 --set 0", portal.shells)
        self.assertFalse(any("screencap" in " ".join(c) for c in portal.calls))
        with open(self.notes, encoding="utf-8") as f:
            self.assertIn("NO screenshots", f.read())

    def test_scenario_error_still_restores(self):
        portal = FakePortal(sha=BRANCH_SHA, music=3)  # BACK never returns Home here: the step's check fails
        result, _ = self._run(portal)
        self.assertEqual(result["status"], "error")
        self.assertIn("expected HomeActivity", result["error"])
        self.assertTrue(result["restore"]["ok"], result["restore"])
        self.assertEqual(portal.state["sha"], OFFICIAL_SHA)
        self.assertEqual(portal.state["music"], 3)
        self.assertFalse(portal.state["dev"])

    def test_signal_during_run_still_restores(self):
        from perflib.session import Interrupted

        portal = BackAwarePortal(sha=OFFICIAL_SHA, music=4)
        original = portal.sleep

        def interrupting_sleep(s):
            if s == 3 and not getattr(portal, "fired", False) and "media volume --stream 3 --set 0" in portal.shells:
                portal.fired = True
                raise Interrupted("signal 15")
            original(s)

        portal.sleep = interrupting_sleep
        result, _ = self._run(portal)
        self.assertEqual(result["status"], "interrupted")
        self.assertTrue(result["restore"]["ok"])
        self.assertEqual(portal.state["music"], 4)


if __name__ == "__main__":
    unittest.main()

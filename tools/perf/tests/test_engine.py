"""Scenario execution against the fake Portal: tap targets, measurement windows, the screenshot rule."""

import os
import tempfile
import unittest

from perflib import scenarios
from perflib.device import Device
from perflib.engine import Options, Session
from tests.fakes import OFFICIAL_SHA, SERIAL, FakeOfficial, FakePortal, OpenLock
from tests.test_uidump import HOME


class HomeTabsPortal(FakePortal):
    def _shell(self, cmd):
        if cmd.startswith("uiautomator dump"):
            self.shells.append(cmd)
            from perflib.proc import Proc
            return Proc(0, HOME)
        return super()._shell(cmd)


class EngineTest(unittest.TestCase):
    def _session(self, portal, name, **opts):
        d = Device(SERIAL, OpenLock(), "/x/fleetctl", runner=portal)
        return Session(d, name, scenarios.get(name), [FakeOfficial().latest()], Options(settle_s=0, **opts),
                       tempfile.mkdtemp(), log=lambda m: None)

    def test_home_tabs_never_dumps_inside_a_measured_window(self):
        portal = HomeTabsPortal(sha=OFFICIAL_SHA)
        s = self._session(portal, "home-tabs", reps=1)
        s.run()
        self.assertEqual(sorted({x["step"] for x in s.samples}), ["to-apps", "to-home", "to-tools"])
        self.assertEqual(len(s.samples), 12)  # 1 rep x 4 passes x 3 switches
        inside = False
        for cmd in portal.shells:
            if cmd.endswith("reset >/dev/null"):
                inside = True
            elif "framestats" in cmd:
                inside = False
            elif cmd.startswith("uiautomator"):
                self.assertFalse(inside, "uiautomator dump between gfxinfo reset and framestats")
        taps = [c for c in portal.shells if c.startswith("input tap")]
        self.assertIn("input tap 1105 1374", taps)
        self.assertIn("input tap 1312 1374", taps)
        self.assertIn("input tap 898 1374", taps)
        self.assertEqual(sum(1 for c in portal.shells if c.startswith("uiautomator")), 1)  # located once per install

    def test_cc_open_checks_back_outside_the_window(self):
        from tests.fakes import BackAwarePortal

        portal = BackAwarePortal(sha=OFFICIAL_SHA)
        s = self._session(portal, "cc-open", reps=2)
        s.run()
        self.assertEqual(len(s.samples), 4)
        inside = False
        for cmd in portal.shells:
            if cmd.endswith("reset >/dev/null"):
                inside = True
            elif "framestats" in cmd:
                inside = False
            elif "mResumedActivity" in cmd or cmd.startswith("uiautomator"):
                self.assertFalse(inside, f"{cmd} inside a measured window")

    def test_screenshot_requested_on_home_tabs_is_refused_by_code(self):
        portal = HomeTabsPortal(sha=OFFICIAL_SHA)
        s = self._session(portal, "home-tabs", reps=1, screenshots=True)
        s._screenshot("x", "k")
        self.assertIn("never takes screenshots", s.screenshots[0]["refused"])
        self.assertFalse(any("screencap" in " ".join(c) for c in portal.calls))

    def test_install_verifies_the_on_device_sha(self):
        portal = HomeTabsPortal(sha=OFFICIAL_SHA)
        s = self._session(portal, "home-tabs", reps=1)
        s.run()
        self.assertEqual(s.installs[0]["sha256"], OFFICIAL_SHA)
        self.assertTrue(s.installs[0]["verified"])
        self.assertTrue(all(not c[3:4] == ["shell"] or "force-stop" not in c[4] for c in portal.calls))
        self.assertTrue(os.path.isdir(os.path.join(s.out, "raw")))


if __name__ == "__main__":
    unittest.main()

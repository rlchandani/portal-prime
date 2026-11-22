"""rotation-tab-after: a turn, then the next Home tab 0.6 s / 3 s later; only the tab switch is measured."""

import json
import tempfile
import unittest

from perflib import scenarios
from perflib.device import Device
from perflib.engine import Options, ScenarioError, Session
from perflib.proc import Proc
from tests.fakes import OFFICIAL_SHA, SERIAL, FakeOfficial, FakePortal, OpenLock
from tests.test_uidump import dump, node

LANDSCAPE = dump(node("[100,750][160,780]", t="Home"), node("[300,750][360,780]", t="Apps"),
                 node("[500,750][560,780]", t="Tools"))
PORTRAIT = dump(node("[60,1230][120,1260]", t="Home"), node("[200,1230][260,1260]", t="Apps"),
                node("[340,1230][400,1260]", t="Tools"))
TAP = {"landscape": {"Home": "130 765", "Apps": "330 765", "Tools": "530 765"},
       "portrait": {"Home": "90 1245", "Apps": "230 1245", "Tools": "370 1245"}}


class TurningPortal(FakePortal):
    """Answers uiautomator with the layout of the last fleet orientation; logs (orientation, cmd)."""

    def __init__(self, **kw):
        super().__init__(**kw)
        self.orientation = "landscape"
        self.trail = []

    def _shell(self, cmd):
        self.trail.append((self.orientation, cmd))
        if cmd.startswith("uiautomator dump"):
            self.shells.append(cmd)
            return Proc(0, PORTRAIT if self.orientation == "portrait" else LANDSCAPE)
        return super()._shell(cmd)

    def _fleet(self, args):
        if args[:3] == ["raw", "POST", "/action"]:
            body = json.loads(args[3])
            if body["action"] == "orientation" and body["request"] in ("portrait", "landscape"):
                self.orientation = body["request"]
                self.trail.append((self.orientation, f"turn {body['request']}"))
        return super()._fleet(args)


def session(portal, reps=1):
    d = Device(SERIAL, OpenLock(), "/x/fleetctl", runner=portal)
    return Session(d, "rotation-tab-after", scenarios.get("rotation-tab-after"), [FakeOfficial().latest()],
                   Options(reps=reps, settle_s=0), tempfile.mkdtemp(), log=lambda m: None)


class RotationTabAfterTest(unittest.TestCase):
    def test_scenario_shape(self):
        sc = scenarios.get("rotation-tab-after")
        self.assertFalse(sc["screenshots"])
        self.assertEqual(sc["session"], [("mini", "landscape")])
        self.assertEqual({s["name"] for s in sc["steps"]}, {"tab-0.6s", "tab-3s"})
        delays = sorted(s["pre_wait"] for s in sc["steps"])
        self.assertEqual(delays, [0.6, 0.6, 0.6, 3.0, 3.0, 3.0])
        # each step turns, and the pass ends where it started: landscape, Home tab
        self.assertEqual(sc["steps"][-1]["pre"], [("orientation", "landscape")])
        self.assertEqual(sc["steps"][-1]["do"], [("tap_label", "Home")])
        self.assertIn("rotation-tab-after", [n for n, _, _ in scenarios.listing()])

    def test_runs_and_samples_only_the_tab_switches(self):
        portal = TurningPortal(sha=OFFICIAL_SHA)
        s = session(portal)
        s.run()
        self.assertEqual(len(s.samples), 12)  # 1 rep x 2 passes x 6 steps
        self.assertEqual({x["step"] for x in s.samples}, {"tab-0.6s", "tab-3s"})
        for x in s.samples:
            self.assertIn("frame_ms", x)
            self.assertIn("do_after_s", x)
            self.assertEqual(x["flags"], [0])

    def test_window_opens_after_the_turn_and_the_delay(self):
        portal = TurningPortal(sha=OFFICIAL_SHA)
        s = session(portal)
        slept = []
        real_sleep = portal.sleep
        portal.sleep = lambda sec: (slept.append(sec), real_sleep(sec))[1]
        s.run()
        cmds = [c for _, c in portal.trail]
        resets = [i for i, c in enumerate(cmds) if c.endswith("reset >/dev/null")]
        self.assertEqual(len(resets), 6 + 12)  # warm-up rep (1 pass) + measured rep (2 passes)
        for i in resets:
            before = cmds[:i]
            last_turn = max(j for j, c in enumerate(before) if c.startswith("turn "))
            # nothing measured-window-like between the turn and the reset, and the tap comes after it
            self.assertFalse(any(c.startswith(("input tap", "uiautomator")) for c in cmds[last_turn:i]))
            self.assertTrue(cmds[i + 1].startswith("input tap"), cmds[i + 1])
        self.assertEqual(sorted({x for x in slept if x in (0.6, 3.0)}), [0.6, 3.0])

    def test_taps_use_the_layout_of_the_orientation_turned_to(self):
        portal = TurningPortal(sha=OFFICIAL_SHA)
        session(portal).run()
        taps = [(o, c[len("input tap "):]) for o, c in portal.trail if c.startswith("input tap")]
        self.assertTrue(taps)
        for orientation, xy in taps:
            self.assertIn(xy, TAP[orientation].values(), (orientation, xy))
        # the scripted order: portrait->Apps, landscape->Tools, portrait->Home, ...
        measured = [t for t in taps][-6:]
        self.assertEqual(measured, [("portrait", TAP["portrait"]["Apps"]), ("landscape", TAP["landscape"]["Tools"]),
                                    ("portrait", TAP["portrait"]["Home"]), ("landscape", TAP["landscape"]["Apps"]),
                                    ("portrait", TAP["portrait"]["Tools"]), ("landscape", TAP["landscape"]["Home"])])

    def test_no_dump_between_a_turn_and_its_tap(self):
        portal = TurningPortal(sha=OFFICIAL_SHA)
        session(portal).run()
        dumps = sum(1 for _, c in portal.trail if c.startswith("uiautomator"))
        self.assertEqual(dumps, 2)  # one per orientation, in setup, then cached

    def test_unlocated_target_after_a_turn_aborts_before_turning(self):
        portal = TurningPortal(sha=OFFICIAL_SHA)
        s = session(portal)
        step = {"name": "x", "pre": [("orientation", "portrait")], "pre_wait": 0.6,
                "do": [("tap_label", "Apps")], "wait": 1}
        with self.assertRaises(ScenarioError):
            s._step(step, "O", 1, 1)
        self.assertFalse(any(c.startswith("turn ") for _, c in portal.trail))


if __name__ == "__main__":
    unittest.main()

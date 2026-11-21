"""Unit tests for the suite's pure parts (no devices): python3 -m unittest discover -s tools/smoke/tests"""

import os
import sys
import unittest
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from smokelib import PACKAGE, logscan, uiparse  # noqa: E402
from smokelib.release import ReleaseError, parse_manifest  # noqa: E402

LOG = [
    "--------- beginning of main",
    "11-20 17:03:13.000  1249  1279 I ActivityManager: Start proc 7433:com.immortal.launcher.debug/u0a45 for activity",
    "11-20 17:03:20.287  7433  7561 I HearthpaneControl: control center registry: 3 floors, 23 areas, 821 entities, 135 sensor classes",
    "11-20 17:05:00.000  7433  7433 E AndroidRuntime: FATAL EXCEPTION: main",
    "11-20 17:05:00.001  7433  7433 E AndroidRuntime: Process: com.other.app, PID: 7433",
    "11-20 17:06:00.000  9000  9000 E AndroidRuntime: FATAL EXCEPTION: worker",
    "11-20 17:06:00.001  9000  9000 E AndroidRuntime: Process: com.immortal.launcher.debug, PID: 9000",
    "11-20 17:07:00.000  1249  1260 E ActivityManager: ANR in com.immortal.launcher.debug (HomeActivity)",
    "11-20 17:08:00.000  9100  9100 E AndroidRuntime: FATAL EXCEPTION: main",
    "11-20 17:08:00.001  9100  9100 E AndroidRuntime: Process: com.immortal.launcher.debug:fleet, PID: 9100",
]


class LogscanTest(unittest.TestCase):
    def setUp(self):
        self.parsed = logscan.parse(LOG)

    def test_parse_skips_banners(self):
        self.assertEqual(len(self.parsed), len(LOG) - 1)
        self.assertEqual(self.parsed[0].tag, "ActivityManager")

    def test_crashes_only_for_package(self):
        found = logscan.crashes(self.parsed, PACKAGE)
        self.assertEqual(len(found), 3)  # not com.other.app's crash; yes a Hearthpane sub-process
        self.assertIn("FATAL EXCEPTION", found[0])
        self.assertIn("ANR in", found[1])
        self.assertTrue(found[2].startswith("11-20 17:08:00.000 FATAL"))

    def test_since_filters_by_time(self):
        self.assertEqual(logscan.crashes(logscan.since(self.parsed, "11-20 17:06:30.000"), PACKAGE)[0][:18],
                         "11-20 17:07:00.000")

    def test_registry_entities(self):
        self.assertEqual(logscan.registry_entities(self.parsed), (821, "11-20 17:03:20.287"))
        self.assertEqual(logscan.registry_entities(self.parsed, pid=1), (None, ""))

    def test_process_starts(self):
        self.assertEqual(len(logscan.process_starts(self.parsed, PACKAGE)), 1)

    def test_utc_offset_rounds_to_quarter_hour(self):
        now = datetime(2026, 11, 21, 1, 10, 0, tzinfo=timezone.utc)
        self.assertEqual(logscan.utc_offset(" 17:10:07 up 3 days", now), timedelta(hours=-8))
        self.assertIsNone(logscan.utc_offset("no clock", now))

    def test_local_ts(self):
        ms = int(datetime(2026, 11, 21, 1, 3, 13, tzinfo=timezone.utc).timestamp() * 1000)
        self.assertEqual(logscan.local_ts(ms, timedelta(hours=-8)), "11-20 17:03:13.000")

    def test_parse_kv(self):
        self.assertEqual(logscan.parse_kv("dryRun=false testPlan=true rooms=0"),
                         {"dryRun": "false", "testPlan": "true", "rooms": "0"})


CALLS_XML = """<hierarchy>
<node text="" content-desc="Living Area" bounds="[104,196][284,376]" clickable="true"/>
<node text="Living Area" content-desc="" bounds="[313,239][533,291]" clickable="false"/>
<node text="Available" content-desc="" bounds="[340,298][456,334]" clickable="false"/>
<node text="Rohit Office" content-desc="" bounds="[1348,239][1587,291]" clickable="false"/>
<node text="Offline" content-desc="" bounds="[1375,298][1491,334]" clickable="false"/>
<node text="" content-desc="Washer done" bounds="[1573,741][1853,809]" clickable="true"/>
<node text="Messenger &amp; WhatsApp" content-desc="" bounds="[0,0][1,1]" clickable="false"/>
</hierarchy>"""

WINDOWS = """
  Window #1 Window{7bcdd1f u0 com.immortal.launcher.debug}:
    mAttrs={(0,0)(wrapx68) gr=TOP CENTER ty=2032 fmt=TRANSLUCENT
    mViewVisibility=0x8 mHaveFrame=true
  Window #2 Window{1111111 u0 com.immortal.launcher.debug/com.immortal.launcher.ControlCenterActivity}:
    mAttrs={(0,0)(fillxwrap) ty=APPLICATION_PANEL fmt=TRANSLUCENT
    mViewVisibility=0x0 mHaveFrame=true
  Window #3 Window{2222222 u0 com.immortal.launcher.debug/com.immortal.launcher.ControlCenterActivity}:
    mAttrs={(0,0)(fillxfill) ty=BASE_APPLICATION
    mViewVisibility=0x0 mHaveFrame=true
  Window #4 Window{3333333 u0 com.other/com.other.Main}:
    mAttrs={(0,0)(fillxfill) ty=2038
    mViewVisibility=0x0 mHaveFrame=true
"""


class UiparseTest(unittest.TestCase):
    def test_nodes_unescape_and_center(self):
        nodes = uiparse.nodes(CALLS_XML)
        self.assertEqual(nodes[-1].text, "Messenger & WhatsApp")
        self.assertEqual(uiparse.by_desc(nodes, "Washer done").center, (1713, 775))

    def test_roster(self):
        got = uiparse.roster(uiparse.nodes(CALLS_XML), ["Living Area", "Rohit Office", "Kitchen"])
        self.assertEqual(got, {"Living Area": "Available", "Rohit Office": "Offline", "Kitchen": None})

    def test_visible_overlays_ignore_hidden_and_other_apps(self):
        found = uiparse.visible_overlays(WINDOWS, ("com.immortal.launcher.debug",))
        self.assertEqual(found, (("APPLICATION_PANEL",
                                  "com.immortal.launcher.debug/com.immortal.launcher.ControlCenterActivity"),))

    def test_count_visible_windows(self):
        self.assertEqual(uiparse.count_visible_windows(WINDOWS, "ControlCenterActivity"), 2)


class ManifestTest(unittest.TestCase):
    GOOD = ('{"schema":1,"packageName":"com.immortal.launcher.debug","versionCode":289,"versionName":"1.295",'
            '"sha256":"' + "a" * 64 + '"}')

    def test_good(self):
        r = parse_manifest("hearthpane-v1.295", self.GOOD)
        self.assertEqual((r.version_code, r.version_name), (289, "1.295"))

    def test_rejects_other_package_and_bad_sha(self):
        with self.assertRaises(ReleaseError):
            parse_manifest("t", self.GOOD.replace("launcher.debug", "evil"))
        with self.assertRaises(ReleaseError):
            parse_manifest("t", self.GOOD.replace("a" * 64, "xyz"))
        with self.assertRaises(ReleaseError):
            parse_manifest("t", "not json")


if __name__ == "__main__":
    unittest.main()

"""--atrace-categories / --atrace-buffer-kb: validation, the job record, and the device command."""

import os
import sys
import tempfile
import unittest

from perflib import PACKAGE, scenarios
from perflib.device import Device
from perflib.engine import (ATRACE_BUFFER_KB, ATRACE_CATEGORIES, Options, Session, atrace_start_command,
                            check_atrace_buffer_kb, parse_atrace_categories)
from tests.fakes import OFFICIAL_SHA, SERIAL, FakeOfficial, OpenLock
from tests.test_engine import HomeTabsPortal

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import bench  # noqa: E402


def _submit(*extra):
    apk = tempfile.NamedTemporaryFile(suffix=".apk", delete=False)
    apk.close()
    args = bench.parse_args(["submit", "--device", SERIAL, "--scenario", "home-tabs", "--apk", apk.name, *extra])
    bench._validate(args)
    return bench._job_from_args(args)


class AtraceOptionsTest(unittest.TestCase):
    def test_categories_parse_commas_and_spaces_and_drop_duplicates(self):
        self.assertEqual(parse_atrace_categories("gfx,view webview, sched,gfx"), ("gfx", "view", "webview", "sched"))

    def test_categories_refuse_anything_but_plain_names(self):
        for bad in ["", " , ", "gfx;reboot", "gfx view && rm -rf /", "Gfx", "$(id)", "-b 1", "a" * 40]:
            with self.assertRaises(ValueError, msg=bad):
                parse_atrace_categories(bad)
        with self.assertRaises(ValueError):
            parse_atrace_categories(",".join(f"c{i}" for i in range(25)))

    def test_buffer_bounds(self):
        self.assertEqual(check_atrace_buffer_kb(1024), 1024)
        self.assertEqual(check_atrace_buffer_kb(262144), 262144)
        for bad in [1023, 262145, 0, -1, True, "4096", 4096.0]:
            with self.assertRaises(ValueError, msg=repr(bad)):
                check_atrace_buffer_kb(bad)

    def test_start_command_defaults_match_the_previous_capture(self):
        self.assertEqual(atrace_start_command(ATRACE_CATEGORIES, ATRACE_BUFFER_KB),
                         f"atrace --async_start -b 16384 -a {PACKAGE} gfx view >/dev/null 2>&1")

    def test_start_command_revalidates(self):
        with self.assertRaises(ValueError):
            atrace_start_command(("gfx", "view;reboot"), 4096)
        with self.assertRaises(ValueError):
            atrace_start_command(("gfx",), 10 ** 9)

    def test_submit_records_defaults_and_custom_values(self):
        job = _submit("--atrace")
        self.assertEqual((job["atrace_categories"], job["atrace_buffer_kb"]), (["gfx", "view"], 16384))
        job = _submit("--atrace", "--atrace-categories", "gfx,view,webview,sched", "--atrace-buffer-kb", "65536")
        self.assertEqual((job["atrace_categories"], job["atrace_buffer_kb"]), (["gfx", "view", "webview", "sched"], 65536))

    def test_submit_refuses_bad_values_and_options_without_atrace(self):
        for extra in (["--atrace", "--atrace-categories", "gfx;id"], ["--atrace", "--atrace-buffer-kb", "10"],
                      ["--atrace-categories", "gfx"], ["--atrace-buffer-kb", "4096"]):
            with self.assertRaises(ValueError, msg=extra):
                _submit(*extra)

    def test_session_starts_atrace_with_the_requested_categories(self):
        portal = HomeTabsPortal(sha=OFFICIAL_SHA)
        d = Device(SERIAL, OpenLock(), "/x/fleetctl", runner=portal)
        s = Session(d, "home-tabs", scenarios.get("home-tabs"), [FakeOfficial().latest()],
                    Options(settle_s=0, reps=1, atrace=True, atrace_categories=("gfx", "view", "webview", "sched"),
                            atrace_buffer_kb=65536), tempfile.mkdtemp(), log=lambda m: None)
        s.run()
        starts = [c for c in portal.shells if c.startswith("atrace --async_start")]
        self.assertTrue(starts)
        self.assertEqual(set(starts),
                         {f"atrace --async_start -b 65536 -a {PACKAGE} gfx view webview sched >/dev/null 2>&1"})


if __name__ == "__main__":
    unittest.main()

"""Safety rules: the screenshot guard, the command filter, BACK, and the device lock."""

import os
import tempfile
import unittest

from perflib import scenarios
from perflib.device import Device
from perflib.lock import DeviceLock, HeldLock, LockError, holder
from perflib.safety import SafetyRefusal, back_allowed, check_command, screenshot_allowed
from tests.fakes import SERIAL, FakePortal, OpenLock

P = "com.immortal.launcher.debug/com.immortal.launcher."


class ScreenshotGuardTest(unittest.TestCase):
    def test_allow_listed_screens_when_the_scenario_allows(self):
        for act in ("ImmortalSettingsActivity", "CallsActivity", "StoreActivity"):
            self.assertTrue(screenshot_allowed(True, P + act, False)[0], act)

    def test_scenario_without_screenshots_always_refuses(self):
        for act in ("ImmortalSettingsActivity", "HomeActivity", "ControlCenterActivity"):
            self.assertFalse(screenshot_allowed(False, P + act, True)[0])

    def test_home_cc_and_others_refused(self):
        for act in ("HomeActivity", "ControlCenterActivity", "HaDashboardActivity"):
            self.assertFalse(screenshot_allowed(True, P + act, True)[0], act)

    def test_photo_frame_only_with_the_test_source(self):
        self.assertTrue(screenshot_allowed(True, P + "PhotoFramePreviewActivity", True)[0])
        self.assertFalse(screenshot_allowed(True, P + "PhotoFramePreviewActivity", False)[0])

    def test_other_packages_and_the_dream_refused(self):
        self.assertFalse(screenshot_allowed(True, "com.portal.assistant/.CardActivity", True)[0])
        self.assertFalse(screenshot_allowed(True, "", True)[0])

    def test_no_screenshot_scenarios_are_declared_so(self):
        for name in ("home-tabs", "screensaver-household", "cc-open", "cc-switch"):
            self.assertFalse(scenarios.get(name)["screenshots"], name)
        self.assertFalse(scenarios.get("mini-size:portrait:home-tabs")["screenshots"])

    def test_device_capture_refuses_without_running_screencap(self):
        portal = FakePortal(top=P + "HomeActivity")
        d = Device(SERIAL, OpenLock(), "/x/fleetctl", runner=portal)
        with self.assertRaises(SafetyRefusal):
            d.capture_png(True, True)
        with self.assertRaises(SafetyRefusal):
            Device(SERIAL, OpenLock(), "/x/fleetctl", runner=FakePortal(top=P + "ImmortalSettingsActivity")
                   ).capture_png(False, False)
        self.assertFalse(any("screencap" in " ".join(c) for c in portal.calls))

    def test_device_capture_on_settings_when_allowed(self):
        portal = FakePortal(top=P + "ImmortalSettingsActivity")
        data = Device(SERIAL, OpenLock(), "/x/fleetctl", runner=portal).capture_png(True, False)
        self.assertTrue(data.startswith(b"\x89PNG"))


class CommandFilterTest(unittest.TestCase):
    def test_refused(self):
        for cmd in ("screencap -p /sdcard/x.png", "screenrecord /sdcard/a.mp4", "am force-stop com.x",
                    "reboot", "pm clear com.x", "pm uninstall com.x", "kill 123", "svc power stayon true",
                    "logcat -c", "logcat -b all -c", "rm -rf /sdcard"):
            with self.assertRaises(SafetyRefusal, msg=cmd):
                check_command(cmd)

    def test_allowed(self):
        for cmd in ("dumpsys gfxinfo com.immortal.launcher.debug framestats", "input tap 1 2",
                    "logcat -d -v monotonic -s ImmortalEngine:I", "rm -f /data/local/tmp/perf-bench-ui.xml",
                    "wm size reset; wm density reset"):
            check_command(cmd)

    def test_shell_refuses_before_running(self):
        portal = FakePortal()
        with self.assertRaises(SafetyRefusal):
            Device(SERIAL, OpenLock(), "/x/fleetctl", runner=portal).shell("am force-stop x")
        self.assertEqual(portal.calls, [])

    def test_back_only_on_hearthpane_non_home(self):
        self.assertTrue(back_allowed(P + "ControlCenterActivity")[0])
        self.assertFalse(back_allowed(P + "HomeActivity")[0])
        self.assertFalse(back_allowed("com.portal.assistant/.JarvisActivity")[0])


class LockTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()

    def test_acquire_and_release_own(self):
        lock = DeviceLock(SERIAL, "perf-bench job1", self.root)
        self.assertTrue(lock.try_acquire("worker-pid 1"))
        self.assertTrue(holder(SERIAL, self.root).startswith("perf-bench job1 worker-pid 1"))
        lock.check()
        self.assertTrue(lock.release())
        self.assertFalse(os.path.exists(lock.path))

    def test_held_by_another_agent_means_no_device_command(self):
        os.mkdir(os.path.join(self.root, f"portal-lock-{SERIAL}"))
        with open(os.path.join(self.root, f"portal-lock-{SERIAL}", "owner"), "w") as f:
            f.write("home-tabs-7 Sun Nov 22\n")
        lock = DeviceLock(SERIAL, "perf-bench job2", self.root)
        self.assertFalse(lock.try_acquire())
        portal = FakePortal()
        with self.assertRaises(LockError):
            Device(SERIAL, lock, "/x/fleetctl", runner=portal).resumed()
        self.assertEqual(portal.calls, [])
        self.assertFalse(lock.release())  # never removes another agent's lock
        self.assertEqual(holder(SERIAL, self.root), "home-tabs-7 Sun Nov 22")

    def test_waiting_retries_until_free(self):
        path = os.path.join(self.root, f"portal-lock-{SERIAL}")
        os.mkdir(path)
        with open(os.path.join(path, "owner"), "w") as f:
            f.write("other agent\n")
        sleeps = []

        def fake_sleep(s):
            sleeps.append(s)
            if len(sleeps) == 2:
                os.remove(os.path.join(path, "owner"))
                os.rmdir(path)

        lock = DeviceLock(SERIAL, "perf-bench job3", self.root)
        self.assertTrue(lock.acquire_waiting(retry_s=60, log=lambda m: None, sleep=fake_sleep))
        self.assertEqual(sleeps, [60, 60])
        self.assertTrue(lock.owned())

    def test_tag_prefix_must_match_a_whole_word(self):
        lock = DeviceLock(SERIAL, "perf-bench job1", self.root)
        lock.try_acquire()
        self.assertFalse(DeviceLock(SERIAL, "perf-bench job", self.root).owned())

    def test_held_lock_never_releases(self):
        lock = DeviceLock(SERIAL, "perf-bench job4", self.root)
        lock.try_acquire()
        held = HeldLock(SERIAL, "perf-bench job4", self.root)
        held.check()
        self.assertFalse(held.release())
        self.assertTrue(lock.owned())


if __name__ == "__main__":
    unittest.main()

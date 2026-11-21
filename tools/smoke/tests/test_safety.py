"""Safety rules: locks, the lock guard, the adb command filter, redaction, exit status."""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from smokelib import report  # noqa: E402
from smokelib.adb import FORBIDDEN, Adb  # noqa: E402
from smokelib.lock import LockGuardError, Locks, guard, lock_dir  # noqa: E402
from smokelib.model import FAIL, PASS, SKIP, WARN, Result, SafetyRefusal, passed  # noqa: E402
from smokelib.redact import Redactor  # noqa: E402
from smokelib.registry import Device  # noqa: E402

SERIAL = "TESTSERIAL0001"
# Built at runtime so no IP literal lives in the repo.
FAKE_IP = ".".join(["10", "9", "8", "7"])


class LockTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()

    def test_acquire_release_own(self):
        locks = Locks("smoke-suite test", self.root)
        self.assertEqual(locks.acquire(SERIAL), (True, None))
        self.assertTrue(locks.holds(SERIAL))
        self.assertTrue(locks.release(SERIAL))
        self.assertFalse(os.path.exists(lock_dir(SERIAL, self.root)))

    def test_locked_by_other_reports_holder_and_never_removes(self):
        os.mkdir(lock_dir(SERIAL, self.root))
        with open(os.path.join(lock_dir(SERIAL, self.root), "owner"), "w") as f:
            f.write("floating-jarvis Fri\n")
        locks = Locks("smoke-suite test", self.root)
        self.assertEqual(locks.acquire(SERIAL), (False, "floating-jarvis Fri"))
        self.assertFalse(locks.holds(SERIAL))
        self.assertFalse(locks.release(SERIAL))
        self.assertTrue(os.path.exists(lock_dir(SERIAL, self.root)))

    def test_owner_changed_is_not_removed(self):
        locks = Locks("smoke-suite test", self.root)
        locks.acquire(SERIAL)
        with open(os.path.join(lock_dir(SERIAL, self.root), "owner"), "w") as f:
            f.write("someone else\n")
        self.assertFalse(locks.release(SERIAL))
        self.assertTrue(os.path.exists(lock_dir(SERIAL, self.root)))

    def test_guard_blocks_unlocked_device_before_any_command(self):
        locks = Locks("smoke-suite test", self.root)
        with self.assertRaises(LockGuardError):
            guard(locks, SERIAL)
        with self.assertRaises(LockGuardError):
            Adb(locks, Redactor()).shell(Device("Test", SERIAL, "Portal"), "true")


class AdbFilterTest(unittest.TestCase):
    def test_forbidden_commands(self):
        for cmd in ("screencap -p /sdcard/x.png", "screenrecord /sdcard/x.mp4", "am force-stop com.x",
                    "reboot", "input keyevent 4", "input keyevent KEYCODE_BACK", "pm clear com.x", "kill 123"):
            self.assertTrue(FORBIDDEN.search(cmd), cmd)

    def test_allowed_commands(self):
        for cmd in ("input keyevent 3", "dumpsys window windows", "pidof com.immortal.launcher.debug",
                    "logcat -d -t 20000", "content call --uri content://x --method privacy"):
            self.assertFalse(FORBIDDEN.search(cmd), cmd)

    def test_shell_refuses_forbidden_even_when_locked(self):
        root = tempfile.mkdtemp()
        locks = Locks("t", root)
        locks.acquire(SERIAL)
        with self.assertRaises(SafetyRefusal):
            Adb(locks, Redactor()).shell(Device("Test", SERIAL, "Portal"), "am force-stop com.immortal.launcher.debug")
        locks.release(SERIAL)


class RedactTest(unittest.TestCase):
    def test_masks_ips_bearer_and_secrets(self):
        r = Redactor()
        r.add("s3cr3t-token-value")
        out = r.text(f"GET http://{FAKE_IP}:8723 Authorization: Bearer abc s3cr3t-token-value fe80:0:0:0:1:2:3:4")
        for leaked in (FAKE_IP, "abc", "s3cr3t", "fe80"):
            self.assertNotIn(leaked, out)

    def test_data_drops_secret_keys(self):
        r = Redactor()
        self.assertEqual(r.data({"token": "x", "ip": FAKE_IP, "nested": [{"pin": 123456, "ok": True}]}),
                         {"token": "***", "ip": "***", "nested": [{"pin": "***", "ok": True}]})


class OutcomeTest(unittest.TestCase):
    def test_passed_allows_skip_only(self):
        self.assertTrue(passed([Result("a", "c", PASS), Result("a", "d", SKIP)]))
        self.assertFalse(passed([Result("a", "c", PASS), Result("a", "d", WARN)]))
        self.assertFalse(passed([Result("a", "c", FAIL)]))

    def test_table_lists_problems(self):
        text = report.table([Result("A", "version.installed", PASS), Result("B", "version.installed", FAIL, "old")],
                            ["A", "B"], ["header"])
        self.assertIn("FAIL B version.installed: old", text)
        self.assertLess(len(text.splitlines()), 40)


if __name__ == "__main__":
    unittest.main()

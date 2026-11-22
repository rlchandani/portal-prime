"""The device lock protocol (/tmp/portal-device-lock.md).

- Acquire: mkdir /tmp/portal-lock-<serial>, then write our owner line. If mkdir fails another agent
  holds the Portal: we wait (retry every 60 s) and run NO adb/fleet command against it meanwhile.
- Release: only a lock whose owner file still names us.
- Every device command re-checks the owner file (guard), so a lost lock stops the run at once.
"""

import os
import shutil
import time

from . import LOCK_ROOT

RETRY_S = 60
MAX_HOLD_S = 30 * 60


class LockError(Exception):
    """Raised before any device command when this run does not hold the Portal's lock."""


def lock_dir(serial, root=LOCK_ROOT):
    return os.path.join(root, f"portal-lock-{serial}")


def holder(serial, root=LOCK_ROOT):
    try:
        with open(os.path.join(lock_dir(serial, root), "owner"), encoding="utf-8") as f:
            return f.readline().strip() or "unknown owner"
    except OSError:
        return None


class DeviceLock:
    """One Portal's lock, owned under [tag] (the owner line starts with it)."""

    def __init__(self, serial, tag, root=LOCK_ROOT, clock=time):
        if not tag or "\n" in tag:
            raise ValueError("lock tag must be one non-empty line")
        self.serial = serial
        self.tag = tag
        self.root = root
        self.clock = clock
        self.acquired_at = None

    @property
    def path(self):
        return lock_dir(self.serial, self.root)

    def try_acquire(self, note=""):
        """True when taken; False when another holder owns it (nothing else is touched)."""
        try:
            os.mkdir(self.path)
        except FileExistsError:
            return False
        stamp = time.strftime("%a %b %d %H:%M:%S %Z %Y")
        with open(os.path.join(self.path, "owner"), "w", encoding="utf-8") as f:
            f.write(f"{self.tag} {note} {stamp}".replace("  ", " ").strip() + "\n")
        self.acquired_at = self.clock.time()
        return True

    def acquire_waiting(self, note="", retry_s=RETRY_S, max_wait_s=None, log=print, sleep=time.sleep):
        """Waits politely (retry every [retry_s]) until the lock is ours. False on max_wait_s expiry."""
        started = self.clock.time()
        last_holder = None
        while not self.try_acquire(note):
            who = holder(self.serial, self.root)
            if who != last_holder:
                log(f"lock {self.path} held by: {who}; waiting (retry every {retry_s}s)")
                last_holder = who
            if max_wait_s is not None and self.clock.time() - started >= max_wait_s:
                return False
            sleep(retry_s)
        return True

    def owned(self):
        who = holder(self.serial, self.root)
        return who is not None and (who == self.tag or who.startswith(self.tag + " "))

    def check(self):
        if not self.owned():
            raise LockError(f"refusing to touch {self.serial}: its lock is not held by '{self.tag}'")

    def release(self):
        """Removes the lock only if its owner file still names us. True when released."""
        if not self.owned():
            return False
        shutil.rmtree(self.path, ignore_errors=True)
        return True


class HeldLock(DeviceLock):
    """A lock a parent worker already holds for us: we may check it but never take or release it."""

    def try_acquire(self, note=""):
        return self.owned()

    def release(self):
        return False

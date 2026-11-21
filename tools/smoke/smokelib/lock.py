"""The device lock protocol (/tmp/portal-device-lock.md).

HARD RULE: if mkdir fails another agent holds the Portal — report "locked by X" and never run a
single adb or fleet command against it. We release only a lock whose owner file is ours.
"""

import os
import shutil
import threading

LOCK_ROOT = "/tmp"
MAX_HOLD_S = 30 * 60


def lock_dir(serial, root=LOCK_ROOT):
    return os.path.join(root, f"portal-lock-{serial}")


class Locks:
    def __init__(self, owner_tag, root=LOCK_ROOT):
        self.owner_tag = owner_tag
        self.root = root
        self._held = frozenset()
        self._mutex = threading.Lock()

    def acquire(self, serial):
        """(True, None) when taken; (False, holder) when another agent has it."""
        path = lock_dir(serial, self.root)
        try:
            os.mkdir(path)
        except FileExistsError:
            return False, self._holder(path)
        except OSError as e:
            return False, f"lock error {type(e).__name__}"
        with open(os.path.join(path, "owner"), "w", encoding="utf-8") as f:
            f.write(self.owner_tag + "\n")
        with self._mutex:
            self._held = self._held | {serial}
        return True, None

    def holds(self, serial):
        return serial in self._held

    def release(self, serial):
        """Removes the lock only if its owner file is still ours. True when released."""
        path = lock_dir(serial, self.root)
        with self._mutex:
            self._held = self._held - {serial}
        if self._holder(path) != self.owner_tag:
            return False
        shutil.rmtree(path, ignore_errors=True)
        return True

    def release_all(self):
        return {s: self.release(s) for s in sorted(self._held)}

    @staticmethod
    def _holder(path):
        try:
            with open(os.path.join(path, "owner"), encoding="utf-8") as f:
                return f.readline().strip() or "unknown owner"
        except OSError:
            return "unknown owner"


class LockGuardError(Exception):
    """Raised before any device command when the suite does not hold that Portal's lock."""


def guard(locks, serial):
    if not locks.holds(serial):
        raise LockGuardError("refusing to touch a Portal whose lock this run does not hold")

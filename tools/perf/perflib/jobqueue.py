"""Per-device job queue: plain files, atomic renames, one worker per Portal.

<root>/<serial>/pending/<id>.json   submitted, waiting
<root>/<serial>/running/<id>.json   claimed by the worker (rename = claim)
<root>/<serial>/done/<id>.json      finished: the job plus status, result paths, timings
<root>/<serial>/worker/             mkdir lock of the single live worker (pid inside)
"""

import json
import os
import secrets
import time

STATES = ("pending", "running", "done")
DEFAULT_CAP_S = 20 * 60
MAX_CAP_S = 30 * 60  # the lock protocol's longest hold


class QueueError(Exception):
    pass


def device_dir(root, serial):
    return os.path.join(root, serial)


def _ensure(root, serial):
    for s in STATES:
        os.makedirs(os.path.join(device_dir(root, serial), s), exist_ok=True)


def new_id(serial):
    return f"{time.strftime('%Y%m%d-%H%M%S')}-{serial[-4:]}-{secrets.token_hex(2)}"


def _write_atomic(path, data):
    tmp = f"{path}.{os.getpid()}.tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
    os.replace(tmp, path)


def submit(root, job):
    """Writes [job] (a dict with at least device and scenario) to pending/; returns its id."""
    serial = job["device"]
    cap = int(job.get("cap_s", DEFAULT_CAP_S))
    if not 60 <= cap <= MAX_CAP_S:
        raise QueueError(f"cap must be 60..{MAX_CAP_S} s (the lock protocol allows a 30 min hold)")
    _ensure(root, serial)
    job_id = new_id(serial)
    record = {**job, "id": job_id, "cap_s": cap, "submitted": time.strftime("%Y-%m-%d %H:%M:%S %Z"),
              "submitted_epoch": time.time()}
    _write_atomic(os.path.join(device_dir(root, serial), "pending", f"{job_id}.json"), record)
    return job_id


def pending(root, serial):
    folder = os.path.join(device_dir(root, serial), "pending")
    try:
        names = sorted(n for n in os.listdir(folder) if n.endswith(".json"))
    except FileNotFoundError:
        return []
    return [os.path.join(folder, n) for n in names]


def claim(root, serial):
    """The oldest pending job, moved to running/ (atomic), or None."""
    for path in pending(root, serial):
        target = os.path.join(device_dir(root, serial), "running", os.path.basename(path))
        try:
            os.rename(path, target)
        except FileNotFoundError:
            continue
        with open(target, encoding="utf-8") as f:
            return target, json.load(f)
    return None


def finish(root, serial, running_path, job, outcome):
    done = os.path.join(device_dir(root, serial), "done", os.path.basename(running_path))
    _write_atomic(done, {**job, **outcome, "finished": time.strftime("%Y-%m-%d %H:%M:%S %Z")})
    try:
        os.remove(running_path)
    except FileNotFoundError:
        pass
    return done


def find(root, job_id):
    """(state, path) of a job across every device folder, or (None, None)."""
    if not job_id or "/" in job_id or job_id.startswith("."):
        return None, None
    try:
        serials = os.listdir(root)
    except FileNotFoundError:
        return None, None
    for state in ("done", "running", "pending"):
        for serial in serials:
            path = os.path.join(root, serial, state, f"{job_id}.json")
            if os.path.isfile(path):
                return state, path
    return None, None


def requeue_orphans(root, serial):
    """Jobs left in running/ by a dead worker go back to pending/ (called by the next worker)."""
    folder = os.path.join(device_dir(root, serial), "running")
    moved = []
    for n in sorted(os.listdir(folder)) if os.path.isdir(folder) else []:
        if n.endswith(".json"):
            os.rename(os.path.join(folder, n), os.path.join(device_dir(root, serial), "pending", n))
            moved.append(n[:-5])
    return moved


class WorkerSlot:
    """mkdir lock so only one worker serves a Portal; a slot whose pid is dead is taken over."""

    def __init__(self, root, serial):
        self.path = os.path.join(device_dir(root, serial), "worker")
        self.pidfile = os.path.join(self.path, "pid")

    def acquire(self):
        _ensure(os.path.dirname(os.path.dirname(self.path)), os.path.basename(os.path.dirname(self.path)))
        try:
            os.mkdir(self.path)
        except FileExistsError:
            other = self._pid()
            if other and _alive(other):
                return False, other
            with open(self.pidfile, "w", encoding="utf-8") as f:
                f.write(str(os.getpid()))
            return True, None
        with open(self.pidfile, "w", encoding="utf-8") as f:
            f.write(str(os.getpid()))
        return True, None

    def release(self):
        if self._pid() == os.getpid():
            try:
                os.remove(self.pidfile)
                os.rmdir(self.path)
            except OSError:
                pass

    def _pid(self):
        try:
            with open(self.pidfile, encoding="utf-8") as f:
                return int(f.read().strip() or 0)
        except (OSError, ValueError):
            return 0


def _alive(pid):
    try:
        os.kill(pid, 0)  # signal 0: existence check on the Mac, sends nothing
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True

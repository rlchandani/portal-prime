"""The per-Portal worker: takes the device lock ONCE per job, runs it in a child process under a hard
time cap (its own watchdog: SIGTERM -> the child restores -> SIGKILL after a grace), restores again
itself if the child could not, releases the lock, logs the session note, and moves on."""

import json
import os
import signal
import subprocess
import sys
import time

from . import DEFAULT_FLEETCTL, DEFAULT_RESULTS_ROOT, jobqueue, notes, report
from .builds import OfficialSource
from .device import Device
from .lock import RETRY_S, DeviceLock, holder
from .restore import restore
from .session import default_out_dir

GRACE_S = 5 * 60  # the child's own restore (an official reinstall) must fit in this
IDLE_POLL_S = 5
HELD_LOCK_ENV = "PERF_BENCH_HELD_LOCK"


class Worker:
    def __init__(self, root, serial, bench_path, fleetctl=DEFAULT_FLEETCTL, log=print, retry_s=RETRY_S):
        self.root = root
        self.serial = serial
        self.bench = bench_path
        self.fleetctl = fleetctl
        self.log = log
        self.retry_s = retry_s
        self.stopping = False
        self.child = None

    def _on_signal(self, signum, _frame):
        self.stopping = True
        self.log(f"worker: signal {signum}; finishing the current job safely")
        if self.child is not None and self.child.poll() is None:
            self.child.send_signal(signal.SIGTERM)

    def serve(self, once=False, exit_when_empty=False):
        slot = jobqueue.WorkerSlot(self.root, self.serial)
        ok, other = slot.acquire()
        if not ok:
            self.log(f"refusing to start: worker pid {other} already serves {self.serial}")
            return 2
        for s in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
            signal.signal(s, self._on_signal)
        try:
            for job_id in jobqueue.requeue_orphans(self.root, self.serial):
                self.log(f"worker: re-queued orphaned job {job_id}")
            self.log(f"worker pid {os.getpid()} serving {self.serial} (queue {jobqueue.device_dir(self.root, self.serial)})")
            while not self.stopping:
                claimed = jobqueue.claim(self.root, self.serial)
                if claimed is None:
                    if once or exit_when_empty:
                        break
                    time.sleep(IDLE_POLL_S)
                    continue
                self.process(*claimed)
                if once:
                    break
        finally:
            slot.release()
        return 0

    def _wait_for_lock(self, lock, note):
        last = None
        while not self.stopping:
            if lock.try_acquire(note):
                return True
            who = holder(self.serial, lock.root)
            if who != last:
                self.log(f"worker: {self.serial} lock held by '{who}'; waiting politely (retry every {self.retry_s}s), "
                         f"no device commands meanwhile")
                last = who
            for _ in range(int(self.retry_s)):
                if self.stopping:
                    break
                time.sleep(1)
        return False

    def process(self, running_path, job):
        job_id = job["id"]
        out_dir = job.get("out") or default_out_dir(job.get("results_root") or DEFAULT_RESULTS_ROOT, job["scenario"])
        os.makedirs(out_dir, exist_ok=True)
        lock = DeviceLock(self.serial, f"perf-bench {job_id}")
        self.log(f"job {job_id}: {job['scenario']} builds={job.get('apks') or []}{' +official' if job.get('official') else ''} "
                 f"reps={job.get('reps')} cap={job['cap_s']}s -> {out_dir}")
        if not self._wait_for_lock(lock, f"worker-pid {os.getpid()} {job['scenario']}"):
            os.rename(running_path, os.path.join(jobqueue.device_dir(self.root, self.serial), "pending",
                                                 os.path.basename(running_path)))
            self.log(f"job {job_id}: worker stopping before the lock was free; job left pending")
            return
        started = time.time()
        timed_out, rc, worker_restore, restore_ok = False, None, None, False
        status = "crashed"
        note_src = {"scenario": job["scenario"], "reps": job.get("reps"), "builds": [], "status": status}
        try:
            rc, timed_out = self._run_child(running_path, out_dir, lock.tag, job["cap_s"])
            result_path = os.path.join(out_dir, "result.json")
            result = report.load(result_path) if os.path.isfile(result_path) else None
            worker_restore = None
            if result is None or not (result.get("restore") or {}).get("ok"):
                self.log(f"job {job_id}: child restore missing/incomplete; worker restores the Portal")
                device = Device(self.serial, lock, self.fleetctl, log=self.log)
                worker_restore = restore(device, OfficialSource(), (result or {}).get("priors") or {}, self.log)
            status = "timeout" if timed_out else (result or {}).get("status", f"crashed (rc {rc})")
            restore_ok = (worker_restore or (result or {}).get("restore") or {}).get("ok", False)
            note_src = dict(result or {"scenario": job["scenario"], "reps": job.get("reps"), "builds": []})
            note_src["status"] = status
            if worker_restore is not None:
                note_src["restore"] = worker_restore
        finally:
            released = lock.release()
        notes.append(self.serial, notes.line(note_src, lock.tag, started_epoch=started, released=released))
        outcome = {"status": status, "rc": rc, "timed_out": timed_out, "restore_ok": restore_ok,
                   "worker_restore": worker_restore, "result": os.path.join(out_dir, "result.json"),
                   "markdown": os.path.join(out_dir, "result.md"), "out": out_dir,
                   "seconds": round(time.time() - started), "lock_released": released}
        done = jobqueue.finish(self.root, self.serial, running_path, job, outcome)
        self.log(f"job {job_id}: {status}, restore {'OK' if restore_ok else 'INCOMPLETE'}, "
                 f"lock {'released' if released else 'NOT released'} -> {done}")

    def _run_child(self, job_path, out_dir, tag, cap_s):
        env = {**os.environ, HELD_LOCK_ENV: tag}
        cmd = [sys.executable, "-I", self.bench, "run", "--job", job_path, "--out", out_dir,
               "--fleetctl", self.fleetctl]
        timed_out = False
        with open(os.path.join(out_dir, "run.log"), "a", encoding="utf-8") as logf:
            self.child = subprocess.Popen(cmd, stdout=logf, stderr=subprocess.STDOUT, env=env,
                                          stdin=subprocess.DEVNULL)
            deadline = time.time() + cap_s
            try:
                while self.child.poll() is None:
                    if time.time() > deadline and not timed_out:
                        timed_out = True
                        self.log(f"watchdog: job over its {cap_s}s cap; SIGTERM (child restores, {GRACE_S}s grace)")
                        self.child.send_signal(signal.SIGTERM)
                        deadline = time.time() + GRACE_S
                    elif timed_out and time.time() > deadline:
                        self.log("watchdog: grace over; SIGKILL")
                        self.child.kill()
                        break
                    time.sleep(1)
                rc = self.child.wait()
            finally:
                self.child = None
        return rc, timed_out


def wait_for(root, job_id, timeout_s=None, poll_s=5, log=print):
    """Blocks until the job is done; returns its done record (or None on timeout / unknown id)."""
    end = None if timeout_s is None else time.time() + timeout_s
    last = None
    while True:
        state, path = jobqueue.find(root, job_id)
        if state is None:
            return None
        if state == "done":
            with open(path, encoding="utf-8") as f:
                return json.load(f)
        if state != last:
            log(f"{job_id}: {state}")
            last = state
        if end is not None and time.time() > end:
            return None
        time.sleep(poll_s)

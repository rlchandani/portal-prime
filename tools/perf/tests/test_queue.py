"""Per-device queue files, the single-worker slot and the watchdog's hard cap."""

import os
import sys
import tempfile
import textwrap
import time
import unittest
from unittest import mock

from perflib import jobqueue, worker
from tests.fakes import SERIAL


class QueueTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()

    def test_submit_claim_finish_find(self):
        a = jobqueue.submit(self.root, {"device": SERIAL, "scenario": "cc-open", "cap_s": 600})
        time.sleep(1.1)  # ids sort by time
        b = jobqueue.submit(self.root, {"device": SERIAL, "scenario": "home-tabs"})
        self.assertEqual(jobqueue.find(self.root, a)[0], "pending")
        path, job = jobqueue.claim(self.root, SERIAL)
        self.assertEqual(job["id"], a)  # oldest first
        self.assertEqual(jobqueue.find(self.root, a)[0], "running")
        jobqueue.finish(self.root, SERIAL, path, job, {"status": "ok"})
        state, done = jobqueue.find(self.root, a)
        self.assertEqual(state, "done")
        self.assertEqual(jobqueue.claim(self.root, SERIAL)[1]["id"], b)
        self.assertIsNone(jobqueue.claim(self.root, SERIAL))

    def test_cap_limits(self):
        with self.assertRaises(jobqueue.QueueError):
            jobqueue.submit(self.root, {"device": SERIAL, "scenario": "x", "cap_s": 31 * 60})
        with self.assertRaises(jobqueue.QueueError):
            jobqueue.submit(self.root, {"device": SERIAL, "scenario": "x", "cap_s": 10})

    def test_devices_are_separate_queues(self):
        jobqueue.submit(self.root, {"device": "OTHER", "scenario": "cc-open"})
        self.assertIsNone(jobqueue.claim(self.root, SERIAL))

    def test_bad_ids_not_found(self):
        self.assertEqual(jobqueue.find(self.root, "../etc"), (None, None))

    def test_orphans_requeued(self):
        jobqueue.submit(self.root, {"device": SERIAL, "scenario": "cc-open"})
        jobqueue.claim(self.root, SERIAL)
        self.assertEqual(len(jobqueue.requeue_orphans(self.root, SERIAL)), 1)
        self.assertIsNotNone(jobqueue.claim(self.root, SERIAL))

    def test_one_worker_per_device(self):
        slot = jobqueue.WorkerSlot(self.root, SERIAL)
        self.assertEqual(slot.acquire(), (True, None))
        other = jobqueue.WorkerSlot(self.root, SERIAL)
        with open(other.pidfile, "w") as f:
            f.write("1")  # pid 1 (launchd) is alive: refuse
        self.assertEqual(other.acquire(), (False, 1))
        with open(other.pidfile, "w") as f:
            f.write("999999")  # dead pid: the slot is taken over
        self.assertEqual(other.acquire(), (True, None))
        other.release()


class WorkerTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.lock_root = tempfile.mkdtemp()

    def _fake_bench(self, body):
        path = os.path.join(self.root, "fake_bench.py")
        with open(path, "w") as f:
            f.write(textwrap.dedent(body))
        return path

    def test_watchdog_terminates_over_cap(self):
        bench = self._fake_bench("""
            import signal, sys, time, os
            def term(*a):
                open(os.path.join(os.path.dirname(sys.argv[0]), "got_term"), "w").close()
                sys.exit(5)
            signal.signal(signal.SIGTERM, term)
            time.sleep(30)
        """)
        w = worker.Worker(self.root, SERIAL, bench, fleetctl=sys.executable, log=lambda m: None)
        out = tempfile.mkdtemp()
        t0 = time.time()
        rc, timed_out = w._run_child("job.json", out, "perf-bench t", cap_s=1)
        self.assertTrue(timed_out)
        self.assertEqual(rc, 5)
        self.assertLess(time.time() - t0, 10)
        self.assertTrue(os.path.exists(os.path.join(self.root, "got_term")))

    def test_watchdog_kills_after_grace(self):
        bench = self._fake_bench("""
            import signal, time
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            time.sleep(30)
        """)
        w = worker.Worker(self.root, SERIAL, bench, fleetctl=sys.executable, log=lambda m: None)
        with mock.patch.object(worker, "GRACE_S", 1):
            rc, timed_out = w._run_child("job.json", tempfile.mkdtemp(), "perf-bench t", cap_s=1)
        self.assertTrue(timed_out)
        self.assertLess(rc, 0)  # killed by a signal

    def test_child_gets_the_held_lock_tag(self):
        bench = self._fake_bench("""
            import os, sys
            out = sys.argv[sys.argv.index("--out") + 1]
            open(os.path.join(out, "tag"), "w").write(os.environ.get("PERF_BENCH_HELD_LOCK", ""))
        """)
        out = tempfile.mkdtemp()
        w = worker.Worker(self.root, SERIAL, bench, fleetctl=sys.executable, log=lambda m: None)
        rc, timed_out = w._run_child("job.json", out, "perf-bench job9", cap_s=30)
        self.assertEqual((rc, timed_out), (0, False))
        with open(os.path.join(out, "tag")) as f:
            self.assertEqual(f.read(), "perf-bench job9")

    def test_wait_for_done_and_unknown(self):
        job_id = jobqueue.submit(self.root, {"device": SERIAL, "scenario": "cc-open"})
        path, job = jobqueue.claim(self.root, SERIAL)
        jobqueue.finish(self.root, SERIAL, path, job, {"status": "ok"})
        self.assertEqual(worker.wait_for(self.root, job_id, timeout_s=1, poll_s=0.1, log=lambda m: None)["status"],
                         "ok")
        self.assertIsNone(worker.wait_for(self.root, "nope", timeout_s=1, poll_s=0.1, log=lambda m: None))


if __name__ == "__main__":
    unittest.main()

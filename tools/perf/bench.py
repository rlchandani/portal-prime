#!/usr/bin/env python3
"""Hearthpane on-device benchmarks: one tool for every Portal perf run, plus a per-device job queue.

  python3 -I tools/perf/bench.py scenarios
  python3 -I tools/perf/bench.py submit --device SERIAL --scenario cc-open --official --reps 3
  python3 -I tools/perf/bench.py worker --device SERIAL            # one per Portal, runs until stopped
  python3 -I tools/perf/bench.py wait JOB_ID                         # blocks, prints the result tables
  python3 -I tools/perf/bench.py compare RESULT.json                 # A/B verdict table
  python3 -I tools/perf/bench.py run --device SERIAL --scenario home-tabs --official --apk B.apk --reps 4

`run` takes the device lock itself (waiting politely) unless a worker already holds it for it.
Every run restores the Portal (official release, wm, rotation, test source, cc_debug, music, Home,
dev off) in a finally block, also on errors and signals. See README.md beside this file.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from perflib import DEFAULT_FLEETCTL, DEFAULT_QUEUE_ROOT, DEFAULT_RESULTS_ROOT  # noqa: E402
from perflib import compare, jobqueue, report, scenarios  # noqa: E402
from perflib.builds import BuildError, OfficialSource, parse_apk_spec  # noqa: E402
from perflib.engine import Options  # noqa: E402
from perflib.lock import DeviceLock, HeldLock, holder  # noqa: E402
from perflib.session import default_out_dir, resolve_builds, run_session  # noqa: E402
from perflib.worker import HELD_LOCK_ENV, Worker, wait_for  # noqa: E402

EXIT_FAIL = 1
EXIT_USAGE = 2
MIN_REPS, MAX_REPS = 1, 30


def log(msg):
    print(msg, flush=True)


def _job_args(p):
    p.add_argument("--device", required=True, help="adb serial (e.g. 2221B01C7T01C0 = Family)")
    p.add_argument("--scenario", required=True, help="see `bench.py scenarios`")
    p.add_argument("--apk", action="append", default=[], metavar="APK[,DM]",
                   help="build under test (repeatable: A, B, ...); ',file.dm' installs with install-multiple")
    p.add_argument("--official", action="store_true", help="the latest official release (label O, first)")
    p.add_argument("--reps", type=int, default=3)
    p.add_argument("--interleave", action=argparse.BooleanOptionalAction, default=True,
                   help="A,B,B,A order (default on); --no-interleave runs all A then all B")
    p.add_argument("--meminfo", action="store_true", help="dumpsys meminfo before/after every rep")
    p.add_argument("--atrace", action="store_true", help="atrace gfx/view around every measured step")
    p.add_argument("--simpleperf", action="store_true", help="simpleperf (app, 4 kHz, -g) per measured step")
    p.add_argument("--screenshots", action="store_true",
                   help="allow captures where the scenario permits AND the screen is allow-listed")
    p.add_argument("--poll-s", type=float, default=None, help="override a long-window step's seconds")
    p.add_argument("--results-root", default=DEFAULT_RESULTS_ROOT)


def parse_args(argv):
    ap = argparse.ArgumentParser(description="Hearthpane on-device benchmarks and per-Portal job queue")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("scenarios", help="list scenarios and their screenshot policy")

    r = sub.add_parser("run", help="run a benchmark now (takes the lock, waits politely)")
    r.add_argument("--job", help="(worker) run this queued job file instead of the flags")
    r.add_argument("--out", help="result folder (default <results-root>/<timestamp>-<scenario>)")
    r.add_argument("--fleetctl", default=DEFAULT_FLEETCTL)
    r.add_argument("--no-wait", action="store_true", help="exit 1 if the device lock is held")
    if "--job" not in argv:
        _job_args(r)

    s = sub.add_parser("submit", help="queue a job for a Portal's worker; prints the job id")
    _job_args(s)
    s.add_argument("--cap-min", type=float, default=jobqueue.DEFAULT_CAP_S / 60, help="hard time cap (<= 30)")
    s.add_argument("--who", default=os.environ.get("USER", "agent"), help="submitter tag for the notes")
    s.add_argument("--queue", default=DEFAULT_QUEUE_ROOT)

    w = sub.add_parser("worker", help="serve one Portal's queue until stopped")
    w.add_argument("--device", required=True)
    w.add_argument("--queue", default=DEFAULT_QUEUE_ROOT)
    w.add_argument("--fleetctl", default=DEFAULT_FLEETCTL)
    w.add_argument("--once", action="store_true", help="process one job, then exit")
    w.add_argument("--exit-when-empty", action="store_true")

    wt = sub.add_parser("wait", help="block until a job is done, print its result")
    wt.add_argument("job_id")
    wt.add_argument("--queue", default=DEFAULT_QUEUE_ROOT)
    wt.add_argument("--timeout-min", type=float, default=None)
    wt.add_argument("--json", action="store_true", help="print the done record as JSON")

    c = sub.add_parser("compare", help="A/B verdict table for a result.json")
    c.add_argument("result")
    return ap.parse_args(argv)


def _validate(args):
    scenarios.get(args.scenario)  # KeyError on unknown
    if not MIN_REPS <= args.reps <= MAX_REPS:
        raise ValueError(f"--reps must be {MIN_REPS}..{MAX_REPS}")
    if not args.apk and not args.official:
        raise ValueError("give --official and/or --apk")
    if len(args.apk) > 8:
        raise ValueError("at most 8 --apk builds")
    for spec in args.apk:
        apk, dm = parse_apk_spec(spec)
        for path in filter(None, (apk, dm)):
            if not os.path.isfile(path):
                raise ValueError(f"no such file: {path}")


def _job_from_args(args):
    return {"device": args.device, "scenario": args.scenario,
            "apks": [",".join(filter(None, parse_apk_spec(x))) for x in args.apk], "official": args.official,
            "reps": args.reps, "interleave": args.interleave, "meminfo": args.meminfo, "atrace": args.atrace,
            "simpleperf": args.simpleperf, "screenshots": args.screenshots, "poll_s": args.poll_s,
            "results_root": args.results_root}


def cmd_scenarios(_args):
    for name, summary, shots in scenarios.listing():
        policy = "" if shots is None else ("  [screenshots: allow-listed screens only]" if shots else "  [NO screenshots]")
        print(f"{name:42s} {summary}{policy}")
    return 0


def cmd_run(args):
    if args.job:
        with open(args.job, encoding="utf-8") as f:
            job = json.load(f)
    else:
        _validate(args)
        job = _job_from_args(args)
    serial = job["device"]
    opts = Options(reps=job["reps"], interleave=job["interleave"], meminfo=job["meminfo"], atrace=job["atrace"],
                   simpleperf=job["simpleperf"], screenshots=job["screenshots"], poll_s=job.get("poll_s"))
    out = args.out or default_out_dir(job.get("results_root") or DEFAULT_RESULTS_ROOT, job["scenario"])
    os.makedirs(out, exist_ok=True)
    official = OfficialSource()
    builds = resolve_builds(job["apks"], job["official"], official)  # before the lock: no device needed
    held = os.environ.get(HELD_LOCK_ENV)
    if held:
        lock, own = HeldLock(serial, held), False
    else:
        lock, own = DeviceLock(serial, f"perf-bench run-{os.getpid()}"), True
        note = f"{job['scenario']} (bench.py run)"
        if args.no_wait:
            if not lock.try_acquire(note):
                log(f"lock held by {holder(serial)}; not running (--no-wait)")
                return EXIT_FAIL
        else:
            lock.acquire_waiting(note, log=log)
    try:
        result, path = run_session(serial=serial, scenario_name=job["scenario"], builds=builds, options=opts,
                                   out_dir=out, lock=lock, fleetctl=args.fleetctl, official=official, log=log,
                                   own_lock=own, job_id=job.get("id"))
    finally:
        if own:
            lock.release()
    log(f"\nresult: {path}")
    print(report.markdown(result))
    return 0 if result["status"] == "ok" and result["restore"]["ok"] else EXIT_FAIL


def cmd_submit(args):
    _validate(args)
    job = {**_job_from_args(args), "cap_s": int(args.cap_min * 60), "who": args.who}
    job_id = jobqueue.submit(args.queue, job)
    print(job_id)
    alive = jobqueue.WorkerSlot(args.queue, args.device)._pid()
    if not alive:
        print(f"note: no worker is serving {args.device}; start one with "
              f"`python3 -I tools/perf/bench.py worker --device {args.device}`", file=sys.stderr)
    return 0


def cmd_worker(args):
    bench = os.path.abspath(__file__)
    return Worker(args.queue, args.device, bench, fleetctl=args.fleetctl, log=log).serve(
        once=args.once, exit_when_empty=args.exit_when_empty)


def cmd_wait(args):
    timeout = None if args.timeout_min is None else args.timeout_min * 60
    done = wait_for(args.queue, args.job_id, timeout, log=lambda m: print(m, file=sys.stderr, flush=True))
    if done is None:
        print(f"{args.job_id}: unknown job or still not done", file=sys.stderr)
        return EXIT_FAIL
    if args.json:
        print(json.dumps(done, indent=2))
    elif os.path.isfile(done.get("markdown", "")):
        with open(done["markdown"], encoding="utf-8") as f:
            print(f.read())
    print(f"job {done['id']}: {done['status']}, restore {'OK' if done.get('restore_ok') else 'INCOMPLETE'}; "
          f"result {done.get('result')}")
    return 0 if done["status"] == "ok" and done.get("restore_ok") else EXIT_FAIL


def cmd_compare(args):
    print(compare.table(report.load(args.result)))
    return 0


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    args = parse_args(argv)
    handlers = {"scenarios": cmd_scenarios, "run": cmd_run, "submit": cmd_submit, "worker": cmd_worker,
                "wait": cmd_wait, "compare": cmd_compare}
    try:
        return handlers[args.cmd](args)
    except (KeyError, ValueError, BuildError, jobqueue.QueueError) as e:
        print(f"error: {e.args[0] if e.args else e}", file=sys.stderr)
        return EXIT_USAGE


if __name__ == "__main__":
    sys.exit(main())

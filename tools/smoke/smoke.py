#!/usr/bin/env python3
"""Hearthpane post-release smoke suite. See README.md beside this file.

  python3 tools/smoke/smoke.py --quick          # version, health, sync (< 1 min)
  python3 tools/smoke/smoke.py --full -v        # every check the Portals' access allows

Exit status: 0 only when every check passed (SKIP = not possible with that Portal's access);
1 when anything failed, warned or a Portal was locked by another agent; 3 on a setup error.
"""

import argparse
import os
import signal
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from smokelib import RELEASE_REPO  # noqa: E402
from smokelib import registry, release, report  # noqa: E402
from smokelib.context import Options  # noqa: E402
from smokelib.model import passed  # noqa: E402
from smokelib.runner import run  # noqa: E402

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEFAULT_FLEETCTL = os.path.join(REPO_ROOT, "provisioning", "fleetctl")
DEFAULT_REPORT_ROOT = "/tmp/portal-smoke"
EXIT_SETUP = 3


def parse_args(argv):
    p = argparse.ArgumentParser(description="Hearthpane post-release smoke suite")
    mode = p.add_mutually_exclusive_group(required=True)
    mode.add_argument("--quick", action="store_const", dest="mode", const="quick",
                      help="version, APK sha, health, dev mode, settings sync (< 1 min)")
    mode.add_argument("--full", action="store_const", dest="mode", const="full", help="every check")
    p.add_argument("--device", action="append", default=[], help="Portal name or serial (repeatable; default all)")
    p.add_argument("--fleetctl", default=os.environ.get("SMOKE_FLEETCTL", DEFAULT_FLEETCTL),
                   help="fleetctl binary; its fleet/*.json registry lists the Portals (env SMOKE_FLEETCTL)")
    p.add_argument("--report-dir", default=os.environ.get("SMOKE_REPORT_DIR", DEFAULT_REPORT_ROOT),
                   help="reports go to <dir>/<timestamp>/ (env SMOKE_REPORT_DIR)")
    p.add_argument("--screensaver-device", default="Family Area", help="Portal for the screensaver check")
    p.add_argument("--screensaver-mode", default="smartFrame", help="screensaver mode to pin")
    p.add_argument("--screensaver-seconds", type=int, default=15)
    p.add_argument("--sync-wait", type=int, default=60, help="seconds to wait for a reminder to sync")
    p.add_argument("--release-repo", default=RELEASE_REPO)
    p.add_argument("-v", "--verbose", action="store_true", help="progress on stderr")
    args = p.parse_args(argv)
    if not 5 <= args.screensaver_seconds <= 120 or not 5 <= args.sync_wait <= 300:
        p.error("--screensaver-seconds must be 5..120 and --sync-wait 5..300")
    return args


def _terminate(signum, frame):
    raise KeyboardInterrupt  # unwinds through the runner's finally: undo steps + lock release


def main(argv=None):
    args = parse_args(argv)
    signal.signal(signal.SIGTERM, _terminate)
    opts = Options(args.mode, args.screensaver_device, args.screensaver_mode, args.screensaver_seconds,
                   args.sync_wait, args.verbose)
    if not os.access(args.fleetctl, os.X_OK):
        print("fleetctl not found: pass --fleetctl or set SMOKE_FLEETCTL", file=sys.stderr)
        return EXIT_SETUP
    try:
        results, payload, folder, names = run(opts, args.fleetctl, args.report_dir, args.device, args.release_repo)
    except (registry.RegistryError, release.ReleaseError) as e:
        print(f"setup error: {e}", file=sys.stderr)
        return EXIT_SETUP
    ok = passed(results)
    header = [f"Hearthpane smoke ({payload['mode']}) · release {payload['release']['versionName']} "
              f"({payload['release']['versionCode']}) · {payload['seconds']}s · {'PASS' if ok else 'FAIL'}"]
    header += [f"  {d['name']} {d['id']} {d['model']} adb={'yes' if d['adb'] else 'no'} lock: {d['lock']}"
               for d in payload["devices"]]
    bad_undo = [c for c in payload["cleanups"] if not c["ok"]]
    if bad_undo:
        header.append(f"  UNDO FAILED: {bad_undo}")
    print(report.table(results, names, header))
    path = report.write_json(folder, {**payload, "passed": ok and not bad_undo, "results": report.as_dicts(results)})
    print(f"\nreport: {path}")
    return 0 if ok and not bad_undo else 1


if __name__ == "__main__":
    sys.exit(main())

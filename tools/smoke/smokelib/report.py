"""The one-screen table and the JSON report."""

import json
import os
from dataclasses import asdict

from .model import FAIL, FLEET, PASS, SKIP, WARN

ORDER = (
    "version.installed", "version.apk_sha", "health.fleet", "health.dev_mode", "health.crashes",
    "api.info", "api.diag", "api.ota", "api.settings_sync", "sync.status", "sync.digest",
    "cc.live", "cc.dry_run", "cc.banner_preview", "calls.roster", "jarvis.version", "jarvis.provider",
    "screensaver.test_source", "reminders.sync", "ota.status", "rotation.hook", "restore.state",
)
CELL = {PASS: "pass", FAIL: "FAIL", WARN: "warn", SKIP: "skip"}
COL_W = 14
NAME_W = 24
DETAIL_W = 118


def table(results, device_names, header_lines):
    cols = list(device_names) + ([FLEET] if any(r.device == FLEET for r in results) else [])
    by = {(r.device, r.check): r for r in results}
    checks = [c for c in ORDER if any(r.check == c for r in results)]
    checks += sorted({r.check for r in results} - set(checks))
    lines = list(header_lines)
    lines.append("")
    lines.append("check".ljust(NAME_W) + "".join(c[:COL_W - 1].ljust(COL_W) for c in cols))
    lines.append("-" * (NAME_W + COL_W * len(cols)))
    for check in checks:
        row = check.ljust(NAME_W)
        for c in cols:
            r = by.get((c, check))
            row += (CELL[r.status] if r else "·").ljust(COL_W)
        lines.append(row)
    problems = [r for r in results if r.status in (FAIL, WARN)]
    if problems:
        lines.append("")
        lines.append("Problems:")
        for r in problems:
            lines.append(f"  {r.status} {r.device} {r.check}: {r.detail}"[:DETAIL_W])
    skips = [r for r in results if r.status == SKIP]
    if skips:
        reasons = sorted({f"{r.check} ({r.detail})" for r in skips})
        lines.append("")
        lines.append("Skipped (access): " + "; ".join(reasons)[:DETAIL_W * 2])
    return "\n".join(lines)


def write_json(folder, payload):
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, "report.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2, sort_keys=True)
    return path


def as_dicts(results):
    return [asdict(r) for r in results]

"""Version and process health (quick + full)."""

import dataclasses
import os
import re
from datetime import datetime, timedelta, timezone

from .. import PACKAGE, logscan
from ..context import Facts
from ..model import FAIL, PASS, WARN, Result, result
from .common import body, logs

SHA_LINE = re.compile(r"^([0-9a-f]{64})\s")


def gather_facts(ctx, device, adb_serials):
    """Read-only facts used by later checks; also yields api.info / api.diag results."""
    info_r = ctx.fleet.info(device)
    diag_r = ctx.fleet.raw(device, "GET", "/diag")
    info, diag = body(info_r), body(diag_r)
    apk = (diag.get("hardware") or {}).get("appApkPath") or ""
    install_ms, install_local = 0, ""
    if apk:
        listing = body(ctx.fleet.raw(device, "GET", f"/fs/list?path={os.path.dirname(apk)}"))
        entry = next((e for e in listing.get("entries", []) if e.get("name") == os.path.basename(apk)), {})
        install_ms = int(entry.get("mtime") or 0)
    offset = logscan.utc_offset(diag.get("uptime", ""), datetime.now(timezone.utc))
    if install_ms and offset is not None:
        install_local = logscan.local_ts(install_ms, offset)
    has_adb = device.serial in adb_serials
    facts = Facts(info=info, apk_path=apk, install_ms=install_ms, install_local=install_local, adb=has_adb,
                  utc_offset_s=int(offset.total_seconds()) if offset is not None else 0)
    ctx.set_facts(device, facts)
    if has_adb:
        ctx.set_facts(device, dataclasses.replace(facts, pid=ctx.adb.pid(device)))
    app = info.get("app") or {}
    return [
        result(device.name, "api.info", info_r.ok and "versionCode" in app,
               f"{app.get('versionName', '?')} ({app.get('versionCode', '?')})" if info_r.ok else info_r.text[:120]),
        result(device.name, "api.diag", diag_r.ok and bool(apk) and "uptime" in diag,
               "uptime, storage, meminfo, hardware" if diag_r.ok else diag_r.text[:120]),
    ]


def installed_version(ctx, device):
    app = ctx.facts(device).info.get("app") or {}
    rel = ctx.release
    ok = app.get("versionCode") == rel.version_code and app.get("versionName") == rel.version_name
    return [result(device.name, "version.installed", ok,
                   f"{app.get('versionName')} ({app.get('versionCode')}) vs release {rel.version_name} ({rel.version_code})")]


def apk_sha(ctx, device):
    facts = ctx.facts(device)
    if not facts.apk_path:
        return [Result(device.name, "version.apk_sha", FAIL, "APK path unknown (diag failed)")]
    if facts.adb:
        _, out = ctx.adb.shell(device, f"sha256sum {facts.apk_path}", timeout=60)
        m = SHA_LINE.match(out.strip())
        sha, via = (m.group(1) if m else None), "adb"
    else:
        sha, _ = ctx.fleet.sha256_of(device, facts.apk_path)
        via = "fleet pull"
    want = ctx.release.sha256
    return [result(device.name, "version.apk_sha", sha == want,
                   f"{(sha or 'unreadable')[:12]}… {'==' if sha == want else '!='} manifest {want[:12]}… ({via})")]


def fleet_health(ctx, device):
    rc, line = ctx.fleet.health_line(device)
    status = line.split("  ")[-1].split()[0] if line else "?"
    pretty = re.sub(r"^.*?\s{2,}", "", line).strip()
    good = rc == 0 and " Good " in f" {line} "
    return [result(device.name, "health.fleet", good, pretty[:110] or f"rc {rc} {status}")]


def dev_mode(ctx, device):
    dev = ctx.facts(device).info.get("devMode")
    return [result(device.name, "health.dev_mode", dev is False, f"devMode={dev}")]


def crashes(ctx, device):
    """No FATAL/ANR for Hearthpane since install (logcat; plus dropbox where adb allows)."""
    facts = ctx.facts(device)
    parsed = logs(ctx, device)
    since = facts.install_local
    window = logscan.since(parsed, since) if since else parsed
    found = list(logscan.crashes(window, PACKAGE))
    starts = logscan.process_starts(window, PACKAGE)
    start = logscan.first_ts(parsed)
    covered = bool(since) and bool(start) and start <= since
    if facts.adb:
        found += dropbox_crashes(ctx, device, facts.install_ms, facts.utc_offset_s)
    note = f"since install {since[6:] or '?'}; log from {start[6:] or '?'}" + ("" if covered else " (partial)")
    if starts:
        note += f"; {len(starts)} process start(s)"
    detail = "; ".join(found[:3]) if found else f"none ({note})"
    status = FAIL if found else (PASS if covered or facts.adb else WARN)
    return [Result(device.name, "health.crashes", status, detail, {"found": found, "processStarts": list(starts)})]


def dropbox_crashes(ctx, device, install_ms, utc_offset_s):
    _, out = ctx.adb.shell(device, "dumpsys dropbox --print data_app_crash data_app_anr data_app_native_crash",
                           timeout=60)
    hits = []
    for entry in re.split(r"\n(?=\d{4}-\d\d-\d\d \d\d:\d\d:\d\d )", out):
        head = entry.split("\n", 1)[0]
        m = re.match(r"(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) (data_app_\w+)", head)
        if not m or f"Process: {PACKAGE}" not in entry:
            continue
        local = datetime.strptime(m.group(1), "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc)
        if (local - timedelta(seconds=utc_offset_s)).timestamp() * 1000 >= install_ms:
            hits.append(f"dropbox {m.group(2)} at {m.group(1)[5:]}")
    return hits

"""Pure parsing for the `ota-update` scenario: logcat markers, poll snapshots, the phase table.

Every input is text the device printed (`logcat -v epoch`, `dumpsys package`, `pidof`), so all of it
is unit-tested without a Portal. Times: logcat lines carry the device wall clock; poll observations
are taken on the Mac and moved onto the device clock with the offset measured from our own
`log -t PerfBenchOta <mark>` lines. Every event is reported in seconds after the "check" request.
"""

import re
import statistics
import time
from collections import namedtuple

from . import PACKAGE

MARK_TAG = "PerfBenchOta"
OTA_TAG = "HearthpaneOta"
FLEET_TAG = "ImmortalFleet"
HOME_CLASS = "com.immortal.launcher.HomeActivity"
# Android resumes Meta's launcher while ours is frozen; Hearthpane's MY_PACKAGE_REPLACED restore
# (ReplacedHomeReceiver) must take the screen back. Before it nothing did unless Android kept
# our HomeActivity record by luck (1.332 OTA over the photo frame: Meta's Home stayed 3.5 min).
STOCK_HOME_CLASS = "com.facebook.aloha.app.home.touch.HomeActivity"
RESTORE_TAG = "HearthpaneReplacedHome"
ASSISTANT = "com.portal.assistant"
WATCHED = (PACKAGE, ASSISTANT, "com.android.systemui", "system_server")
# Device-side filter for the logcat dump (keeps the transfer small; times are filtered here).
LOGCAT_PATTERN = (f"{MARK_TAG}|{OTA_TAG}|committed install|headless install|am_proc_start|am_proc_died|"
                  f"_on_resume_called|Displayed|Force stopping|{RESTORE_TAG}")

LogLine = namedtuple("LogLine", "epoch pid level tag msg")
_LINE = re.compile(r"^\s*(\d+\.\d+)\s+(\d+)\s+\d+\s+([VDIWEFS])\s+([^:]*?)\s*: ?(.*)$")
_AVAILABLE = re.compile(r"update available: (\S+) \((\d+)\)")
_PROC_START = re.compile(r"\[\d+,(\d+),\d+,([^,\]]+)")
_PROC_DIED = re.compile(r"\[\d+,(\d+),([^,\]]+)")

# (key, label, logcat marker, poll observation). Logcat wins when both exist (it is exact).
EVENTS = (
    ("check_sent", "check requested (fleet POST /update/ota check)", "check_sent", "check_sent"),
    ("manifest_fetched", "manifest fetched and validated (check)", "manifest_check", None),
    ("check_done", "check answered (status available)", None, "check_reply"),
    ("install_sent", "install requested (fleet POST /update/ota install)", "install_sent", "install_sent"),
    ("download_start", "download started (manifest re-fetched)", "manifest_install", "status_downloading"),
    ("verified", "download done + integrity verified (installing)", "verified", "status_installing"),
    ("committed", "installer session committed", "committed", None),
    ("old_stopped", "system stops the old launcher (Force stopping, install)", "old_stopped", None),
    ("package_replaced", "package replaced (new versionCode)", None, "version_new"),
    ("old_process_gone", "old launcher process gone", "old_died", "pid_gone"),
    ("stock_home_resumed", "Meta Home resumed in the gap (ours frozen)", "stock_resumed", None),
    ("new_process", "new launcher process started", "new_started", "pid_new"),
    ("home_restore", "Hearthpane restore asked for Home (MY_PACKAGE_REPLACED)", "home_restore", None),
    ("home_first_frame", "Home first frame (Displayed HomeActivity)", "home_displayed", None),
    ("home_resumed", "HomeActivity resumed", "home_resumed", "home_top"),
)

PHASES = (
    ("check (request -> answer)", "check_sent", "check_done"),
    ("  manifest fetch inside the check", "check_sent", "manifest_fetched"),
    ("install request -> download start", "install_sent", "download_start"),
    ("download + integrity check", "download_start", "verified"),
    ("verified -> installer committed", "verified", "committed"),
    ("verified -> system stops old launcher", "verified", "old_stopped"),
    ("verified -> package replaced", "verified", "package_replaced"),
    ("verified -> old process gone", "verified", "old_process_gone"),
    ("old process gone -> new process", "old_process_gone", "new_process"),
    ("new process -> Home first frame", "new_process", "home_first_frame"),
    ("new process -> HomeActivity resumed", "new_process", "home_resumed"),
    ("Meta Home on top: stock resume -> HomeActivity resumed", "stock_home_resumed", "home_resumed"),
)

TOTALS = (
    ("install request -> Home first frame", "install_sent", ("home_first_frame", "home_resumed")),
    ("whole update: check -> Home first frame", "check_sent", ("home_first_frame", "home_resumed")),
    ("launcher down: old process gone -> Home first frame", "old_process_gone", ("home_first_frame", "home_resumed")),
)


def parse_logcat(text):
    out = []
    for raw in (text or "").splitlines():
        m = _LINE.match(raw)
        if m:
            out.append(LogLine(float(m.group(1)), int(m.group(2)), m.group(3), m.group(4).strip(), m.group(5)))
    return out


def _first(lines, after, pred):
    for line in lines:
        if after is not None and line.epoch >= after and pred(line):
            return line
    return None


def markers(lines, package=PACKAGE):
    """Device-clock epochs of the OTA milestones, each searched after the one it depends on."""
    lines = sorted(lines, key=lambda x: x.epoch)
    found, extra = {}, {}

    def mark(name):
        hit = _first(lines, 0.0, lambda x: x.tag == MARK_TAG and x.msg.strip() == name)
        return hit.epoch if hit else None

    found["check_sent"], found["install_sent"] = mark("check-sent"), mark("install-sent")
    ota = lambda prefix: lambda x: x.tag == OTA_TAG and x.msg.startswith(prefix)  # noqa: E731
    check = _first(lines, found["check_sent"], ota("update available"))
    if check and (found["install_sent"] is None or check.epoch < found["install_sent"]):
        found["manifest_check"] = check.epoch
        m = _AVAILABLE.search(check.msg)
        if m:
            extra["available"] = {"versionName": m.group(1), "versionCode": int(m.group(2))}
    hit = _first(lines, found["install_sent"], ota("update available"))
    found["manifest_install"] = hit.epoch if hit else None
    hit = _first(lines, found["install_sent"], ota("installing "))
    found["verified"] = hit.epoch if hit else None
    hit = _first(lines, found["verified"], lambda x: x.tag == FLEET_TAG and f"committed install of {package}" in x.msg)
    found["committed"] = hit.epoch if hit else None
    warn = _first(lines, found["install_sent"], lambda x: x.tag == OTA_TAG and x.level in "WE")
    if warn:
        extra["ota_warning"] = warn.msg[:200]
    after = found["verified"] or found["install_sent"]
    hit = _first(lines, after, lambda x: x.tag == "ActivityManager" and x.msg.startswith(f"Force stopping {package} "))
    found["old_stopped"] = hit.epoch if hit else None
    died = _first(lines, after, lambda x: x.tag == "am_proc_died" and _proc(_PROC_DIED, x.msg)[1] == package)
    found["old_died"] = died.epoch if died else None
    start = _first(lines, after, lambda x: x.tag == "am_proc_start" and _proc(_PROC_START, x.msg)[1] == package)
    found["new_started"] = start.epoch if start else None
    new_pid = _proc(_PROC_START, start.msg)[0] if start else None
    stock = _first(lines, found["old_stopped"], lambda x: x.tag.endswith("_on_resume_called") and STOCK_HOME_CLASS in x.msg)
    found["stock_resumed"] = stock.epoch if stock else None
    restore = _first(lines, found["new_started"], lambda x: x.tag == RESTORE_TAG and "restoring Home" in x.msg)
    found["home_restore"] = restore.epoch if restore else None
    verdict = _first(lines, found["new_started"], lambda x: x.tag == RESTORE_TAG and "Home start(s)" in x.msg)
    if verdict:
        extra["replaced_home"] = verdict.msg[:200]
    if new_pid:
        extra["new_pid"] = new_pid
    shown = _first(lines, found["new_started"], lambda x: x.tag in ("ActivityTaskManager", "ActivityManager")
                   and x.msg.startswith(f"Displayed {package}/{HOME_CLASS}"))
    found["home_displayed"] = shown.epoch if shown else None
    if shown:
        extra["displayed"] = shown.msg.split(":", 1)[-1].strip()
    resumed = _first(lines, found["new_started"], lambda x: x.tag.endswith("_on_resume_called") and HOME_CLASS in x.msg
                     and (new_pid is None or x.pid == new_pid))
    found["home_resumed"] = resumed.epoch if resumed else None
    since = found["check_sent"]
    extra["assistant_starts"] = [x.epoch for x in lines if since is not None and x.epoch >= since
                                 and x.tag == "am_proc_start" and _proc(_PROC_START, x.msg)[1] == ASSISTANT]
    return {k: v for k, v in found.items() if v is not None}, extra


def _proc(pattern, msg):
    m = pattern.search(msg)
    return (int(m.group(1)), m.group(2).strip()) if m else (None, None)


def clock_offset(log_marks, mac_marks):
    """Device epoch minus Mac epoch, from our own marks: [(name, mac_before, mac_after)]."""
    deltas = [log_marks[name] - (before + after) / 2 for name, before, after in mac_marks if name in log_marks]
    return statistics.median(deltas) if deltas else None


def parse_package(text):
    """versionCode, versionName and lastUpdateTime (first of each) from `dumpsys package <pkg>`."""
    code = re.search(r"versionCode=(\d+)", text or "")
    name = re.search(r"versionName=(\S+)", text or "")
    updated = re.search(r"lastUpdateTime=(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)", text or "")
    return {"versionCode": int(code.group(1)) if code else None, "versionName": name.group(1) if name else "",
            "lastUpdateTime": updated.group(1) if updated else ""}


def local_epoch(stamp):
    """'2026-11-22 12:03:04' (device local time; the Portals share the Mac's zone) -> epoch, or None."""
    try:
        return time.mktime(time.strptime(stamp, "%Y-%m-%d %H:%M:%S"))
    except (TypeError, ValueError):
        return None


def parse_snapshot(text):
    """Output of ota.SNAPSHOT_COMMAND: uptime, boot id, watched pids, package version."""
    lines = (text or "").splitlines()
    up = re.match(r"\s*(\d+(?:\.\d+)?)\s", (lines[0] + " ") if lines else "")
    boot = next((x.strip() for x in lines[1:3] if re.fullmatch(r"[0-9a-f-]{36}", x.strip())), "")
    pids = {}
    for line in lines:
        m = re.match(r"pid (\S+)=(.*)$", line.strip())
        if m:
            pids[m.group(1)] = m.group(2).split()[0] if m.group(2).split() else ""
    return {"uptime_s": float(up.group(1)) if up else None, "boot_id": boot, "pids": pids, **parse_package(text)}


def parse_poll(text):
    """'pid=1234' + a versionCode line -> (pid or '', versionCode or None)."""
    m = re.search(r"pid=(\S*)", text or "")
    return (m.group(1) if m else ""), parse_package(text)["versionCode"]


def timeline(log_marks, polls, offset):
    """{event: {t, epoch, source}} with t = seconds after the check request (device clock)."""
    events = {}
    for key, _label, log_key, poll_key in EVENTS:
        if log_key and log_key in log_marks:
            events[key] = {"epoch": log_marks[log_key], "source": "logcat"}
        elif poll_key and poll_key in polls and offset is not None:
            events[key] = {"epoch": polls[poll_key] + offset, "source": "poll (first seen)"}
    zero = events.get("check_sent", {}).get("epoch")
    for e in events.values():
        e["t"] = round(e["epoch"] - zero, 2) if zero is not None else None
        e["epoch"] = round(e["epoch"], 3)
    return events


def _span(events, start, ends):
    a = events.get(start)
    b = next((events[e] for e in ((ends,) if isinstance(ends, str) else ends) if e in events), None)
    if not a or not b:
        return None
    return round(b["epoch"] - a["epoch"], 2)


def phases(events):
    return [{"phase": name, "from": a, "to": b, "seconds": _span(events, a, b)} for name, a, b in PHASES]


def totals(events):
    return [{"total": name, "from": a, "to": ends[0] if ends[0] in events else ends[-1],
             "seconds": _span(events, a, ends)} for name, a, ends in TOTALS]


def download(events, size_bytes):
    secs = _span(events, "download_start", "verified")
    mbps = round(size_bytes / 1e6 / secs, 2) if size_bytes and secs and secs > 0 else None
    return {"bytes": size_bytes, "seconds": secs, "mb_per_s": mbps,
            "note": "download + sha256/package/signer checks (the app logs no separate download-end line)"}


def restarts(before, after, assistant_starts):
    """Reboot and per-process restart verdicts from the before/after snapshots."""
    up0, up1 = before.get("uptime_s"), after.get("uptime_s")
    rebooted = None
    if up0 is not None and up1 is not None:
        rebooted = up1 < up0 or (bool(before.get("boot_id")) and before.get("boot_id") != after.get("boot_id"))
    procs = {}
    for name in WATCHED:
        b, a = before.get("pids", {}).get(name, ""), after.get("pids", {}).get(name, "")
        procs[name] = {"before": b, "after": a, "restarted": bool(b) and b != a}
    procs[ASSISTANT]["starts_in_window"] = len(assistant_starts)
    return {"rebooted": rebooted, "uptime_before_s": up0, "uptime_after_s": up1,
            "boot_id_same": before.get("boot_id") == after.get("boot_id"), "processes": procs}


def _s(x):
    return "-" if x is None else f"{x:.2f}"


def markdown(ota):
    """The OTA section of result.md."""
    ev = ota.get("events", {})
    labels = {k: label for k, label, _l, _p in EVENTS}
    src = ota.get("from") or {}
    dst = ota.get("to") or {}
    lines = [f"## OTA update ({ota.get('mode', '?')}): {src.get('versionName', '?')} ({src.get('versionCode', '?')}) "
             f"-> {dst.get('versionName', '?')} ({dst.get('versionCode', '?')})", "",
             f"- Outcome: {ota.get('outcome', '?')}"]
    if ota.get("note"):
        lines.append(f"- Note: {ota['note']}")
    if not ev:
        return "\n".join(lines)
    lines += ["", "| Phase | From -> To | Seconds |", "|---|---|---|"]
    lines += [f"| {p['phase']} | {p['from']} -> {p['to']} | {_s(p['seconds'])} |" for p in ota.get("phases", [])]
    lines += ["", "| Total | Seconds |", "|---|---|"]
    lines += [f"| {t['total']} (to {t['to']}) | {_s(t['seconds'])} |" for t in ota.get("totals", [])]
    d = ota.get("download") or {}
    lines += ["", f"- Download: {d.get('bytes') or '?'} bytes in {_s(d.get('seconds'))} s = "
              f"{d.get('mb_per_s') or '?'} MB/s ({d.get('note', '')})"]
    r = ota.get("restarts") or {}
    procs = "; ".join(f"{n} {p['before'] or '-'} -> {p['after'] or '-'}{' RESTARTED' if p['restarted'] else ''}"
                      for n, p in (r.get("processes") or {}).items())
    lines += [f"- Reboot: {'YES' if r.get('rebooted') else 'no' if r.get('rebooted') is False else '?'} "
              f"(uptime {r.get('uptime_before_s')} -> {r.get('uptime_after_s')} s, boot id "
              f"{'same' if r.get('boot_id_same') else 'CHANGED'})", f"- Processes (pid before -> after): {procs}"]
    restore = (ota.get("logcat") or {}).get("replaced_home")
    lines.append(f"- Home restore after the replace ({RESTORE_TAG}): {restore or 'none logged'}")
    if ota.get("pm_last_update_t") is not None:
        lines.append(f"- dumpsys lastUpdateTime: t={ota['pm_last_update_t']} s (1 s resolution, cross-check)")
    lines += ["", "| Event | t (s after check) | Source |", "|---|---|---|"]
    order = [k for k, *_ in EVENTS if k in ev]
    lines += [f"| {labels[k]} | {_s(ev[k]['t'])} | {ev[k]['source']} |" for k in order]
    return "\n".join(lines)

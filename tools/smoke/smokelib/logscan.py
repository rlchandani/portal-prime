"""Pure logcat parsing (threadtime format, device-local time). Unit tested."""

import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

LINE = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+(.*?)\s*: (.*)$")
REGISTRY = re.compile(r"control center registry: .*?(\d+) entities")
UPTIME_CLOCK = re.compile(r"(\d\d):(\d\d):(\d\d)")
OFFSET_STEP_MIN = 15


@dataclass(frozen=True)
class Line:
    ts: str  # "MM-DD HH:MM:SS.mmm", device-local; sorts correctly within a year
    pid: int
    level: str
    tag: str
    msg: str


def parse(lines):
    out = []
    for raw in lines:
        m = LINE.match(raw.rstrip("\r"))
        if m:
            out.append(Line(m.group(1), int(m.group(2)), m.group(4), m.group(5), m.group(6)))
    return tuple(out)


def since(parsed, ts):
    return tuple(l for l in parsed if l.ts > ts)


def last_ts(parsed):
    return parsed[-1].ts if parsed else ""


def first_ts(parsed):
    return parsed[0].ts if parsed else ""


def crashes(parsed, package):
    """Crash/ANR evidence for [package]: FATAL EXCEPTION blocks naming it, and system ANR lines."""
    found = []
    for i, l in enumerate(parsed):
        if l.tag == "AndroidRuntime" and "FATAL EXCEPTION" in l.msg:
            block = " ".join(x.msg for x in parsed[i + 1:i + 4]
                             if x.tag == "AndroidRuntime" and x.pid == l.pid and "FATAL EXCEPTION" not in x.msg)
            if re.search(rf"Process: {re.escape(package)}(:[\w.]+)?,", block):
                found.append(f"{l.ts} FATAL EXCEPTION ({l.msg.split(':')[-1].strip()})")
        elif l.tag == "ActivityManager" and l.msg.startswith(f"ANR in {package}"):
            found.append(f"{l.ts} {l.msg[:120]}")
        elif l.level == "F" and l.tag == "libc" and package in l.msg:
            found.append(f"{l.ts} native fatal: {l.msg[:120]}")
    return tuple(found)


def process_starts(parsed, package):
    return tuple(l.ts for l in parsed if l.tag == "ActivityManager" and "Start proc" in l.msg and f":{package}/" in l.msg)


def registry_entities(parsed, pid=None):
    """Entity count from the newest Control Center registry line (of [pid], when given)."""
    for l in reversed(parsed):
        if l.tag == "HearthpaneControl" and (pid is None or l.pid == pid):
            m = REGISTRY.search(l.msg)
            if m:
                return int(m.group(1)), l.ts
    return None, ""


def has(parsed, tag, needle):
    return any(l.tag == tag and needle in l.msg for l in parsed)


def find(parsed, tag, needle):
    return tuple(l for l in parsed if l.tag == tag and needle in l.msg)


def utc_offset(device_clock_text, now_utc):
    """Device UTC offset from its `uptime` clock ("17:10:03 up …") vs our UTC now, to 15 min."""
    m = UPTIME_CLOCK.search(device_clock_text or "")
    if not m:
        return None
    h, mi, s = (int(v) for v in m.groups())
    device_s = h * 3600 + mi * 60 + s
    utc_s = now_utc.hour * 3600 + now_utc.minute * 60 + now_utc.second
    diff = (device_s - utc_s + 12 * 3600) % (24 * 3600) - 12 * 3600
    step = OFFSET_STEP_MIN * 60
    return timedelta(seconds=round(diff / step) * step)


def local_ts(epoch_ms, offset):
    t = datetime.fromtimestamp(epoch_ms / 1000, tz=timezone.utc) + offset
    return t.strftime("%m-%d %H:%M:%S.") + f"{t.microsecond // 1000:03d}"


def parse_kv(text):
    """'dryRun=false testPlan=false floors=0' -> {'dryRun': 'false', …}"""
    return dict(re.findall(r"(\w+)=(\S+)", text or ""))

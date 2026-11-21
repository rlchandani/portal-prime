"""Result types (immutable)."""

from dataclasses import dataclass, field
from typing import Any, Mapping

PASS = "PASS"
FAIL = "FAIL"
WARN = "WARN"
SKIP = "SKIP"
STATUSES = (PASS, FAIL, WARN, SKIP)
FLEET = "fleet"  # the "device" of a check that compares Portals with each other


@dataclass(frozen=True)
class Result:
    device: str
    check: str
    status: str
    detail: str = ""
    data: Mapping[str, Any] = field(default_factory=dict)
    seconds: float = 0.0


def result(device, check, ok, detail="", data=None, fail_status=FAIL):
    return Result(device, check, PASS if ok else fail_status, detail, dict(data or {}))


def skip(device, check, why):
    return Result(device, check, SKIP, why)


def passed(results):
    """True when nothing failed or warned (SKIP means the Portal's access doesn't allow the check)."""
    return all(r.status in (PASS, SKIP) for r in results)


class SafetyRefusal(Exception):
    """A safety rule blocked a step; the check reports SKIP/FAIL instead of touching the device."""

"""`dumpsys meminfo <pid>`: TOTAL PSS and Graphics (MB)."""

import re

_TOTAL = re.compile(r"^\s*TOTAL(?: PSS)?:\s+(\d+)")
_TOTAL_ROW = re.compile(r"^\s*TOTAL\s+(\d+)\s")
_GRAPHICS = re.compile(r"^\s*Graphics:\s+(\d+)")


def parse(text):
    """{"pss_mb": float|None, "graphics_mb": float|None} (kB in the dump)."""
    pss = graphics = None
    for line in text.splitlines():
        m = _TOTAL.match(line) or (_TOTAL_ROW.match(line) if pss is None else None)
        if m and pss is None:
            pss = int(m.group(1))
        m = _GRAPHICS.match(line)
        if m and graphics is None:
            graphics = int(m.group(1))
    mb = lambda kb: round(kb / 1024.0, 1) if kb is not None else None  # noqa: E731
    return {"pss_mb": mb(pss), "graphics_mb": mb(graphics)}

"""Pure parsers for `dumpsys window` and uiautomator XML (unit tested; no device access)."""

import html
import re
from dataclasses import dataclass

NODE = re.compile(r"<node\b[^>]*>")
ATTR = re.compile(r'([\w-]+)="([^"]*)"')
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
WINDOW = re.compile(r"^\s*Window #\d+ Window\{\S+ \S+ ([^}]*)\}")
NOT_OVERLAY = {"BASE_APPLICATION", "1", "APPLICATION_STARTING", "3", "INPUT_METHOD", "2011",
               "DOCK_DIVIDER", "2034"}
PRESENCE = ("Available", "In a call", "Quiet hours", "Offline")
ROSTER_LOOKAHEAD = 3


@dataclass(frozen=True)
class Node:
    text: str
    desc: str
    bounds: tuple
    clickable: bool

    @property
    def center(self):
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2


def nodes(xml):
    out = []
    for m in NODE.finditer(xml or ""):
        a = dict(ATTR.findall(m.group(0)))
        b = BOUNDS.match(a.get("bounds", ""))
        out.append(Node(html.unescape(a.get("text", "")), html.unescape(a.get("content-desc", "")),
                        tuple(int(v) for v in b.groups()) if b else (0, 0, 0, 0), a.get("clickable") == "true"))
    return tuple(out)


def by_desc(all_nodes, desc):
    return next((n for n in all_nodes if n.desc == desc), None)


def roster(all_nodes, names):
    """{room name: its presence label, or None if the room isn't listed} from the Calls screen."""
    texts = [n.text for n in all_nodes if n.text]
    out = {}
    for name in names:
        status = None
        if name in texts:
            i = texts.index(name)
            status = next((t for t in texts[i + 1:i + 1 + ROSTER_LOOKAHEAD] if t in PRESENCE), None)
        out[name] = status
    return out


def visible_overlays(dumpsys, packages):
    """(type, owner) of visible windows owned by [packages] that are not plain activity windows."""
    found = []
    owner, wtype = None, None
    for line in (dumpsys or "").splitlines():
        m = WINDOW.match(line)
        if m:
            owner, wtype = m.group(1), None
            continue
        if owner is None:
            continue
        t = re.search(r"\bty=(\w+)", line)
        if t and wtype is None:
            wtype = t.group(1)
        v = re.search(r"mViewVisibility=(0x[0-9a-f]+)", line)
        if v:
            mine = any(owner.startswith(p) for p in packages)
            if mine and v.group(1) == "0x0" and wtype not in NOT_OVERLAY:
                found.append((wtype or "?", owner))
            owner = None
    return tuple(found)


def count_visible_windows(dumpsys, owner_suffix):
    """Visible windows whose owner ends with [owner_suffix] (an activity draws a preview as a 2nd one)."""
    n, owner = 0, None
    for line in (dumpsys or "").splitlines():
        m = WINDOW.match(line)
        if m:
            owner = m.group(1)
            continue
        v = re.search(r"mViewVisibility=(0x[0-9a-f]+)", line)
        if v and owner is not None:
            n += 1 if owner.endswith(owner_suffix) and v.group(1) == "0x0" else 0
            owner = None
    return n

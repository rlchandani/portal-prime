"""uiautomator XML: locating tap targets by label and auditing horizontal overflow."""

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass

_BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
LABEL_MAX = 48


@dataclass(frozen=True)
class Node:
    cls: str
    text: str
    desc: str
    rid: str
    clickable: bool
    scrollable: bool
    bounds: tuple
    depth: int
    children: tuple = ()

    @property
    def center(self):
        l, t, r, b = self.bounds
        return (l + r) // 2, (t + b) // 2

    @property
    def width(self):
        return self.bounds[2] - self.bounds[0]

    @property
    def label(self):
        return (self.text or self.desc or self.rid or "")[:LABEL_MAX]


def _bounds(s):
    m = _BOUNDS.search(s or "")
    return tuple(int(x) for x in m.groups()) if m else (0, 0, 0, 0)


def _extract_xml(text):
    start = text.find("<?xml")
    start = text.find("<hierarchy") if start < 0 else start
    end = text.rfind("</hierarchy>")
    if start < 0 or end < 0:
        raise ValueError("no uiautomator hierarchy in the dump")
    return text[start:end + len("</hierarchy>")]


def parse(text):
    """All nodes, depth-first, each with its direct children."""
    root = ET.fromstring(_extract_xml(text))

    def build(el, depth):
        kids = tuple(build(c, depth + 1) for c in el if c.tag == "node")
        return Node(el.get("class", ""), el.get("text", ""), el.get("content-desc", ""), el.get("resource-id", ""),
                    el.get("clickable") == "true", el.get("scrollable") == "true", _bounds(el.get("bounds")),
                    depth, kids)

    tops = [build(c, 0) for c in root if c.tag == "node"]
    out = []

    def walk(n):
        out.append(n)
        for c in n.children:
            walk(c)

    for t in tops:
        walk(t)
    return out


def screen_size(nodes):
    """(width, height) of the screen from the root nodes' bounds."""
    roots = [n for n in nodes if n.depth == 0] or nodes
    return max((n.bounds[2] for n in roots), default=0), max((n.bounds[3] for n in roots), default=0)


def find(nodes, label, exact=True, prefer="bottom"):
    """The node whose text or content-desc matches [label] (case-insensitive), or None.

    [prefer] picks among matches: "bottom" (largest centre y: a tab bar), "top", or "first".
    """
    want = label.lower()

    def hit(n):
        vals = (n.text.lower(), n.desc.lower())
        return any(v == want for v in vals) if exact else any(want in v for v in vals)

    matches = [n for n in nodes if hit(n) and n.width > 0]
    if not matches:
        return None
    if prefer == "bottom":
        return max(matches, key=lambda n: n.center[1])
    if prefer == "top":
        return min(matches, key=lambda n: n.center[1])
    return matches[0]


def overflow(nodes, slack=1):
    """Nodes wider than the screen or reaching past its left/right edge, and horizontally
    scrollable containers (a HorizontalScrollView, or scrollable with children past its sides)."""
    width, _ = screen_size(nodes)
    findings = []
    for n in nodes:
        l, _, r, _ = n.bounds
        reasons = []
        if width and n.width > width + slack:
            reasons.append(f"wider than screen ({n.width} > {width})")
        elif width and (l < -slack or r > width + slack):
            reasons.append(f"past screen edge (x {l}..{r}, screen {width})")
        if n.scrollable:
            kids_l = min((c.bounds[0] for c in n.children), default=l)
            kids_r = max((c.bounds[2] for c in n.children), default=r)
            if "HorizontalScrollView" in n.cls or kids_l < l - slack or kids_r > r + slack:
                reasons.append("horizontally scrollable")
        if reasons:
            findings.append({"class": n.cls.rsplit(".", 1)[-1], "label": n.label, "bounds": list(n.bounds),
                             "reasons": reasons})
    return findings

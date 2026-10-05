#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Reproduce the frozen revision 04 SVG icons as Android VectorDrawables.

Run this script to regenerate only approved_icon_*.xml and ApprovedIconCatalog.kt.
Run with --check to verify those outputs without writing anything.
The immutable input is bundled in scripts/resources/approved-icons-rev04.json;
the mock tree is not required. Optionally add --verify-source-root PATH to
cross-check all six original source hashes against an available revision 04 tree
(PATH is the directory containing assets/icons.js, native.css, complete.css, etc.).
SVG paths (including A/a arcs) remain verbatim. Circles and rounded rectangles
use exact elliptical arcs. No dependencies, Gradle, Android SDK, or ADB required.
The approved source widths are 2 / 2.2 / 2.4; neither loaded CSS file overrides them.
"""

import argparse
from collections import defaultdict
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
SNAPSHOT = ROOT / "scripts/resources/approved-icons-rev04.json"
# Pin canonical JSON, allowing formatting/newline differences across clone platforms.
SNAPSHOT_SHA256 = "0510e52e0245b35d545ffef33593febad1b652b0b4d5667c71edfa13dd0e1026"
ANDROID = "{http://schemas.android.com/apk/res/android}"
ET.register_namespace("android", ANDROID[1:-1])
ALIASES = {
    "settings": "gear", "portal settings": "gear",
    "com.facebook.alohaapps.settings": "gear", "builtin:settings": "gear",
    "moon": "moonz", "sleep": "moonz",
    "calls": "phone", "builtin:calls": "phone",
    "apps": "gridapps", "grid": "gridapps",
    "photos": "photo", "photo_memory": "photo", "screensaver": "photo",
    "工具": "tools", "quick_controls": "tools", "builtin:tools": "tools",
    "calendar": "cal", "today": "cal", "portalhub": "cal", "com.portal.calendar": "cal",
    "store": "bag", "curated": "bag", "builtin:store": "bag",
    "f-droid": "bag", "fdroid": "bag", "org.fdroid.fdroid": "bag",
    "aurora store": "bag", "aurorastore": "bag", "com.aurora.store": "bag",
    "browser": "globe", "org.chromium.chrome": "globe", "org.mozilla.firefox": "globe",
    "iss": "globe", "news": "globe",
    "org.videolan.vlc": "vlc",
    "newpipe": "film", "org.schabi.newpipe": "film", "camera": "film",
    "ha": "home", "home assistant": "home", "homeassistant": "home",
    "io.homeassistant.companion.android": "home",
    "io.homeassistant.companion.android.minimal": "home",
    "home_s": "homeS", "ha_widget": "homeS",
    "files": "folder", "material files": "folder", "me.zhanghai.android.files": "folder",
    "f-droid store": "bag",
    "breezy weather": "weather", "org.breezyweather": "weather", "aurora": "weather",
    "jarvis": "notes", "com.portal.assistant": "notes", "stories": "notes", "todo": "notes",
    "transistor": "radio", "org.y20k.transistor": "radio",
    "builtin:music": "music",
    "alarm": "bell", "world_clock": "clock", "worldclock": "clock",
    "lamp": "bulb", "speed": "wifi", "bluetooth": "bt", "brightness": "display",
    "back": "chevL", "chevronleft": "chevL", "chev_l": "chevL",
    "close": "x", "calculator": "calc", "converter": "calc",
    "crypto": "calc", "stocks": "calc", "options": "calc",
    "timers": "timer", "intercom": "megaphone", "countdowns": "hourglass",
}
COPYRIGHT = """Copyright (c) 2026 Starbright Lab.

This source code is licensed under the MIT license found in the
LICENSE file in the root directory of this source tree."""
NUMBER = r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?"
PAINT = {
    "fill": "black", "stroke": "none", "stroke-width": "1",
    "stroke-linecap": "butt", "stroke-linejoin": "miter", "stroke-miterlimit": "4",
    "fill-rule": "nonzero", "fill-opacity": "1", "stroke-opacity": "1",
}
GEOMETRY = {
    "path": {"d"}, "circle": {"cx", "cy", "r"},
    "rect": {"x", "y", "width", "height", "rx", "ry"},
    "line": {"x1", "y1", "x2", "y2"},
    "polyline": {"points"}, "polygon": {"points"},
}


def number(value):
    value = Decimal(str(value))
    if not value.is_finite():
        raise ValueError(f"Non-finite SVG number: {value}")
    return value


def fmt(value):
    text = format(number(value), "f")
    return (text.rstrip("0").rstrip(".") if "." in text else text) if value != 0 else "0"


def attr(element, key, default=0):
    return number(element.get(key, default))


def android_attributes(values):
    return {ANDROID + key: str(value) for key, value in values.items()}


def validate_path(data):
    """Check Android/SVG command arity, tokens, and arc flags before emitting XML."""
    tokens = re.findall(rf"[MmLlHhVvCcSsQqTtAaZz]|{NUMBER}", data)
    if re.sub(rf"[MmLlHhVvCcSsQqTtAaZz]|{NUMBER}|[\s,]", "", data):
        raise ValueError(f"Unsupported path tokens: {data}")
    if not tokens or tokens[0] not in ("M", "m"):
        raise ValueError("A path must start with M/m")
    arity = {"m": 2, "l": 2, "h": 1, "v": 1, "c": 6, "s": 4, "q": 4,
             "t": 2, "a": 7, "z": 0}
    index = 0
    while index < len(tokens):
        command = tokens[index].lower()
        if command not in arity:
            raise ValueError("Missing path command")
        index += 1
        end = index
        while end < len(tokens) and tokens[end].lower() not in arity:
            end += 1
        count = end - index
        size = arity[command]
        if (size == 0 and count) or (size and (not count or count % size)):
            raise ValueError(f"Invalid {command} command parameter count: {count}")
        if command == "a":
            for start in range(index, end, size):
                if any(number(tokens[start + offset]) not in (0, 1) for offset in (3, 4)):
                    raise ValueError("Arc flags must be 0 or 1")
        index = end


def path_data(element):
    tag = element.tag
    if tag == "path":
        return element.attrib["d"]
    if tag == "circle":
        cx, cy, radius = attr(element, "cx"), attr(element, "cy"), attr(element, "r")
        if radius <= 0:
            raise ValueError("A circle needs a positive radius")
        x0, x1, y, r = map(fmt, (cx + radius, cx - radius, cy, radius))
        return f"M{x0} {y}A{r} {r} 0 1 0 {x1} {y}A{r} {r} 0 1 0 {x0} {y}Z"
    if tag == "rect":
        x, y = attr(element, "x"), attr(element, "y")
        width, height = attr(element, "width"), attr(element, "height")
        if width <= 0 or height <= 0:
            raise ValueError("A rectangle needs positive dimensions")
        rx = attr(element, "rx", element.get("ry", "0"))
        ry = attr(element, "ry", element.get("rx", "0"))
        if min(rx, ry) < 0:
            raise ValueError("A rectangle cannot have a negative corner radius")
        rx, ry = min(rx, width / 2), min(ry, height / 2)
        right, bottom = x + width, y + height
        if not rx or not ry:
            return f"M{fmt(x)} {fmt(y)}H{fmt(right)}V{fmt(bottom)}H{fmt(x)}Z"
        arc = f"A{fmt(rx)} {fmt(ry)} 0 0 1 "
        return (
            f"M{fmt(x + rx)} {fmt(y)}H{fmt(right - rx)}"
            f"{arc}{fmt(right)} {fmt(y + ry)}V{fmt(bottom - ry)}"
            f"{arc}{fmt(right - rx)} {fmt(bottom)}H{fmt(x + rx)}"
            f"{arc}{fmt(x)} {fmt(bottom - ry)}V{fmt(y + ry)}"
            f"{arc}{fmt(x + rx)} {fmt(y)}Z"
        )
    if tag == "line":
        return (f'M{fmt(attr(element, "x1"))} {fmt(attr(element, "y1"))}'
                f'L{fmt(attr(element, "x2"))} {fmt(attr(element, "y2"))}')
    points = element.get("points", "")
    if re.sub(rf"{NUMBER}|[\s,]", "", points):
        raise ValueError("Invalid polyline/polygon points")
    coords = re.findall(NUMBER, points)
    if len(coords) < 4 or len(coords) % 2:
        raise ValueError("A polyline/polygon needs pairs of coordinates")
    return "".join(
        ("M" if index == 0 else "L") + fmt(number(coords[index])) + " " +
        fmt(number(coords[index + 1])) for index in range(0, len(coords), 2)
    ) + ("Z" if tag == "polygon" else "")


def color(value):
    if value == "none":
        return "#00000000"
    if value.lower() in ("#fff", "#ffffff", "white", "black", "#000", "#000000",
                         "currentcolor"):
        return "#FFFFFFFF"
    raise ValueError(f"Unsupported colored paint in monochrome icon: {value}")


def to_vector(svg, name):
    source = ET.fromstring(svg)
    if source.tag != "svg":
        raise ValueError("Expected an SVG root")
    if set(source.attrib) - (set(PAINT) | {"viewBox"}):
        raise ValueError("Unsupported SVG root attributes")
    viewbox = source.get("viewBox", "").split()
    if len(viewbox) != 4:
        raise ValueError("Expected a four-number viewBox")
    x, y, width, height = map(number, viewbox)
    if width != height or width <= 0:
        raise ValueError("Approved icons need a positive square viewBox")
    vector = ET.Element("vector", android_attributes({
        "width": "24dp", "height": "24dp", "viewportWidth": "24", "viewportHeight": "24",
    }))
    destination = vector
    if (x, y, width, height) != (0, 0, 24, 24):
        scale = 24 / width
        destination = ET.SubElement(vector, "group", android_attributes({
            "scaleX": fmt(scale), "scaleY": fmt(scale),
            "translateX": fmt(-x * scale), "translateY": fmt(-y * scale),
        }))
    inherited = PAINT | source.attrib
    for element in source:
        if element.tag not in GEOMETRY:
            raise ValueError(f"Unsupported SVG element: {element.tag}")
        if set(element.attrib) - (GEOMETRY[element.tag] | set(PAINT) | {"opacity"}):
            raise ValueError(f"Unsupported {element.tag} attributes")
        paint = inherited | element.attrib
        data = path_data(element)
        validate_path(data)
        opacity = number(element.get("opacity", "1"))
        fill_alpha = opacity * number(paint["fill-opacity"])
        stroke_alpha = opacity * number(paint["stroke-opacity"])
        if not all(0 <= alpha <= 1 for alpha in (opacity, fill_alpha, stroke_alpha)):
            raise ValueError("Opacity must be in [0, 1]")
        if paint["stroke-linecap"] not in ("butt", "round", "square"):
            raise ValueError("Unsupported stroke cap")
        if paint["stroke-linejoin"] not in ("miter", "round", "bevel"):
            raise ValueError("Unsupported stroke join")
        if paint["fill-rule"] not in ("nonzero", "evenodd"):
            raise ValueError("Unsupported fill rule")
        if number(paint["stroke-width"]) < 0:
            raise ValueError("Stroke width must be nonnegative")
        ET.SubElement(destination, "path", android_attributes({
            "fillColor": color(paint["fill"]), "fillAlpha": fmt(fill_alpha),
            "fillType": "evenOdd" if paint["fill-rule"] == "evenodd" else "nonZero",
            "strokeColor": color(paint["stroke"]), "strokeAlpha": fmt(stroke_alpha),
            "strokeWidth": fmt(number(paint["stroke-width"])),
            "strokeLineCap": paint["stroke-linecap"], "strokeLineJoin": paint["stroke-linejoin"],
            "strokeMiterLimit": fmt(number(paint["stroke-miterlimit"])),
            "pathData": data,
        }))
    ET.indent(vector, space="    ")
    header = COPYRIGHT.replace("\n\n", "\n\n    ").replace("\nThis", "\n    This")
    header = header.replace("\nLICENSE", "\n    LICENSE")
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!--\n    {header}\n\n"
        f"    Generated from frozen revision 04 assets/icons.js: {name}.\n"
        "    Regenerate with scripts/generate_approved_icons_rev04.py.\n"
        "-->\n" + ET.tostring(vector, encoding="unicode") + "\n"
    )


def load_icons(verify_source_root=None):
    snapshot = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
    canonical = json.dumps(
        snapshot, sort_keys=True, ensure_ascii=True, separators=(",", ":"),
    ).encode("utf-8")
    if hashlib.sha256(canonical).hexdigest() != SNAPSHOT_SHA256:
        raise ValueError("Bundled approved input changed: approved-icons-rev04.json")
    library = snapshot["icons"]
    path_count = sum(len(ET.fromstring(svg).findall("path")) for svg in library.values())
    if len(library) != 45 or path_count != 51:
        raise ValueError("Expected all 45 frozen SVG icons and 51 original paths")
    if verify_source_root is not None:
        source_root = Path(verify_source_root)
        for path, expected in snapshot["provenance"]["source_sha256"].items():
            try:
                contents = (source_root / path).read_bytes()
            except OSError as error:
                raise ValueError(f"Approved source file unavailable: {source_root / path}") from error
            if hashlib.sha256(contents).hexdigest() != expected:
                raise ValueError(f"Frozen revision 04 source changed: {path}")
        text = (source_root / snapshot["provenance"]["icon_library"]).read_text(encoding="utf-8")
        pairs = re.findall(r"^\s*(\w+):'(<svg[^\n]*?</svg>)',?\s*$", text, re.MULTILINE)
        if len(pairs) != 45 or dict(pairs) != library:
            raise ValueError("Approved source SVGs differ from the bundled snapshot")
    return library


def resource_name(name):
    return "approved_icon_" + re.sub(r"([a-z])([A-Z])", r"\1_\2", name).lower()


def catalog(library):
    names = defaultdict(set)
    for name in library:
        names[name].add(name.lower())
    for alias, target in ALIASES.items():
        if target not in library:
            raise ValueError(f"Alias {alias} points to an unknown icon: {target}")
        names[target].add(alias)
    lookup = {}
    for target, aliases in names.items():
        for alias in aliases:
            if alias in lookup and lookup[alias] != target:
                raise ValueError(f"Conflicting icon alias: {alias}")
            lookup[alias] = target
    branches = []
    for name in sorted(names):
        # Keep the generated cases readable without dynamic resource lookup.
        aliases = sorted(names[name])
        lines = [", ".join(f'"{alias}"' for alias in aliases[index:index + 3])
                 for index in range(0, len(aliases), 3)]
        branches.append("    " + ",\n    ".join(lines) +
                        f" -> R.drawable.{resource_name(name)}")
    copyright_lines = "\n".join(" * " + line if line else " *"
                                for line in COPYRIGHT.splitlines())
    return f"""/*
{copyright_lines}
 */
package com.immortal.launcher

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import java.util.Locale

// Generated from frozen Portal+ revision 04. Use the generator to update the catalog.
// Source paths, stroke widths/caps/joins, fill rules and alpha live in the drawables.
// Aliases follow the approved dock, widget, tool and installed-app glyph assignments.
@DrawableRes
internal fun approvedIconResource(name: String): Int = when (name.lowercase(Locale.ROOT)) {{
{chr(10).join(branches)}
    else -> R.drawable.approved_icon_info
}}

/** Decorative glyph; the enclosing action supplies its accessible label. */
@Composable
internal fun ApprovedIcon(
    name: String,
    modifier: Modifier = Modifier.size(30.dp),
    tint: Color = LocalContentColor.current,
): Unit {{
    Icon(
        painter = painterResource(approvedIconResource(name)),
        contentDescription = null,
        modifier = modifier,
        tint = tint,
    )
}}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify without modifying files")
    parser.add_argument(
        "--verify-source-root", type=Path, metavar="PATH",
        help="Optionally verify six provenance hashes against a revision 04 source directory",
    )
    args = parser.parse_args()
    try:
        library = load_icons(args.verify_source_root)
    except (OSError, ValueError) as error:
        parser.exit(1, f"{error}\n")
    outputs = {
        ROOT / f"app/src/main/res/drawable/{resource_name(name)}.xml": to_vector(svg, name)
        for name, svg in sorted(library.items())
    }
    outputs[ROOT / "app/src/main/java/com/immortal/launcher/ApprovedIconCatalog.kt"] = catalog(library)
    mismatches = []
    for path, expected in outputs.items():
        if args.check:
            if not path.is_file() or path.read_text() != expected:
                mismatches.append(str(path.relative_to(ROOT)))
        else:
            path.write_text(expected)
    if mismatches:
        parser.exit(1, "Missing or stale generated files:\n" + "\n".join(mismatches) + "\n")
    verb = "Verified" if args.check else "Generated"
    paths = sum(svg.count("<path ") for svg in library.values())
    print(f"{verb} {len(library)} vector drawables and the Compose catalog; "
          f"{paths} original SVG paths preserved; immutable bundled input matches."
          + (" 6 original source hashes and SVGs match." if args.verify_source_root is not None else ""))


if __name__ == "__main__":
    main()

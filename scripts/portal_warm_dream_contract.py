#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

"""Pure activity-dump parsing for private warm-Dream return checks.

Only mResumedActivity and live Hist rows supply evidence. Tokens are returned
for private identity comparisons; callers must omit them from public reports.
This module performs no I/O and has no CLI.
"""

import re


_PACKAGE = r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*"
_CLASS = r"\.?[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*"
_RECORD = (
    r"ActivityRecord\{(?P<token>[^\s{}]+)[ \t]+u0[ \t]+"
    rf"(?P<package>{_PACKAGE})/(?P<class>{_CLASS})[ \t]+"
    r"t[0-9]+(?:[ \t]+[^{}\r\n]*)?\}"
)
_RESUMED = re.compile(r"^[ \t]*mResumedActivity[ \t]*:[ \t]*" + _RECORD + r"[ \t]*$")
_LIVE = re.compile(r"^[ \t]*(?:\*[ \t]*)?Hist[ \t]+#[0-9]+[ \t]*:[ \t]*"
                   + _RECORD + r"[ \t]*$")
_RESUMED_LABEL = re.compile(r"^[ \t]*mResumedActivity\b")
_MANAGER_SECTION = re.compile(r"^[ \t]*ACTIVITY MANAGER ([A-Z ]+)")
_HISTORY_SECTION = re.compile(
    r"^[ \t]*(?:Recent(?: tasks| activities)?|Historical(?: tasks| activities)?"
    r"|Activity history|History)[ \t]*:", re.IGNORECASE
)


def parse_activity_dump(text):
    """Return normalized components and tokens from current u0 activity rows.

    A fragment without a section heading is accepted. Explicit recent/history
    sections are excluded until an ACTIVITY MANAGER ACTIVITIES heading.
    A leading dot in a class is relative to its package, including .debug.
    """
    result = {"resumed_component": None, "resumed_token": None, "live_records": []}
    live_section = True
    for line in text.splitlines():
        section = _MANAGER_SECTION.match(line)
        if section:
            live_section = section.group(1).strip() == "ACTIVITIES"
            continue
        if _HISTORY_SECTION.match(line):
            live_section = False
            continue
        if not live_section:
            continue
        resumed = _RESUMED_LABEL.match(line) is not None
        match = (_RESUMED if resumed else _LIVE).match(line)
        if resumed:
            # Explicit null or malformed current state must not preserve a
            # previously seen resumed reference.
            result["resumed_component"] = None
            result["resumed_token"] = None
        if match is None:
            continue
        package = match.group("package")
        cls = match.group("class")
        if cls.startswith("."):
            cls = package + cls
        component = package + "/" + cls
        token = match.group("token")
        if resumed:
            result["resumed_component"] = component
            result["resumed_token"] = token
        else:
            result["live_records"].append({"token": token, "component": component})
    return result


def activity_contract(text, package="com.immortal.launcher.debug"):
    """Return only kind, private resumed identity, and live own-activity counts."""
    parsed = parse_activity_dump(text)
    home = package + "/com.immortal.launcher.HomeActivity"
    preview = package + "/com.immortal.launcher.PhotoFramePreviewActivity"
    component = parsed["resumed_component"]
    kind = {home: "home", preview: "preview", None: "none"}.get(component, "other")
    return {
        "resumedKind": kind,
        "resumedToken": parsed["resumed_token"],
        "liveHomeCount": sum(row["component"] == home for row in parsed["live_records"]),
        "livePreviewCount": sum(row["component"] == preview for row in parsed["live_records"]),
    }

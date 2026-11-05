#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Render a saved schema-v1 two-room delivery receipt offline.

Formats selected fields from public verification.json evidence. This does not
run checks, contact devices, establish fresh health or promote release status.
"""
import argparse
from datetime import datetime
import html
import json
import math
import os
from pathlib import Path
import re
import sys
import tempfile


class EvidenceError(ValueError):
    """Fixed diagnostic; never include captured contents."""


def require(condition):
    if not condition:
        raise EvidenceError("invalid_delivery_evidence")


def text(value):
    require(type(value) is str and bool(value.strip()))
    value.encode("utf-8")
    # Keep prose in one paragraph; prevent explicit links, HTML, fences and rows.
    escaped = html.escape(" ".join(value.split()), quote=False)
    escaped = re.sub(r"([\\`*_{}\[\]()#+!|~=])", r"\\\1", escaped)
    escaped = re.sub(r"^-+", lambda match: r"\-" * len(match[0]), escaped)
    return re.sub(r"^(\d{1,9})\.(?=\s)", r"\1\\.", escaped)


def summary(evidence):
    """Return deterministic Markdown after checking internal evidence consistency."""
    require(type(evidence["schemaVersion"]) is int and evidence["schemaVersion"] == 1)
    require(evidence["status"] == "delivered_and_read_back_in_both_rooms")
    version = evidence["version"]
    require(type(version["code"]) is int and version["code"] > 0)
    name = text(version["name"])
    scope = text(evidence["scope"])
    clock = evidence["issueClock"]
    clock_keys = ("originalStartUtc", "discoveryCapUtc", "targetUtc", "hardLimitUtc")
    dates = [datetime.fromisoformat(clock[key].replace("Z", "+00:00")) for key in clock_keys]
    require(all(date.utcoffset() is not None for date in dates))
    require(all(a <= b for a, b in zip(dates, dates[1:])))
    recorded = datetime.fromisoformat(evidence["recordedUtc"].replace("Z", "+00:00"))
    require(recorded.utcoffset() is not None and recorded >= dates[0])
    elapsed_minutes = (recorded - dates[0]).total_seconds() / 60
    target_state = "within" if recorded <= dates[2] else "past"
    hard_state = "within" if recorded <= dates[3] else "past"

    build, jvm = evidence["build"], evidence["jvm"]
    require(type(build["exitCode"]) is int and build["exitCode"] == 0)
    require(type(build["seconds"]) in (int, float) and
            math.isfinite(build["seconds"]) and build["seconds"] >= 0)
    require(all(type(jvm[key]) is int and jvm[key] > 0 for key in ("tests", "suites")))
    require(all(type(jvm[key]) is int and jvm[key] == 0
                for key in ("failures", "errors", "skipped")))
    require(evidence["independentReview"]["accepted"] is True)

    candidate = evidence["artifacts"]["performance"]
    require(type(candidate["bytes"]) is int and candidate["bytes"] > 0)
    require(type(candidate["sha256"]) is str and
            re.fullmatch(r"[0-9a-f]{64}", candidate["sha256"]) is not None)
    for room in ("family", "living"):
        installed = evidence["delivery"][room]
        if room == "living":
            installed = installed["installed"]
        require(type(installed["bytes"]) is int and installed["bytes"] == candidate["bytes"])
        require(installed["sha256"] == candidate["sha256"] and installed["serviceReady"] is True)
        app = installed["app"]
        require(type(app["versionCode"]) is int and app["versionCode"] == version["code"])
        require(app["versionName"] == version["name"])
    require(type(evidence["limits"]) is list and bool(evidence["limits"]))
    limits = [text(limit) for limit in evidence["limits"]]

    lines = [
        f"# Recorded delivery {name} / code {version['code']}", "",
        "Offline summary of supplied evidence; this is not a fresh device check.", "",
        scope, "",
        f"Recorded UTC: {text(evidence['recordedUtc'])}.", "",
        f"Elapsed at evidence capture: {elapsed_minutes:.2f} minutes.",
        f"Time limits at evidence capture: target {target_state}; hard limit {hard_state}.", "",
        f"Build exit 0 in {build['seconds']:.2f} seconds. "
        f"JVM: {jvm['tests']} tests across {jvm['suites']} suites; "
        "zero failures, errors or skips. Independent review recorded as accepted.", "",
        f"Candidate: {candidate['bytes']} bytes, SHA-256 `{candidate['sha256']}`.", "",
        "| Room | Recorded version | Candidate bytes/hash | Service ready |",
        "| --- | --- | --- | --- |",
        f"| Family Room | {name} / {version['code']} | Match | Yes |",
        f"| Living Area | {name} / {version['code']} | Match | Yes |", "",
        "Original issue clock (recorded time is not a completion timestamp):", "",
    ]
    labels = ("Start", "Discovery cap", "Target", "Hard limit")
    lines.extend(f"- {label}: {text(clock[key])}" for label, key in zip(labels, clock_keys))
    lines.extend(["", "Recorded limitations:", ""])
    lines.extend(f"- {limit}" for limit in limits)
    return "\n".join(lines) + "\n"


def load_evidence(path):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result)
            result[key] = value
        return result

    def constant(_value):
        raise EvidenceError("invalid_delivery_evidence")

    return json.loads(path.read_text(encoding="utf-8"),
                      object_pairs_hook=pairs, parse_constant=constant)


def publish(rendered, output):
    """Publish complete bytes without replacing a preexisting destination."""
    pending = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8",
                                         dir=output.parent, prefix=".portal-summary-",
                                         delete=False) as stream:
            pending = Path(stream.name)
            stream.write(rendered)
        # Same-directory hard link makes complete publication exclusive and atomic.
        os.link(pending, output)
    finally:
        if pending is not None:
            try:
                pending.unlink()
            except OSError:
                pass  # Output is complete; a cleanup failure may retain public scratch bytes.


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path, help="Saved public verification.json")
    parser.add_argument("--output", type=Path, required=True, help="New Markdown file; never overwritten")
    args = parser.parse_args(argv)
    try:
        rendered = summary(load_evidence(args.evidence))
        publish(rendered, args.output)
    except (OSError, ValueError, TypeError, KeyError, AttributeError,
            OverflowError, RecursionError):
        print("invalid_delivery_evidence_or_output", file=sys.stderr)
        return 2
    print("summary_written")
    return 0


if __name__ == "__main__":
    sys.exit(main())

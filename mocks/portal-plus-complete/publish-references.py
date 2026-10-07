#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Validate all planned captures, build thumbnails and freeze review evidence."""
import hashlib
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
plan = json.loads((HERE / "reference-plan.json").read_text())
coverage = json.loads((HERE / "coverage.json").read_text())
verification = json.loads((HERE / "verification.json").read_text())
conditional = json.loads((HERE / "conditional-verification.json").read_text())
if conditional["failed"] or conditional["errors"]:
    raise ValueError("Conditional verification has failures")
home_layout = json.loads((HERE / "home-layout-verification.json").read_text())
if home_layout["failed"] or home_layout["errors"]:
    raise ValueError("Home layout verification has failures")
advanced_settings = json.loads((HERE / "advanced-settings-verification.json").read_text())
if advanced_settings["failed"] or advanced_settings["errors"]:
    raise ValueError("Advanced settings verification has failures")
capture = json.loads((HERE / "capture-report.json").read_text())
if capture["next"] != len(plan) or {r["id"] for r in capture["results"]} != {p["id"] for p in plan}:
    raise ValueError("Capture report does not cover the complete reference plan")
if any(r["missing"] or not r["images"] for r in capture["results"]):
    raise ValueError("Invalid state in capture report")

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

entries = []
(HERE / "thumbnails").mkdir(exist_ok=True)
for item in plan:
    image_path = HERE / item["file"]
    with Image.open(image_path) as image:
        if image.size != (2160, 1440):
            raise ValueError(f"Wrong dimensions: {image_path}: {image.size}")
        thumbnail = "thumbnails/" + item["id"] + ".jpg"
        image.convert("RGB").resize((480, 320), Image.Resampling.LANCZOS).save(
            HERE / thumbnail, quality=82, optimize=True
        )
    entries.append({
        **item, "thumbnail": thumbnail, "width": 2160, "height": 1440,
        "sha256": digest(image_path), "bytes": image_path.stat().st_size
    })

for item in coverage["sourceHashes"]:
    if digest(ROOT / item["path"]) != item["sha256"]:
        raise ValueError("Source changed since audit: " + item["path"])
if verification["failed"] or verification["errors"]:
    raise ValueError("Browser verification has failures")

manifest = {
    "revision": coverage["revision"],
    "createdAt": datetime.now(timezone.utc).isoformat(),
    "sourceCommit": subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
    ).strip(),
    "branchAtReview": subprocess.check_output(
        ["git", "branch", "--show-current"], cwd=ROOT, text=True
    ).strip(),
    "dimensions": {"width": 2160, "height": 1440},
    "verification": {"total": verification["total"], "passed": verification["passed"]},
    "conditionalVerification": {"total": conditional["total"], "passed": conditional["passed"]},
    "homeLayoutVerification": {
        "cases": home_layout["cases"], "total": home_layout["total"], "passed": home_layout["passed"]
    },
    "advancedSettingsVerification": {
        "total": advanced_settings["total"], "passed": advanced_settings["passed"]
    },
    "sourceHashes": coverage["sourceHashes"],
    "mockHashes": [
        {"path": str(p.relative_to(HERE)), "sha256": digest(p)}
        for p in sorted(HERE.rglob("*"))
        if p.is_file() and p.suffix in {".md", ".html", ".css", ".js", ".cjs", ".py", ".json", ".ttf", ".otf", ".svg", ".jpg", ".png"}
        and not any(d in p.parts for d in ("reference", "thumbnails", "revisions"))
        and p.name not in {"references.json", "references.js", "home-inspect.png"}
    ],
    "entries": entries
}
(HERE / "references.json").write_text(json.dumps(manifest, indent=2) + "\n")
header = """/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
"""
(HERE / "references.js").write_text(
    header + "const PORTAL_REFERENCES = " + json.dumps(manifest) + ";\n"
)
print(json.dumps({
    "references": len(entries), "dimensions": "2160x1440",
    "browserChecks": manifest["verification"], "sourceCommit": manifest["sourceCommit"]
}))

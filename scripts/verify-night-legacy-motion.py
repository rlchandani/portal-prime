#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
# Licensed under the MIT license in the repository's LICENSE file.
"""Check retained CSS motion in the real device probe's anonymous DOM samples."""
import gzip
import json
import sys
from pathlib import Path


def image_ids(sample):
    return {
        node[key]
        for node in sample["nodes"] if node["visible"]
        for key in ("backgroundId", "srcId") if node[key]
    }


def same_scene(a, b):
    return a["documentId"] == b["documentId"] and a["sceneId"] == b["sceneId"]


def verify(report):
    results = []
    for case in report["modes"]:
        samples = case["samples"]
        before = next(s for s in samples if s["phase"] == "partial_dwell")
        frozen = next(s for s in samples if s["phase"] == "settled_pause_reference")
        before_nodes = {n["id"]: n for n in before["nodes"]}
        frozen_animations = {a["id"]: a for a in frozen["animations"]}
        # CSS transitions are admission/spotlight effects, not ongoing photo motion.
        eligible = {
            a["id"]: a for a in before["animations"]
            if a["playState"] == "running" and not a["transition"]
            and a["currentTime"] is not None
            and before_nodes.get(a["targetId"], {}).get("visible", False)
            and a["id"] in frozen_animations
            and frozen_animations[a["id"]]["currentTime"] is not None
        }
        eligible_samples = []
        for sample in samples:
            if sample["phase"] not in ("revealed_reference", "continuation"):
                continue
            # Reject evidence from a new image, even while it is fading in.
            if image_ids(sample) - image_ids(frozen):
                break
            if same_scene(frozen, sample) and not sample["paused"]:
                eligible_samples.append(sample)
        advanced = set()
        reset = False
        states_restored = False
        for sample in eligible_samples:
            current_nodes = {n["id"]: n for n in sample["nodes"]}
            retained_photos = [
                n for n in before["nodes"]
                if n["visible"] and (n["backgroundId"] or n["srcId"])
            ]
            states_restored |= bool(retained_photos) and all(
                n["id"] in current_nodes
                and all(current_nodes[n["id"]][key] == n[key]
                        for key in ("backgroundId", "srcId", "animationPlayState"))
                for n in retained_photos
            )
            current = {a["id"]: a for a in sample["animations"]}
            for key, original in eligible.items():
                now = current.get(key)
                reference = frozen_animations[key]
                if not now or now["currentTime"] is None:
                    continue
                if (now["targetId"], now["name"]) != (original["targetId"], original["name"]):
                    continue
                delta = now["currentTime"] - reference["currentTime"]
                reset |= delta < -50
                if delta > 1 and now["playState"] == "running":
                    advanced.add(key)
        required_motion = case["mode"] in ("kenBurns", "framed", "push")
        passed = (
            frozen["getAnimationsSupported"]
            and bool(eligible_samples) and states_restored and not reset
            and len(advanced) == len(eligible)
            and (bool(eligible) or not required_motion)
        )
        results.append({
            "mode": case["mode"], "passed": passed,
            "retainedSamplesBeforeNewImage": len(eligible_samples),
            "retainedActiveAnimations": len(eligible),
            "retainedAnimationsAdvanced": len(advanced),
            "computedPhotoPlayStatesRestored": states_restored,
            "animationResetObserved": reset,
        })
    return {"passed": len(results) == 11 and all(r["passed"] for r in results),
            "modes": results}


if __name__ == "__main__":
    path = Path(sys.argv[1])
    data = gzip.decompress(path.read_bytes()) if path.suffix == ".gz" else path.read_bytes()
    result = verify(json.loads(data))
    print(json.dumps(result, indent=2))
    sys.exit(0 if result["passed"] else 1)

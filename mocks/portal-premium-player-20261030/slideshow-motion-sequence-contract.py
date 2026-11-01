# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Bounded, in-memory observations of caller-supplied RGB capture frames.

inspect(frames, times, rois, thresholds=None) takes seconds and named normalized
(left, top, right, bottom) ROIs. Pixel boxes round outward with floor/ceil.
Changed pixels differ in ANY RGB channel from the preceding supplied frame.
A near-black pixel has all channels <=8; a near-black ROI has >=99% such pixels.

Black-run durations are observed spans: last black timestamp minus first black
timestamp, including final runs without extrapolation. One sample has span zero.
Unobserved gaps cannot establish continuous blackness, missing frames, panel
FPS, smoothness, or semantic animation. motionObserved means captured pixel
changes only (including noise or overlays inside an ROI).

There is no automatic acceptance rule: pass is None without caller thresholds.
Optional thresholds are per-ROI dictionaries with any of:
maxNearBlackDurationSeconds, maxConsecutiveNearBlackFrames,
minChangedComparisons, maxChangedFraction. Limits are inclusive. A maximum
change threshold with no comparisons remains unknown (pass=None).
No file, video, device, clock, or network I/O occurs in this module.
"""

import math

from PIL import Image, ImageChops


MAX_FRAMES = 512
MAX_ROIS = 16
MAX_FRAME_PIXELS = 8_388_608
MAX_DIMENSION = 4096
MAX_ANALYSIS_PIXELS = 512_000_000  # Sum of ROI areas * supplied frame count.
_BLACK = [255] * 9 + [0] * 247
_CHANGED = [0] + [255] * 255
_THRESHOLDS = {
    "maxNearBlackDurationSeconds", "maxConsecutiveNearBlackFrames",
    "minChangedComparisons", "maxChangedFraction",
}


def _number(value):
    if type(value) not in (int, float):
        return False
    try:
        return math.isfinite(value)
    except OverflowError:
        return False


def _validate(frames, times, rois, thresholds):
    errors, boxes = [], {}
    if not isinstance(frames, (list, tuple)) or not 1 <= len(frames) <= MAX_FRAMES:
        return ["frameCountBounds"], boxes
    if not all(isinstance(frame, Image.Image) and frame.mode == "RGB" for frame in frames):
        return ["rgbFramesRequired"], boxes
    size = frames[0].size
    if (min(size) <= 0 or max(size) > MAX_DIMENSION
            or size[0] * size[1] > MAX_FRAME_PIXELS):
        errors.append("frameSizeBounds")
    if any(frame.size != size for frame in frames):
        errors.append("equalFrameSizes")
    if (not isinstance(times, (list, tuple)) or len(times) != len(frames)
            or not all(_number(t) for t in times)):
        errors.append("finiteTimestampPerFrame")
    elif (any(b <= a or not math.isfinite(float(b) - float(a))
              for a, b in zip(times, times[1:]))
          or not math.isfinite(float(times[-1]) - float(times[0]))):
        errors.append("timestampsStrictlyIncreasing")
    if not isinstance(rois, dict) or not 1 <= len(rois) <= MAX_ROIS:
        errors.append("namedRoiCountBounds")
    else:
        for name, bounds in rois.items():
            valid = (isinstance(name, str) and 0 < len(name) <= 64
                     and isinstance(bounds, (list, tuple)) and len(bounds) == 4
                     and all(_number(value) for value in bounds))
            if valid:
                l, t, r, b = bounds
                valid = 0 <= l < r <= 1 and 0 <= t < b <= 1
            if not valid:
                errors.append("normalizedRoiBounds")
                continue
            boxes[name] = (math.floor(l * size[0]), math.floor(t * size[1]),
                           math.ceil(r * size[0]), math.ceil(b * size[1]))
        if sum((r - l) * (b - t) for l, t, r, b in boxes.values()) * len(frames) > MAX_ANALYSIS_PIXELS:
            errors.append("analysisPixelBudget")
    if thresholds is not None:
        if not isinstance(thresholds, dict):
            errors.append("thresholdSchema")
        else:
            for name, limits in thresholds.items():
                if name not in boxes or not isinstance(limits, dict):
                    errors.append("thresholdSchema")
                    continue
                for key, value in limits.items():
                    valid = key in _THRESHOLDS and _number(value) and value >= 0
                    if key in ("maxConsecutiveNearBlackFrames", "minChangedComparisons"):
                        valid = valid and type(value) is int
                    if key == "maxChangedFraction":
                        valid = valid and value <= 1
                    if not valid:
                        errors.append("thresholdSchema")
    return list(dict.fromkeys(errors)), boxes


def _near_black_count(image):
    channels = [channel.point(_BLACK) for channel in image.split()]
    return ImageChops.multiply(ImageChops.multiply(channels[0], channels[1]),
                               channels[2]).histogram()[255]


def _changed_count(previous, current):
    channels = [channel.point(_CHANGED)
                for channel in ImageChops.difference(previous, current).split()]
    return ImageChops.lighter(ImageChops.lighter(channels[0], channels[1]),
                              channels[2]).histogram()[255]


def inspect(frames, times, rois, thresholds=None):
    """Return observations and optional caller-threshold checks; never mutate inputs."""
    errors, boxes = _validate(frames, times, rois, thresholds)
    result = dict(valid=not errors, errors=errors, **{"pass": None},
                  frameCount=len(frames) if isinstance(frames, (list, tuple)) else 0,
                  rois={}, thresholdChecks=[], smoothnessAssessed=False,
                  blackContinuityBetweenSamplesAssessed=False,
                  scope="supplied_capture_frames_only")
    if errors:
        return result
    times = [float(t) for t in times]
    result.update(timestampsSeconds=times, observedSpanSeconds=times[-1] - times[0],
                  comparisonCount=len(frames) - 1,
                  captureIntervalsSeconds=[b - a for a, b in zip(times, times[1:])])
    for name, box in boxes.items():
        area = (box[2] - box[0]) * (box[3] - box[1])
        per_frame, runs, previous, start = [], [], None, None

        def finish_run(end):
            runs.append(dict(startIndex=start, endIndex=end,
                             startTimestampSeconds=times[start],
                             endTimestampSeconds=times[end],
                             durationSeconds=times[end] - times[start],
                             durationMeaning="first_to_last_observed_black_timestamp",
                             frameCount=end - start + 1))

        for index, frame in enumerate(frames):
            current = frame.crop(box)
            changed = None if previous is None else _changed_count(previous, current)
            black_count = _near_black_count(current)
            black = black_count * 100 >= area * 99
            per_frame.append(dict(index=index, timestampSeconds=times[index],
                                  changedPixels=changed,
                                  changedFraction=None if changed is None else changed / area,
                                  nearBlackPixels=black_count,
                                  nearBlackFraction=black_count / area,
                                  nearBlack=black))
            if black and start is None:
                start = index
            elif not black and start is not None:
                finish_run(index - 1)
                start = None
            previous = current
        if start is not None:
            finish_run(len(frames) - 1)
        changed_comparisons = sum(item["changedPixels"] > 0 for item in per_frame[1:])
        maximum_change = max((item["changedFraction"] for item in per_frame[1:]), default=None)
        longest = max(runs, key=lambda run: run["durationSeconds"], default=None)
        result["rois"][name] = dict(
            normalizedBounds=list(rois[name]), pixelBounds=list(box), pixelCount=area,
            perFrame=per_frame, changedComparisons=changed_comparisons,
            motionObserved=changed_comparisons > 0, maxChangedFraction=maximum_change,
            nearBlackRuns=runs, longestNearBlackRun=longest,
            longestConsecutiveNearBlackDurationSeconds=longest["durationSeconds"] if longest else 0,
            maxConsecutiveNearBlackFrames=max((run["frameCount"] for run in runs), default=0),
            finalFrameExtrapolated=False)
        metrics = result["rois"][name]
        for key, limit in (thresholds or {}).get(name, {}).items():
            metric = {
                "maxNearBlackDurationSeconds": metrics["longestConsecutiveNearBlackDurationSeconds"],
                "maxConsecutiveNearBlackFrames": metrics["maxConsecutiveNearBlackFrames"],
                "minChangedComparisons": changed_comparisons,
                "maxChangedFraction": maximum_change,
            }[key]
            passed = None if metric is None else (
                metric >= limit if key == "minChangedComparisons" else metric <= limit)
            result["thresholdChecks"].append(
                dict(roi=name, threshold=key, limit=limit, observed=metric, **{"pass": passed}))
    statuses = [item["pass"] for item in result["thresholdChecks"]]
    if statuses:
        result["pass"] = False if False in statuses else None if None in statuses else True
    return result

# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""In-memory synthetic whole-photo marker contract; no native/FPS inference or I/O.

Source fixtures must draw solid corner rectangles flush to their source edges,
with integer dimensions int(width * markerFraction), int(height * markerFraction).
Bounding boxes use Pillow's exclusive right/bottom convention. Aspect error is
relative to the source aspect. Results contain geometry/checks only, never pixels,
marker RGB values, source paths, or settings.
"""

import itertools
import math

from PIL import Image, ImageChops


CORNERS = ("tl", "tr", "bl", "br")


def _runs(projection):
    start = None
    for index, present in enumerate(list(projection) + [False]):
        if present and start is None:
            start = index
        elif not present and start is not None:
            yield start, index
            start = None


def _rectangles(image, color):
    """Separate rectangular color islands, including palettes reused in a spread."""
    mask = Image.new("L", image.size, 255)
    for channel, wanted in zip(image.split(), color):
        accepted = channel.point([255 if abs(value - wanted) <= 3 else 0
                                  for value in range(256)])
        mask = ImageChops.multiply(mask, accepted)
    rectangles = []
    for left, right in _runs(mask.getprojection()[0]):
        stripe = mask.crop((left, 0, right, image.height))
        for top, bottom in _runs(stripe.getprojection()[1]):
            region = mask.crop((left, top, right, bottom))
            count = region.histogram()[255]
            if count > 50:
                rectangles.append(dict(box=(left, top, right, bottom), area=count,
                                       solid=count / ((right - left) * (bottom - top)) >= .9))
    return rectangles


def _geometry(meta, markers, viewport):
    boxes = {corner: marker["box"] for corner, marker in zip(CORNERS, markers)}
    tl, tr, bl, br = (boxes[corner] for corner in CORNERS)
    left, top = min(tl[0], bl[0]), min(tl[1], tr[1])
    right, bottom = max(tr[2], br[2]), max(bl[3], br[3])
    width, height = right - left, bottom - top
    source_aspect = meta["width"] / meta["height"]
    aspect_error = abs(width / height / source_aspect - 1) if width > 0 and height > 0 else math.inf
    expected_w = width * int(meta["width"] * meta.get("markerFraction", .12)) / meta["width"]
    expected_h = height * int(meta["height"] * meta.get("markerFraction", .12)) / meta["height"]
    checks = dict(
        markerArea=all(marker["area"] > 50 and marker["solid"] for marker in markers),
        boundedViewport=0 <= left < right <= viewport[0] and 0 <= top < bottom <= viewport[1],
        sourceAspect=aspect_error < .015,
        markerDimensions=all(
            abs((box[2] - box[0]) - expected_w) <= max(3, expected_w * .02)
            and abs((box[3] - box[1]) - expected_h) <= max(3, expected_h * .02)
            for box in boxes.values()),
        cornerPositions=(
            abs(tl[0] - left) <= 3 and abs(tl[1] - top) <= 3
            and abs(tr[2] - right) <= 3 and abs(tr[1] - top) <= 3
            and abs(bl[0] - left) <= 3 and abs(bl[3] - bottom) <= 3
            and abs(br[2] - right) <= 3 and abs(br[3] - bottom) <= 3),
        distinctCorners=len(set(boxes.values())) == 4,
    )
    return dict(id=meta["id"], photoBounds=[left, top, right, bottom],
                photoWidth=width, photoHeight=height,
                aspectError=round(aspect_error, 6) if math.isfinite(aspect_error) else None,
                cornerBBoxes={corner: list(box) for corner, box in boxes.items()},
                markerAreas={corner: marker["area"] for corner, marker in zip(CORNERS, markers)},
                checks=checks)


def _valid_photo(meta):
    if not isinstance(meta, dict) or not isinstance(meta.get("id"), str):
        return False
    if any(type(meta.get(key)) is not int or meta[key] <= 0 for key in ("width", "height")):
        return False
    fraction = meta.get("markerFraction", .12)
    if type(fraction) not in (int, float) or not math.isfinite(fraction) or not 0 < fraction < .5:
        return False
    if min(int(meta["width"] * fraction), int(meta["height"] * fraction)) < 1:
        return False
    colors = meta.get("colors")
    return isinstance(colors, dict) and set(colors) == set(CORNERS) and all(
        isinstance(colors[corner], (list, tuple)) and len(colors[corner]) == 3
        and all(type(value) is int and 0 <= value <= 255 for value in colors[corner])
        for corner in CORNERS)


def inspect(image, photos: list[dict], spread: bool) -> dict:
    """Inspect one actual screenshot against caller-owned synthetic fixture metadata."""
    checks, per_photo = [], []

    def check(name, passed, photo_id=None):
        item = dict(name=name, **{"pass": bool(passed)})
        if photo_id is not None:
            item["photoId"] = photo_id
        checks.append(item)

    valid = (isinstance(image, Image.Image) and type(spread) is bool
             and isinstance(photos, list) and bool(photos) and all(_valid_photo(meta) for meta in photos)
             and len({meta["id"] for meta in photos}) == len(photos))
    check("validInputs", valid)
    if not valid:
        return {"pass": False, "checks": checks, "perPhoto": per_photo}
    rgb = image.convert("RGB")
    cache, used = {}, set()
    for meta in photos:
        choices = []
        for corner in CORNERS:
            color = tuple(meta["colors"][corner])
            if color not in cache:
                cache[color] = _rectangles(rgb, color)
            available = [marker for marker in cache[color] if (color, marker["box"]) not in used]
            check(corner + "MarkerPresent", bool(available), meta["id"])
            choices.append(available)
        if any(not choice for choice in choices):
            per_photo.append(dict(id=meta["id"], photoBounds=None, cornerBBoxes={}))
            continue
        candidates = [(_geometry(meta, markers, rgb.size), markers)
                      for markers in itertools.product(*choices)]
        candidates.sort(key=lambda pair: (
            -sum(pair[0]["checks"].values()),
            pair[0]["aspectError"] if pair[0]["aspectError"] is not None else math.inf,
            pair[0]["photoBounds"][0], pair[0]["photoBounds"][1]))
        geometry, markers = candidates[0]
        for corner, marker in zip(CORNERS, markers):
            used.add((tuple(meta["colors"][corner]), marker["box"]))
        for name, passed in geometry.pop("checks").items():
            check(name, passed, meta["id"])
        per_photo.append(geometry)

    if spread:
        check("spreadPair", len(photos) == 2)
        geometries = [geometry for geometry in per_photo if geometry["photoBounds"] is not None]
        check("spreadGeometryAvailable", len(geometries) == 2)
        if len(photos) == 2 and len(geometries) == 2:
            a, b = sorted(geometries, key=lambda geometry: geometry["photoBounds"][0])
            al, at, ar, ab = a["photoBounds"]
            bl, bt, br, bb = b["photoBounds"]
            check("spreadNoOverlap", ar <= bl or ab <= bt or bb <= at)
            check("spreadHorizontalGap", bl - ar >= 12)
            if photos[0]["width"] * photos[1]["height"] == photos[1]["width"] * photos[0]["height"]:
                check("spreadMatchingWidth", abs(a["photoWidth"] - b["photoWidth"]) <= 3)
                check("spreadMatchingHeight", abs(a["photoHeight"] - b["photoHeight"]) <= 3)
                check("spreadAlignedTop", abs(at - bt) <= 3)
                check("spreadAlignedBottom", abs(ab - bb) <= 3)
    return {"pass": all(item["pass"] for item in checks), "checks": checks, "perPhoto": per_photo}

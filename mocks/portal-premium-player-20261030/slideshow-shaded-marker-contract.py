# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Optional in-memory contract for static, axis-aligned, whole synthetic photos.

Only use when the caller has qualified the mode as axis-aligned and knows the
overlay is BLACK. At each pixel, one shared RGB multiplier in [.15, 1] must
explain the source marker within +/-3 channel values. The multiplier may vary
spatially (including a vertical native clock gradient). This does not identify
the overlay, inspect devices, infer source files, or measure animation/FPS.

The unchanged Gallery oracle supplies geometry and spread checks, including its
existing three-pixel geometry tolerances. Marker interiors must be complete,
solid rectangles. Rotations, perspective, intentional crops, occluded markers,
and shading below .15 are outside this contract. Input and search budgets fail
closed. Results contain checks and geometry only.
"""

import itertools
import math
import pathlib
import types

from PIL import Image, ImageChops, ImageMath


MIN_FACTOR = .15
CHANNEL_TOLERANCE = 3
MAX_PIXELS = 8_388_608
MAX_DIMENSION = 4096  # Also bounds the projection scans for unusually thin inputs.
MAX_PHOTOS = 8
MAX_REGIONS = 64
MAX_MARKERS_PER_COLOR = 8  # At most 8**4 geometry candidates per photo.
SOURCE_PALETTES = {
    "A": dict(tl=(224, 48, 96), tr=(48, 196, 160),
              bl=(64, 120, 232), br=(232, 176, 48)),
    "B": dict(tl=(192, 64, 224), tr=(32, 184, 224),
              bl=(224, 96, 48), br=(128, 200, 48)),
}


def _load_gallery():
    # Fixed sibling source only; compile/exec never reads or writes .pyc files.
    # Loading happens once, outside inspect(), without changing global import
    # settings or registering/replacing another caller's Gallery module.
    path = pathlib.Path(__file__).with_name("gallery-pixel-contract.py")
    module = types.ModuleType("_slideshow_gallery_geometry")
    module.__file__ = str(path)
    exec(compile(path.read_bytes(), str(path), "exec"), module.__dict__)
    return module


_GALLERY = _load_gallery()
CORNERS = _GALLERY.CORNERS


class _SearchBudgetExceeded(Exception):
    pass


def _shade_mask(image, color):
    """Exact interval-intersection test, implemented with native Pillow masks.

    Each nonzero source channel c constrains t to [(p-3)/c, (p+3)/c].
    Intersecting these intervals with [.15, 1] is equivalent to channel range
    checks plus pairwise overlap. Integer cross-products avoid rounding errors
    and avoid enumerating pixels or a discretized list of shading factors.
    Zero source channels simply require p <= 3.
    """
    channels = image.split()
    mask = Image.new("L", image.size, 255)
    for channel, wanted in zip(channels, color):
        low = math.ceil(MIN_FACTOR * wanted - CHANNEL_TOLERANCE)
        high = wanted + CHANNEL_TOLERANCE
        mask = ImageChops.multiply(mask, channel.point(
            [255 if low <= value <= high else 0 for value in range(256)]))
    for a, b in itertools.combinations(range(3), 2):
        if not color[a] or not color[b]:
            continue
        ca, cb = color[a], color[b]
        overlap = ImageMath.lambda_eval(
            lambda v: (abs(v["a"] * cb - v["b"] * ca)
                       <= CHANNEL_TOLERANCE * (ca + cb)) * 255,
            a=channels[a], b=channels[b]).convert("L")
        mask = ImageChops.multiply(mask, overlap)
    return mask


def _rectangles(image, color):
    mask = _shade_mask(image, color)
    rectangles, regions = [], 0
    for stripe_index, (left, right) in enumerate(_GALLERY._runs(mask.getprojection()[0])):
        if stripe_index >= MAX_REGIONS:
            raise _SearchBudgetExceeded
        stripe = mask.crop((left, 0, right, image.height))
        for top, bottom in _GALLERY._runs(stripe.getprojection()[1]):
            regions += 1
            if regions > MAX_REGIONS:
                raise _SearchBudgetExceeded
            count = stripe.crop((0, top, right - left, bottom)).histogram()[255]
            if count > 50:
                rectangles.append(dict(
                    box=(left, top, right, bottom), area=count,
                    solid=count == (right - left) * (bottom - top)))
                if len(rectangles) > MAX_MARKERS_PER_COLOR:
                    raise _SearchBudgetExceeded
    return rectangles


# Copy the oracle's function namespace to substitute ONLY its color detector.
# Its exact-color function and globals stay intact; its geometry, validation,
# marker allocation, candidate ranking, and spread checks are reused verbatim.
_shaded_globals = dict(_GALLERY.__dict__, _rectangles=_rectangles)
_shaded_inspect = types.FunctionType(
    _GALLERY.inspect.__code__, _shaded_globals, "_shaded_gallery_inspect")


def inspect(image, photos: list[dict], spread: bool = False) -> dict:
    """Check caller-supplied pixels and metadata; performs no I/O.

    Metadata uses the Gallery schema: id, source width/height, corner colors,
    and markerFraction (default .12). Markers must be drawn flush to source
    edges with int(source dimension * markerFraction) dimensions. Supply both
    metadata entries and spread=True for an axis-aligned two-photo spread.
    Neutral/repeated corner palettes are rejected to avoid matte ambiguity.
    """
    valid = (isinstance(image, Image.Image) and type(spread) is bool
             and isinstance(photos, list) and 0 < len(photos) <= MAX_PHOTOS
             and all(_GALLERY._valid_photo(meta) for meta in photos)
             and len({meta["id"] for meta in photos}) == len(photos))
    checks = [dict(name="validInputs", **{"pass": valid})]
    if not valid:
        return {"pass": False, "checks": checks, "perPhoto": []}
    bounded = (0 < image.width * image.height <= MAX_PIXELS
               and max(image.size) <= MAX_DIMENSION)
    palette_valid = all(
        len({tuple(color) for color in meta["colors"].values()}) == 4
        and all(max(color) - min(color) >= 64 for color in meta["colors"].values())
        for meta in photos)
    checks.extend([dict(name="boundedInputs", **{"pass": bounded}),
                   dict(name="chromaticCornerPalette", **{"pass": palette_valid})])
    if not bounded or not palette_valid:
        return {"pass": False, "checks": checks, "perPhoto": []}
    try:
        result = _shaded_inspect(image, photos, spread)
    except _SearchBudgetExceeded:
        checks.append(dict(name="candidateBudget", **{"pass": False}))
        return {"pass": False, "checks": checks, "perPhoto": []}
    result["checks"] = checks[1:] + result["checks"]
    return result

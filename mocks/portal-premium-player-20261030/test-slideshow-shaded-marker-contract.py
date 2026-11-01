# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Fixture-free regressions: all source photos and screenshots exist in memory."""

import pathlib
import types
import unittest
from unittest.mock import patch

from PIL import Image, ImageDraw


def load_source(filename):
    path = pathlib.Path(__file__).with_name(filename)
    module = types.ModuleType(filename.replace("-", "_"))
    module.__file__ = str(path)
    exec(compile(path.read_bytes(), str(path), "exec"), module.__dict__)
    return module


CONTRACT = load_source("slideshow-shaded-marker-contract.py")
EXACT = CONTRACT._GALLERY
BOX = (80, 60, 480, 360)


def photo(palette="A", identifier=None, width=400, height=300):
    return dict(id=identifier or palette, width=width, height=height,
                markerFraction=.12, colors=dict(CONTRACT.SOURCE_PALETTES[palette]))


def source(meta):
    w, h = meta["width"], meta["height"]
    mw, mh = int(w * .12), int(h * .12)
    image = Image.new("RGB", (w, h), (75, 80, 85))
    draw = ImageDraw.Draw(image)
    boxes = dict(tl=(0, 0, mw, mh), tr=(w - mw, 0, w, mh),
                 bl=(0, h - mh, mw, h), br=(w - mw, h - mh, w, h))
    for corner, (l, t, r, b) in boxes.items():
        draw.rectangle((l, t, r - 1, b - 1), fill=meta["colors"][corner])
    return image


def shade(image, top=1, bottom=None):
    """Pillow multiplication shades every pixel; only a height-sized loop."""
    bottom = top if bottom is None else bottom
    column = Image.new("RGB", (1, image.height))
    column.putdata([(round(255 * (top + (bottom - top) * y / (image.height - 1))),) * 3
                    for y in range(image.height)])
    from PIL import ImageChops
    return ImageChops.multiply(image, column.resize(image.size))


def composition(placements, top=1, bottom=None, viewport=(960, 600)):
    image = Image.new("RGB", viewport, (90, 90, 90))
    for meta, (l, t, r, b) in placements:
        image.paste(shade(source(meta).resize((r - l, b - t), Image.Resampling.NEAREST),
                          top, bottom), (l, t))
    return image


class ShadedContractTest(unittest.TestCase):
    def assert_rejected(self, image, metas, spread=False, failed=None):
        result = CONTRACT.inspect(image, metas, spread)
        self.assertFalse(result["pass"], result)
        if failed:
            self.assertTrue(any(c["name"] == failed and not c["pass"]
                                for c in result["checks"]), result)

    def test_uniform_and_vertical_shading_of_complete_contained_images(self):
        for palette in ("A", "B"):
            meta = photo(palette)
            for top, bottom in ((1, 1), (.15, .15), (.5, .5), (1, .15), (.15, 1)):
                with self.subTest(palette=palette, top=top, bottom=bottom):
                    result = CONTRACT.inspect(composition([(meta, BOX)], top, bottom), [meta])
                    self.assertTrue(result["pass"], result)
                    self.assertEqual(list(BOX), result["perPhoto"][0]["photoBounds"])
                    self.assertEqual([80, 60, 128, 96],
                                     result["perPhoto"][0]["cornerBBoxes"]["tl"])

    def test_scaled_and_integer_source_geometry(self):
        for meta, box in ((photo(width=403, height=307), (40, 20, 846, 634)),
                          (photo(width=20, height=20), (100, 100, 500, 500))):
            result = CONTRACT.inspect(composition([(meta, box)], 1, .2,
                                                 viewport=(960, 700)), [meta])
            self.assertTrue(result["pass"], result)

    def test_spread_palettes_and_reused_palette(self):
        for palette in ("A", "B"):
            a, b = photo("A", "left"), photo(palette, "right")
            result = CONTRACT.inspect(composition(
                [(a, (20, 60, 420, 360)), (b, (440, 60, 840, 360))], 1, .15),
                [a, b], True)
            self.assertTrue(result["pass"], result)

    def test_factor_and_channel_tolerance_are_bounded_and_shared(self):
        color = (224, 48, 96)
        accepted = ((112, 24, 48), (115, 21, 48), (34, 7, 14), (227, 45, 96))
        rejected = ((116, 20, 48), (112, 40, 48), (20, 4, 8), (240, 52, 103))
        for value in accepted + rejected:
            mask = CONTRACT._shade_mask(Image.new("RGB", (1, 1), value), color)
            self.assertEqual(value in accepted, mask.getpixel((0, 0)) == 255, value)

    def test_mask_matches_continuous_interval_oracle(self):
        # Small, diverse deterministic samples check the vector mask independently.
        colors = list(CONTRACT.SOURCE_PALETTES["A"].values()) + [(224, 0, 96)]
        pixels = [(r, g, b) for r in range(0, 256, 17)
                  for g in range(0, 256, 31) for b in range(0, 256, 47)]
        image = Image.new("RGB", (len(pixels), 1))
        image.putdata(pixels)
        for color in colors:
            expected = []
            for pixel in pixels:
                low, high = .15, 1
                valid = True
                for p, c in zip(pixel, color):
                    if c:
                        low, high = max(low, (p - 3) / c), min(high, (p + 3) / c)
                    else:
                        valid &= p <= 3
                expected.append(255 if valid and low <= high else 0)
            self.assertEqual(bytes(expected), CONTRACT._shade_mask(image, color).tobytes())

    def test_missing_and_cropped_corners(self):
        meta = photo()
        for corner in CONTRACT.CORNERS:
            image = composition([(meta, BOX)], .5)
            geometry = CONTRACT.inspect(image, [meta])["perPhoto"][0]
            l, t, r, b = geometry["cornerBBoxes"][corner]
            ImageDraw.Draw(image).rectangle((l, t, r - 1, b - 1), fill=(40, 40, 40))
            self.assert_rejected(image, [meta], failed=corner + "MarkerPresent")
        image = Image.new("RGB", (960, 600), (90, 90, 90))
        image.paste(shade(source(meta).crop((20, 0, 400, 300)), .5), BOX[:2])
        self.assert_rejected(image, [meta])

    def test_stretch_and_viewport_clipping(self):
        meta = photo()
        self.assert_rejected(composition([(meta, (80, 60, 580, 360))], .5),
                             [meta], failed="sourceAspect")
        for box in ((-20, 60, 380, 360), (600, 60, 1000, 360),
                    (80, -20, 480, 280), (80, 320, 480, 620)):
            self.assert_rejected(composition([(meta, box)], .5), [meta])

    def test_wrong_hue_grey_matte_and_weather_icons(self):
        meta = photo()
        for color in ((24, 112, 48), (60, 60, 60), (255, 255, 255), (240, 200, 40)):
            image = composition([(meta, BOX)], .5)
            ImageDraw.Draw(image).rectangle((80, 60, 127, 95), fill=color)
            self.assert_rejected(image, [meta], failed="tlMarkerPresent")
        # Correctly colored weather-like discs cannot substitute for rectangles.
        image = Image.new("RGB", (960, 600), (90, 90, 90))
        draw = ImageDraw.Draw(image)
        for corner, (l, t) in zip(CONTRACT.CORNERS,
                                 ((80, 60), (432, 60), (80, 324), (432, 324))):
            draw.ellipse((l, t, l + 47, t + 35), fill=meta["colors"][corner])
        self.assert_rejected(image, [meta], failed="markerArea")

    def test_incomplete_marker_interiors_dimensions_and_positions(self):
        meta = photo()
        # A single missing interior pixel must fail, even with intact bounds.
        image = composition([(meta, BOX)], .5)
        image.putpixel((100, 80), (40, 40, 40))
        self.assert_rejected(image, [meta], failed="markerArea")
        image = composition([(meta, BOX)], .5)
        ImageDraw.Draw(image).rectangle((80, 60, 103, 95), fill=(40, 40, 40))
        self.assert_rejected(image, [meta], failed="markerDimensions")
        image = composition([(meta, BOX)], .5)
        marker = image.crop((80, 60, 128, 96))
        ImageDraw.Draw(image).rectangle((80, 60, 127, 95), fill=(40, 40, 40))
        image.paste(marker, (90, 70))
        self.assert_rejected(image, [meta], failed="cornerPositions")

    def test_spread_geometry_stays_strict(self):
        a, b = photo("A"), photo("B")
        for box in ((410, 60, 810, 360), (430, 60, 830, 360),
                    (440, 64, 840, 364), (440, 60, 920, 420)):
            self.assert_rejected(composition([(a, (20, 60, 420, 360)), (b, box)], .5),
                                 [a, b], True)

    def test_budgets_invalid_inputs_and_no_core_io(self):
        meta = photo()
        image = composition([(meta, BOX)], .5)
        for metas in ([], [meta, meta], [dict(meta, width=0)], [dict(meta, colors={})]):
            self.assert_rejected(image, metas, failed="validInputs")
        with patch.object(CONTRACT, "MAX_PIXELS", 100):
            self.assert_rejected(image, [meta], failed="boundedInputs")
        self.assert_rejected(Image.new("RGB", (4097, 1)), [meta], failed="boundedInputs")
        with patch.object(CONTRACT, "MAX_MARKERS_PER_COLOR", 0):
            self.assert_rejected(image, [meta], failed="candidateBudget")
        with patch("builtins.open", side_effect=AssertionError("core I/O")), \
                patch.object(pathlib.Path, "read_bytes", side_effect=AssertionError("source I/O")), \
                patch.object(Image, "open", side_effect=AssertionError("image I/O")):
            self.assertTrue(CONTRACT.inspect(image, [meta])["pass"])
        result = CONTRACT.inspect(image, [meta])
        self.assertNotIn("colors", str(result))
        self.assertNotIn("image", result)

    def test_real_fragment_budgets_fail_closed(self):
        meta = photo()
        for count, size, spacing in ((9, 8, 20), (65, 1, 2)):
            image = Image.new("RGB", (240, 180), (90, 90, 90))
            draw = ImageDraw.Draw(image)
            for index in range(count):
                left = 2 + index * spacing
                draw.rectangle((left, 20, left + size - 1, 20 + size - 1),
                               fill=meta["colors"]["tl"])
            self.assert_rejected(image, [meta], failed="candidateBudget")

    def test_neutral_palettes_rejected_and_import_never_writes_bytecode(self):
        meta = photo()
        meta["colors"]["tl"] = (90, 90, 90)
        self.assert_rejected(composition([(meta, BOX)]), [meta],
                             failed="chromaticCornerPalette")
        import importlib._bootstrap_external
        with patch.object(importlib._bootstrap_external, "_write_atomic",
                          side_effect=AssertionError("bytecode write")):
            reloaded = load_source("slideshow-shaded-marker-contract.py")
        self.assertTrue(reloaded.inspect(composition([(photo(), BOX)]), [photo()])["pass"])

    def test_exact_gallery_oracle_is_unchanged(self):
        meta = photo()
        image = composition([(meta, BOX)])
        self.assertTrue(EXACT.inspect(image, [meta], False)["pass"])
        self.assertFalse(EXACT.inspect(composition([(meta, BOX)], .5), [meta], False)["pass"])
        color = meta["colors"]["tl"]
        ImageDraw.Draw(image).rectangle((80, 60, 127, 95),
                                       fill=tuple(c + 3 for c in color))
        self.assertTrue(EXACT.inspect(image, [meta], False)["pass"])
        ImageDraw.Draw(image).rectangle((80, 60, 127, 95),
                                       fill=tuple(c + 4 for c in color))
        self.assertFalse(EXACT.inspect(image, [meta], False)["pass"])
        self.assertIs(EXACT.inspect.__globals__["_rectangles"], EXACT._rectangles)


if __name__ == "__main__":
    unittest.main()

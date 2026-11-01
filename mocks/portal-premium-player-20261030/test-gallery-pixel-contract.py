# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Portable, in-memory synthetic pixel-contract regressions."""

import importlib.util
import pathlib
import unittest

from PIL import Image, ImageDraw


SPEC = importlib.util.spec_from_file_location(
    "gallery_pixel_contract",
    pathlib.Path(__file__).with_name("gallery-pixel-contract.py"),
)
CONTRACT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONTRACT)


def photo(identifier="a", width=400, height=300, shift=0):
    return dict(id=identifier, width=width, height=height, markerFraction=.12,
                colors=dict(tl=(220, 20 + shift, 30), tr=(20, 220, 30 + shift),
                            bl=(30 + shift, 20, 220), br=(220, 180, 20 + shift)))


def source(meta):
    width, height = meta["width"], meta["height"]
    mw, mh = int(width * meta["markerFraction"]), int(height * meta["markerFraction"])
    image = Image.new("RGB", (width, height), (65, 70, 75))
    draw = ImageDraw.Draw(image)
    for corner, box in dict(tl=(0, 0, mw - 1, mh - 1),
                            tr=(width - mw, 0, width - 1, mh - 1),
                            bl=(0, height - mh, mw - 1, height - 1),
                            br=(width - mw, height - mh, width - 1, height - 1)).items():
        draw.rectangle(box, fill=meta["colors"][corner])
    return image


def composition(placements):
    image = Image.new("RGB", (1000, 700), (10, 12, 14))
    for meta, box in placements:
        image.paste(source(meta).resize((box[2] - box[0], box[3] - box[1]),
                                       Image.Resampling.NEAREST), box[:2])
    return image


class PixelContractTest(unittest.TestCase):
    def test_whole_photo_and_flush_marker_geometry(self):
        meta = photo()
        result = CONTRACT.inspect(composition([(meta, (100, 100, 900, 700))]), [meta], False)
        self.assertTrue(result["pass"], result["checks"])
        self.assertEqual([100, 100, 900, 700], result["perPhoto"][0]["photoBounds"])
        self.assertEqual([100, 100, 196, 172],
                         result["perPhoto"][0]["cornerBBoxes"]["tl"])

    def test_correct_spread_with_reused_palette(self):
        a, b = photo("a"), photo("b")
        result = CONTRACT.inspect(composition([(a, (40, 100, 440, 400)),
                                              (b, (460, 100, 860, 400))]), [a, b], True)
        self.assertTrue(result["pass"], result["checks"])

    def test_integer_source_marker_dimensions_survive_scaling(self):
        meta = photo(width=20, height=20)
        result = CONTRACT.inspect(composition([(meta, (100, 100, 500, 500))]), [meta], False)
        self.assertTrue(result["pass"], result["checks"])
        self.assertEqual([100, 100, 140, 140],
                         result["perPhoto"][0]["cornerBBoxes"]["tl"])

    def test_different_aspect_spread_does_not_require_matching_sizes(self):
        a, b = photo("a"), photo("b", width=300, height=400, shift=10)
        image = composition([(a, (40, 100, 440, 400)), (b, (460, 100, 760, 500))])
        result = CONTRACT.inspect(image, [a, b], True)
        self.assertTrue(result["pass"], result["checks"])

    def test_color_tolerance_accepts_three_and_rejects_four(self):
        meta = photo()
        image = composition([(meta, (100, 100, 500, 400))])
        changed = tuple(value + 3 for value in meta["colors"]["tl"])
        ImageDraw.Draw(image).rectangle((100, 100, 147, 135), fill=changed)
        self.assertTrue(CONTRACT.inspect(image, [meta], False)["pass"])
        ImageDraw.Draw(image).rectangle((100, 100, 147, 135),
                                       fill=tuple(value + 4 for value in meta["colors"]["tl"]))
        self.assertFalse(CONTRACT.inspect(image, [meta], False)["pass"])

    def test_crop_and_stretch_fail(self):
        meta = photo()
        cropped = Image.new("RGB", (1000, 700), (10, 12, 14))
        cropped.paste(source(meta).crop((20, 0, 400, 300)), (100, 100))
        self.assertFalse(CONTRACT.inspect(cropped, [meta], False)["pass"])
        stretched = composition([(meta, (100, 100, 600, 400))])
        self.assertFalse(CONTRACT.inspect(stretched, [meta], False)["pass"])

    def test_missing_corner_and_shrunken_marker_fail(self):
        meta = photo()
        for box in ((100, 100, 147, 135), (100, 100, 125, 135)):
            image = composition([(meta, (100, 100, 500, 400))])
            ImageDraw.Draw(image).rectangle(box, fill=(65, 70, 75))
            self.assertFalse(CONTRACT.inspect(image, [meta], False)["pass"])

    def test_swapped_corners_fail(self):
        meta = photo()
        image = composition([(meta, (100, 100, 500, 400))])
        draw = ImageDraw.Draw(image)
        draw.rectangle((100, 100, 147, 135), fill=meta["colors"]["tr"])
        draw.rectangle((452, 100, 499, 135), fill=meta["colors"]["tl"])
        self.assertFalse(CONTRACT.inspect(image, [meta], False)["pass"])

    def test_overlap_and_insufficient_gap_fail(self):
        a, b = photo("a"), photo("b", shift=10)
        for left in (420, 450):  # Overlap, then only a ten-pixel gap.
            image = composition([(a, (40, 100, 440, 400)),
                                 (b, (left, 100, left + 400, 400))])
            self.assertFalse(CONTRACT.inspect(image, [a, b], True)["pass"])

    def test_same_aspect_spread_rejects_size_or_vertical_mismatch(self):
        a, b = photo("a"), photo("b", shift=10)
        for box in ((460, 104, 860, 404), (460, 100, 940, 460)):
            image = composition([(a, (40, 100, 440, 400)), (b, box)])
            self.assertFalse(CONTRACT.inspect(image, [a, b], True)["pass"])

    def test_tiny_marker_area_fails_and_output_has_no_pixels_or_colors(self):
        meta = photo()
        result = CONTRACT.inspect(composition([(meta, (100, 100, 160, 145))]), [meta], False)
        self.assertFalse(result["pass"])
        self.assertNotIn("colors", str(result))
        self.assertNotIn("image", result)


if __name__ == "__main__":
    unittest.main()

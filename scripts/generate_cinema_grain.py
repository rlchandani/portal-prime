#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Generate the original, deterministic static grain tile; no imaging package."""
import random
import struct
import zlib
from pathlib import Path


def grain_png():
    size = 128
    random_source = random.Random(0xC1E2026)
    pixels = b"".join(
        b"\0" + bytes(random_source.randrange(96, 160) for _ in range(size))
        for _ in range(size)
    )

    def chunk(kind, payload):
        return (
            struct.pack(">I", len(payload)) + kind + payload
            + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
        )

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 0, 0, 0, 0))
        + chunk(b"tEXt", b"Copyright\0Copyright (c) 2026 Starbright Lab; MIT license")
        + chunk(b"IDAT", zlib.compress(pixels, 9))
        + chunk(b"IEND", b"")
    )


if __name__ == "__main__":
    output = Path(__file__).resolve().parents[1] / "app/src/main/assets/screensaver/cinema-grain.png"
    output.write_bytes(grain_png())
    print(f"Generated {output.name}: {output.stat().st_size} bytes, 128x128 grayscale")

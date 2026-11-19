# Copyright (c) 2026 Starbright Lab. MIT license (see LICENSE in the repository root).
"""Regenerates app/src/test/resources/wake/microfrontend-synthetic.txt for MicroFrontendTest.

The expected features come from TensorFlow Lite Micro's C microfrontend built with strict IEEE float
(-ffp-contract=off; clang otherwise fuses multiply-adds on arm64 and moves one filterbank weight by 1/4096):

  1. Fetch the C sources (tensorflow/lite/experimental/microfrontend/lib and kissfft) from
     https://github.com/rhasspy/pymicro-features into SRC.
  2. clang++ -O2 -ffp-contract=off -DFIXED_POINT=16 -I$SRC -I$SRC/kissfft -o ref microfrontend_ref.cc \
       $SRC/tensorflow/lite/experimental/microfrontend/lib/*.cc   (excluding the *_main.cc / memmap tools)
  3. python3 microfrontend_vectors.py signal.raw && ./ref signal.raw > microfrontend-synthetic.txt

The signal is integer-only (an LCG plus a stepped triangle wave), mirrored in MicroFrontendTest, so no audio
file is involved.
"""
import struct
import sys


def signal(n_samples):
    state = 12345
    out = []
    for n in range(n_samples):
        state = (state * 1664525 + 1013904223) & 0xFFFFFFFF
        noise = (state >> 16) - 32768
        seg = (n // 4000) % 6
        period = 18 + (n // 800) % 70
        phase = n % period
        if phase < period // 2:
            tri = (4 * 8000 * phase) // period - 16000
        else:
            tri = 16000 - (4 * 8000 * (phase - period // 2)) // period
        s = [0, noise >> 6, tri + (noise >> 4), 3 * tri + (noise >> 2), (tri >> 3) + (noise >> 9), noise][seg]
        out.append(max(-32768, min(32767, s)))
    return out


if __name__ == "__main__":
    with open(sys.argv[1], "wb") as f:
        f.write(struct.pack("<48000h", *signal(48000)))

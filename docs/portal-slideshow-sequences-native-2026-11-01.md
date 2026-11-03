# Slideshow sequence content checks — Family Area

**Result:** every decoded frame in 16 actual Portal recordings retains generated
photo content in the checked central region. This covers the 12 legacy styles,
then four additional cases with a 16-photo deck. It is a content-visibility
check, not full-screen, per-tile, face-detection, FPS or optical-smoothness
acceptance.

There is **no new runtime fix or performance gain** in this delivery. The
previous Mosaic fix remains installed in optimized, non-debuggable APK
**bfc9430d…**. Source **4a4612b6** adds only the bounded observation checker and
its tests. No APK was installed; Living Area was not changed.

The slot began **11:59:49 UTC** on November 1 with an unchanged hard deadline of
**12:29:49**. All recordings, analysis and fresh device health passed before
**12:14:15**, within **14 minutes 26 seconds**.

## Conditions and what the numbers mean

Wireless ADB recorded the actual PhotoDreamService on Family Area's Android
10/API 29 Portal+, native panel **2160 × 1440**. The encoder used **1080 × 720**
at 3 Mbps, with an 18-second recording limit. Analysis decoded every encoded
frame and resized it to **540 × 360**. Photo motion was enabled, dwell was
five seconds, and the dark-room clock was temporarily disabled.

The first 12 cases use two generated PNGs per deck. Four dense cases use 16
generated landscape, portrait and panorama PNGs. Household albums and provider
data were not used.

The checked normalized region is **(0.3, 0.2, 0.7, 0.6)**. It avoids the corner
clock. Two independent measurements apply:

1. No decoded state has at least 99% of that region at RGB channels ≤8.
2. More than 10% of that region matches the fixture's known **72,76,86** interior
   under a shared black-shading multiplier between 0.15 and 1, within ±3 channel
   values, and with grayscale luminance above 45.

The second measurement rejects an empty gray matte, bright frame bevel, white
paper or caption. Independent review tested the frame/bevel/paper colors and
confirmed they are rejected. This is a generated-fixture check; it is not a
general classifier for arbitrary household photos.

The first sampling pass used every sixth frame. It was replaced by an
**every-encoded-frame analysis**, in 128-frame batches with one-frame overlap.
Every adjacent decoded-frame comparison is retained once. The overlap prevents
losing a transition at a batch boundary. The checker performs no extrapolation
after the last observed frame.

## All-frame results

All **10,222 decoded frames** pass both central-region measurements:
**zero near-black observations**, and fixture-interior presence above 10%.
Observed pixel changes can include compression noise and do not prove semantic
animation.

| Case | Deck photos | Decoded frames | Observed timestamp span, s | Largest timestamp gap, s | Minimum fixture-interior presence |
| --- | ---: | ---: | ---: | ---: | ---: |
| Mosaic | 2 | 972 | 17.936 | 0.082 | 72.05% |
| Panorama | 2 | 1,053 | 17.955 | 0.050 | 94.48% |
| Push | 2 | 1,063 | 17.956 | 0.035 | 94.40% |
| Framed Print | 2 | 1,058 | 17.964 | 0.050 | 85.86% |
| Ken Burns | 2 | 1,049 | 17.951 | 0.050 | 86.85% |
| Diptych | 2 | 384 | 15.489 | 3.200 | 78.08% |
| Gallery Wall | 2 | 114 | **1.917** | 0.035 | 53.09% |
| Memories | 2 | 1,066 | 17.953 | 0.034 | 80.40% |
| Polaroid | 2 | 204 | 14.405 | 4.066 | 29.44% |
| Filmstrip | 2 | 328 | 15.042 | 3.583 | 32.55% |
| Cinema | 2 | 1,053 | 17.969 | 0.051 | 13.75% |
| Hero Sidebar | 2 | 60 | **1.003** | 0.034 | 27.50% |
| Mosaic, dense | 16 | 1,011 | 17.955 | 0.050 | 65.05% |
| Gallery Wall, dense | 16 | 272 | 15.233 | 3.877 | 42.33% |
| Hero Sidebar, dense | 16 | 256 | 15.071 | 3.884 | 74.46% |
| Filmstrip, dense | 16 | 279 | 14.535 | 3.601 | 10.70% |

Quiet compositions may hold their last presentation without emitting another
encoded frame. In particular, the two-photo Gallery Wall and Hero Sidebar
observations do not span a full five-second replacement cycle. The dense clips
provide later presentation states over approximately 15 seconds, but gaps still
prevent claiming uninterrupted panel visibility.

The dense Mosaic contact sheet visibly contains nine tiles. The changing
Filmstrip contacts contain generated portrait and panoramic cards. These
representative contacts support composition review at their displayed
timestamps; the automated region gate does not prove every tile's framing.

![Actual dense Mosaic presentation states](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-premium-player-20261030/evidence/family-slideshow-motion-native/contacts/mosaic-dense.png)

![Actual dense Filmstrip presentation states](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-premium-player-20261030/evidence/family-slideshow-motion-native/contacts/filmstrip-dense.png)

## Tests, review and device restoration

The observation helper's **13 tests pass**. They cover changed/stationary
regions, a changing clock outside the photo region, RGB/99% boundary values,
irregular and invalid timestamps, unknown single-frame durations, caller-only
thresholds, bounded work and input preservation. Independent review verifies
the remaining-eight report's **4,258 frames**, chunk merging and classifier
scope; it does not independently accept the subsequent dense report.

The three capture/restoration parents terminate successfully in **96.474,
183.835 and 95.041 seconds**. Each restores all **97 settings**, **22 source
fields**, pin/accessibility state, automatic-brightness policy and original
Immortal Dream, removes its owned fixtures, retains the APK and leaves no
instrumentation or performance collector. Fresh read-only health confirms
the same installed APK, non-debuggable build and global font scale.

No Android source changed, so the previously passing Android builds/unit gate
were not rerun. This delivery verifies the Python changes with their relevant
tests; it does not claim a fresh Kotlin test run.

Recordings add encoder load. Their frame counts and timestamps are **not**
Panel FPS, Choreographer deadlines, GPU timings or a matched performance
comparison. No CPU/GPU/memory improvement was measured. Cached-face geometry
has its existing targeted source tests; these no-face fixtures do not verify
face recall or protection on a real album.

The user's physical room-light/brightness-comfort check remains pending. The
broader optimization, slideshow and music goal remains incomplete.

[All 16 recordings and per-frame observations](../mocks/portal-premium-player-20261030/evidence/family-slideshow-motion-native/)

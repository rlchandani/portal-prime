# Live cached face metadata — Family Area

New cached face results now reach an incoming legacy slideshow presentation
without restarting its WebView. The current presentation stays steady.
Previously, the controller froze face metadata before its deferred detector
warm-up ran; results only entered a later engine-start snapshot.

Optimized, non-debuggable APK **880e1d48…** is installed on Family Area, signed
with the existing certificate. Branch:
`codex/slideshow-style-matrix-20261101`. Living Area was not changed.

## Behavior and hardware cost

The controller exposes a read-only `getPhotoFocus(index)` bridge. The index
must belong to its frozen photo list, and the engine must still own the
WebView. Ownership is checked before and after the read. Retired owners,
invalid indices and entries larger than 256 characters return no metadata.

The initial preparation already hydrates the shared face cache off Main.
The new getter reads one atomic entry from that in-memory cache. It does not
decode, run the detector, load another album or scan every photo.

`photos.js` keeps at most 64 resource-to-photo metadata aliases. The existing
display preparation consumes its alias after successful decode and the
pause/publication gate, before the mode calculates incoming geometry.
Valid cached point, weight and optional union bounds update the existing
photo object. Malformed data or an unavailable optional bridge preserve the
previous trustworthy fields.

There is one cached read per prepared resource, with no animation-frame
polling, timer, observer, additional worker or pixel allocation. A result
arriving during decode can protect that incoming presentation. A paused
preparation waits for resume; a retired preparation does not read a late
result. Existing visible DOM geometry is not recalculated.

This changes metadata availability. The existing detector, decode caps,
crop policy and group-padding rules remain responsible for analysis and
framing. It does not claim better detector recall or lower frame times.

## Reproduction and verification

| Gate | Result |
| --- | --- |
| Original metadata behavior | **3 failures / 5 new JavaScript tests** |
| Full slideshow JavaScript gate after correction | **331/331 passing** |
| Fresh Android unit gate | **3,761 tests / 394 suites**, no failures, errors or skips |
| Debug and optimized APK builds | Passed, **3 minutes 55 seconds** |
| Final Android test APK build | Passed |
| Actual Family Area native gate | **27/27 checks**, three Window captures |
| Native probe duration | **10.287 seconds** |
| Read-only production review | No concrete blockers |

The native gate runs the actual `PhotoFramePreviewActivity` and production
`PhotoFrameController` on Android 10/API 29, at 2160 × 1440. It supplies one
generated 2400 × 800 image and seeds a point-only entry before launch. After
the first genuine draw/Window capture, it upgrades that same process cache
entry with known separated face bounds. This tests the real cache-to-bridge-
normalizer-to-renderer path, using synthetic cached results.

| Native observation | Red region | Blue region |
| --- | ---: | ---: |
| Initial point-only presentation | 496,773 classified pixels | **0 pixels** |
| After cache update, during the same presentation | **Unchanged geometry and pixels** | **0 pixels** |
| Next presentation | **240,000 pixels / 480 × 500** | **105,000 pixels / 210 × 500** |

Both final rectangles have 100% classified fill and match the largest safe
crop scale. The activity and WebView references remain the same. The current
presentation's central-region hash remains identical after the update; the
next presentation has the new metadata and changed crop geometry.

Photo identity is checked against the original deck URL. An opaque loading
endpoint may become a ready derivative file on the next preparation; that
expected resource change is not classified as a source change or a reload.

![Native photo-region crops before, during the hold, and on the next presentation](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-premium-player-20261030/evidence/family-live-face-metadata/native-sequence.png)

Published PNGs crop rows 0–1099 of the original Window captures to keep the
evidence focused on the generated photo. Measurements use the full original
captures. This is an on-device preview check on the debug artifact, not an
optimized-artifact animation/FPS gate, DreamService-specific test, model-recall
benchmark or household-album trial.

## Restoration and limits

All **97 settings**, photo sources, accessibility configuration, persistent
pin, font scale and brightness policy match their saved values. The Android
system process is unchanged. The original test APK and Dream are restored,
and the optimized candidate is installed.

The original face-cache file is restored byte-for-byte; the generated cache
entry, image/folder and four owned device report/capture files are removed.
The owned preview and draw/lifecycle listeners are destroyed or unregistered.
No instrumentation or performance collector remains active. Credentials,
configuration snapshots, raw captures and rollback APKs remain private.

The issue began **13:47:28 UTC** on November 1, with the original deadline
**14:17:28**. Native validation and restoration finished at **13:58:59 UTC**:
**11 minutes 32 seconds**, without resetting the cap.

Physical night-clock fade comfort remains pending because the user cannot
test now. Whole-platform performance and complete slideshow acceptance remain
open. Music provider/audio wiring remains deferred.

[Native checks](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-live-face-metadata/checks.json),
[marker geometry](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-live-face-metadata/geometry.json)
and [deployment/restoration receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-live-face-metadata/verification.json).

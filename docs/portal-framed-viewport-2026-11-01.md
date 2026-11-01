# Framed Print viewport recovery — Family Area

**Delivered:** Framed Print follows a changing WebView viewport while retaining
the same photo, scene and decoded resource. Narrowing the viewport no longer
leaves the print sized for the previous landscape viewport.

The original issue slot was **14:26:51–14:56:51 UTC, November 1**.
Installation and exact restoration were verified at **14:36:22 UTC**,
**9 minutes 31 seconds** into that slot. The 30-minute cap was not reset.

## Implementation

The change is confined to the Framed Print renderer in `mode-impls.js`.
It measures the current scene, retains each admitted photo's aspect metadata,
and updates the existing print dimensions. A viewport event burst owns one
animation frame. That callback reads the viewport once before updating either
admitted layer, including the two layers retained during a dissolve.

The existing timer owner defers updates while paused and cancels them on
cleanup. Cleanup also removes the resize listener and releases photo metadata.
There is no continuous resize poll, new photo selection, extra decode or
scene restart. Initial-view fallback, photo motion, cached-face behavior,
cadence and dissolve ownership retain their existing contracts.

## Evidence

| Check | Result |
| --- | --- |
| New source regressions before the fix | **0 passed / 4 failed** |
| New source regressions after the fix | **4/4 passed** |
| Existing Framed/Push lifecycle checks plus new regressions | **22/22 passed** |
| Final broader JavaScript suite, including native adapter | **350 passed / 0 failed / 4 skipped** |
| JVM unit gate | **UP-TO-DATE**; unchanged JVM inputs; retained **3,761 tests / 394 suites**, zero failures/errors/skips |
| Final debug and optimized app builds | Passed, **49 seconds** |
| Fresh Android test APK build | Passed, **26 seconds** |
| Family Area API 29 Window/WebView assertions | **46/46**, **4.176 seconds** |
| Actual Window PixelCopy captures | **3**, each **2160 × 1440** |
| Independent corner-pixel verification | All **4** generated corner markers complete in all **3** captures |
| Independent central pixels | **81/81 red pixels** in all **3** captures |
| Native workflow including restoration | **35.985 seconds** |

The controlled source tests reproduce a retained **1,296-pixel** print after
an **800-pixel** viewport resize. It now resizes to **480 pixels**, then returns
to its original dimensions. They also cover a resize burst while paused,
admission after a held decode, pending-frame cancellation on close, listener
removal and resizing both layers during a dissolve.

The physical test uses the shipped renderer, CSS and decoder in one owned,
offline Android Window/WebView. Its generated **400 × 300** PNG has a red center
and four distinct corner squares. The native WebView shrinks and expands
without changing the Activity, Window, document, photo, deck or cursor.

| Physical state | WebView viewport, Window pixels | Independently observed photo pixels | Decode calls / image requests / photo selections |
| --- | --- | --- | --- |
| Initial landscape | **2160 × 1440** | **1296 × 972** | **1 / 1 / 1** |
| Narrow viewport | **892 × 1440** | **536 × 402** | **1 / 1 / 1** |
| Expanded landscape | **2160 × 1440** | **1296 × 972** | **1 / 1 / 1** |

Independent NumPy/Pillow classification checks the complete generated marker
rectangles and their proportions, rather than accepting DOM geometry alone.
The final photo bounds exactly match the initial capture. One physical attempt
passed; no device-test retries were needed.

This verifies renderer reflow in a debug owned WebView with photo motion
disabled. It does not accept physical rotation, the Dream host, panel FPS,
optical motion quality or room-light brightness comfort. The installed optimized
APK contains byte-identical renderer assets; it was not itself instrumented.
Four skipped tests are unrelated optional Chrome checks for the mobile remote.

## Installation and restoration

Installed optimized APK:
**a00d3abe4524a5c6398a04b6d875fd1ab3cb1530c4d09ce930a2fbbc7e11c7bd**.
Physically tested debug APK:
**3f8e9d0ef106044df12bb516715956b872f6a1915fd244adf001f695a3630f24**.
The signing certificate matches the prior installed build.

All **97 settings**, photo-source records, byte-identical face cache,
accessibility configuration, persistent pin, font/brightness policy and original
Dream are preserved. The original test APK is restored. The system process
is unchanged; instrumentation and the collector are inactive. Owned generated
files and reports are removed. Living Area was not touched.

[Native assertions and observations](../mocks/portal-premium-player-20261030/evidence/family-framed-viewport/checks.json),
[independent pixel check](../mocks/portal-premium-player-20261030/evidence/family-framed-viewport/geometry.json)
and [restoration receipt](../mocks/portal-premium-player-20261030/evidence/family-framed-viewport/verification.json)
are public generated evidence, with
[initial](../mocks/portal-premium-player-20261030/evidence/family-framed-viewport/before.png),
[narrow](../mocks/portal-premium-player-20261030/evidence/family-framed-viewport/narrow.png)
and [expanded](../mocks/portal-premium-player-20261030/evidence/family-framed-viewport/after.png)
Window captures. Credentials, household configuration and rollback APKs
remain outside Git.

The latest physical room-light fade check remains pending because the user
cannot test it now. The broader performance goal remains incomplete, and music
provider/audio wiring remains deferred.

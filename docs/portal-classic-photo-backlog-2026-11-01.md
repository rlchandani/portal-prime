# Classic photo navigation backlog — November 1

**Delivered to Family Area.** Classic slideshow navigation now skips superseded
queued photo requests before reading and decoding them. The same actual Android
check changes from **3 failures / 20 checks to 20/20 passing**.

| Controlled 12-request navigation burst | Before | After |
|---|---:|---:|
| Generated PNG file opens | 12 | 1 |
| Generated PNG file closes | 12 | 1 |
| Decoded bitmaps retained in the inactive UI queue | 12 | 1 |
| Allocation bytes owned by those queued bitmaps | 33,748,992 | 2,073,600 |
| Latest requested photo admitted without recycling | Pass | Pass |
| Unpublished captured pixels released on stop | Pass | Pass |
| Displayed pixels prematurely recycled | 0 | 0 |
| Failed assertions | 3 | 0 |

The queued bitmap allocation is **93.86% lower** in this controlled burst.
These are allocation and actual file-read counts, not process memory, decode
latency or panel FPS measurements.

## Implementation

`PhotoFrameController.gen` already identifies the current navigation request.
It is now volatile so the decode worker can observe newer main-thread requests.
Both Classic local and remote workers check generation and stop state before
starting their work. A second check releases an unpublished result if navigation
supersedes it during decoding or fetching. The existing UI check and
`PausableFrameCallbacks.postOwned` discard hook remain in place for later races.

This preserves the existing executor, decoder and pixel ownership model.
An already executing decode or download is **not interrupted**. The native
slideshow loader, stage, pixel budget and callback queue are unchanged.

## Device validation

The fixture uses the real controller and decoder on Family Area's
**Android 10 / API 29, 2160 × 1440** hardware. It owns a UUID-isolated generated
photo folder and briefly holds the actual I/O worker while twelve navigation
requests enter its queue. UI admission is inactive during decoding.

A file observer counts opens and closes only for generated PNGs. Initial
decoding, the expected image header read and caption I/O are outside the
measurement. Before inspecting results, the fixture drains the worker and
requires balanced file events with a 200 ms quiet period. It then verifies the
latest bitmap's identity, dimensions and admission, followed by stop cleanup,
displayed-pixel safety and destruction of its owned activity.

Both runs execute the same ordered twenty checks with no harness failure.
Their native fixture elapsed times are **4.842 and 4.607 seconds**; these
whole-fixture timings are not a decode-speed benchmark. Neither run requires
fixture recycling of unpublished pixels to repair production teardown.

The controlled latch establishes synchronization itself, so this fixture does
not independently prove volatile visibility or supersession during an active
decode. Those paths and the remote pre/post-fetch guards received independent
source review with no actionable findings. A complete remote-source flow was
not device exercised.

## Build and deployment

All **3,765 fresh JVM tests across 395 suites pass**, with zero failures,
errors or skips. Debug, instrumentation and optimized builds pass in the same
**1 minute 41 second** build.

Optimized APK
**872b6af7354b6616320b0081f0525d8c27c0bad29759c3bcc7a02a4a5d76ae10**
is installed with the existing signing certificate. Installation and
preservation checks finish at **19:03:33.609 UTC**, **8 minutes 51.609 seconds**
after the unchanged **18:54:42 UTC** issue start.

All 97 setting values, sources, accessibility services, persistent pin,
brightness policy/value and both face caches match the baseline. The original
test APK and Immortal Dream are restored; no instrumentation or performance
collector remains. The system-server PID is unchanged. Living Area is untouched.

[Before/after evidence](../mocks/portal-premium-player-20261030/evidence/family-classic-backlog/comparison.json)
includes both complete native reports, preservation guards, fresh unit totals,
source hashes and a SHA-256 manifest. The user's physical room-light fade test
remains pending, and the legacy WebView night-motion issue remains parked.
The broader performance/music goal remains incomplete.

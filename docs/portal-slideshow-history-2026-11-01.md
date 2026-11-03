# Interrupted slideshow history — Family Area

**Delivered:** reversing a still-loading history move no longer duplicates
the current photo's history entry or skips an entry in the opposite direction.

Previously, A → B → Previous (still loading A) → Next → Previous returned
**B**, because cancelling the pending move could produce `[A, B, B]`.
It now returns **A**. The reverse-direction and repeated-request cases are
covered too.

The original issue slot was **14:17:22–14:47:22 UTC**. Fresh device restoration
was verified by **14:23:47 UTC**, **6 minutes 25 seconds** into that slot.
The 30-minute cap was not reset.

## Change and evidence

The production change is confined to `scheduler.js`: capture the pending
scene's existing history index before disposal, then select the replacement
relative to that index. Committed history still changes only when a scene
is accepted. The decoder, dissolve, owner limits and late-result guards are
unchanged.

| Check | Result |
| --- | --- |
| New source regressions before the fix | **1 passed / 3 failed** |
| Same source regressions after the fix | **4/4 passed** |
| Broader JavaScript suite | **346 passed / 0 failed / 4 skipped** |
| JVM unit gate | **UP-TO-DATE**; unchanged JVM inputs, retained **3,761 tests / 394 suites**, zero failures/errors/skips |
| Debug and optimized app builds | Passed, **45 seconds** |
| Fresh Android test APK build | Passed, **26 seconds** |
| Family Area API 29 Window/WebView checks | **36/36**, **9.972 seconds** |
| Actual Window captures | **2**, **2160 × 1440** |
| Independently checked endpoint pixels | **81/81 red pixels** at each endpoint |
| Native workflow including restoration | **50.510 seconds** |

The four skipped tests are unrelated, opt-in Chrome checks for the mobile
remote. The controlled source tests exercise the shipped scheduler and
Framed Print renderer, including both cancellation directions, multiple
pending history moves, stale completion and terminal stop.

The physical check reuses the owned offline WebView helper and loads the
shipped scheduler, Ken Burns renderer, CSS and decoder. Three generated
color PNGs provide a deterministic deck. It runs:

1. A, then B, retiring the outgoing owner after each shipped fade.
2. Previous to A, holding the result of an actual successful decode.
3. Next to B, disposing the pending A owner and aborting its original signal.
4. Release the obsolete decode: B and its committed owner remain unchanged;
   the disposed root receives no late writes.
5. Previous: A appears, with the same Activity, WebView and document.

Observed scene ownership is **1, 1, 2, 1, 1, 1** across those checkpoints.
Native endpoint captures independently identify A's red pixels. Intermediate
source indices are observed through the actual DOM, rather than pixel-captured.
One physical attempt passed; no on-device retries were needed.

This verifies interrupted navigation through existing history entries.
It does not accept all possible navigation sequences, actual gesture latency,
continuous panel FPS, optical fade comfort or the Dream host. The physical
probe uses a debug test host; the installed optimized APK contains byte-identical
scheduler and renderer assets, but was not itself instrumented.

## Installation and restoration

Installed optimized APK:
**949374146dfe5cf7338254f0a12ce511657fa909e422cd1df82796c7c56b0eb0**.
Physically tested debug APK:
**8eabd7148995ab365a4d304eb04fbcc108957f4860262a97a05a40698607b83f**.
The signing certificate matches the baseline.

All **97 settings**, source records, byte-identical face cache, accessibility
configuration, persistent pin, font/brightness policy and original Dream are
preserved. The test APK is restored. The system process is unchanged,
instrumentation and the collector are inactive, and owned generated files and
reports are removed. Living Area was not touched.

[Native assertions](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-slideshow-history/checks.json),
[independent pixel check](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-slideshow-history/geometry.json)
and [restoration receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-slideshow-history/verification.json)
are public generated evidence. Credentials, household configuration and
rollback APKs remain outside Git.

The room-light brightness comfort check and broader performance goal remain
incomplete. Music provider/audio wiring remains deferred.

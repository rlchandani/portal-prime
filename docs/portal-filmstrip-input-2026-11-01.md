# Filmstrip input during decoding — Family Area

**Delivered:** a swipe no longer disappears merely because Filmstrip is
finishing another move. The active move completes first; one latest pending
direction then runs against its accepted position. Rapid input cannot start
competing decode batches or build an unlimited queue.

The bounded issue began at **14:04:05 UTC**, with a **14:34:05 UTC** hard limit.
Fresh device restoration was verified by **14:15:39 UTC**, **11 minutes
34 seconds** into that original slot. The cap was not reset.

## Runtime change

`mode-impls.js` uses `makeTimers` for both automatic Filmstrip movement and
manual navigation. Previously, `run` returned `false` while busy; a second
swipe could therefore vanish during a decode or native acknowledgement.
Three native-adapter regression tests reproduced the rejection before the
change.

`navigate` now retains one pending function and settles a superseded request
as cancelled. A separate in-flight owner prevents the asynchronous admission
step from starting two moves. The next destination is evaluated after the
current move commits. Pause retains the input without admitting new work;
successful manual publication resets the existing dwell timer. Cleanup
settles both requests, and a late decoder cannot publish to a closed scene.

There is no polling timer, per-frame callback or new image owner. This fixes
input admission; no delivered panel-FPS improvement is claimed.

## Verification

| Check | Result |
| --- | --- |
| Native-adapter regression before/after | **30/33 → 33/33** |
| New actual legacy-renderer regressions | **5/5**, including a 20-request burst |
| Broader JavaScript suite | **342 passed, 0 failed, 4 skipped** |
| JVM unit gate | **UP-TO-DATE**, unchanged JVM inputs; retained **3,761 tests / 394 suites**, zero failures/errors/skips |
| Debug and optimized builds | Passed, **1 minute 58 seconds** |
| Final Android test APK build | Passed, **23 seconds** |
| Family Area Android 10 Window/WebView probe | **25/25 assertions**, **6.539 seconds** |
| Native captures | **2**, actual **2160 × 1440** Window PixelCopy |
| Independent center pixels | **81/81 red pixels** before and after |
| Final native workflow including restoration | **46.269 seconds** |

The four skipped checks are unrelated, opt-in Chrome checks for the mobile
remote. They were not exercised for this slideshow change. An existing
Filmstrip preparation test explicitly expected rejection while busy; it now
checks retained input, unchanged in-flight request count and complete exposure.
The first broader run was stopped at that obsolete expectation before the
corrected suite passed.

The physical probe loads the shipped renderer, CSS and image decoder in an
owned offline WebView. Three generated color PNGs are available; viewport
admission initially decodes **two**. A test gate holds an actual successful
decode result, then issues Next and Previous. Both promises stay pending,
publication records **[1, 0]**, and the same Activity, WebView and document
return to the original red photo. Closing another busy pair settles both
requests and prevents late DOM changes.

The captured native pixels verify the initial/final red photo; they do not
capture the intervening green photo, optical motion comfort, actual touch
latency or continuous panel frames. Native-bridge queue behavior is exercised
by controlled acknowledgement tests; the physical probe uses the legacy
renderer without that bridge. It is a debug test host, not a probe of the
installed optimized artifact or the Dream host.

## First attempt and restoration

The first physical attempt failed a harness assumption requiring all three
photos to decode before readiness. That contradicted viewport-bounded
admission. The corrected helper reports the decode count and retains strict
native pixel assertions.

The parent's final cleanup check also incorrectly attempted `run-as` after
restoring the non-debuggable APK. An independent Fleet file listing and
**13 fresh restoration checks** confirmed that the original optimized app,
test APK, settings, sources, face cache, services, pin and Dream were restored
and all owned evidence removed. Both failures remain in the
[first-attempt receipt](../mocks/portal-premium-player-20261030/evidence/family-filmstrip-input/first-attempt.json).
Only one corrected physical rerun was made.

The final optimized APK is **ac3b46b796c57e57f1552bebc541abde03f5321d3df38587f4eb558b2ccc0e5c**.
The physically tested debug APK is
**21c32201874379bac8267ab7a292d586d2c293ff7d4a0b334f88f1e0010e1a4b**.
The signing certificate matches the installed baseline.

All **97 settings**, photo-source records, byte-identical face cache,
accessibility configuration, persistent pin, font/brightness policy and
original Dream are preserved. Android's system process did not change.
The test APK is restored, instrumentation and the performance collector are
inactive, and generated files are removed. Living Area was not touched.
Credentials, household configuration, rollback APKs and raw logs stay
outside Git.

See the [final receipt](../mocks/portal-premium-player-20261030/evidence/family-filmstrip-input/verification.json),
[native assertions](../mocks/portal-premium-player-20261030/evidence/family-filmstrip-input/checks.json)
and [pixel check](../mocks/portal-premium-player-20261030/evidence/family-filmstrip-input/geometry.json).

A separate audit reproduced interrupted Previous/Next history duplication;
that is retained for the next bounded issue and was not changed here.
The room-light brightness comfort check and broader performance goal remain
incomplete. Music provider/audio wiring remains deferred.

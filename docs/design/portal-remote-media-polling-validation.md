# Browser playback: retain live controls during polling

The browser companion previously rebuilt Playback on each successful four-second poll. Five
unchanged responses created 135 elements, registered 30 listeners and replaced the card five
times. Chromium confirmed that the focused Pause button was detached and focus was lost.

Playback now retains its mounted view while the room, page and available control structure stay
the same. Metadata, transport state, timeline and volume update only when their displayed state
changes. Unchanged disabled/hidden attributes and native range values are not rewritten. Range
values are compared after clamping and step alignment, matching the browser's sanitization.

A focused seek/volume slider retains its draft and maximum while the rest of the card updates.
Submission clamps to the latest reported maximum. After focus leaves, the range and displayed
value reconcile with the newest response. Artwork uses the existing one-entry ImagePool, binds
the current consumer during pending loads, rejects stale deliveries and retries after failure.
The first successful response clears a prior playback error before rendering.

## Validation

- The complete JavaScript gate passed 101 tests, with zero failures, cancellations or skips.
- Eleven new regressions cover unchanged Home/Remote polling, changing metadata/transport,
  slider interaction, errors/recovery, active/inactive transitions, room isolation, artwork
  version changes/retry and fractional-second native range values.
- Chromium at 412 x 915: five unchanged polls on both Home and Remote produced zero DOM
  mutations and zero range value writes; the focused Pause button remained mounted and focused.
- A 10,345-ms position aligned to 10,000 ms without repeating native value writes.
- Changing title, Play/Pause, position and volume retained transport/seek nodes and displayed
  the new values. During a shrinking-volume-maximum check, the focused draft remained 11 / 15
  while playback advanced to 42 seconds, then reconciled to the reported 3 / 5 after blur.
- No horizontal overflow occurred in the mobile fixture.

Browser data is generated. These checks exercise the shipped controller and real Chromium range
and focus behavior; they do not measure private playback-session interaction on a user's phone.
The existing four-second cadence and media API remain intact.

Debug and optimized builds passed. The Android unit gate passed 1,885 tests across 221 suites,
with zero failures, errors or skips. The deployment helper passed 36 offline tests, including
its corrected inherited selector-verifier binding. The superseded SVG-container build and
pre-correction helper/admission evidence remain archived; neither was deployed.

Family Area retained the candidate after exactly two installs (bootstrap, then candidate) and
two verified install returns to Home. Both served JavaScript assets matched the tested local
bytes. Complete bundled/native-reference profile inventories matched: 8,667 methods and 2,576
classes, including 285 application hot methods. This does not prove individual compiled-method
execution or a native frame-time improvement.

Home readiness, configuration/source invariance, exact installed APK and collector inactivity
passed. No rollback, settings edits, Living Area operations or phone operations occurred. No
private playback-session interaction was measured; generated Chromium fixtures establish that
part of the behavior.

Retained APK SHA-256:
`8a59677565ae95b44c6473c6c8a0d221d53f03e573524d4587746bd4f7c59dbe`.

This issue's original window is 2026-10-12 06:08:15 through 06:38:15 UTC, including rollback
and closure. The clock was not reset. The ledger records the final duration after the local
commit. The overall performance objective remains incomplete.

Local proof root:
`~/portal-prime/build/fleet/diagnostics/remote-media-polling-2026-10-11`.

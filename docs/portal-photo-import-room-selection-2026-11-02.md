# Photo imports survive active-Portal reselection

Bounded unit:16:43:33–17:13:33UTC maximum, including cleanup.
Branch `codex/photo-import-room-selection-20261102`, based on `8f877894`.
The private closure receipt records actual completion time and commit.

## Problem and change

Tapping the already-active “Selected” row under “Your Portals” called
`selectRoom`, which aborted reads, cancelled the running import, reset the session
generation and replaced the photo view. A held first upload became unconfirmed;
the second file was never sent.

Valid, unchanged endpoint/token selection now closes the chooser without
retiring requests, queued imports, the current folder or page generation. Updated
room-name metadata is adopted as an immutable snapshot and reflected in the
header. Captured transfer identity remains unchanged.

A new token at the same endpoint, a different room, removal or an expired pairing
still follows the existing retirement path. No transport, upload acknowledgement,
server storage, native companion or settings-registry behavior was changed.

## Evidence

| Check | Result |
|---|---|
| New regression sensitivity |21 cases:2 failed /19 passed before the patch |
| Focused controller checks |397/397 passed |
| Full browser JavaScript |688/688 passed;15,526ms |
| Slideshow source/labels/styles |354/354 passed;5,607ms |
| JVM gate |3,869 tests /406 suites;0 failures, errors or skips |
| Debug/optimized build |Passed in3m25s; R8/vital lint passed |
| Independent review |No concrete defect;43 focused tests and4 supplementary offline caller checks passed |
| Installed Family1.79 reproduction |1 held request; reselecting cancelled the queued photo and left the first unconfirmed |
| Installed Family1.80 acceptance |1 held request stayed Sending after selection; then both actual uploads completed |
| Selection overhead |0 replacement metadata/roster requests; current folder preserved |
| Actual stored originals |Both matched exactly:95 and96bytes |
|390×844 photo view |2 fixture tiles;0 horizontal overflow |
| Two-room delivery |Exact optimized1.80/code74 installed and fully read back on both |

The baseline intercepted the held upload before forwarding any photo body to the
Portal. Final acceptance held the first request until the Selected interaction,
then allowed both requests to the actual Portal API. Both originals were read back
and compared byte for byte. Browser file selection and room selection used the
shipped UI. No controller implementation was substituted.

The new controller cases cover unchanged selection, renamed metadata, replacement
token and expired pairing. Existing peer-switch/retired-response tests remain.
Independent caller checks include re-pairing, active/final-room removal and
cross-tab token replacement. Those negative credential scenarios are offline
tests; no real pairing was invalidated.

These are request-count and correctness results, not universal browser latency,
physical-phone UX or native slideshow performance measurements.

## Artifact, preservation and cleanup

Package:`com.immortal.launcher.debug`; optimized APK:25,860,898bytes.
SHA-256:
`988881568224a8bfe69b249789e05ed13395c51fad71adb092ac85a77c2b56d6`.
The installed signing identity is unchanged. Both APKs contain the final
controller, and both devices serve those exact controller bytes.

Fleet install connections closed during self-replacement. Their initial exit
status was not treated as installation success; terminal identity and full
installed APK readbacks established the result.

Complete profile, layout and screensaver configuration are unchanged in both
rooms. Family's font scale and enabled accessibility services are unchanged, with
no pending Binding. Its original Chrome task is retained. Both were returned to
their baseline's normal manual photo-frame presentation; Family's resumed
`PhotoFramePreviewActivity` was observed. No natural Dream recovery or new optical
acceptance is claimed.

Two generated gray PNGs, the initially-absent isolated public directory, temporary
file-picker inputs and extra browser tab were removed. Only those owned files were
deleted; the directory was removed with `rmdir`. Test routes were restored.
No user photo, credential, global Meta setting or account was changed.

Already-open browser remote tabs need a reload to run the newly served JavaScript.
No GitHub authentication/publication or Home Assistant deployment occurred.
Weather1.78 remains separately held under its original exhausted slot.

Public proof:
`mocks/portal-widget-gallery-20261102/evidence/photo-import-room-selection-verification.json`,
`photo-import-reselection-before.png` and `photo-import-reselection-after.png`.
The images show only the generated-file transfer ledger.
Raw private receipts:
`/private/tmp/portal-photo-import-room-selection-20261102/`.

# Current roadmap

Local review page at <http://localhost:8891/roadmap/>. The existing Python server
serves the parent gallery directory. This folder is the entire write scope.

The page is a saved, manually maintained priority queue, not live telemetry,
an exhaustive product inventory or whole-product acceptance. It does not grant
UI redesign approval. The separate gallery remains unapproved.

## Update verified facts

Edit `roadmap.json` and its `updatedAt` / `updatedLabel` fields. Each task separates:

- `status`: `building`, `verified`, `queued`, `approval`, `delivered`, or `parked`.
- `verification`: the evidence level shown visibly on the row and detail pane.
- `deployment`: actual artifact/device state, separate from local gate results.
- `acceptance`, `evidence`, `limits`, and `next`: scoped facts and outstanding work.

`verified` means locally verified maintenance, with no deployment implication.
The delivered/implemented group includes device fixes and CI source; CI is
explicitly “Implemented / locally verified”, with hosted execution unobserved.
Do not infer completion from test counts or infer passing results from running
gates. Update the companion record only after its actual terminal receipt.

Initial facts come from `docs/portal-product-operating-state.md`, the named
delivery receipts and the coordinator's 2026-11-02 terminal native-gate update:
3,796 tests / 398 suites; both APKs, R8, vital lint, version sync and signature
checks passed; maintenance commit `2682e3a99edb4210ea298eff6272c83273a974f5`.
Both rooms now have exact 1.67 / code 61 after the accepted Home timer fix. Native settings maintenance is included, without fresh settings UI acceptance. Phone maintenance remains uninstalled. See docs/portal-home-timer-delivery-2026-11-02.md.

Only these cropped card captures were copied from the private temporary folder:
`world-before-card.png`, `final-three-normal-card.png`, and
`final-three-large-font-cold-card.png`. Full-screen private photos were not copied.

No dependencies, external assets, external requests, polling, synthetic progress,
GitHub authentication/publication, product changes or commits are part of this page.
The single same-origin JSON request loads the saved snapshot.

Main validated desktop 1600 × 1000, mobile 390 × 844, and simulated Portal
2160 × 1440 viewports. Filters, empty-state recovery, text-size image selection,
task details, same-page fragment navigation and mobile focus/return passed.
All thirteen mobile detail panels fit without horizontal overflow; console errors
were zero. Desktop/mobile screenshots were visually reviewed. Main corrected
same-page task-link synchronization and long evidence-string wrapping.
See `validation.json` for the bounded browser receipt. This is not native device
acceptance or a complete accessibility/performance audit.

The later timer snapshot adds the accepted native timer fix and a dialog-only native capture (`images/timer-editor-native.png`). Browser data updates do not imply repeat acceptance of older features.

The visibility snapshot records exact 1.68 / code 62 in both rooms, Family scoped
settings acceptance, 3,800 JVM / 654 JS tests, and the separate committed phone
preflight correction (564 tests, uninstalled). Current version and queue copy
were refreshed. All thirteen mobile detail panels and the desktop update fit
without horizontal overflow; served JSON matched the source file. Earlier
filter/Portal-viewport checks above retain their original snapshot scope.

The local-readiness snapshot adds the verified local-only checker and a queued
browser connection-status investigation. It contains fourteen tasks. The actual
checker passed 40 slideshow JS, 654 bundled JS, 161 Python and 3,800 JVM tests
in 152,626 ms; no installation/publication occurred. The actual performance APK
retains the accepted 1.68 identity. See the local-readiness delivery receipt.
All fourteen mobile detail panels and the desktop update fit without horizontal
overflow after reload; screenshots were visually reviewed and console errors
were zero. Earlier filter and simulated-Portal checks retain their earlier scope.

The connection-status snapshot records exact1.69 in both rooms, Family6/6
installed-browser cancellation trials, zero extra status GETs over six connected
tab switches and unchanged room configuration. It adds a small queued favicon
polish finding (15tasks). Manifest/touch icons already work; phone hardware is
unavailable. Earlier artifact/browser proofs retain their original scope.
All15 mobile detail panels and the desktop update fit without horizontal overflow
after reload; detail screenshots were visually reviewed. Roadmap console errors
were zero. Earlier filter/simulated-Portal checks retain their earlier scope.

The tab-branding snapshot records exact **1.70 / code 64** in both rooms, actual
Family paired/unpaired public PNG requests, preserved management authentication
and complete room configuration. Its16-task queue adds current companion rendered
settings acceptance as the next bounded unit; the independent plan is preparation
only. All16 desktop/mobile detail panels passed overflow/missing-label checks with
zero page errors. The desktop queue screenshot was visually reviewed. Earlier
filter and simulated Portal checks retain their original scope.

The companion rendered snapshot records **1.7.1-music-preview / code13** with
564 fresh JVM tests and33 actual Android UI tests passed. The exact app was
installed/read back only in an owned read-only emulator, which was closed
at09:50:04 UTC. Source AVD files and original devices were preserved. Two
category-picker crops show the actual native before/after; no personal photos
or credentials were copied. The17-task queue keeps live paired-room acceptance
queued and physical Pixel acceptance open. Earlier snapshot proofs retain
their original scope. See the named rendered-settings delivery receipt.

Fresh17-task desktop1600×1000 and mobile390×844 detail checks passed with
no horizontal overflow, missing platform labels or page errors. Both actual
category crops loaded at996×204; the desktop screenshot was visually reviewed.
This saved-page proof does not establish live-room or physical-phone behavior.

The18-task live-settings snapshot records five passed scoped check families on
actual room APIs, plus failed cold native reconnect and an unrun numeric UI check.
The actual cropped native ledger is companion-live-result.png. Complete profiles
and screensaver config were restored in both rooms; owned emulator/relay stopped.
No production patch or physical-phone deployment is implied. Fleet/provisioning
local gates are the next independent unit; cold failure is not silently retried.

Fresh 18-item snapshot browser check: every detail opened at 390 × 844 without horizontal overflow or undefined labels; the live-result image returned HTTP 200; desktop detail retained the partial-verification label and cold-failure limitation. No page errors were observed. These are roadmap presentation checks, not native acceptance.

The19-task fleet checkpoint records an implemented CLI transport/JSON correction with23 Rust,17 actual CLI and33 room-helper tests; both-room identity/config readbacks and Family procfs acceptance passed. Windows/hosted gates remain open. Interrupted uploads are a new source-supported candidate queued for isolated reproduction, not a claimed defect.

Fresh19-task checks opened every detail at390×844 and1600×1000 without horizontal overflow, undefined platform labels or page errors. Fleet local verification/Windows limits and the queued upload hypothesis were visibly preserved. This is saved-page presentation evidence only.

The upload-integrity snapshot records exact **1.71/code65** in both rooms,
Family13 API checks, complete configuration preservation and verified owned
scratch cleanup. Three8MB medians remain essentially unchanged. It has20 tasks
with a separate source-supported JSON body hypothesis queued; the Living deploy
helper false negative is visible beside independent exact installed-APK proof.
All20 detail panels were checked at390×844 and1600×1000 without horizontal
overflow, undefined labels or page errors. The updated current-version footer
was visible. These are saved-page checks, not repeat native/phone acceptance.

The JSON-integrity snapshot records exact **1.72/code66** in both rooms,
22 Family JSON/header checks, binary compatibility and unchanged complete room
configuration. The three-sample no-op timing increase (9.50 → 13.24 ms) is visible,
without a performance claim. All21 detail panels were checked at390×844 and
1600×1000 without horizontal overflow, undefined labels or page errors. The
21st task queues the actual deploy-helper false negative with its saved transport
transcript; no helper correction or new Portal release is inferred.

The subsequent deployment-helper snapshot keeps 21 tasks: 10 delivered,
6 locally or partially verified, 2 awaiting approval and 3 parked. The helper
now accepts the exact new response-loss wording while still requiring changed
installed-APK identity and exact size/hash. Its 37 regression tests, saved actual
transcript replay and actual read-only room checks have separate receipts.
No fresh install with lost acknowledgement was performed.

Only the updated detail and queue were checked this unit at 390 × 844 and
1600 × 1000: all 21 task rows rendered, the exact-verification and simulated
replay limits were visible, and there was no horizontal overflow or page error.
Earlier all-detail/filter/image checks retain their original scope.

The subsequent 22-task snapshot closes the unrun native numeric editor case:
seven draft states passed against actual room schemas, disabled-button taps left
whole profiles unchanged, and one native 60-second Save confirmed both rooms.
Only the intended control changed; Living stayed disabled, then whole profiles
and screensaver configs were exactly restored. The owned emulator stopped.
Actual 601-disabled and 600-enabled control crops are linked from the proof area.

Only the changed live-settings/QA-isolation details were checked at 390 × 844 and
1600 × 1000 this unit, with no horizontal overflow, missing labels or page errors.
Both new native crops returned HTTP 200 and were visually reviewed. Cold
reconnect/physical Pixel remain open. Source backing disks/config were preserved
by size/mtime, but seven generated sidecars changed; an independent runner
isolation correction is queued. These saved-page checks do not prove native
performance or cold connection behavior.

The QA-isolation snapshot marks the disposable emulator runner locally verified:
23 offline checks, real unpaired native readiness, exact existing companion APK
readback and zero changes in the original AVD directory. Normal owned shutdown
and clone removal passed. It adds the unfinished no-HA music direction explicitly
to the queue. The current total is 23 tasks: 10 delivered/implemented, 7 local or
partial verification, 2 approval, 3 parked and 1 queued standalone-route review.
No fresh pairing, cold reconnect, physical Pixel, music playback or Android
release is implied by this tooling/presentation update.

Fresh checks opened all 23 details at 1600 × 1000 and 390 × 844: all task rows
rendered, with no horizontal overflow, missing labels or page errors. Desktop
queue and mobile QA detail captures were visually reviewed. These are saved-page
presentation checks; native app and source-isolation proof is recorded separately.

The standalone music snapshot has 24 tasks: 10 delivered/implemented, 8 locally
verified or researched, 2 approval, 3 parked and 1 queued account-dependent
browser acceptance. Actual Family normal-browser Chrome138, public media APIs,
silent48kHz audio and the signed-out official page were observed. The linked
2160 × 642 public crop excludes owner tabs/address bar and contains no account.
No Premium playback, native session/control, continuity or performance proof
is implied. The custom native player still needs its full separately qualified
integration; official website readiness is a smaller route. No MA/HA deployment.

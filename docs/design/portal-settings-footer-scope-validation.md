# Settings footer composition scope validation

## Change and hypothesis

Baseline b23eb6e contains the bounded scrolling sidebar. Its version producer lives in ApprovedSettingsShell, while its value is read in a shared inline Column scope. Captures recorded sixteen sidebar-item composition invocations for eight categories. The second round began roughly 2–3 ms after version publication; that timing was correlation, not sufficient proof of cause.

The candidate moves the unchanged producer and the same two device/model/version Text nodes into a private non-inline composable. FleetConfig.name remains a freshly read String argument from the parent; it is not cached in the helper. No layout wrapper, preference, navigation, animation or build-optimization change is introduced. The same application-context key, IO lookup, failure fallback and versionPublished diagnostic remain. Room-name changes are still reflected when the parent recomposes; the old incidental reread on version publication is removed, and neither version supplies live name observation.

Independent source review found no verified blocker. Compiled benchmark bytecode contains a separate restart group, end group and scope update for the helper, with the producer in that method. This supports scope isolation but is not a frame-performance result. The full gate passed 1,300 tests, zero failures/errors/skips; debug and benchmark builds succeeded.

## Matched device protocol

Family Area and Living Area completed baseline-a, candidate-a, baseline-b and candidate-b. Each block used twenty warmups and ten timed fresh Settings Activity entries in the existing process, source-known actual gear input, explicit fifteen-second captures without stack sampling, delayed metadata polling and exact capture ownership/stop checks. In total, 160 warmups, 80 timed entries and 240 verified collector stops completed. Every full settings schema and screensaver configuration remained unchanged; every block returned Home cleanly. No Dream contamination, callback drops or invalid frames were reported.

Installed APK hashes were verified after each deployment:

- Baseline benchmark: 6717bd78cc5976b21078d7c17de8a9f0c278dd5d84f5da92b8aa86d98f78358b.
- Candidate benchmark: 1ceca7b13054d115f6fa287d0b61c408488adeb712c18d04f824a54382306092.
- Candidate debug build: f861fcdda8c0e781c59d7c8286b7a00e9094646eab66895d478424fc22718354.

Analysis uses final stopped snapshots. For all eighty timed entries, the pre-/post-stop Settings UI-work, search-preparation and frame metrics were also exactly equal. Each entry recorded three Settings callbacks, including two FIRST_DRAW flags; these are Window frame observations, not delivered FPS. ART/profile/thermal state is unmeasured, and fixed ordering plus small warmed cohorts limit generalization.

## Composition result and mixed frame evidence

Every timed baseline entry recorded sixteen sidebar-item invocations; every candidate entry recorded eight. The baseline/candidate/baseline/candidate reversal reproduced this difference in both rooms. This is direct runtime evidence that the footer boundary removes the extra category-composition round in these entries. Invocation count is not a unique-category cardinality test or a measurement of all UI work.

Each table value is the median of ten per-entry largest frames, including first draws. Maximum is the largest recorded frame across that block.

| Room | Pair | Median largest frame ms, baseline → candidate | Maximum frame ms, baseline → candidate | Root-phase proxy ms, baseline → candidate |
| --- | --- | ---: | ---: | ---: |
| Family | a | 142.59 → 123.70 | 157.54 → 144.51 | 40.33 → 36.95 |
| Family | b | 110.13 → 107.09 | 146.00 → 137.51 | 32.39 → 33.09 |
| Living | a | 110.70 → 104.56 | 147.93 → 146.06 | 32.79 → 28.50 |
| Living | b | 103.00 → 110.31 | 127.66 → 151.53 | 35.38 → 31.60 |

Family median largest frames decreased 13.25/2.77%; Living a decreased 5.55%, while Living b increased 7.09%. Living b's maximum increased 18.70%. Family b's root-phase proxy also increased 2.17%. These adverse observations remain preserved. This experiment does not establish a repeatable universal startup/frame-speed improvement, and no cause for the adverse block has been isolated.

The root-phase proxy sums only the observed sequential shell composition and measure spans, with one-millisecond completion-timestamp tolerance. It omits late footer restart, surrounding apply/framework work and other startup paths. Parent/child spans are never added. It is not total UI CPU time or touch-to-visible duration. Removing eight row invocations is a deterministic work reduction; the remaining opening stall requires broader diagnosis.

## Functional and recovery checks

Both rooms passed source-known category-position actions with Advanced off/on, preserving the Settings window. Initial sidebar invocations were seven/eight respectively. The Advanced position was inactive when off and responsive when on. Synthetic search applied, recomposed and cleared; viewport swipes were accepted. Complete schema and original Advanced setting were restored, and collectors were inactive. These checks do not prove exact category titles, actual overflow scrolling or screen-reader behavior.

Each room passed five Home/Apps/browser/Back/screensaver cycles and thirty foreground/ownership checks, including Apps from slideshow. Pin → cover → center tap revealed the same existing Chromium window, and Home cleared pin/cover. Hide dispatch was 6,603 microseconds Family /13,405 Living, not visible reveal latency. A separate final entry/return check observed a different window from Settings and two new HomeActivity frame callbacks in each room. Final Home ownership was clean, configurations unchanged and collectors inactive.

## Decision and remaining work

Retain the narrow composition-isolation change as removal of a reproduced unnecessary row pass, with source/compiled/runtime evidence and scoped functional regression checks. Do not present it as overall Settings performance sign-off: frame results are mixed, and the adverse Living-b block remains an open observation. Candidate benchmark remains on both rooms for continued testing; no release or remote publication is made by this work.

Broader startup queue/layout work, cold first-use, slideshow tails, long-uptime memory, overflow/D-pad focus, screen-reader behavior, browser/website readiness and phone/web control performance remain open. A separate isolated slideshow metadata worker fix is underway; it has not been deployed by this validation. The broad goal remains active.

Private evidence is under ignored build/fleet/diagnostics/settings-footer-2026-10-10: test XMLs, APKs, compiled-scope review, eight cohort summaries/raw entries, final-snapshot analysis, functional records, navigation smoke and final ownership. No household photo bytes, screenshots, UI text, DOM content or credentials were exported.

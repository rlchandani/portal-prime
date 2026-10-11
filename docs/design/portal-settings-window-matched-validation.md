# Matched Settings window-animation comparison

Removing the unwanted Settings window motion remains a UX correction. A fresh Family Area control/candidate/control comparison did **not** establish a Settings startup speed improvement. The candidate was not promoted for performance, and the accepted APK was restored.

## Protocol and results

Each block used a fresh SDK install-profile bootstrap and direct compilation admission, followed by exactly 20 warm-up and 10 timed independent Settings entries. The collector duration was 15 seconds, main-thread sampling was disabled, and all 30 collector stops per block were verified. Control and candidate archived app sources differ only in the Settings window theme and manifest binding. Historic timings were not imported.

The preregistered primary requirement was at least a 10% reduction in median launch-to-pre-draw time against **both** controls. Median per-entry largest Settings frame duration also had to be no worse than either control.

| Median, milliseconds | Control before | No window animation | Control after |
|---|---:|---:|---:|
| Launch request to first pre-draw | 95.13 | 99.13 | 100.24 |
| Launch request to first frame callback receipt | 113.11 | 118.83 | 118.60 |
| Per-entry largest Settings frame | 86.99 | 91.00 | 92.11 |

The candidate was 4.21% slower than the first control and 1.11% faster than the final control on the primary endpoint. The control itself drifted 5.37%. The performance gate therefore failed. Callback receipt is not physical display latency or touch-to-screen latency; overlapping intervals are not additive. This experiment measures fresh Settings entry, not retained-panel category revisits.

The native process terminated with EXIT 0: three completed blocks, 60 warm-ups, 30 timed entries, 90 verified collector stops, no transport read retries, and no experiment or cleanup failure. Living Area and the phone had zero operations.

## Offline validator correction

The original analyzer incorrectly required Settings timeline entry IDs to be globally unique across independent captures. `FramePerformanceCapture` constructs a new `SettingsEntryTimeline` for each capture; its first entry is correctly numbered 1 every time. A source-faithful fixture reproduced this analyzer failure before correction.

A separate completion-audit analyzer now requires entry ID 1 for these single-entry captures and retains the independent session uniqueness, native window uniqueness, start/stop pairing, event validation, capture identity and complete hash-graph checks. Original scripts, manifest, protocol, postflight and captures remain unchanged. Acceptance criteria were not modified. The separate audit completed with EXIT 0 and diagnostic validity passed; performance criteria remained failed.

The seven sequence-controller tests and 32 analyzer tests passed normally and with Python optimization. The corrected analyzer suite also passed all 32 tests normally and with optimization, including repeated session-local IDs, duplicate-session rejection, reused-window rejection and mismatched/out-of-session timeline rejection. These are diagnostic tests, not additional product performance improvements.

## Restoration and provenance

Restoration reinstalled the accepted benchmark APK and SDK profile. Direct evidence verified the actual installed APK and dex metadata, complete canonical SDK reference-profile method/class/flag and DEX-checksum equivalence, and a mapped ARM64 OAT 170 header with `speed-profile` / `install-dm`. A fresh SDK cache result of 1 passed. No individual compiled method machine-code claim is made. All configuration/schema/photo-source snapshots matched, Home was restored, the collector was inactive, and the frozen current app source matched afterward.

Local private evidence is under the ignored `build/fleet/diagnostics/settings-window-matched-2026-10-11` directory of the primary workspace. The source inventory contains 792 files, including 789 build-source files. Evidence identifiers:

| Artifact | SHA-256 |
|---|---|
| Frozen inputs | `952dc43af00746897214b4f35850786a314421455ddf0beb3e19c0a950bae3fb` |
| Completed native postflight | `03a8edc71708d7f134ef95f4461aa2c37697072e5525a1e9feabdf00b2e19a8d` |
| Original analyzer | `37fcc998d518bda2927cd7b142d2d19866399d53bec1d3c0b05851b9bdb28bad` |
| Corrected offline analyzer | `d23b14c6a8b144ace10b09d675e740c21fc441bf2819d5aa415a317b7f75a030` |
| Corrected analyzer tests | `199858fe721d8a3572682d9a3579fe0e5372e244baf4788e37bc895aa79f61ac` |
| Completed offline analysis | `925f36783f549e3c06970a906e1c3f2b82ff9a30f149563912cea047c053a41d` |
| Restored accepted APK | `bc6af47b4184de27f81fc59c6502871bb8a796505c59c1e047ae859dd3f28298` |
| Final restoration admission | `a244a8d3469a2c99657003e37a200b60632fe91e78bb4ebd5339bc1c75465e22` |
| Current complete app source | `7781cc874fd74abfa14b3a6907a15e929f1c90bc08fa1b616b740d247d26866b` |

## Next bounded investigation

Retained inactive detail panes use alpha-zero graphics layers. Compose 1.10.4 still includes them in the parent measurement policy, but unchanged constraints and clean children may reuse cached measurements. This is a hypothesis for warmed category navigation, not proof of avoidable work and not an explanation for a first Settings entry with only one pane. Attribute actual active/inactive measurement work before changing retention, focus, draft or scroll behavior.

The browser cascade-column mock remains awaiting human review. This comparison adds no native columns and establishes no completion of the broader performance objective.

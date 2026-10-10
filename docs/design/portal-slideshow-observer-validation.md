# Slideshow readiness observer validation — 10 October 2026

The bounded readiness read is tested and deployed on Family Area and Living Area. A repeated, controlled-content observer comparison completes all 32 captures with configuration and credential-group restoration. Frame results are mixed. Most timed readiness markers precede the first startup poll, so this experiment does not establish the observer's effect on startup. Overall performance remains unaccepted.

## Diagnostic implementation

Local branch `codex/portal-slideshow-readiness-observer`, implementation commit `0533660`, adds authenticated read-only `GET /perf/state?readiness=1`. It returns existing bounded slideshow scheduler-promotion records alongside live capture ownership, before histogram and process-memory sampling. The default ownership response, full `/perf` diagnostics, capture expiry and method/authentication guards remain unchanged. No user-facing setting or rendering behavior changes.

The targeted regression executes 14 tests against the old implementation: exactly two intended missing-readiness failures, 12 passes and no errors/skips. With the implementation, all 14 pass. The full native gate freshly executes 1,468 tests with zero failures, errors or skips; debug and benchmark builds succeed.

| Artifact | SHA-256 |
| --- | --- |
| Retained benchmark APK, both rooms | `0bd5abb3cbe67dca685adf68360c4a1a3f3b311cc28869acd3a9de24b3bb9d97` |
| Debug APK | `30758728e8506699792ce4d6465d4daf31bb0fce728a8f8424034df8f9aab214` |
| Unchanged corrected Filmstrip asset | `2271b57c8c4cc809222a659a59bd5741192c63af7ee0ed89e48dc75cac632543` |

All rendering assets match the previously retained corrected Filmstrip APK. The associated baseline profile regenerates for the native code change; no fresh ART compilation or profile speed benefit is claimed.

## Controlled device protocol

Both devices are API 29 Portal+ units at 2160 × 1440. Twelve deterministic generated JPEGs, totaling 8,830,300 bytes, are uploaded to unique owned app-cache folders and verified by file size and SHA-256 before and after installation. No household media is read or exported. LocalMedia enumerates the explicitly selected folder in stable path order; shuffle is temporarily disabled.

The order is full-one → light-one → full-two → light-two, with both rooms run in parallel in each cohort. Each room/cohort has one warmup and three timed fresh-preview entries in an existing warmed process: eight warmups and 24 timed captures total. Both variants use the same APK, generated content, readiness predicate, request-relative 12-second full checkpoint and 48-second exit target. Only the startup observer route differs. Each owned 90-second capture is stopped and known Home recovery is checked.

Eighteen virtual observer scenarios, 12 source/restoration scenarios and four collector-ownership scenarios pass before device use. Ten analyzer controls check valid results, explicit warmup censoring and rejection of timed overflow, nonfinite percentiles, configuration/source-group drift, callback drops, wrong style and wrong observer counts. Captured files are never modified.

All physical entries complete with clean collector stops, complete configuration/schema preservation and restoration of the original mode, folder and shuffle values. Complete dormant source credentials are compared in memory only. Foreign field changes are preserved and would make completion false. The normal photo sources are restored after each cohort. Final read-only checks verify both installed APK hashes, inactive collectors, deselected generated folders, zero pending/failed cleanup and no pin/cover. Both foreground packages are explicitly reported as Immortal at that final check.

## Observed results and limits

Values below are medians across three timed entries per room/cohort. Promotion admission is a native scheduler bridge marker, not the first presented photo. Run percentiles describe the preview Window host.

| Observer / round | Room | Promotion admission ms | Run p95 ms | Run p99 ms | Largest timed frame ms |
| --- | --- | ---: | ---: | ---: | ---: |
| Full / one | Family Area | 797 | 60.50 | 171.25 | 271.57 |
| Full / one | Living Area | 812 | 65.00 | 150.00 | 179.63 |
| Light / one | Family Area | 820 | 60.75 | 218.50 | 222.89 |
| Light / one | Living Area | 755 | 59.75 | 140.50 | 176.61 |
| Full / two | Family Area | 764 | 50.50 | 121.50 | 159.01 |
| Full / two | Living Area | 729 | 52.00 | 152.25 | 158.47 |
| Light / two | Family Area | 713 | 53.25 | 182.50 | 187.59 |
| Light / two | Living Area | 768 | 59.75 | 131.00 | 139.31 |

Every timed capture has zero histogram overflow, callback drops and invalid/detail-invalid samples. The first full-read warmups contain one overflow each: Family's maximum is 1,245.57 ms and Living's is 893.89 ms. Their p99 values remain finite; the overflow is retained explicitly. These stalls remain unresolved.

The protocol sleeps one second after navigation before startup polling. In 22 of 24 timed captures, the promotion marker is under 1,000 ms from capture start. Capture start necessarily precedes navigation, so those markers predate the earliest startup polling query. The observer manipulation therefore misses most warmed startup periods. The remaining two entries have no sufficient timestamp evidence to prove overlap. **Do not interpret mixed/small differences as proof that diagnostic polling is harmless, or as a startup speed improvement.**

The next observer protocol must poll immediately after navigation, record native snapshot elapsed times and pre-readiness poll counts, and verify that each compared startup actually overlaps its assigned observer. Cache/JIT/background state remains uncontrolled even with matching content/build. Generated content is not equivalent to a large household album.

Across the first 12 timed entries, Home's frame count adds zero between the 12-second checkpoint and pre-exit counter. Hidden Home redraws are not observed in those samples. This does not rule out background CPU/IO or renderer work.

Window durations are not delivered FPS or physical touch latency. The capture does not continuously attest foreground ownership or photo advancement. Overlapping work/frame components are not additive CPU totals. App PSS excludes the separate Chromium renderer; its memory and graphics cost need separate evidence. [Android's WebView memory guidance](https://developer.android.com/topic/performance/memory/guide/webview-memory) describes that separate process and Chrome DevTools profiling.

## Parallel rendering candidate and remaining work

The separate `codex/filmstrip-conservative-culling` candidate at `9e5a3f772a53d4f529a8c64e25fba34321af78ca` suppresses offscreen cards after bounded two-frame computed-endpoint verification, retaining the full unresolved exposure union through interrupted motion. Actual focused RED is 42 tests with five intended failures; corrected GREEN is 42/42 with no cancellations/skips. Independent read-only review finds no concrete blocker. The rendering candidate is not integrated or installed.

Mandatory remaining gates include zero generated-browser pixel differences, shadows/alpha/face geometry and outgoing transitions; installed WebView settlement/serialization behavior; and sustained device benefit with synchronous style/layout cost. Continue actual Dream/pinned-app behavior, direct touch/navigation, cold starts, browser/website readiness, prolonged uptime/renderer memory and complete phone/web validation under the full performance goal.

Private immutable numeric evidence lives under ignored `build/fleet/diagnostics/slideshow-observer-controlled-2026-10-10/`; revised culling evidence lives under `filmstrip-culling-revised-2026-10-10/`. The generated input folders remain in app cache because the current Fleet filesystem API has no delete operation; derived-cache footprint is not quantified. They are deselected from the normal photo source. Household photos, UI text, DOM, credentials and complete configurations are not exported.

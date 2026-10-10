# Slideshow metadata worker and admission validation

## Current decision

The candidate verifies that legacy Shuffle metadata preparation runs off the UI thread, but it does **not** establish a consistent performance improvement. All four candidate comparison blocks recorded a worse maximum preview frame, and Family Area's second block also had a worse median largest preview frame. Keep this build experimental while investigating the remaining stalls. The broader performance and UX goal remains active.

## Implementation and build evidence

The instrumentation-only baseline is local commit `8ec9b4c`; the combined candidate is `2e83d29`, incorporating the independently reviewed preparation implementation `af3a09b`. The coordinator permits one running preparation, keeps only the latest pending/result, and rejects superseded admission. Cache hydration and JSON assembly use the existing IO executor. WebView construction, attachment and destruction remain on Main. Existing photo order, duplicates, dimensions, rounding, authentication, settings and face-analysis policy are preserved.

The candidate passed 1,352 JVM tests with zero failures, errors or skips. Debug and benchmark builds succeeded. The baseline passed 1,331 tests and both builds. Independent implementation, capture integration and cohort protocol reviews found no verified blocker in their stated scopes. JVM tests do not establish physical device concurrency, rendering smoothness or actual first-photo readiness.

Installed benchmark APK SHA-256 values were verified after each deployment:

- Baseline: `1f9eeeca225ea00ddfef496c5b7e31f30d71f26b1ae477d1bd8ec486c25adb03`.
- Candidate: `fe41968e0446de97c9b7580d0b0fc439179369748796f998f9af859ebe6c534f`.
- Candidate debug artifact: `9beaf3f2fc3e0ddb03b1b1374e94a9bc19f61e3f53a7aa09d98fef29273b51f8`.

## Matched device protocol

Family Area and Living Area each completed baseline-a, candidate-a, baseline-b and candidate-b. Each block used ten warmups and ten timed fresh preview Activities in an existing warmed process, with the original local-folder Shuffle settings. Captures were twenty seconds, phase all, with Main-stack sampling off. In total, 80 warmups and 80 timed entry/return trials completed, with 160 verified collector stops.

Every candidate start, including warmups, attributed metadata preparation to another thread: 80 candidate starts total. Baseline timed preparation recorded Main attribution. Main admission, the current engine's page-finished callback, retirement-path entry and fresh Home frames after exit were checked. Every full settings schema and screensaver configuration remained unchanged. All blocks returned Home cleanly, with no callback drops, invalid records, untracked hosts/windows or capture attachment failures reported.

Session ownership was reconciled before navigation or cleanup, including a command whose completion was initially unconfirmed. The protocol never blindly retried start or deliberately stopped a different identity. This relies on serialized operators; the API does not provide an atomic session lease.

## Mixed performance results

Each median is the median of ten per-entry largest Window frame durations, including first draws. Maximum is the largest observed frame across the block. These values are not animation FPS or physical touch latency.

| Room | Pair | Median largest preview frame ms, baseline → candidate | Maximum preview frame ms, baseline → candidate | Median largest Home frame ms, baseline → candidate |
| --- | --- | ---: | ---: | ---: |
| Family Area | a | 144.17 → 125.55 | 285.63 → 321.46 | 70.19 → 60.90 |
| Family Area | b | 123.03 → 128.87 | 167.32 → 341.48 | 57.91 → 70.01 |
| Living Area | a | 106.27 → 96.07 | 235.54 → 288.88 | 57.61 → 61.25 |
| Living Area | b | 109.52 → 94.98 | 216.85 → 259.01 | 67.48 → 64.12 |

Median largest preview frames decreased in three blocks, by approximately 9.60–13.27%, but Family-b increased 4.75%. Maximum preview frames increased in every block, by 12.55%, 104.09%, 22.65% and 19.44%. Family-a also recorded a 321.84 ms maximum Home frame, compared with 138.15 ms in its baseline. These adverse observations remain preserved; their cause has not been isolated.

Warmed baseline metadata preparation was below one millisecond even at the maximum in every timed block. Candidate preparation medians were approximately 0.94–1.90 ms on the worker. Removing this Main-thread work reduces an architectural risk, but those measurements do not explain the larger 100–340 ms frame stalls. Admission medians were approximately 24–30 ms, with mixed changes. The baseline admission span includes its metadata; the candidate admission span excludes worker preparation. Do not add these overlapping or differently bounded spans.

The page-finished callback occurred at a median approximately 0.60–0.66 seconds from capture start. That includes other startup work and command/capture timing and does not prove a decoded, visible first photo. Small warmed cohorts and fixed block ordering do not control all ART, thermal or background activity effects.

## Longer testing and next attribution

The fifteen-minute device soak is a separate pending check at the time of this report. Its protocol preserves original idle/presence policies, polls only numeric diagnostics, checks capture ownership, requires automatic listener/thread shutdown at expiry, and returns Home with configuration equality. Seven virtual-clock cases passed, including lost start responses, unrelated sessions, incomplete expiry cleanup and malformed/non-finite measurements. Simulated protocol verification is not a completed device soak.

A successful soak will establish only the bounded observations recorded by that run. Process memory does not include all external Chromium renderer memory; engine page completion does not demonstrate continued photo advancement. Neither result can establish leak-free behavior, delivered FPS or final performance acceptance.

Next attribution should separate WebView construction from whole admission and inspect surrounding startup/layout/queued work during the long frames. Source review ruled out the selected Flip clock as a hidden WebView initializer: the active clock factory selects native clock rendering. A separate WebView prewarm experiment may help fresh-process startup, but cannot by itself explain repeated stalls in these warmed runs. The selected provider and supported startup APIs remain unverified.

Interrupted startup, failure/authentication paths, resizing, actual first-photo and transition quality, cold starts, browser document readiness, long-uptime behavior and phone/web performance remain open. No release, GitHub push or universal performance sign-off is made by this validation.

Private immutable evidence is in ignored `build/fleet/diagnostics/slideshow-metadata-2026-10-10`: test XMLs, exact APK/source hashes, review records, eight cohort summaries and their warm/timed records, matched analysis and protocol fault cases. No household photo bytes, screenshots, visible UI text, DOM content or credentials were exported.

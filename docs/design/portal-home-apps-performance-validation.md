# Home/Apps isolation and live diagnostic state — 10 October 2026

The isolated navigation checks pass on both rooms, but Home/Apps rendering is not performance-accepted. Repeated foreground verification changes the measurement workload, and full `/perf` reads unnecessarily serialize every histogram and sample process memory when the caller needs only capture ownership. A live authenticated GET `/perf/state` now provides the same identity, deadline and cleanup state without that extra work. It improves the diagnostic path; it does not establish improved UI smoothness.

## Source, build and deployment

Implementation commit `4b7d381` changes only Fleet routing and diagnostic snapshot selection in production. It keeps the existing synchronization, expiry callback, collector/listener counts and full-report behavior. The state response is rebuilt from the current session for each read, has no response cache, excludes metrics/memory, and cannot create a collector. POST receives 405 behind the existing bearer authentication boundary.

Nine focused regression tests initially produced seven expected failures for the absent endpoint/state method and two passing baseline cases. All nine passed after implementation. Independent review found no production blocker and identified a useful closure-test gap: a closed session must still report two pending listeners instead of implying finished cleanup. That assertion was strengthened. The final required native check passed 1,457 tests without failures, errors or skips, and built debug and benchmark variants.

| Artifact | SHA-256 | Bytes |
| --- | --- | ---: |
| Debug | `d3f870f8746573426efe8c45bc917d7189b62f4c17eed4a97c8d848aa3642f7d` | 48,630,198 |
| Benchmark | `5916315cc56e9682a161eba24acc29625a95879ce82f40c6316a0c89ee52b788` | 37,064,080 |

The exact preserved benchmark bytes were installed on Family Area and Living Area. Complete screensaver configuration and settings schema were preserved and compared in memory. Both collectors were inactive after installation; background WebView startup preparation remained disabled. All seven packaged screensaver assets exactly match the restored baseline from the preceding [Ken Burns validation](portal-slideshow-ken-burns-validation.md). The rejected retirement optimization remains reverted. No Home, Apps, navigation animation or slideshow UI code is changed by this build.

## Isolated unsampled captures

Each capture performs thirty Home → Apps → Home cycles at a four-second cycle cadence. The first two conditions use the previously restored baseline APK; their sequence is frequent foreground-window verification followed by checkpoint-only verification. Both query the full report before each command. The third condition uses the diagnostic-state build, after a separate Home → Apps → Home warmup, and uses light state checks before commands with full reports at six checkpoints and completion.

Capture phase `apps` is a label, not a window filter. Isolation comes from limiting actions to Home and Apps. Both tabs retain the known original Home window. Checkpoint-only variants verify that window at setup, every five cycles and cleanup; they do not verify every action's tab content. No Main stack sampling is enabled in these six captures.

| Condition | Room | Window samples | p95 ms | p99 ms | Largest ms |
| --- | --- | ---: | ---: | ---: | ---: |
| Full reports + frequent window checks | Family Area | 784 | 45.75 | 65.00 | 84.94 |
| Full reports + frequent window checks | Living Area | 785 | 51.75 | 76.25 | 99.59 |
| Full reports + checkpoint window checks | Family Area | 782 | 28.50 | 47.25 | 59.67 |
| Full reports + checkpoint window checks | Living Area | 782 | 35.00 | 50.50 | 60.14 |
| Light state + checkpoint window checks | Family Area | 781 | 60.50 | 73.75 | 97.90 |
| Light state + checkpoint window checks | Living Area | 782 | 56.50 | 74.50 | 103.87 |

All six captures completed, recording 552 accepted action requests including setup and cleanup. Every collector stopped, final metrics passed validation, the known Home window returned and complete configuration/schema remained unchanged. No first-draw frames, callback drops or invalid frame samples were recorded. Frames still exceed the display's 16.7 ms budget, and transition stalls remain.

The conditions are sequential, with different process lifetimes, warmup history and request timing. The lower state-read workload subsequently recorded higher frame percentiles than the preceding full-read checkpoint condition. These observations establish sensitivity and remaining work, not a causal speedup or regression of Home UI. No UI performance sign-off is inferred from the cheaper diagnostic operation.

## Diagnostic request cost

Within the state-read captures, each room completed 96 light state reads and seven full reports. Family's average observed HTTP round trip was 12.423 ms for state and 98.373 ms for full reports; Living's was 22.008 ms and 132.978 ms respectively. These are different, unpaired request types observed at different moments; they include network, server work and response transfer. Unit tests additionally verify that light reads cannot invoke memory measurement, never return histogram payloads, read replacement identities live, retain pending listener counts after closure, and continue to request owned expiry cleanup.

The host protocol retains matching capture-ID guards before navigation, reconciles ambiguous start/stop responses without blindly repeating commands, requires inactive collector/listener state, validates final full metrics against the same closed capture and refuses navigation after capture replacement. Each protocol variant passed 22 virtual ownership/response-loss/cleanup/privacy cases before physical use. The profile symbol exporter also passed seven generated boundary/privacy cases. These are protocol checks, not physical touch or visual acceptance.

## Short Main-thread attribution

A separate capture performed ten cycles per room, with an explicit sixty-second sampling budget and roughly forty seconds of navigation. It added 64 accepted action requests, then stopped and restored the known Home window and unchanged configuration. Sampler overhead is present; these frame values are kept separate from performance acceptance.

| Observation | Family Area | Living Area |
| --- | ---: | ---: |
| Non-empty stack observations | 1,661 | 1,702 |
| Outside literal native-poll leaf | 97 | 116 |
| Skipped scheduled sample slots | 2,344 | 2,306 |
| Accessibility-delegate family observations | 33 | 40 |
| Semantics family observations | 24 | 29 |
| Recomposer family observations | 22 | 22 |
| Node-coordinate geometry family observations | 3 | 5 |
| Window p95 / p99 ms | 48.75 / 61.00 | 50.00 / 58.50 |

Counts are inclusive wall-time observations with overlapping operation families, bounded inspected depth, bounded symbol admission and skipped cadence slots. They do not measure CPU time. Absence from a sample or top-symbol list does not establish absence between observations. No sampled family is treated as the complete stall cause.

Family's first-app histogram observed `MediaNotificationListenerService.Companion.activeCountsByPackage` four times. Source inspection confirms Apps synchronously calls the notification service before its unchanged package/resume epoch guard. Warm entry already reuses the package/icon inventory, but the badge read can still perform Binder work on Main. The narrow off-Main, cancellation-safe badge change is now integrated and tested. Its repeated device results and remaining limits are recorded in [Apps badge validation](portal-apps-badge-activation-validation.md); overall Home/Apps smoothness remains unaccepted. Accessibility, semantics and composition also remain attribution targets; accessibility functionality will not be removed to improve a benchmark.

## Remaining acceptance and evidence

The broader objective remains active: settings and swipes, direct touch, first usable photo/document, browser renderer and actual website readiness, cold/first-use behavior, pinned-app lifetime, long uptime/memory and complete phone/web validation still need appropriate evidence. The Pixel remains securely locked on the latest read-only check, so the generated physical phone UI rerun is pending unlock. Filmstrip has since passed its sampled generated-fixture/control gate and independent hash review; physical performance acceptance remains pending. See the [latest Apps and parallel findings](portal-apps-badge-activation-validation.md).

Private immutable evidence is under ignored `build/fleet/diagnostics/home-apps-{isolation,state-read,state-profile}-2026-10-10/`, `capture-state-read-2026-10-10/` and `capture-state-read-deployment-2026-10-10/`. Protocol source snapshots and hashes are preserved with the results. Exported data contains bounded numeric verification, fixed labels, package/window ownership and bounded code symbols. Household photos, screenshots, UI text, DOM content, credentials and complete configuration values were not exported. No release, GitHub connection or push is made.

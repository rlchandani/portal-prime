# Ken Burns hidden-slot retirement and repeat validation — 10 October 2026

The resource-retirement candidate passes correctness and sampled generated-image fidelity checks, but its repeated device observations do not establish a performance improvement. All four updated-build comparisons recorded higher histogram p99 values than their preceding baseline. The deck remained randomized, so these differences do not establish matched-content causality. This candidate is not performance-accepted.

## Implementation and build identity

Commit `54145b4` integrates the independently reviewed Ken Burns retirement change from `f147765d`. Only the Ken Burns mode implementation changes production behavior. An outgoing slot releases its image backgrounds, animation and transform-promotion hint after valid computed opacity reaches exact zero. Slot generation, current scene ownership and target/property checks prevent late events from retiring a reused or visible slot. A bounded owned fallback covers event loss and delayed fades. Reuse restores the original CSS motion and the decoded image URL. Pause/resume and disposal remain owned by the existing timer lifecycle.

The same preserved APK bytes were installed for each corresponding cohort. The baseline is the Gallery retirement/native-tier build from the preceding [validation](portal-slideshow-gallery-tier-validation.md). The updated build changes Ken Burns retirement. Background WebView startup preparation remained disabled in both.

| Variant | Benchmark APK SHA-256 | Bytes |
| --- | --- | ---: |
| A — baseline | `762c9645676a8f8abd0c733374c795d5bac0b66b5174c3a146f146f29b62468d` | 37,064,080 |
| B — retirement candidate | `da80637f4822be8ab6d3217c85aa473da11a042e7158621cc555d4e62f508356` | 37,064,568 |

The native integration passed 1,448 unit tests without failures, errors or skips, and built debug and benchmark APKs. Packaged screensaver assets matched their source and startup preparation was confirmed disabled. Fresh JavaScript checks passed 155 tests with no failures and one optional skip. Fresh isolated offline software Chrome comparisons passed 66 generated-image comparisons and 66 baseline repeat controls with zero changed pixels and zero channel delta. JPEG, alpha PNG and WebP fixtures cover sampled rest, movement, fade, pause/resume and reuse phases.

These checks establish scoped ownership behavior and sampled software-rendered fidelity. They do not establish continuous physical presentation, lower renderer memory, GPU-memory release or improved hardware timing.

## Eight completed fixed-style captures

Each room ran A1, B1, A2 and B2 in that order. Every capture reached 180,000 ms, using fixed Ken Burns with a 10-second photo interval. Original idle/presence policies were retained. Main stack sampling remained disabled. The collector stopped, the known preview window exited and the original Home window returned. Original complete screensaver configuration and settings schema were restored and compared in memory after each run. Installation also preserved both snapshots.

The table reports Immortal preview Window samples, including first draws. Values are milliseconds. Window frame metrics are not delivered FPS, touch latency or visible-image readiness.

| Cohort | Room | Samples | p95 | p99 | Largest frame |
| --- | --- | ---: | ---: | ---: | ---: |
| A1 | Family Area | 5,798 | 34.25 | 149.25 | 352.14 |
| A1 | Living Area | 5,826 | 33.75 | 135.50 | 288.25 |
| B1 | Family Area | 5,913 | 33.75 | 185.50 | 462.42 |
| B1 | Living Area | 5,732 | 33.25 | 175.25 | 459.39 |
| A2 | Family Area | 5,645 | 34.00 | 190.25 | 455.90 |
| A2 | Living Area | 5,809 | 33.50 | 133.25 | 421.10 |
| B2 | Family Area | 5,912 | 34.50 | 190.75 | 522.50 |
| B2 | Living Area | 5,785 | 33.75 | 162.00 | 380.87 |

The updated-build p99 difference was +36.25 / +0.50 ms on Family and +39.75 / +28.75 ms on Living for the first/second comparisons respectively. The largest-frame change was mixed on Living's second comparison. Typical p95 values remained around 33–35 ms and large steady stalls remained in both variants. No consistently better smoothness is demonstrated.

All eight captures recorded 18 local image resolutions at the requested ready 2400 cache tier: 144 total, with no larger-tier delivery, original fallback or missing-original result. These are configured derivative tiers, not measured decoded pixel dimensions. Metadata preparation remained off Main. Frame callbacks reported no drops or invalid samples; no untracked hosts/windows or attachment failures were reported.

The retained slow-frame journal is limited to 32 duration-sorted samples per host and is not a complete chronological trace. First draws are separated from steady-frame investigation. Queue delay and command submission remain observations, not an identified graphics cause; command submission is not GPU execution and overlapping timing components are not added together. App PSS excludes the separate Chromium renderer and cannot prove leak-free behavior.

## Performance gate and restoration

After the fixed-style repeats and navigation regression, both Portals were restored to the preserved baseline benchmark APK (the A hash above). Deployment verification confirmed unchanged complete settings/screensaver configuration, inactive collectors and disabled background WebView startup preparation on both rooms. The candidate was also removed from the active production source through a history-preserving local revert. Its original commit, generated evidence and private observations remain available for investigation. The required native test/build check succeeded after the source revert (1,448 native results, no failures/errors/skips); the affected asset suite freshly passed 68 tests with one optional skip. Both variants built, all seven packaged assets matched the reverted source and preserved baseline APK, and the rebuilt benchmark APK exactly matched the baseline SHA-256. Post-restoration Home → Apps → Home checks passed on both rooms with the known Home window retained, inactive collectors and unchanged settings. The slower-tail observations are the reason for withholding performance acceptance, without claiming randomized-content causality.

## Rejected Filmstrip brightness relocation

The separate child-brightness experiment `473329d` was rejected and never installed. Baseline repeat controls were exact in all 63 comparisons, but 57 of 63 candidate comparisons changed pixels, with channel delta reaching 61. Candidate geometry, resource ownership and timing checks passed, and replay reproduced the same differences. Those passing checks do not override failed fidelity. The worktree was queued for recoverable archival.

The separate visible/entering-resource prototype `a270fb1` is also frozen, rejected and undeployed. It reduced initial resolve/decode calls from 13 to 4 in four evaluated generated fixtures and passed 18 focused tests. The strict generated browser gate passed 107 of 108 candidate pixel samples, but a PNG sample at 1200 × 600, `step-9-1400`, differed by 43,068 pixels with maximum channel delta 69. Baseline repeat controls were exact in 160 of 162 samples; wide JPEG/WebP mid-motion controls were unstable. The cause remains unresolved. These observations do not establish valid complete fidelity or physical performance acceptance. A follow-up is stabilizing the controls and diagnosing the remaining discrepancy, retaining zero tolerance and all coverage.

## Navigation and remaining acceptance

A separate bounded navigation harness is prepared for Home, Apps, Chromium, Back, preview, Apps from preview and return Home. Its final checks distinguish the known Home window from the preview window. Nine virtual safety cases cover normal/delayed navigation, stuck Home, foreign preflight, lost start/stop responses, unchanged versus replaced capture identity, unapplied stop and final-metrics rejection. A reviewer reproduced two initial P2 issues; regression tests failed before correction and passed afterward. Completion now requires sanitized final metrics, and ambiguous stop completion is reconciled against the expected capture without blindly repeating stop. The physical run completed twenty cycles on each Portal, covering 364 accepted action requests including setup and cleanup. Each Apps-from-preview check returned to the known original Home window, excluding the preview window. Collectors stopped, final metrics passed validation, original Home returned and complete configuration/schema stayed unchanged. This is a scoped command/window regression result, not full visual or performance acceptance.

| Room | Home samples | Home p95 / p99 (ms) | Home largest (ms) | Browser window observation p95 (ms) | Apps from preview window observation p95 (ms) |
| --- | ---: | ---: | ---: | ---: | ---: |
| Family Area | 1,118 | 94.75 / 166.50 | 320.63 | 463.61 | 429.45 |
| Living Area | 1,143 | 76.25 / 108.25 | 298.85 | 353.10 | 308.97 |

The Home Window measurements aggregate several navigation paths and include first draws. They still contain large frame costs. Window observation includes command receipt, HTTP/polling time and accessibility publication; the protocol polls at 100 ms intervals and does not measure exact first presentation. No matched baseline navigation comparison was performed, so these values establish neither a candidate regression nor an improvement.

Command receipt and foreground-window activation do not prove the tab's content is correct or a website is usable. The dashboard uses an external Chromium renderer and existing authenticated browser session. Immortal Window metrics do not measure that browser's document rendering.

Complete settings/swipe performance, first usable photo/document, cold and first-use behavior, direct touch, long-uptime stability and physical phone/web acceptance remain open. The phone generated-component rerun remains pending secure Pixel unlock. No overall performance sign-off, release or GitHub push is made.

Private immutable evidence remains under ignored `build/fleet/diagnostics/slideshow-ken-burns-{integration,js,cohorts,navigation}-2026-10-10/` plus repeat-deployment and reverted-build records. The tested private host protocol sources were snapshotted with hashes under the navigation evidence directory; their runtime configuration and credentials remained in memory. Exported records contain fixed labels, numeric measurements and verification flags. Household images, screenshots, UI text, DOM content, credentials and complete configuration values were not exported.

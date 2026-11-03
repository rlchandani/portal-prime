# Gallery Window performance — Family Area, November 1

**Both tested Gallery cohorts pass their recorded Window-work thresholds.**
The current optimized APK ran one 90-second Gallery print capture and one
90-second Gallery spread capture on Family Area. This is verification of the
existing rendering code, not a delivered speed improvement.

| Recorded Dream Window cohort | Gallery print | Gallery spread |
|---|---:|---:|
| Frame callbacks | 1,046 | 1,060 |
| First-draw callbacks | 0 | 0 |
| p95 total Window work | 14.25 ms | 14.00 ms |
| p99 total Window work | 21.75 ms | 22.00 ms |
| Maximum total Window work | 44.505 ms | 33.023 ms |
| Callbacks over the 16.667 ms budget | 30 / 1,046 (2.87%) | 24 / 1,060 (2.26%) |
| Callbacks over 33.333 ms | 2 | 0 |
| Callback drops | 0 | 0 |
| Matching Gallery Dream scene admissions | 18 | 18 |
| Process PSS at the final read | 231,398 KiB | 259,927 KiB |
| Graphics memory at the final read | 73,624 KiB | 78,444 KiB |

The frame targets were recorded before capture: at least 300 callbacks and
eight matching scene admissions, p95 at most 16.667 ms, p99 at most 33.334 ms,
and at most 5% over budget. Independent grading also checks zero invalid
Window/scene/work records, zero drops, zero attachment/tracking failures,
terminal collector state, and report/session identity. Both cases pass all
twelve grading checks.

## Method and limits

The installed **optimized, non-debuggable** APK is
**872b6af7354b6616320b0081f0525d8c27c0bad29759c3bcc7a02a4a5d76ae10**.
The [hardware budget](portal-hardware-budget-2026-10-30.md) identifies Family
Area as Android 10/API 29 on QCS605/Adreno615v2. Wireless ADB verifies its
physical-device identity; no instrumentation or emulator supplies this capture.

Each actual `PhotoDreamService` uses a generated local folder of sixteen
800 × 1200 portrait PNGs and a five-second interval, with existing clock/overlay
settings. Gallery's whole-photo path skips face analysis. The sensor clock is
temporarily disabled for the bounded capture, and both modes use their normal
prepared-image dissolves.

There is a ten-second setup delay before each capture. That delay is excluded
from the ninety seconds, but **decoded-ready/cache-warm conditions are not
independently established**. The result therefore applies to the observed
cohort, not a separately proven warm-cache workload.

The analysis selects only `PhotoDreamService@16666666`, verifies both exact
ninety-second session identities, and excludes the separately tracked Home
histogram. It checks Dream activity at the start and end; continuous Dream
activity is not independently sampled.

Percentiles are **conservative upper edges of 0.25 ms histogram bins**. These
are Android Window `FrameMetrics` callbacks, not delivered panel FPS or
complete vsync/presentation measurements. Scene records establish admission
and guarded Main acceptance, not independent visible advancement of eighteen
different photos. End-of-capture PSS/graphics readings are snapshots, not peak
residency measurements or memory-reduction results.

This accepts neither pristine cold startup, remote sources, large original
images, every overlay/face arrangement, other styles nor optical smoothness.
There is no control/candidate comparison, and no new production code.

## Restoration and evidence

All 97 setting values, photo sources, persistent pin, accessibility services,
brightness policy, both face caches, app/test APKs and system-server PID match
the private baseline. Generated device files are removed. The original Dream
is restored, with no instrumentation, collector thread or frame listener left.
Living Area is untouched.

Capture and restoration finish at **19:14:21.864 UTC**, **9 minutes
24.864 seconds** after the unchanged **19:04:57 UTC** slot start. No renderer
trial or capture is repeated. Independent review prompts stricter report/session
binding and publication qualifications; the same recordings pass the stricter
offline grading.

The prior **3,765-test JVM gate** belongs to this unchanged installed artifact;
it is not rerun for this evidence-only pass. The player sidecar finds no new
unaddressed UI requirement within its source-review scope. Provider/audio wiring
remains deferred.

[Sanitized evidence](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-gallery-warm/summary.json)
includes the original targets, full Window histograms, scalar scene/work
records, preservation guards, source hashes and a SHA-256 manifest.
Credentials, source/configuration snapshots and rollback APKs remain private.
Physical light-transition comfort and previously parked investigations remain
open. The broader goal is incomplete.

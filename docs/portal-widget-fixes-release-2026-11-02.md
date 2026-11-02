# Home widget fixes — local device delivery 1.65

This is a small device iteration under the whole-product objective, on
`codex/portal-hi-widget-fixes-20261102`, based on `6c018dc8`; native delivery commit
`d9fce1fb`.
It does not implement the unapproved gallery redesign or close the ongoing goal.
No GitHub authentication, hooks, push, tag or public release occurred.

## Behavior and acceptance

| Change | Before | Implemented behavior and actual verification |
| --- | --- | --- |
| Photo Memory editing | Family's Customize → Photo Memory Settings opened **Slideshow**. Its source entry depends on slideshow enablement. | The existing action opens **Photo sources** directly. Verified on Family with idle slideshow **disabled and enabled**; source controls were present. First Back returns to the Photos category; second Back returns to Home, following existing history. Main photo-preview action is unchanged. |
| Alarm caption | `remember(cfg)` retained the next trigger, and no clock state invalidated the countdown. Partial minutes were truncated. | Reuse the scheduler, round positive partial minutes up, observe saved config only while active, and refresh at the next wall-clock minute. Family's live caption changed **105 hr 17 min → 105 hr 16 min** with unchanged config. Three Apps/Home return cycles retained the current caption. Editor enable, inline disable and editor readback worked; original **disabled / 07:30** state was restored. |

The long countdown is the device's existing schedule, not a new alarm time.
No alarm time, label or repeat-day edit was performed. The native sample used
12-hour Home display; a separate 24-hour-format runtime case was not executed.
Hidden-page cancellation is supported by existing lifecycle code and independent
static review; this run did not instrument observer/coroutine counts.

All screensaver fields returned by the Fleet read facade matched the original
snapshot after restoring its enabled state. Private XML/screenshots and configuration
snapshots remain outside Git under `/private/tmp/portal-hi-widget-fixes-20261102`.

## Automated and independent checks

Command:

```sh
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assemblePerformance \
  --no-daemon --console=plain
```

- **Build successful in 2m 31s**; debug and optimized performance APKs assembled.
- **3,779 JVM tests / 396 suites; 0 failures, errors or skips.**
- The 14 added timing cases cover advancing wall time, sub-minute rounding,
  disabled/invalid schedules, passed/exact occurrences, weekdays, DST, timezone
  behavior and minute-boundary delays. The worker observed three failing assertions
  with floor/modulo baseline behavior before the passing implementation.
- Performance variant's R8 and vital lint tasks passed.
- `scripts/check-version-sync.sh` passed: **59 / 1.65**.
- Independent [QA review](portal-hi-first-units-review-2026-11-02.md) found no
  concrete defect in routing, config observation, timing or lifecycle integration.
  That review was static, not an additional hardware run.
- Family's crash buffer for the candidate test window had **zero mentions of the
  candidate package**. This is a limited log check, not a crash-rate guarantee.

No measured code-coverage percentage is available; passing test counts do not
establish 100% coverage.

## Artifact and deployment matrix

| Item | Value |
| --- | --- |
| Variant / package | Optimized, nondebuggable `performance` / `com.immortal.launcher.debug` |
| Version | `1.65`, code `59` |
| APK size | **25,860,666 bytes** |
| APK SHA-256 | `640054a148e01012500417b5ba01682f45d21b6d17c99b05740a957a3b0cf0c8` |
| Signing certificate SHA-256 | `4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6` |
| Family rollback APK | Preserved privately; SHA-256 `872b6af7354b6616320b0081f0525d8c27c0bad29759c3bcc7a02a4a5d76ae10`; matching signer verified. A version downgrade may require a higher-version rollback build on this nondebuggable variant. |

| Target | Installation | Post-install verification | Remaining limit |
| --- | --- | --- | --- |
| **Family Area** | One successful `adb install -r` over **Wi-Fi ADB**, preserving data. | Fleet reports room name and **59 / 1.65**; installed APK hash matches candidate. Routing, alarm-minute update, return and restore cases passed above. | API 29 device sample; no API 28 device acceptance or universal performance claim. |
| **Living Area** | Uploaded and installed once through the existing authenticated Fleet API. | Existing `InstallObserver` finished **ready**, after **6 readback observations**. Install ACK was uncertain because the serving app restarted; installed bytes, SHA-256, version and room name were independently read back. A second readback confirmed the same hash and **59 / 1.65**. | No duplicate physical interaction/performance run. Pre-update API lacked installed-path diagnostics and ADB refused connections, so a separate Living rollback APK could not be extracted. Android signature enforcement remained active. |
| Native phone companion | No code or artifact change. | None required for this native-only route/caption change. | Historical companion tests do not establish current cross-platform parity. |
| Browser remote | No runtime asset or API change in this APK. | Separate existing JavaScript regressions passed locally; CI gate work is recorded independently. | No current live browser/phone end-to-end feature claim. |

`rustc` was unavailable, so no Fleet CLI build is claimed. Deployment reused the
existing HTTP API and tested install-observation helper, not a replacement installer.
No development-mode change or Living settings write was performed.

## Hardware and performance limits

Family: **QCS605 / Adreno 615**, Android 10 / API 29, **2160 × 1440**,
**2,848,792 KiB physical RAM**, approximately **60 Hz**. Its frame deadline is
**16.67 ms**.

| Sample | Result | Interpretation |
| --- | --- | --- |
| Old Photo Memory edit/Back navigation, three cycles | Main process summary: **17 frames, 16 janky (94.12%)**, p50 **81 ms**, p90 **150 ms**. | Small navigation sample; initial capture/window context differs from the candidate sample. |
| Candidate Apps/Home navigation, three cycles | Main process summary: **102 frames, 16 janky (15.69%)**, p50 **9 ms**, p90 **26 ms**, p95 **46 ms**, p99 **105 ms**. | Existing outlier frames remain. Different scenario: this is **not** evidence of a 94% → 16% improvement from these fixes. Duplicate window/process summary rows are not added together. |
| Baseline / candidate total PSS snapshots | **301,464 KiB / 126,970 KiB**. | Process ages and foreground states differ after installation; **not** a controlled memory-improvement comparison. |
| Alarm update work | At most one scheduled tick per active enabled minute; disabled/inactive producers stop through existing lifecycle policy. | Source/review evidence. No measured CPU, energy or coroutine-count result claimed. |

These are correctness fixes, with a performance-conscious implementation.
They do not resolve previously parked Home/Apps/settings, legacy slideshow motion
or black-handoff issues. An equivalent-scenario before/after performance comparison
remains unverified; no extra investigation slot is silently granted.

## Next delivery

Continue from [the product operating state](portal-product-operating-state.md).
CI protection for the existing Python and bundled-JavaScript regression suites is
the next bounded unit. Hosted CI runs and public publication remain unobserved.

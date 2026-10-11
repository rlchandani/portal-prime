# Family Area Settings: installation-time profile validation

## Result

The exact accepted APK passed the preregistered Settings timing gates after installation with
its bundled profile in a matching `base.dm` file. The median largest Settings frame was
87.79 ms, versus 100.12 ms and 97.83 ms in two APK-only controls: 10.3–12.3% lower.
Root composition was 24.0–35.4% lower. This is a measured improvement on Family Area;
it does not establish physical touch latency, delivered FPS, or completion of the broader
performance goal.

The accepted APK remains installed on Family Area, with compiled profile metadata. Its bytes,
signing key, UI, settings, and photo-source configuration are unchanged. Living Area and the
phone were not operated on. The cascading Settings columns remain a browser mock awaiting
approval; no native column redesign was implemented.

## Fixed artifact and scope

- Device: Family Area, Portal+, Android 10 / API 29.
- Package: `com.immortal.launcher.debug`, non-debuggable benchmark.
- Measured APK: 37,150,356 bytes, SHA-256
  `bc6af47b4184de27f81fc59c6502871bb8a796505c59c1e047ae859dd3f28298`.
- Flags: native Filmstrip on, native Home off, Home diagnostics on, WebView background
  preparation off; minification off.
- Each measured block freshly installed that same APK, performed 20 verified warmups,
  then 10 timed fresh Settings Activity entries. Each capture used `sampleMain=false`.
- Order: control A, control B, then metadata. Compilation can survive an APK-only reinstall,
  so an APK-only reinstall after metadata was not misrepresented as an uncompiled control.
  This ordering clarification was saved before the new device captures; timing gates stayed
  unchanged.

Every block completed 30 captures and 30 verified collector stops. Each timed block supplied
30 Settings frame callbacks, including 20 FIRST_DRAW flags. Callback drops, invalid samples,
and overflow samples were zero. These callbacks are startup observations, not a continuous
animation or FPS sample. Nested Compose spans overlap and must not be summed.

## Measurements

All entries below are medians across ten timed openings. The largest-frame row is the median
of the largest recorded Settings Window frame in each opening.

| Metric | APK-only A | APK-only B | APK + profile metadata |
|---|---:|---:|---:|
| Largest Settings frame, ms | 100.12 | 97.83 | 87.79 |
| Mean recorded Settings frame, ms | 73.01 | 73.35 | 58.21 |
| Root composition, ms | 13.06 | 15.37 | 9.93 |
| Search composition, ms | 5.33 | 5.52 | 3.72 |
| Root child measurement, ms | 12.80 | 9.92 | 8.29 |
| Process PSS, MiB | 102.39 | 88.70 | 111.18 |

Both comparisons pass the unchanged gates: at least 10% reduction in the median largest
frame, and no root-composition regression above 5%. The metadata block's largest-frame
range was 72.91–96.67 ms; root composition was 7.89–12.70 ms.

Memory is a tradeoff: median PSS was 8.79–22.48 MiB higher than the controls. The ten metadata
samples ranged from 103.03 to 117.37 MiB and ended at 106.09 MiB, below the first sample's
113.83 MiB. This short sequence does not establish sustained memory stability or explain the
entire difference. Fixed order, thermal state, ART/JIT history, caches, scheduling, and GC were
not controlled; the controls themselves had different PSS and root-composition medians.

## Compilation evidence

After each control installation, a freshly changed AndroidX ProfileVerifier cache reported
`PROFILE_ENQUEUED_FOR_COMPILATION` (code 2). After metadata installation, a freshly changed
cache reported `COMPILED_WITH_PROFILE` (code 1); it still reported code 1 after the cohort.
The cache's PackageInfo update timestamp changed, but was not independently queried from
PackageManager. This is AndroidX's observation at its last write, not a live dexopt query.

The running accepted process also mapped its own readable `base.odex` file (13,097,192 bytes).
A bounded 1 MiB prefix read found an ELF header, one OAT version 170 header, and the paired
labels `compiler-filter=speed-profile` and `compilation-reason=install-dm`. These corroborate
installation-time profile uptake. The key-value store was not structurally parsed, so this is
header-label evidence, not a full OAT integrity or method-coverage audit. Installed `base.dm`
was readable, 12,213 bytes, and had a ZIP signature. No binary device profile was retained.

## Installer implementation and rollback

Retained source is opt-in experiment tooling. Normal builds leave
`portalSelfInstallDexMetadata=false`. Enabling it requires
`portalSelfInstallDexMetadataApkSha256` with an exact lowercase SHA-256 target.

The temporary installer checks SDK 28–30, requested and actual archive package identity,
and the exact designated APK hash before preparing metadata. It reads the two embedded
profiles with actual-stream bounds, checks their headers, lengths and CRCs, and writes only
`primary.prof` and `primary.profm` into a bounded ZIP. Invalid target metadata fails before
session creation. `base.apk` and `base.dm` are staged and fsynced in the same PackageInstaller
session. Other APK hashes retain APK-only installation, preserving rollback through a normal
build. No new setting, preference, HTTP endpoint, shell permission, or signing key was added.

The temporary bootstrap was installed through the old APK-only installer, then installed the
exact accepted APK with metadata. The bootstrap itself was never measured as a UI candidate.
The accepted APK replaced it. Both bootstrap and rollback artifacts passed signature checks,
shared the accepted signing certificate, and were non-debuggable with the same package.

## Validation and next target

- Full JVM gate: **1,636 tests across 192 suites**, no failures, errors, or skipped tests.
- Debug, normal benchmark/rollback, and opt-in benchmark/bootstrap builds passed offline.
  Final source digest matched the frozen green build.
- 90 matched captures, 30 timed openings; independent installed APK hashes verified in every
  block. Configuration, schema and source groups matched the original RAM-only snapshots.
- Native post-compilation smoke verified seven search edits, result selection, Back,
  category navigation and refocusing/clearing search in the same Settings Window.
  This did not verify composing-keyboard ranges, D-pad operation or physical touch latency.
- Final collector inactive; Home restored; accepted APK independently hash-verified.

A separate static profile audit decoded all four profiled DEX files and verified their CRC32
values, method-ID counts and index bounds against the exact accepted APK. It found 34,922
profiled methods and 5,389 classes, all in dependencies, with zero entries under
`com.immortal.launcher`. Exact Settings Activity coverage was 0/17 methods; the
ApprovedSettingsShell matching family was 0/197; ApprovedNativeSettingsPage was 0/28
(groups overlap). The next bounded candidate should profile Immortal's own exercised Settings
entry/rendering methods, then validate generated coverage and repeat device timing. Static
coverage alone is not a speedup claim.

Private reproducible inputs, build logs, numeric captures and proof files are under the ignored
`build/fleet/diagnostics/settings-self-profile-2026-10-11/` directory in the primary workspace.
Authentication and full settings/source snapshots stayed in RAM; no household values were
exported. The earlier six-hour window was not restarted by this experiment.

Official references:

- [Manually create and measure Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure)
- [How Baseline Profiles work](https://developer.android.com/topic/performance/baselineprofiles/overview)

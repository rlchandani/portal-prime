# Family Area Settings: accepted Baseline Profile improvement

A Settings-specific Baseline Profile is committed in `app/src/main/baseline-prof.txt`
and its measured APK is installed on Family Area. This follows the accepted
[direct Appearance rows](portal-settings-hardware-and-appearance-candidate.md).
It reduces the median largest frame by a further **15.29%–20.55%** against that
already improved build. No application bytecode or UI behavior changed.

## Measured result

The comparison ran control before, candidate, control after on Family Area.
Each block used two warm-up fresh Settings pages and five measured fresh pages
in a warm process. Detail selections retained the same Settings window.
Settings UI/pane work observers were disabled (`sampleSettingsWork:false`).

| Quantity | Control before | Profile candidate | Control after |
| --- | ---: | ---: | ---: |
| Median largest frame during Appearance opening | 54.303 ms | 43.145 ms | 50.930 ms |
| Worst frame across five samples | 64.816 ms | 67.149 ms | 67.937 ms |
| Measured fresh Settings pages | 5 | 5 | 5 |

All predeclared gates passed: median at least 10% better than both controls,
worst frame no more than 10% worse than either control, and control median drift
within 15%. **Worst-frame performance did not improve overall**: the candidate
was 3.60% worse than the first control and 1.16% better than the second.
These percentages belong to this comparison; they must not be combined with
percentages from the separate direct-row comparison into a single speed claim.

The 43.145 ms median largest frame still exceeds the 16.667 ms frame budget.
This is a small matched sample of Appearance opening, not a measurement of
physical tap latency, delivered FPS, all Settings categories, or fully cold
startup. A universal 60 FPS claim is not supported. The original six-hour
deadline was missed and is not reset by this result. The broader performance
goal remains incomplete.

## Implementation and hardware fit

The profile uses hot/post-startup (`HP`) rules for the existing Settings activity,
renderer, components, shell, rows and navigation. It adds 1,285 resolved app
method entries, all in `classes3.dex`. There are no app startup flags or app
class-preload entries. It does not mark the whole launcher for startup work.

All four DEX files are byte-for-byte identical to the control. All other APK
contents outside profiles and signatures are also identical. Existing SDK
profile sets and flags remain unchanged: 34,922 methods and 5,389 classes.
Package, signing certificate and non-debuggable build type match the control.

The actual Family Area device is a Portal+ with QCS605, Adreno615v2,
approximately 2.72 GiB of kernel-visible RAM, Android 10/API 29, and a
2160 × 1440 display. Baseline Profiles are supported on this Android version
without Google services. The device-specific facts and their measurement limits
are recorded in the direct-row document.

For immediate compilation on Android 9–11, sideload deployment must co-install
APK dex metadata. This deployment used the existing target-pinned bootstrap
and verified the exact APK, bundled metadata, decoded reference profile and
APK-specific mapped compiled output (`speed-profile`, reason `install-dm`).
The installed reference profile contains all 1,285 app entries plus the unchanged
SDK entries. This confirms profile membership and APK-specific compilation;
it does not individually prove native compilation of every listed method.

The ordinary self-installer's metadata setting was not changed by this patch.
Merely bundling the profile does not guarantee immediate AOT compilation on
future ordinary sideloads; ProfileInstaller can install it for subsequent
background compilation. The measured deployment explicitly supplied metadata.

## Verification and installed state

- Full unit gate: **1,728 tests in 198 suites**, zero failures, errors or skips.
- Debug, benchmark candidate and target-pinned bootstrap assembly succeeded.
- Independent offline profile inspection: eight helper tests passed; DEX,
  profile scope, flags, SDK equality, package and certificate verified.
- The earlier eight native functional checks apply to the identical application
  bytecode. This profile comparison completed 15 fresh-page timed captures with
  Appearance controls visible and the same Settings owner. It did not repeat
  all eight mutating functional checks.
- Promotion verified unchanged configuration, restored Home and an inactive
  collector. Living Area operations: zero. Phone operations: zero.

The initial comparison passed its performance gates, but its final restoration
script rejected a cached ProfileVerifier status of 3. Its original report
remains `completed:false`; it was not rewritten. Status 3 warns that a compiled
profile may omit methods and is not a complete method-set audit.

A separate restoration attestation verified the exact previous APK, full decoded
SDK reference profile and APK-specific compiled output. No cache file was
modified and that attestation did not reinstall the package. A separate successful
promotion then installed the measured candidate and verified the full candidate
profile, compilation, configuration and Home. The precise cause of the cached
status remains unproven; the accepted installed state rests on direct artifact
and profile checks rather than overriding the warning.

The previously observed header Home edge case following a status-bar change
remains outside this profile change. Cascading Settings columns remain a mock.

## Local evidence

Evidence is under `~/portal-prime/build/fleet/diagnostics/`:

- `settings-app-baseline-2026-10-11/`: APKs, build logs, frozen build admission,
  independent profile inspection and original comparison `postflight.json`.
- `settings-app-baseline-restoration-2026-10-11/postflight.json`: successful
  independent restoration attestation, linked to the original comparison.
- `settings-app-baseline-promoted-2026-10-11/postflight.json`: successful
  candidate promotion, linked to both comparison and restoration attestation.

Current installed candidate APK SHA-256:
`d1635e3271943c8ed0d447d9ebe6da98692c5cb96d506b203980ade34026f2b4`.
Previous accepted control APK SHA-256:
`5c185e77a0e8708603bf285790343b503681ab6ea704a478414fb7600a256e86`.
No registry credentials or private setting values are committed.

## Official sources

- [Baseline Profiles overview](https://developer.android.com/topic/performance/baselineprofiles/overview)
- [Manual profile rules and APK/metadata installation](https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure)
- [ProfileVerifier compilation status meanings](https://developer.android.com/reference/androidx/profileinstaller/ProfileVerifier.CompilationStatus)

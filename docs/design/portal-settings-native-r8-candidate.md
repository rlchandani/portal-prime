# Family Area: native R8 comparison accepted

The native R8 candidate passes the completed comparison and five native checks.
Promotion status is recorded below. This is a further improvement over the
already accepted direct Appearance rows and Settings Baseline Profile.

## Measured result

| Quantity | Control before | Native R8 candidate | Control after |
| --- | ---: | ---: | ---: |
| Median largest frame during Appearance opening | 44.089 ms | 27.779 ms | 48.204 ms |
| Worst frame across five samples | 51.969 ms | 39.315 ms | 87.307 ms |
| Measured fresh Settings pages | 5 | 5 | 5 |

The median improves **36.99%–42.37%** against the two controls. The worst observed
frame improves **24.35%–54.97%**. Control median drift is **9.33%**. All predeclared
gates pass: at least 10% better median than both controls, worst frame no more
than 10% worse than either control, and control drift at most 15%.

Each block uses two warm-up fresh Settings pages and five measured fresh pages
in a warm process. Detail selections retain the same Settings window. Timing
uses `sampleMain:false` and `sampleSettingsWork:false`; Settings work observers
record zero samples. These are small matched frame measurements, not physical
tap latency, delivered FPS, every category, or fully cold startup. The 27.779 ms
median largest frame still exceeds the 16.667 ms frame budget. Universal 60 FPS
and completion of the broader goal are not established. The six-hour deadline
remains missed. Do not compound percentages with earlier comparisons.

## Interrupted comparison and transparent continuation

The initial attempt stopped before candidate timing because the OAT verifier
assumed four DEX files; R8 merged this APK into one. Its original report remains
`completed:false`. Exact artifact-derived DEX-count validation was added, with
all structural, compiler and profile checks retained; 11 parser checks, nine
integration checks and an independent read-only review passed.

The corrected run completed five control-before and five candidate captures,
plus three Settings functional checks, then stopped on a test error: it searched
for `appsHeading` in public rows, which deliberately exclude that heading. The
API exposes `appsPageVisible`. That report also remains `completed:false`.

The continuation used the correct public field, completed the two remaining
native checks and the five final-control captures. **None of the ten original
timing captures were repeated or replaced.** All original inputs/captures and
reports were frozen by hashes and linked. The completed continuation passes the
strict gates and exact accepted restoration. This was an interrupted comparison,
not an uninterrupted single execution. Control drift remains within the declared
gate; the interruption is a limitation of this small experiment.

## Implementation, hardware and verification

R8 removes, merges and optimizes app/dependency bytecode while preserving the
small legacy SMB/event-bus reflection boundary, JavaScript bridge names, surviving
app class names and finite Compose diagnostic selectors. Inlining can still remove
frames from diagnostics; zero samples do not prove absent work. UI assets and
resources, package, certificate and non-debuggable build type match the control.

The APK shrinks from 37,166,836 to 25,386,112 bytes (**31.70%**). This does not
establish a memory reduction. The candidate's rewritten profile resolves to its
own DEX; complete bundled/reference method/class sets and DEX checksums match.
The exact installed APK/dex metadata and its mapped OAT170 compiled output are
verified: one DEX, `speed-profile`, reason `install-dm`. This does not individually
prove native compilation of every profile method.

Family Area is a QCS605 Portal+ with Adreno615v2, about 2.72 GiB usable RAM,
Android 10/API 29 and a 2160 × 1440 display. No thermal or paging cause has been
established. Reducing main-thread work is appropriate; eight cores do not make
Settings' main thread eight times faster.

Full unit gate: **1,728 tests in 198 suites**, zero failures, errors or skips;
debug, candidate benchmark and target-pinned bootstrap assembly succeed. JVM
unit tests do not execute the optimized APK. Five native checks on the exact
candidate cover:

- Mini-player toggle roundtrip.
- Appearance reload following an external edit while hidden.
- Appearance/Wallpaper/Back retaining the same Settings owner.
- Home → Apps → Home retaining the same Home window.
- Screensaver preview style-bridge receipt, nonzero window frames and return Home.

The preview check establishes bridge/runtime smoke, not first-photo pixels,
photo advancement, all styles or every photo source. Long-run and complete
feature compatibility remain part of the broader goal. Cascading columns remain
a mock. Living Area and phone operations are zero.

## Promotion and local evidence

At the original promotion, the exact candidate was installed on Family Area. Promotion verifies its own APK,
dex metadata, complete reference profile, OAT170 output, unchanged configuration,
Home and inactive collector. It completes in 53.67 s. Recovery was corrected to
install the accepted bootstrap/APK without depending on candidate Home; eight
focused offline tests cover malformed admission and that recovery dependency.
These mocks do not establish recovery from every device/transport failure.

Native R8 is now the **benchmark default**. `portalBenchmarkNativeR8=false`
selects the unoptimized control. Explicit `portalBenchmarkOptimize=true` retains
the older external-probe compatibility rules unless native R8 is also explicitly
selected. Ordinary debug/dev/release minification policy is unchanged; this result
does not claim optimized release compatibility across all features.

The final default rebuild passes the full 1,728-test gate and debug/benchmark
assembly. Its APK is **byte-for-byte identical** to the measured and installed
candidate. No additional native timing captures were needed for the default change.

Reproduce the Family Area benchmark with:

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark --no-daemon
```

Immediate Android 9–11 profile compilation still requires target-pinned bootstrap
and co-installed dex metadata. The benchmark default does not enable metadata for
ordinary self-installers or remove deployment authentication/ownership guards.

Evidence roots under `~/portal-prime/build/fleet/diagnostics/`:
`settings-native-r8-2026-10-11` (first attempt/builds),
`settings-native-r8-verified-2026-10-11` (ten original captures/three Settings checks),
`settings-native-r8-completed-2026-10-11` (continuation/final control/complete report),
and `settings-native-r8-promoted-2026-10-11` (guarded promotion).

Candidate SHA-256:
`a622b1faa1c31fa1329c33929464bef735e227361c728451f9c0a2d4064d2aae`.
Previous accepted SHA-256:
`d1635e3271943c8ed0d447d9ebe6da98692c5cb96d506b203980ade34026f2b4`.

## Header Home follow-up on the accepted artifact

A separate 14.95-second native probe passed three Header Home returns across
fresh Settings pages: original hidden system bar, visible system bar, and the
original hidden state again. The actual header target moved 68 physical pixels
when the system bar appeared. Two matching fresh target bounds were read before
each single tap; no uncertain tap was retried. Original settings, Home and the
inactive collector were verified, with the exact accepted APK retained and no
installation or timing capture. The historical timeout was not reproduced;
this does not establish its original root cause or claim an app-code fix.

Evidence: `settings-header-home-2026-10-11/postflight.json` beneath the diagnostic
root above.

## Broader navigation follow-up

A read-only Settings-inspector extension passes the 1,732-test gate and enables
broader native navigation coverage. The bounded continuation passes 16 checks
(four overviews and 12 page/Back visits) before stopping at Welcome greeting's
heading verification. Its report remains incomplete; the inspector candidate
is not retained. Family Area is restored to this measured `a622…` artifact with
unchanged configuration and exact compilation-profile verification. No new
frame measurements or speed improvement are claimed. See
[the native coverage record](portal-settings-native-navigation-coverage.md).

The subsequent [owned Settings Back revision](portal-settings-owned-back-validation.md)
passes the 1,736-test gate and a fresh 31-check native navigation sweep. Its
`c862…` APK is now retained on Family Area with native R8, unchanged UI assets
and exact compilation-profile verification. The 27.779 ms measured result above
belongs to the earlier `a622…` APK; this functional follow-up adds no new timing
comparison or measured speed claim.

## Official guidance

- [Compose performance](https://developer.android.com/develop/ui/compose/performance).
- [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview).
- [R8 keep rules](https://developer.android.com/blog/posts/configure-and-troubleshoot-r8-keep-rules).

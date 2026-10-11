# Family Area: native R8 candidate remains unmeasured

R8 is available as an explicit benchmark candidate with
`-PportalBenchmarkNativeR8=true`. It is **not enabled for ordinary builds**,
not promoted, and not an accepted performance improvement. Family Area was
restored to the accepted [Settings Baseline Profile build](portal-settings-baseline-profile.md).
The original six-hour deadline remains missed.

## Hardware and established results

The actual device has QCS605 (eight logical cores, two faster and six smaller),
Adreno615v2, approximately 2.72 GiB kernel-visible usable RAM, Android 10/API 29,
and a 2160 × 1440 display. Settings main-thread work does not scale across all
cores. The available evidence establishes neither thermal throttling nor active
paging as the cause. Universal 60 FPS is not established: the accepted profile
comparison's median largest frame was 43.145 ms against 50.930–54.303 ms controls,
above the 16.667 ms frame budget.

The accepted direct Appearance rows improved their own matched median by
25.06%–30.89%. The subsequent accepted Baseline Profile improved its own matched
median by 15.29%–20.55%. These separate comparisons must not be combined into one
percentage. Both changes remain in the restored build. Full measurement and
hardware limitations are recorded in the linked accepted-change documents.

## Candidate implementation and offline checks

The new opt-in path uses R8's standard optimization while retaining annotated
WebView bridge methods and the small legacy SMB/authentication/event-bus boundary.
Surviving app class names and the finite Compose diagnostic stack selectors keep
their names; shrinking and optimization are allowed for those selectors. Inlining
can still remove stack frames, so absent samples do not establish absent work.
Existing external-probe compatibility rules remain a separate benchmark option.

The native R8 candidate passes the full unit gate: **1,728 tests in 198 suites**,
zero failures, errors or skips. Debug, native benchmark and target-pinned bootstrap
builds succeed. A separate read-only review found no remaining substantiated rule
issue; review and unit tests do not establish runtime compatibility of every photo
source or dependency.

Offline inspection confirms identical package/signing certificate, a
non-debuggable build, identical UI resources/assets, and profiles resolving to
the candidate's own DEX. R8 legitimately rewrites the profile and merges four DEX
files into one; profile entries need not equal those of the unoptimized APK.

| APK | Bytes | DEX files |
| --- | ---: | ---: |
| Accepted control | 37,166,836 | 4 |
| R8 candidate | 25,386,112 | 1 |

The 31.70% smaller APK is a download/install-size result, **not a measured memory,
Settings latency or FPS improvement**.

## Single device attempt and verifier defect

The bounded controller admitted the accepted build, completed five measured
control-before captures, installed the candidate bootstrap and candidate, then
stopped in OAT170 header verification before any candidate timing or the five
planned native functional checks. The old verifier hard-coded `dex_count == 4`.
That condition cannot validate the candidate's one-DEX layout. The failing line
also checks instruction set and header bounds; the candidate prefix was not saved,
so this report does not claim direct observation of every candidate OAT field.

The attempt's original report remains **`completed:false`**, failure `InvalidOat`.
No median comparison, speed claim or native functional acceptance is available.
There was no second timing run in this pass. Restoration independently verified
the exact accepted APK, bundled dex metadata, complete reference-profile equality,
and APK-specific compiled output (`speed-profile`, reason `install-dm`). Home was
restored, configuration unchanged, collector inactive. Elapsed time: 157.93 s.
Living Area operations: zero. Phone operations: zero.

An isolated offline verifier correction now requires an explicit expected DEX
count from the admitted artifact, retaining the structural/compiler checks. Eleven
checks pass against the saved four-DEX control and synthetic one-DEX/invalid-header
fixtures. This is **not** verification of the actual candidate's OAT, integration
into the historical controller, or a successful native rerun. The historical
parser, admission, report and evidence were not modified. Native R8 remains pending
that integration and one complete matched comparison before promotion.

## Evidence and sources

Local evidence root:
`~/portal-prime/build/fleet/diagnostics/settings-native-r8-2026-10-11/`.
It contains frozen admission, build logs, mapping/configuration, offline profile
inspection, the original incomplete `postflight.json`, control captures, restoration
evidence and `verifier-fix-offline.json`.

Candidate SHA-256:
`a622b1faa1c31fa1329c33929464bef735e227361c728451f9c0a2d4064d2aae`.
Restored accepted SHA-256:
`d1635e3271943c8ed0d447d9ebe6da98692c5cb96d506b203980ade34026f2b4`.

Official Android guidance informed the candidate:

- [Compose performance](https://developer.android.com/develop/ui/compose/performance)
  recommends release R8 and Baseline Profiles before local optimizations.
- [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview)
  describes profile rewriting for optimized release code.
- [R8 keep-rule guidance](https://developer.android.com/blog/posts/configure-and-troubleshoot-r8-keep-rules)
  recommends scoped reflection rules and retaining shrinking/optimization where safe.

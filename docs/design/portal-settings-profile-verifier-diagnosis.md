# Family Area Settings profile verification diagnosis

The first Settings app-profile timing trial stopped before its first candidate capture. Its
fresh AndroidX cache reported `RESULT_CODE_COMPILED_WITH_PROFILE_NON_MATCHING` (3); the
original code-1-only admission requirement remains failed. No timings from that stopped
trial are promoted or imported into the subsequent protocol.

## Observed cause

A separate inspection on Family Area, with no performance captures, recorded these actual
own-app files and cache values:

| Installation step | Cache result | Cached current bytes | Actual reference bytes |
| --- | ---: | ---: | ---: |
| Accepted APK and compiled SDK profile | 1 | 11,414 | 11,414 |
| Candidate bootstrap, APK only | 2 | 10,844 | 0 (present, empty file) |
| Candidate target, exact APK and bundled DM | 3 | 10,843 | 10,843 |

The candidate current and reference profile files were byte-identical: SHA-256
`af66c93404cd1ccc830a5c36768c3cf229c5ee189bc0950e61f6391af0e41550`.
The candidate APK was `d2720b06b88577e87c99887cc60f97912f3fd52ebd04f2b86cf884d66578ef89`;
the bootstrap was `dc77fe2509436d9e6ee30a6341f74992e6e703ace2b09aaee5ebd081afd0c5d1`.
The two APKs differ in their pinned installer build configuration. Their profiles therefore
have different DEX checksums and compressed byte representations.

The cached AndroidX ProfileInstaller 1.4.0 verifier bytecode sets result 3 when the prior
cache result is 2, a reference profile is now present, and its byte size is smaller than the
prior cached current-profile size. The inspected transition meets that condition by one
byte. The verifier does not compare method identities in that branch.

## Direct reference-profile evidence

The actual candidate reference file was decoded with the cached AndroidX v010 decoder and
mapped against the frozen APK. All four DEX ZIP CRC32 checksums, method counts and covered
indices matched. After canonicalizing ART's `base.apk` container keys, its inventory matched
the bundled candidate profile exactly:

- 35,137 method entries, 5,389 class entries, and 103,856 H/S/P flag entries.
- Zero missing, extra or changed method/class entries or flags.
- 215 app methods, all HP; no app startup flags, Home methods or app class entries.
- All six required Settings entry signatures and generated shell methods covered.

This establishes complete resolved method/class/flag membership for this reference profile.
It does not establish inline-cache byte equality or native machine-code coverage of every
method. Result 3 in another installation must be investigated on its own evidence.

The inspection verified that the installed DM's `primary.prof` and `primary.profm` payloads
matched the candidate APK's bundled assets exactly. Its mapped own OAT 170 prefix contained
`speed-profile` and `install-dm` labels; that inspection used label searches and did not parse
the structural key/value store. A subsequent bounded ARM64 ELF/OAT170 structural parser was
validated on the restored accepted APK, confirming those labels inside the actual store.
Fresh structural parsing remains mandatory for each timed condition.

The accepted APK and its SDK profile were restored after both inspection attempts. The
final read-only accepted check verified exact DM payloads, its actual reference profile,
structural OAT labels, unchanged configuration/schema/source groups and inactive collectors.

## Separate runtime-verified timing protocol

The new protocol is preregistered in the private ignored diagnostics directory
`settings-app-profile-2026-10-11/runtime-verified/acceptance-criteria.json`, SHA-256
`eb8b464a8497e61c9cea1ee70a11c4b101d9edaab95e6ec046f5d40bc174d61a`.
It admits a fresh before/candidate/after block only after exact installed APK/DM verification,
complete own reference-profile mapping and set equality, and structural own OAT verification.
Cache codes remain observations; a candidate code 3 requires the observed prior-cache size
condition plus all direct gates. Earlier timing captures cannot be reused.

Each block uses 20 warmups and 10 timed fresh Settings entries. The unchanged timing gates
are at least 10% lower largest-frame median versus both controls and no more than 5% root
composition regression. Missing/dropped/invalid/overflow records reject the block. Explicit
memory gates limit median PSS overhead to 20 MiB above the larger control median and the
last-three versus first-three candidate median increase to 5 MiB. These short samples do
not prove long-run leak freedom.

One sequence owns cleanup and unconditionally reinstalls the accepted SDK-bootstrap/target
pair on exit, then verifies the complete compilation admission again. Capture ownership
requires an acknowledged start; unknown/replaced sessions are polled read-only. Native
state-check/stop requests are separate operations, so exclusive controller ownership is
required; the local lease covers participating operators rather than constituting a server
lease. Protocol, input/tool and capture digests bind the analysis to this sequence.

The focused private verification suite passed 61 tests (35 mapping/comparison, 13 capture
ownership/replay, 8 OAT header, 5 memory rejection). The historical frozen candidate source
`50ac69d96f540797c8eaa13a3cab10cf826855dbee7803d858db542649ba1d28` had already passed all
1,636 app tests and debug/benchmark builds. The fresh timing sequence is now complete; its
candidate failed the preregistered performance gate. Living Area and the phone were untouched.
The cascading-column browser mock is complete and separate from this profile experiment.

## Final three-block result

The runtime-verified sequence exited 0 after completing all three fresh conditions and final
restoration. Offline `runtime-verified/analyze.py` also exited 0 after verifying the bound
captures and producing `matched-analysis.json`. Those exit codes indicate completion, while
`timingGatePassed=false` and `performanceGatePassed=false` reject the candidate. The original
code-1-only trial remains failed and contributed no candidate timings to these results.

Each block completed 20 warmups and 10 timed fresh Settings entries, with 30 verified collector
stops: 90 captures and 30 timed entries overall. Each timed block supplied 30 Settings frame
callbacks, including 20 FIRST_DRAW flags. Callback drops, invalid samples and overflow samples
were zero. The metrics are Window callbacks and Compose wall-time spans; they do not measure
physical tap latency, delivered FPS or independent, additive CPU costs.

| Metric (medians across 10 timed entries) | Baseline before | Candidate | Baseline after |
| --- | ---: | ---: | ---: |
| Largest Settings frame, ms | 89.23 | 94.97 | 95.80 |
| Mean recorded Settings frame, ms | 61.43 | 65.61 | 67.11 |
| Root composition, ms | 10.94 | 8.92 | 11.22 |
| Search composition, ms | 4.00 | 3.38 | 4.04 |
| Root measure, ms | 9.42 | 12.40 | 10.75 |
| Median PSS, MiB | 114.02 | 108.00 | 108.92 |

The candidate largest-frame median was 6.44% higher than baseline-before and only 0.86% lower
than baseline-after. It failed the required 10% improvement against both controls. Root
composition was 18.43% and 20.45% lower, passing the no-more-than-5% regression gate. The
candidate root-measure span was 31.54% and 15.33% higher than the controls; that span was
reported rather than substituted for the preregistered largest-frame outcome.

Direct compilation admission passed for every block. Accepted controls contained 34,922
profiled methods and 5,389 classes; the candidate contained 35,137 methods and 5,389 classes.
Each own reference inventory matched its exact frozen APK's bundled method/class/HSP set and
DEX checksums. Installed DM payloads matched their bundled assets. Every condition's own
ARM64 OAT170 key/value store was structurally parsed and reported `speed-profile` /
`install-dm`. Cache observations were 1 / 3 / 1; the candidate's code 3 again followed queued
10,844 bytes becoming a 10,843-byte reference. These checks establish the declared compilation
condition without proving machine-code coverage of individual methods.

## Memory disposition

The candidate passed the short-cohort memory screen. Its median PSS was 108.002 MiB, or
6.018 MiB below the larger control median of 114.021 MiB, satisfying the 20 MiB overhead limit.
Its first-three median was 107.335 MiB and last-three median was 110.364 MiB: a 3.029 MiB
increase, within the 5 MiB growth limit. PSS ranges were 108.686-117.392 MiB before,
105.722-110.433 MiB for the candidate, and 105.069-110.662 MiB after.

Candidate ordered PSS, MiB: 110.253, 106.526, 107.335, 105.722, 107.659, 107.217, 108.346, 109.730, 110.364, 110.433.

The disposition is `Accept short-cohort memory screen`; it does not override the failed timing
gate or establish long-run leak freedom. Overall candidate disposition: reject.

## Accepted restoration and source cleanup

The sequence unconditionally installed the SDK bootstrap followed by accepted APK
`bc6af47b4184de27f81fc59c6502871bb8a796505c59c1e047ae859dd3f28298` on exit. Final admission
again verified exact DM payloads, full reference mapping/set equality and structurally parsed
own OAT170 `speed-profile` / `install-dm` labels. The restored reference was 11,414 bytes,
SHA-256 `83484e493451704762ac461723eabdb2ed44e3738136ae9c77a73df7efe0d350`.
Postflight recorded `acceptedApkRestored`, `exactCompiledSdkProfileVerified`,
`collectorInactive`, `homeRestored` and `configurationSchemaAndSourcesPreserved` all true.
The OAT evidence is a bounded 1 MiB prefix, sufficient for this structural header gate rather
than a full OAT integrity or per-method machine-code audit.

After the rejected performance gate and verified restoration, only the untracked 15-rule
`app/src/main/baseline-prof.txt` was removed. Its exact experiment contents are preserved privately
at `settings-app-profile-2026-10-11/rejected-app-baseline-prof.txt`, SHA-256
`ee0f1a21c66a50448f3d32db53dcf8f67b36959d90231139d4f50485be7aba34`. The restored
781-file app-source digest is `1b89eb349152c1a43995a77a2272cc6d510f8d14e6e3d351fec2036cded2386e`,
matching the committed helper base. The `50ac...` digest above identifies the historical
candidate build, not the restored source. Documentation and source cleanup are complete;
the final report and experiment plan are retained in local Git history.

The offline review verified all 27 frozen tool/input hashes, the protocol binding and all
capture digests through the analyzer. It also checked reference/OAT-prefix snapshot hashes
and evidence JSON against each admission, including final restoration. No concrete evidence
binding or restoration concern was found. Private final inputs and outputs are under
`settings-app-profile-2026-10-11/runtime-verified/`; `matched-analysis.json` SHA-256 is
`7dddb0b9ab180aff94fcfde09fe78abe407b2f9fdfbf707a1d1db5e64fb1a2ee`.

## Queued diagnosis

The largest observed frame component in `runtime-verified/two-block-work-span-preview.json`
was `UNKNOWN_DELAY_DURATION` (`queued`). Medians of the per-entry component means were
31.46 ms for baseline-before and 36.04 ms for the candidate; corresponding per-entry maximum
medians were 64.44 ms and 69.44 ms. Queued delay and main-thread measure/layout remain the
next component-diagnosis focus on the restored accepted baseline.

This preview compares only the first control and candidate; it does not include the completed
final control. It identifies a descriptive lead, not a causal bottleneck. Nested work spans
overlap, so their medians must not be summed or added to frame-component medians to attribute
frame time. Fixed order, ART/JIT history, thermal/cache state, GC, scheduling and DEX/layout
differences also limit attribution. The profile experiment is closed as rejected; broader
Settings performance diagnosis remains queued.

## References

- [ProfileVerifier compilation status](https://developer.android.com/reference/androidx/profileinstaller/ProfileVerifier.CompilationStatus).
- [Baseline profile debugging](https://developer.android.com/topic/performance/baselineprofiles/debug-baseline-profiles).
- [Official Android 10 OAT header](https://android.googlesource.com/platform/art/+/refs/heads/android10-s2-release/runtime/oat.h).
- [Official Android 10 OAT reader](https://android.googlesource.com/platform/art/+/refs/heads/android10-s2-release/runtime/oat_file.cc).

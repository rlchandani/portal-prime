# Settings search state candidate — rejected

Family Area only, 11 October 2026. The newer state-based `BasicTextField` did not meet the
predeclared performance gates, so its single-file change was removed and the exact accepted
APK was restored. No Settings column redesign, Home, phone or Living Area change was made.
This is a completed experiment, not a shipped performance improvement.

## Candidate and acceptance boundary

The only candidate app-source change was in `ApprovedSettingsShell.kt`: a remembered
`TextFieldState` replaced the String/onValueChange input path, with SingleLine limits and the
state-based decorator. Result matching, registry/search indexing, Advanced consent, navigation,
styles and preference persistence were unchanged. The state was remembered without adding
save/restore behavior. A review of the resolved Foundation 1.10.4 bytecode confirmed both input
implementations wrap decorator siblings in a TopStart Box; visual alignment was not measured.

Before the first control, retention required at least 10% improvement in the median per-entry
largest Settings Window frame and 20% improvement in search composition against **both**
controls, no root-composition median regression above 5%, valid callback data, full unit/build
gates, native input/navigation checks and configuration preservation.

The candidate passed all 1,602 unit tests across 191 suites with zero failures, errors or skips.
Both debug and benchmark builds succeeded. The benchmark stayed non-debuggable, R8 off,
native Filmstrip on, native Home off and background WebView startup off. Candidate app-source
hash was `dcdbde6cac6e683efab89a7cf7b3e643ec84ecb085fce0d9d8065a453094c9e3`.

## Matched device comparison

Each block freshly replaced its selected APK, verified its installed bytes, then ran 20 warmup
entries and 10 timed fresh Settings Activity entries in that process. Sampling was off. Order
was accepted/candidate/accepted. All 90 owned captures closed successfully; each block's 30
capture stops were independently verified. Settings schema, full screensaver configuration and
source groups were compared in memory and preserved in all three blocks. Home was restored
and the collector inactive after each block. Living Area and phone had zero operations.

| Recorded metric | Accepted before | State-input candidate | Accepted after |
| --- | ---: | ---: | ---: |
| Largest Settings Window frame per entry, median | 102.20 ms | 110.80 ms | 117.73 ms |
| Mean Settings Window frame per entry, median | 75.01 ms | 78.81 ms | 82.11 ms |
| Root composition span, median | 13.57 ms | 15.24 ms | 13.64 ms |
| Search composition span, median | 4.82 ms | 5.58 ms | 5.42 ms |
| Root child measurement span, median | 13.15 ms | 12.81 ms | 13.69 ms |

The candidate's largest-frame median was 8.42% worse than the first control and only 5.88%
better than the final control. Search composition was 15.76% and 2.89% worse, and root
composition was 12.28% and 11.68% worse, respectively. Every predeclared timing gate failed.
There were zero callback drops, invalid frame samples and overflow samples. Every block
recorded 30 Settings Window callbacks and 20 FIRST_DRAW flags; these counts are not FPS.

The controls themselves differed by 15.19% in largest-frame median despite stable root
composition near 13.6 ms. This does not isolate a causal regression or prove that state-based
inputs are generally slower. Thermal state, ART compilation, caches and scheduling were
uncontrolled. Metrics are Window callbacks and executed Compose spans, **not** physical
tap-to-visible latency, delivered FPS or measured CPU execution time. Nested spans overlap;
component maxima cannot be added or subtracted to attribute one frame.

Median PSS was 94.71 / 100.62 / 109.45 MiB. This short ordered experiment does not establish
a leak or assign the memory difference to the candidate.

## Native input and navigation checks

The candidate completed seven real accessibility editing operations: set, append, backspace,
clear, set, set and clear. A fixed unique search result produced new detail composition in the
same Settings Window, Back returned in one action, sidebar navigation kept the same Window,
and the field could be refocused and cleared. Final cleanup verified collector closure,
unchanged configuration/schema/source groups, Home and installed candidate hash.

This covers accepted SET_TEXT operations and source-known result inputs plus composition and
Window identity. It does not assert pixels, exact selection ranges, composing-keyboard
behavior, D-pad behavior or physical touch timing. The candidate was rejected on performance,
so no additional UI sign-off is claimed.

## Attribution capture and compilation lead

An initial sampled capture ended after one entry because its Home-return helper required an
idle collector inside an active capture. Cleanup passed. The corrected ownership-aware helper
completed three fresh Settings openings and six subpage enter/Back checks in 14,944 ms,
preserving all configuration and the accepted APK. It produced 509 stack observations: 414
native-poll leaves and 95 outside native poll. There were 983 skipped sampling cadence slots,
20 depth-truncated observations and saturation of the 256-symbol inclusive table. The separate
bounded timeline retained all 95 eligible observations, 801 symbols, no omitted records and
median/max stack-read duration of 1/42 ms. These are wall-time observations with profiler
perturbation, not sampled method CPU times or a speed comparison.

The pane-bookkeeping stacks are a lead rather than a quantified bottleneck. The corrected
capture recorded 15 shell and 15 detail composition spans for three openings plus twelve
enter/Back route changes; it does not prove a redundant full-shell pass. No pane-cache
rewrite was introduced. Wrapper snapshot reads unused by most subpages remain a static
opportunity, without a measured claim about overview-entry cost.

The accepted APK already embeds `baseline.prof` (11,402 bytes), `baseline.profm` (587 bytes),
ProfileInstaller 1.4.0 and its Startup initializer. A read-only, exact 24-byte ProfileVerifier
cache read after the final accepted control returned schema 1, result 2
(`PROFILE_ENQUEUED_FOR_COMPILATION`) and cached current-profile size 11,414 bytes. This is the
last cached verification; current package-update time was not independently checked and live
dexopt state is unproven. Exact current/reference profile directory listing through Fleet
returned no directory metadata. That does not prove those profiles are absent.

[Android's manual workflow](https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure)
documents APK-plus-`.dm` installation on Android 9–11, with `speed-profile` / `install-dm`
verification. Our HeadlessInstaller writes only `base.apk`; it does not stage profile metadata.
A local `.dm` containing only the two exact renamed profile files was prepared from the accepted
APK, checked byte-for-byte, and not installed. Its profile format is 010, APK hash binding is the
accepted hash below, and metadata SHA-256 is
`8967e5eff982c52c4dcc7c47736c956ddb9a9d5ac28d45a89000134ab2ace089`.
This identifies the next controlled compilation investigation; it is not an AOT or speed claim.

## Restoration and evidence

Accepted benchmark: 37,150,356 bytes,
`bc6af47b4184de27f81fc59c6502871bb8a796505c59c1e047ae859dd3f28298`.
Rejected benchmark: 37,150,356 bytes,
`05412f3338e1ee2190880e925e11e1b97585ac42a387c4ecb41e6212c2bfe7ea`.

The exact rejected source diff was retained privately, then removed. App-source digest returned
to `e13615c2bee4128c1a1a937ddf0e3492c0fc34658b46936f7daf0a0e247697d8`.
Final control and the subsequent read-only check independently verified accepted installed
bytes/hash, inactive collector, preserved configuration/schema/source groups and known Home
Window. Living Area already retains the accepted artifact and received no experimental work.

Private evidence lives under ignored `build/fleet/diagnostics/`:
`settings-state-search-2026-10-11`, `settings-next-attribution-2026-10-11` and
`settings-profile-metadata-2026-10-11`. Tokens and full settings/source snapshots stayed in RAM;
no household photo pixels or credentials were exported. No GitHub action or release occurred.

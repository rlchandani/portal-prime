# Portal+ Tools native validation

October 6–7, 2026. Branch: `codex/portal-plus-approved-ux`.
This record covers the Tools overhaul and its representative device checks.
It does not close the application's complete interaction/performance matrix in
`portal-plus-quality-gates.md`.
The subsequent [Jarvis shortcut and model checks](portal-plus-jarvis-handoff.md)
record their own newer installed APK.

## Dock spacing follow-up — October 7

The bottom dock margin is halved from 32 to 16 dp in the retained launcher and
the standalone app shell. On Home, favorites follow the dock down by 16 dp.
The weighted widget area gains those 16 dp; the existing 24 dp gap between
favorites and the dock, saved widget spans and touch-target sizes are preserved.
This changes layout constraints only, with no added animation, polling or state.

Debug and benchmark builds succeed; all **599 existing JVM tests** pass.
The installed benchmark APK SHA-256 matches
`e3fc194656e8c3e1975468ee841988d8e9f28367bda675508fc8ee32a41615f8`.
Actual Portal screenshots confirm the dock and favorites moved down **18 physical
pixels**, the widget area grew by **18 pixels**, and the bottom gap is **18 pixels**
(16 dp at density 1.125). Home, Apps and Tools remain aligned.

Focused browser checks confirm the 16 dp bottom gap across all three tabs,
unchanged 92 dp dock / 74 dp buttons, a 16 dp widget-area gain and the existing
24 dp favorites-to-dock gap in normal and Customize modes. The 25 synthetic
review captures are regenerated and validate at 2160 × 1440 without browser
errors. Earlier native header/appearance captures below retain their original
32 dp bottom gap. Their performance measurements apply to the previous APK;
the spacing-only follow-up does not repeat or supersede that performance probe.

## Header and shared appearance follow-up — October 7

The decorative Portal outline before “immortal” is removed. The five header
actions are phone remote, sleep, day/night appearance, app/settings search and
Settings. Phone remote uses a smartphone; actual Calls retains the handset.
Sleep uses a crescent with Z, distinct from the theme crescent/star.

Home, Apps, Tools, their workspace controls and Settings follow the existing
registry-backed `settingsTheme`. Switching appearance updates the retained
composition without restarting an Activity, fetching images again or adding
polling. Day mode adds one static wash over the selected launcher wallpaper.
Tools and its rail remain transparent. Photo captions remain white over the
dark photo scrim; light semantic colors and focus indicators have contrasting
variants. Immersive camera, lamp and alarm displays keep their existing policies.

The final benchmark APK SHA-256 is
`d2da99339d382a88faf7f4c10b05f6173aab44cc399a1fdfbd0a994e87c5c759`.
The installed `base.apk` SHA-256 matches; the device is left on the Tools library.
Debug, benchmark, Android-test builds and **599 JVM tests** pass.

The final native appearance probe passes **95 checks**: five controls fit without
overlap, chrome and Converter surfaces follow both themes, drafts survive theme
and tab changes, Settings updates the same appearance, the choice survives
Activity recreation, and all **16 measured theme transitions** apply. Visual
checks use stable themed surfaces and a bounded frame-readiness wait, rather
than assuming wallpaper brightness proves the theme. Original appearance value
and key presence are restored. Private evidence:
`cache/portal-ux-e2e/appearance-6106005904/`.

The final Tools regression passes **405 checks in 64.333 seconds**, restoring
configuration/audio fingerprints and owned fixtures. Private evidence:
`cache/portal-ux-e2e/tools-6105898743/`.
Browser verification passes **156 navigation + 130 Tools checks**, with zero
browser errors. Eight additional focused checks confirm final Sleep SVG parity.
The 25-screen browser gallery is regenerated and all 25 images validate at
2160 × 1440 with no browser errors. Fullscreen previews follow native dark-control
policies even when the launcher is in day mode. These are synthetic browser checks,
separate from native evidence.

The same final benchmark build passes **82 performance-probe checks** in
114.509 seconds. Three tool-switching trials each retain 120 valid frame samples:
p95 **47.50 / 38.47 / 37.77 ms**, p99 **50.59 / 44.07 / 39.05 ms**.
There are zero rendered frames in the 2.048-second idle sample. PSS changes from
118,319 to 119,820 KiB across 25 extra four-tool cycles; this includes instrumentation
overhead and is not a long-running leak certification. Private evidence:
`cache/portal-ux-e2e/tools-6106081608/`.

The separate sixteen-toggle appearance interval contains 120 valid retained
frame samples, p50 **11.83 ms**, p95 **82.61 ms**, p99 **104.24 ms**.
The raw gfxinfo reset interval is 8.35 seconds and includes touch feedback and
theme redraws. Preference-transition counting confirms all sixteen changes.
These use `FrameCompleted - IntendedVsync`, not physical input latency.
The **16.67 ms p95 / 33.33 ms p99** screening target remains unmet; the theme
update is not claimed to deliver consistent 60 fps.

The native gallery includes the new day/night library and Converter captures.
The remaining screenshots and the baseline artifact/measurements below document
the earlier Tools-overhaul build.

## Implemented behavior

- Categorized library: eleven ordinary tools, plus Advanced-only Speed test.
- One retained launcher header, wallpaper and dock; Tools and its left rail have
  transparent backgrounds. Cards and sheets retain readable surfaces.
- No return button above the tool rail. Tapping the active bottom Tools tab
  restores the library, including its filter and scroll. Leaving for Home and
  returning to Tools preserves the selected tool.
- Contextual setup sheets and nested pickers. Stories open inside the workspace;
  explicit camera viewing, lamp display and alarm preview use fullscreen displays.
- At most four local utility panes are cached. Hidden panes have a CREATED
  lifecycle and no input, focus or accessibility exposure. Audio, camera and
  discovery/Speed owners are transient; saveable drafts survive their disposal.
- Network, recorder preparation/finalization and collection work stay off the UI
  thread. Foreground audio/transfers cancel on hiding or disposal.
- Strict new countdown dates, serialized timer/camera mutation, cancellable feed
  work, and one-shot snooze delivery independent of the consumed alarm's enabled
  flag. Startup no longer cancels an outstanding snooze.

## Environment and artifact conditions

Connected Portal+, Android 10 / API 29, 2160 × 1440 pixels, 180 dpi
(1920 × 1280 dp), 60 Hz. Deployment and testing use Wi-Fi ADB with USB unplugged.
The existing Sky wallpaper and household configuration are preserved.

The debug and benchmark variants use `com.immortal.launcher.debug` and the same
debug signing key, allowing in-place installation without clearing data.
Benchmark inherits release configuration, is non-debuggable and unminified,
and is profileable by shell. It is not a published release artifact.
The final build uses `cmd package compile -m verify -f` (normal JIT; existing
profiles retained). A full profile reset is prohibited by this Portal firmware
with “Only system can clear all profile data.” These measurements are therefore
not fully cleared cold-install results. Ahead-of-time `-m speed -f` was tested
separately and did not improve the measured workload.

The prior installed APK and private preference archive were saved before the
first replacement under `/private/tmp/portal-tools-before-2026-10-06/`.
No keystore, preference contents, token or recorded audio belongs in this record.

Final APK SHA-256:

- Debug: `2f12845096ec9e53eaa5a7b1ba85ce04a5067b12768f1e97fc9403da96829c0c`
- Benchmark: `ae77cc56bf8b58f51f20de4c5b816a628a6ca78f48a70ab6e2321188fa78015e`

The final installed `base.apk` was retrieved and byte-compared with the benchmark
artifact; they match exactly.

## Build and browser evidence

`assembleDebug`, `testDebugUnitTest`, `assembleDebugAndroidTest` and
`assembleBenchmark` pass. The complete JVM run has **599 tests, zero failures,
errors or skips**.

The updated Tools browser reference passes **119 checks with zero browser errors**.
Browser data and hardware actions remain synthetic. The
[localhost native evidence gallery](http://127.0.0.1:8765/portal-plus-tools-redesign/native-validation.html)
presents actual device captures and the measured limitations. Native screenshots are
stored separately under `mocks/portal-plus-tools-redesign/native-reference/`.

## Native functional evidence

The final expanded `toolsSmoke` run completed **405 assertions in 66.435 s**, with no failed assertions.
Its private evidence directory is `cache/portal-ux-e2e/tools-6103009185/`.
The earlier expanded 389-assertion run also checked Advanced hidden → visible
through the settings registry and restored its value/key presence. Advanced was
already enabled in the final 405-assertion run.
This run used the non-debuggable benchmark build; it did not measure rendering performance.

| Area | Observed checks |
|---|---|
| Library and navigation | All eleven ordinary entries; same launcher window, header, dock and fixed workspace heading; selected rail; bottom-tab return; filter retention; Home round trip retains selected tool |
| Alarm | Nested Hour/Minute Cancel; draft/window return; unchanged saved alarm; explicit silent ring preview; Dismiss returns |
| Timers | Zero duration rejected; temporary 23-hour timer created; cancellation confirmation Cancel retains it; confirm removes it and restores original store |
| Countdowns | Conditional Year; nested Month/Day/Year/Icon Cancel; nested numeric Save; current-date one-off added; Remove Cancel retains exact fixture; confirm restores original collection |
| Converter | Native workspace and local calculation body; representative entry only, not every unit pair |
| Lamp | Numeric Cancel; red/warmth gating; explicit display; returned ephemeral controls and originating workspace |
| Cameras | Invalid input; temporary unreachable loopback RTSP camera Add/View/error/return/Remove; original collection restored |
| Stories | Bundled reader inside retained workspace; explicit reader Back; no automatic narration. Additional automatic native check observed speech-engine Start and Stop returning to idle |
| Notes | Draft retained on tool round trip; Clear Cancel; Save temporary text; Clear Cancel preserves saved text; confirm restores initially absent key. Actual Record/Stop/Play/Stop, Delete Cancel preserves the recorded file, and confirm Delete restores the originally absent file |
| Intercom | Invalid address stays in context; no unintended microphone/broadcast start |
| ISS/Aurora | Native entry, workspace and return; no assertion that live predictions or viewing conditions are accurate |
| Advanced Speed | Actual Start/Cancel/Run again/Cancel; root return; Advanced value/key presence preserved. Earlier expanded run also verified default hiding and temporary registry enable/restore |

Configuration fingerprints for alarm, timers, countdowns, cameras, notes and
Immortal settings match before/after. The ephemeral lamp toggle is restored.
Voice-file fingerprint and microphone grant state match. Fixtures
are removed by owned IDs or exact synthetic values, never by clearing collections.
Microphone permission-dialog cancellation was skipped because access was already
granted; permission flags were not independently fingerprinted.

Earlier probe failures exposed test assumptions about selected tab parents,
editable-field label children and animated dialog window geometry. The corrected
probe follows the actual accessibility relationships and waits for stable dialog
geometry. The expanded memo run also found success feedback below the fold after
recording added playback/delete controls. Feedback now appears above those actions;
the repeated actual audio cycle passes. Its measured performance loop performs no
accessibility-tree traversal.

## Performance evidence

The test warms Alarm, Converter, Countdowns and Lamp; it then performs three
bounded 28-second switching trials, followed by 25 additional four-tool cycles
for PSS and a two-second idle interval. Each trial retains 120 valid frame samples.
There are zero hierarchy reads inside the switching loop.

`FrameCompleted - IntendedVsync` is an Android gfxinfo frame-duration proxy,
not presentation hitch rate or physical input latency. Synchronous input-call
durations include a requested 32 ms pointer hold. Functional readiness polling
includes accessibility and polling overhead.

| Candidate / compilation condition | Trial 1 p95 | Trial 2 p95 | Trial 3 p95 |
|---|---:|---:|---:|
| Disposed tool panes / default install | 64.11 ms | 61.28 ms | 61.99 ms |
| Bounded pane cache / prior default-install condition | 46.28 ms | 37.07 ms | 35.04 ms |
| Cache + extra Alarm resume observer / AOT experiment | 44.54 ms | 43.98 ms | 42.37 ms |
| Final cache + visibility-key Alarm refresh / verify + JIT | 52.64 ms | 39.15 ms | 38.13 ms |

The baseline's expensive frames spent substantial time on the UI thread before
submission: trial 2's p95 DrawStart → SyncQueued was 34.72 ms; p95
IssueDrawCommandsStart → FrameCompleted was 9.79 ms. This motivated the bounded
cache. Its earlier final default-install trial reduced p95 by about 43%. The final
artifact’s warm third trial reduces p95 by about **38%** against the baseline
(61.99 → 38.13 ms), while remaining above the **16.67 ms p95 / 33.33 ms p99**
frame screening budget. This budget is not passed; no consistent-60-fps claim is made.

All measured runs had zero rendered idle frames in their sampled two-second intervals.
PSS did not grow across the sampled blocks: baseline 127,791 → 123,191 KiB;
cache 122,232 → 120,505 KiB; AOT experiment 180,073 → 172,015 KiB. PSS includes instrumentation and test overhead and
uses no forced GC. The final run completed 82 assertions without failure in 115.184 s, using
120 valid frames per trial and 150/152/153 switches. Its p99 values were
68.61/48.39/46.72 ms. Final PSS was 108,632 → 105,832 KiB across 25 extra
four-tool cycles; its idle sample lasted 2.046 s with no rendered frames.
Private evidence: `cache/portal-ux-e2e/tools-6103089738/`. These samples are
not a long-running leak certification.

## Enlarged text and keyboard

On the final benchmark build, system `font_scale` was temporarily set to 1.3.
The library text wrapped without overlap, the rail remained bounded and scrollable,
and an unsaved camera form kept Cancel/Add above the actual on-screen keyboard.
Cancel returned to the originating camera workspace. No camera was added.

The original setting was absent (`null`). Android initially regenerated a `1.0`
row while restoring its configuration; removing that default row afterward restored
the original absence exactly. The original probe’s alarm/lamp screenshots caught the prior window before
GPU presentation; settled manual captures replaced those public images.
Native proof captures: `font-library.png` and
`font-keyboard.png`. This representative check does not cover every keyboard,
locale or accessibility configuration.

## Additional narration check

A separate automatic ADB/accessibility check on October 7 observed `Reading aloud`
after Android's utterance Start callback, pressed the currently visible Stop action,
and observed `Narration stopped`, `Read aloud` and no `Stop reading` control. Both
assertions passed. The check returned the Portal to the Tools library afterward.
These observations establish engine/UI state, not an assessment of aural voice quality.
Captures: `narration-active.png`, `narration-stopped.png` and the settled `story-reader.png`.

## Remaining limits

Real alarm firing/snooze timing and timer completion audio, two-Portal intercom,
compatible household RTSP playback, actual ISS/Aurora forecast accuracy and every
converter unit pair are not established by this smoke run. The browser gallery
cannot establish those hardware/network behaviors or exact Android pixel parity.

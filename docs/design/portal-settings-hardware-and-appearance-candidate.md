# Family Area Settings: accepted direct Appearance rows

Direct Appearance rows are enabled by default. This document records the first
accepted Settings improvement on Family Area. The same signed APK passed eight
native functional checks and a matched control/candidate/control performance
gate. The final default benchmark rebuild was byte-for-byte identical to that
measured APK.

Family Area now runs the additional [Settings Baseline Profile improvement](portal-settings-baseline-profile.md),
which retains these direct rows and the same application bytecode.

## Measured result

Each block used two warm-up fresh Settings pages followed by five measured
fresh pages. All selections stayed within the same Settings window. The process
was warm; this is not a fully cold process or operating-system startup test.

| Quantity | Control before | Direct rows | Control after |
| --- | ---: | ---: | ---: |
| Median largest frame during Appearance opening | 63.317 ms | 47.448 ms | 68.659 ms |
| Worst frame across the five samples | 72.286 ms | 54.037 ms | 95.908 ms |
| Measured fresh Settings pages | 5 | 5 | 5 |

The median largest frame fell **25.06%–30.89%** against the two controls.
The worst observed frame fell **25.24%–43.66%**. The control medians differed
by 8.44%. All predeclared gates passed:

- Candidate median at least 10% below both control medians.
- Candidate worst frame no more than 10% above either control's worst frame.
- Control median drift no more than 15%.

Timing APKs shared the same app source, build type, signing certificate and
compilation treatment. Each block verified its installed APK, bundled dex
metadata, exact SDK profile and APK-specific compiled output. Settings UI and
pane work observers were disabled; the capture explicitly used
`sampleSettingsWork:false`. There were zero Settings work-observer records.

These measurements establish a repeatable improvement in this small matched
comparison. They measure the largest rendered frame, not physical tap-to-display
latency, delivered FPS, every Settings category, or every cold first-open frame.
The 47.448 ms median largest frame still exceeds a 16.667 ms frame budget.
The expired six-hour window remains expired; this result does not claim that
all performance work or that deadline was completed.

## Actual hardware

Observed 2026-10-11 15:02:22 UTC through read-only Fleet requests. No hardware,
Android or app configuration was changed during the hardware read.

| Fact | Family Area observation |
| --- | --- |
| Model / device | Portal+ / cipher |
| Android | API 29 |
| Processor | Qualcomm QCS605, eight logical cores |
| CPU topology | Six smaller cores (`0x803`), two faster cores (`0x802`) |
| Maximum frequencies sampled | CPU 0 and 4: 1.7088 GHz; CPU 7: 2.5152 GHz |
| GPU | Adreno615v2 |
| Kernel-visible physical memory | 2,782 MiB, approximately 2.72 GiB |
| Available memory at observation | 649.7 MiB |
| Swap total / free | 1,536 / 577 MiB |
| Fleet app process resident memory | 172.9 MiB |
| Display | 2160 × 1440 pixels |
| Settings FrameMetrics budget | 16.666666 ms |

The device and Qualcomm documentation agree on the processor, GPU and
approximately 2.5 GHz maximum CPU frequency. Eight cores do not make Compose's
main-thread work eight times faster. Responsive Settings is a reasonable target;
a universal 16.667 ms cold-frame guarantee has not been established.

Memory is kernel-visible usable memory, not the physical DRAM package size.
Process memory is RSS, not PSS or exclusive allocation. Swap occupancy alone
is not evidence of active paging. The hardware read established neither a
memory bottleneck nor thermal throttling.

## Implementation and scope

Google recommends ordinary Row/Column containers for small, bounded lists.
Appearance has at most five registry controls: `tileSize`, `showMiniPlayer`,
`hideStatusBar`, `clockFormat` and `settingsTheme`.

`SettingsRows` emits that registry projection into the retained detail panel's
existing scrolling Column, removing its nested lazy-list work. It reuses
projection, grouping, controls, validation/apply, Advanced visibility, requested
row handling and the hint. Persistence and the Fleet settings schema are
unchanged. Longer lists retain the lazy renderer. Cascading columns remain a
browser mock.

`PORTAL_SETTINGS_DIRECT_APPEARANCE_ROWS` now defaults to true in ordinary and
benchmark builds. Explicit benchmark
`-PportalSettingsDirectAppearanceRows=false` retains the control path.

The diagnostic request's optional `sampleSettingsWork` defaults to true for
compatibility. Explicit false skips Settings layout timing, pane-work allocation,
search-preparation timing and entry tracing while retaining frame metrics.
Regression coverage verifies no added modifier or timing-clock reads and
preserves the real search index.

## Functional and deployment verification

The final default build passed **1,728 tests in 198 suites**, with zero failures,
errors or skips. Debug and benchmark assembly succeeded. The default benchmark
APK has the same compiled flags and SHA-256 as the measured candidate.

Eight native Family Area checks passed:

- Tile size, clock format and Appearance: open, dismiss, select and restore.
- Mini-player: toggle round trip.
- Advanced: hide, cancel consent, enable and reveal.
- Hide status bar: toggle and restore.
- Search to `immortal.settingsTheme` and `immortal.hideStatusBar`: requested row
  committed and visible within the same Settings window.

After the comparison, the previous accepted APK and exact SDK compilation were
restored and verified. Promotion then installed the measured candidate and
verified its APK hash and compilation. Configuration was unchanged, Home
restored and the collector inactive. **Living Area operations: zero. Phone
operations: zero.**

An earlier header Home tap following a system status-bar change timed out.
Its cause remains unproven. The completed functional run verified the initial
header return and used the explicit remote Home command after system-bar
changes and search. This improvement does not claim to fix that header edge
case.

## Evidence

Local diagnostics live under `~/portal-prime/build/fleet/diagnostics/`:

- `settings-appearance-frame-only-build-2026-10-11/`: five matched APKs and build admission.
- `settings-appearance-frame-only-functional-read-ready-2026-10-11/postflight.json`:
  all eight native checks and verified restoration.
- `settings-appearance-frame-only-perf-read-ready-2026-10-11/postflight.json`:
  completed comparison, passed gates and verified restoration; 15 captures.
- `settings-appearance-direct-rows-promoted-2026-10-11/postflight.json`:
  completed promotion, installed hash, compilation and unchanged configuration.
- `settings-appearance-direct-rows-promoted-2026-10-11/default-enabled-verification.json`:
  full tests, assemblies and byte-identical default benchmark proof.
- `settings-appearance-direct-rows-2026-10-11/hardware-facts.json`:
  actual hardware read.

Measured and originally installed direct-row candidate APK SHA-256:
`5c185e77a0e8708603bf285790343b503681ab6ea704a478414fb7600a256e86`.
Functional postflight SHA-256:
`541c3913b1494f5389ba24a8bce868507e8214540d1e727ca7677a932c8e6023`.
Performance postflight SHA-256:
`4b5d9c92cd589c9059960c8a81272c272ee875c7a19971bf99f77c7edee941d5`.
No registry credentials or private setting values are committed.

The earlier attribution results remain in Git history and their local
`settings-appearance-direct-rows-2026-10-11/` evidence. They included work
observers and are not the basis for the accepted speed claim.

## Sources

- [Qualcomm QCS605](https://www.qualcomm.com/processors/application-processors/products/qcs605)
- [Qualcomm platform specifications, QCS605 AI Box](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/QVIC-2024-HW-platforms.pdf)
  (processor topology, not the reference board's RAM or display)
- [Google: Lazy layouts performance optimization, section 4](https://developer.android.com/agents/skills/jetpack-compose/jetpack-compose-performance/references/lazy-layouts)
- [Google: Compose lists and nested scrolling](https://developer.android.com/develop/ui/compose/lists)

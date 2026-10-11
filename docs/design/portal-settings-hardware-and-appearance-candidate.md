# Family Area Settings: hardware and direct-row candidate

The Family Area hardware can support responsive Settings. All eight affected
native Appearance checks now pass. The direct-row candidate remains disabled
by default pending a clean controlled performance comparison; no candidate has
been promoted and no production speedup is yet established.

## Hardware read from the actual device

Observed 2026-10-11 15:02:22 UTC using read-only Fleet requests. No hardware,
Android, or app configuration was changed.

| Fact | Device observation |
| --- | --- |
| Model / device | Portal+ / cipher |
| Android | API 29 |
| Processor | Qualcomm QCS605, eight logical cores |
| Maximum frequencies sampled | CPU 0 and 4: 1.7088 GHz; CPU 7: 2.5152 GHz |
| CPU part counts | Six `0x803`; two `0x802` |
| GPU | Adreno615v2 |
| Kernel-visible physical memory | 2,782 MiB |
| Available memory at this observation | 649.7 MiB |
| Swap total / free | 1,536 / 577 MiB |
| Fleet app process resident memory | 172.9 MiB |
| Previously verified display size | 2160 × 1440 pixels |
| Settings FrameMetrics budget | 16.666666 ms |

The device observation and Qualcomm's QCS605 documentation agree on the
processor, Adreno 615, and approximately 2.5 GHz maximum CPU frequency.
Qualcomm documents two faster cores and six smaller cores. Eight cores do not
make Compose's main-thread critical path eight times faster.

The memory figure is kernel-visible usable memory, not a claim about the
physical DRAM package. Resident memory is RSS, not PSS or an exclusive app
allocation. Swap occupancy alone does not establish active paging or explain
the Settings stall; no memory bottleneck has been demonstrated by this read.
This observation also does not establish thermal throttling.

## Established guidance and the actual change

Google's Compose performance guidance recommends ordinary `Row` / `Column`
containers for small, bounded collections, avoiding lazy layout's composition,
measurement, and prefetch machinery. Appearance projects at most five controls
from the existing registry:

- `tileSize`
- `showMiniPlayer`
- `hideStatusBar`
- `clockFormat`
- `settingsTheme`

`SettingsRows` emits that projection into the retained detail's existing
scrolling Column. It reuses the existing projection, grouping, control renderer,
validation/apply path, Advanced visibility, requested-row handling, and hint.
No settings persistence or fleet schema was changed. Long lists retain the lazy
renderer. Cascading columns remain a browser mock.

The implementation is opt-in through
`-PportalSettingsDirectAppearanceRows=true` for benchmark builds. The default
value in ordinary builds is false. Thus the candidate has not been promoted to
the installed accepted build.

## Completed verification

- Debug and benchmark assemblies succeeded.
- Full JVM gate: 1,719 tests in 198 suites; zero failures, errors, or skips.
- Candidate packaged flags, package identity, and signing certificate checked.
- Driver/analyzer helpers: 54 tests passed normally and with Python optimization.
- Native Family Area run: 52 captures, 173 attempted and acknowledged inputs,
  12 Back operations, zero uncertain inputs.
- Navigation maintained one Settings window owner.
- Frozen inputs, source, and capture hashes verified.
- Accepted APK and SDK compilation profile restored and verified.
- Configuration unchanged; Home restored; collector inactive.
- Living Area operations: zero. Phone operations: zero.

These checks establish the recorded navigation recipe, not complete picker,
search, scroll, or settings-value behavior.

## Observations, not a controlled speed claim

Both runs include functional and pane observers. They are attribution runs,
not matched final performance comparisons. Each has one first Appearance
selection and five returns to the same retained Appearance pane. “First” does
not mean a fully cold process or a cold operating system.

| Observed quantity | Earlier attribution run | Direct-row candidate |
| --- | ---: | ---: |
| First Appearance child measurement span | 80.200 ms | 28.220 ms |
| First Appearance detail composition span | 17.039 ms | 25.156 ms |
| Largest Settings frame during first Appearance selection | 227.642 ms | 146.100 ms |
| Median Appearance measurement span on five retained returns | 0.225 ms | 0.224 ms |
| First unchanged Wallpaper measurement span | 34.567 ms | 27.013 ms |
| Largest Settings frame during first unchanged Wallpaper selection | 172.044 ms | 119.251 ms |

The unchanged Wallpaper path improved as well. Device state, compilation, and
cache effects therefore prevent attributing the before/after frame difference
solely to the Appearance change. The work also shifts between composition and
measurement. These inclusive wall-time spans overlap and must not be added as
CPU cost or treated as physical tap-to-display latency.

The candidate's 42 measured selections recorded inactive outer-pane measurement
spans of at most 0.234 ms. This does not rule out descendant-only work, but it
does not justify rewriting retained panes as the next step.

## Current acceptance status

The original driver failures were resolved using device evidence, without
changing the Settings UI to fit the test. Actual detail rows begin at pixel
486 on the 2160-pixel display; the old 25% threshold discarded them. Compact
buttons also require the real sidebar boundary rather than a midpoint rule.
A picker closing is a pending observation, not an invalid Settings target.

All **eight affected native checks passed** on the Family Area candidate:
three pickers open/dismiss/select/restore, the mini-player toggle round trip,
Advanced visibility/consent cancel/enable, the status-bar toggle/restore, and
search navigation to the first and last requested rows. The requested row
checks verified its committed route, visible bounds, and the same Settings
window. The original configuration, accepted APK and exact SDK profile were
restored, Home returned, and the collector was inactive. Living Area and the
phone received zero operations.

A header Home tap after the system status-bar test timed out in an earlier
functional run. The cause has not been established. The completed run retained
the successful initial header return and used the existing explicit remote
Home command to reset after system-bar changes and search. Do not claim this
proves that header edge case is fixed.

The initial matched timing attempt correctly rejected its first measured
sample: the older Settings UI work profiler activates during any capture,
independently of the newer UI and pane BuildConfig flags. No clean performance
comparison or production speedup was established by that attempt.

The existing capture request now accepts optional `sampleSettingsWork`.
It defaults to true for backwards compatibility. Explicit false skips Settings
layout work, pane work allocation, search preparation timing and entry tracing;
frame metrics remain available. Regression tests verify no added Settings
modifier or timing clocks while preserving the real search index. The focused
red/green test cycle passed, followed by the full **1,728-test / 198-suite** gate
with zero failures, errors or skips, and a successful debug assembly.

The direct-row renderer remains disabled by default. Its performance promotion
still requires the same-source, same-compilation control/candidate/control
comparison with the existing frame-budget and tail gates. The expired six-hour
window remains expired. A reported frame duration is not physical tap latency
or a guarantee for every cold first-open frame.

## Evidence

Private local diagnostics are under
`build/fleet/diagnostics/settings-appearance-direct-rows-2026-10-11/`:
`hardware-facts.json`, `analysis.json`, `postflight.json`, `build-admission.json`,
and the 52 capture files. They contain no committed registry credentials.

The stopped acceptance cycle is recorded in local
`build/fleet/diagnostics/settings-appearance-matched-window-ready-2026-10-11/postflight.json`.
The reusable matched APKs and build admission are in
`build/fleet/diagnostics/settings-appearance-matched-2026-10-11/`.
Current matched app-source fingerprint:
`1b44088e317f2fb8f88a134b476b29c122179d2e84cdc4dc714874d13dcbbee6`.
The fingerprint below belongs to the earlier attribution run.

- Candidate full app-source fingerprint:
  `fe1e16b50a779b78996360fcf72b3a7787e1746caf4411f2d9fab5b86fdc5415`.
- Candidate benchmark APK SHA-256:
  `0106b2b4ebd46de336b7a0146a87b0d9ae93381a87ee5d778c69e521a382282c`.
- Candidate analysis frozen manifest SHA-256:
  `6116621f48421271b8db2473b1d43bb0ed25df2e28ab728eac83971a27ed9914`.
- Earlier attribution manifest SHA-256:
  `988eb8583c359505083b65586511f40ed5c9c5e2af3a9d7058ed8a8c3f62795e`.

## Sources

- [Qualcomm QCS605](https://www.qualcomm.com/processors/application-processors/products/qcs605)
- [Qualcomm platform specifications, QCS605 AI Box](https://www.qualcomm.com/content/dam/qcomm-martech/dm-assets/documents/QVIC-2024-HW-platforms.pdf)
  (used for processor topology, not that reference board's RAM or display)
- [Google: Lazy layouts performance optimization, section 4](https://developer.android.com/agents/skills/jetpack-compose/jetpack-compose-performance/references/lazy-layouts)
- [Google: Compose lists and nested scrolling](https://developer.android.com/develop/ui/compose/lists)

## New native and diagnostic evidence

- `build/fleet/diagnostics/settings-appearance-functional-final-2026-10-11/postflight.json`:
  completed eight-check native run, exact accepted restoration, unchanged config.
- Completed functional postflight SHA-256:
  `f672a8ed6f800211d365abfc0c4ba7ab14f1de51efc168a811035df5ff9bf117`.
- `build/fleet/diagnostics/settings-appearance-matched-bounds-2026-10-11/postflight.json`:
  timing sample rejected for active Settings work instrumentation; rollback verified.
- `build/fleet/diagnostics/settings-frame-only-full-gate-2026-10-11.log`:
  full current tests and debug assembly for the capture opt-out.

The eight-check native run used the earlier matched APK. The capture-request
change has not yet been installed or timed on the device. Its Settings UI and
persistence behavior are unchanged; the new native artifacts still need their
runtime acceptance before promotion.

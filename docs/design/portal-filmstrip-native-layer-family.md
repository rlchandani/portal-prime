# Family Area Filmstrip native-layer diagnostic — 10 October 2026

The explicit hardware-layer request is rejected as a performance fix. Identical instrumentation and assets are used in baseline → candidate → restored baseline. The retained Apps-only build is restored afterward. Family Area is the sole device; Living Area remains untouched.

## Change and verification

Only the explicitly selected Filmstrip WebView requests `View.LAYER_TYPE_HARDWARE`, immediately after controller ownership registration. Original CSS/JavaScript, geometry, overlays, input handling and host lifecycle are preserved. ZIP differences are `classes3.dex` and its baseline profile; all other entries, including manifest and slideshow assets, match. The temporary source request is withdrawn after building.

Both variants use a bounded optional layer observation read on guarded Main acceptance, tied to the original capture session. It retains only layer type, attached/accelerated flags and viewport dimensions. A review found that an acceptance clock sampled before acquiring the session monitor could admit a view read after expiry. A deterministic contention regression demonstrates that failure; moving the clock inside the lock passes the regression and the full gate. The observer/guard is committed separately as `1875772`; it is measurement infrastructure, not a frame-speed improvement.

Both instrumented control and candidate pass fresh native gates: 1,482 unit tests, zero failures/errors/skips, plus debug and nondebuggable benchmark builds. All 93 slideshow/resource/lifecycle regressions pass. The adapted physical protocol passes 68 cohort, 17 source-restoration, five ownership/export and 14 wrong/missing-layer rejection cases before device captures.

## Device comparison

Twelve generated uniform-geometry JPEGs are freshly file-size/hash verified. Each block has one warmup plus three timed 40-second entries, with five-second nominal advance interval and no Main stack sampling. The accepted style observation confirms an attached, accelerated 2160 x 1440 WebView with layer type 0 in both controls and type 2 in the candidate. This proves the requested view-layer state, not texture residency or rendering reuse.

| Timed median | Window mean ms | p95 ms | p99 ms | Mean commands ms | App PSS KiB | App graphics KiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Baseline before | 27.00 | 51.50 | 114.25 | 13.06 | 169863 | 55468 |
| Hardware layer | 29.19 | 51.50 | 112.25 | 13.63 | 176443 | 55696 |
| Restored baseline | 30.50 | 54.50 | 102.75 | 14.14 | 159476 | 55652 |

P95 is unchanged against the preceding baseline and 5.50% lower than the restored control. P99 is 1.75% lower than the preceding baseline and 9.25% worse than the restored control. Mean duration is 8.10% worse than the preceding control and 4.30% lower than the restored one. The predeclared 10% tail thresholds against both surrounding baselines are not met. Mean command duration has no consistent improvement. Do not retain the request.

All twelve collector stops and three block-level source restorations complete. Configuration, current-value settings schema and dormant source credentials match their original RAM-only snapshots. The original retained APK is hash-verified, generated photos are deselected, Home is confirmed and collectors/listeners/pin/cover/cleanup state is clear. Callback-drop/invalid/overflow counts are zero in all timed preview samples.

These short sequential generated-content observations do not measure delivered FPS, actual photo advancement, on-device pixel equivalence, thermal/cache/JIT control or separate Chromium renderer memory. Forty seconds does not exercise a full twelve-card wrap. Neither request flags nor a small app graphics delta prove an offscreen target exists. Do not extrapolate this test to Dream/covered hosts or call it complete slideshow acceptance.

## Next delivery window

The user set a six-hour delivery target at 21:01 UTC on 10 October (03:01 UTC on 11 October; approximately 8:01 p.m. Pacific). Stop pursuing additional low-confidence layer tweaks. Main owns the Filmstrip rendering/controller/loader work; independent workers own the pure coverflow geometry model and Home/Apps transition cause and patch. Preserve the coverflow appearance, transitions, full wrap and interruption/pause/ownership behavior; the existing native one-photo/pair renderer is not a drop-in replacement.

Target implementation in the first three hours, focused Family-only functionality/performance checks and fixes in hours three through five, then accepted-build deployment and a measured result report in the final hour. No broad healthy-feature or Living Area benchmark repeats, GitHub connection, authentication, push or release. Do not claim all unknown defects fixed; retain only improvements supported by device evidence and report unresolved outliers.

Private immutable numeric evidence and preserved artifacts live under ignored `build/fleet/diagnostics/filmstrip-native-layer-2026-10-10/`. No household photos, screenshots, DOM, credentials or configuration values are exported.

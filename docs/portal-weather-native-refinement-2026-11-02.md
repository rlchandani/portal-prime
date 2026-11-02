# Weather native refinement — 2 November 2026

The approved Weather candidate is implemented on `codex/weather-native-refinement-20261102`, but **native acceptance is incomplete**. The original15:05:14–15:35:14UTC slot closes without a release to either room.

Adaptive content, current conditions, hourly/daily/off, distinct initial loading/completed failure, explicit missing forecasts, neutral unknown WMO icons and full-width Weather settings use the existing automatic location and producers. Geometry, mode and clock format are absent from fetch producer keys; this is source evidence, not a measured zero-request claim. No new provider or preferences were added.

13 policy tests passed; the full gate passed **3882 tests /407 suites**, zero failures/errors, debug assembly, optimized performance assembly/R8 and vital lint. Version sync passed at1.78/code72, using the existing signer. Final candidate SHA-256: `a4f96600c51fcddc25de7f8b7fd986c7fb687e1f6447bb8a1ec046f8a688375a`.

Native Family checks confirmed real conditions, hourly/off, both palette presentations, the Weather settings destination/Home return and short-card scrolling on the first candidate. Final rebuilt hourly is clean. Medium daily low values still clip. A4×4 card falls below the existing grid viewport. Setting150% font scale was not sufficient proof that the renderer applied it. Controlled unavailable/missing-forecast states remain unverified natively. These prevent delivery acceptance.

| Six-swipe sample | Baseline1.77 | First candidate | Final warm candidate |
|---|---:|---:|---:|
| Frames |76|77|76|
| Janky frames |13 (17.11%)|23 (29.87%)|8 (10.53%)|
| p50 /p90 /p95 /p99 |8/25/38/85ms|10/26/32/48ms|7/16/23/27ms|
| App PSS |187525KB|120411KB|122045KB|

The final candidate included six warmup swipes before its measured six; sample/app-age differences prevent a general improvement or memory-reduction claim. No ANR, universal FPS or network-request acceptance is inferred.

Family was restored with `adb install -r -d` to exact original1.77 SHA-256 `5df871735ada5aae37375a6cd6129aa9fcb13329c982441a0cc90db2fd4f383e`. Complete profile, screensaver and layout match their originals; font scale is1.0 and original Chrome task27174 remains listed. Accessibility has no pending Binding service. Living received no operations. No accounts, tokens or personal photo screenshots were published. Candidate evidence is limited to a Weather-only crop.

Timing: native testing and restoration finished before the deadline; local bookkeeping closure was15:35:21.379UTC,6.51s after the15:35:14.869UTC cap. No issue slot was restarted.

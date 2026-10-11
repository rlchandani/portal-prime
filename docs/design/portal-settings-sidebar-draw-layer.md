# Family Area: sidebar draw layer not admitted

The single comparison stopped during native functional verification. The sidebar
layer is **not promoted**, and its opt-in source change has been removed. The
accepted native R8 build remains the benchmark default and is restored on Family
Area. No second comparison or replacement timing samples were run.

## Scope and evidence

The proposed change added a default Compose `graphicsLayer()` to the unchanged
Settings sidebar to isolate its drawing from detail-panel invalidations. It had
no transforms, clipping, alpha changes, forced offscreen bitmap, motion or layout
changes. Both candidate and matched control used native R8, one DEX file, identical
UI resources/assets, the same package/signing certificate, and a non-debuggable
benchmark build. Both own profiles resolved 8,596 methods / 2,553 classes, including
246 app methods / 64 app classes. Full unit validation passed 1,728 tests in 198
suites; debug and benchmark builds passed. Source/API review found no concrete
functional defect; this is not native visual or full compatibility proof.

The run completed five control-before and five candidate frame captures, then six
native checks: mini-player toggle, retained Appearance refresh, Wallpaper/Back,
Home/Apps/Home, preview bridge/window smoke, and Header Home with the opposite
system-bar state. It stopped with `target_changed` while preparing the native
`hideStatusBar` toggle for the final Header Home check. This guard prevented a tap
against stale coordinates. The final control block was never executed; this does
not establish an application root cause or a valid matched performance win.

The five control-before largest-frame samples were 47.608332, 20.547303,
40.494914, 43.796197 and 41.134817 ms. The five candidate samples were 29.209957,
23.351120, 24.414518, 35.152131 and 38.967395 ms. Their incomplete medians are
41.134817 and 29.209957 ms. These observations cannot pass the predeclared
control/candidate/control gate without the final control and full functional
verification. The report intentionally remains `completed:false`, with no
performance decision and `candidatePromoted:false`.

## Verified recovery

The 209.17-second run restored the exact accepted APK, its dex metadata, complete
reference profile and own OAT170 compiled output (`speed-profile`, `install-dm`).
Original settings were reapplied on the accepted APK even if candidate recovery
could not use its UI. Configuration, Home and inactive collector were verified;
restoration errors are empty. Living Area and phone operations were zero.

Accepted SHA-256:
`a622b1faa1c31fa1329c33929464bef735e227361c728451f9c0a2d4064d2aae`.
Candidate SHA-256 (not accepted):
`2ae7d75e4ebf4bcd898cd57b06fe990daae5357dc516437a99897720ae71ae50`.
Control SHA-256:
`2891299c881e6298896f5ad99c378abd42007e52cd6c7594c29bc24c2d7f39ef`.

Private local evidence remains beneath
`~/portal-prime/build/fleet/diagnostics/settings-sidebar-layer-2026-10-11/`:
`build-admission.json`, `profile-inspection.json`, the original ten frame captures,
`postflight.json`, compilation/profile evidence, pinned APKs and build logs.
The original report and captures are not overwritten or represented as completed.

The deployed improvement remains [the accepted native R8 result](portal-settings-native-r8-candidate.md):
36.99%–42.37% lower median largest frame in its completed small comparison, 27.779 ms
versus 44.089–48.204 ms. It still exceeds the 16.667 ms frame budget and does not
establish universal 60 FPS or completion of the broader goal. The original six-hour
deadline remains missed. Repeated blind profiling is not a substitute for an
identified cause and a bounded implementation.

## Official implementation guidance

- [Compose graphics layer: display-list isolation and default buffer behavior](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers).
- [Compose performance](https://developer.android.com/develop/ui/compose/performance).

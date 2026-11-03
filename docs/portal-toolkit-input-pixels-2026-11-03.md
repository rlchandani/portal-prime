# Portal Toolkit mirror pixel bounds

Mirror coordinates now clamp to the last valid device pixel: width-1 and
height-1. A tap inside the right letterbox of an 800×400 view for a 1280×800
device now builds `input tap 1279 400`; the baseline emitted column 1280.
Bottom-bar long presses and overshooting drag endpoints receive the same
correction. Existing letterbox scaling, command construction and edge-clamping
behavior remain.

The actual pure mapper and command builders run without mocks. The focused
baseline executes 20 methods: five assertion failures and fifteen controls,
zero errors/skips. Three failures are new command witnesses; two are existing
methods whose three inclusive assertion literals and two comments were corrected
before RED. All twelve original methods remain. The new one-pixel case is a
passing control. Both fixture files are byte-identical between RED and GREEN.
No copied model or actual ViewModel/gesture/codec is executed.

All **220 JVM tests / 37 fresh suites**, including 20 focused methods, pass with
zero failures/errors/skips. Debug assembly exits 0 at
**2026-11-03T19:27:52.125259+00:00–2026-11-03T19:28:12.464779+00:00**.
Native-library stripping and JVM bootstrap-sharing warnings remain in the log.
The result collector initially rejected a Z timestamp after baseline Gradle
exited 1; it then parsed the same fresh XML without another test run.

Local Toolkit commit: `685adb1cd358a7af794e053fa84f3deaaa06454c`, parent
`a74f5663d4ee38367e7a1769f55f4075a4befc92`. Production changes exactly two upper
clamp lines; test changes are the new eight-method fixture and the disclosed
existing expectation correction. No dependency/version/UI/workflow change.
Independent source/result review passes 14/14 and is retained by its hash in the structured proof.
Existing configured CI discovers the tests; hosted execution was not observed.

Debug APK: 13901710 bytes, SHA-256
`f56f662786983d92ac93074e663098037d618070b70112cbee6dc27ccf669974`. Build bytes establish no installed or signed
release acceptance. Original Toolkit checkout remains unchanged.

- Actual pure InputInjector mapping and command builders only; MirrorScreen/ViewModel gesture callers and wm-size dimensions are source-reviewed, not instantiated.
- No native gesture, rendering, codec, orientation, input injection, phone/Portal, provider, ADB transport or performance acceptance.
- Letterbox gestures still clamp to edges; no suppression or UX redesign. Interior scaling and invalid-dimension fallback remain.
- Two old methods deliberately encoded width/height as inclusive bounds. Three assertion literals and two comments were corrected before RED; all 20 focused methods remain frozen thereafter.
- One-pixel99/100 is a passing control, not a baseline failure. No NaN/infinity/overflow or malformed-size policy claim.
- Initial result collector failed on a Z timestamp after Gradle exited 1. Same XML was parsed and archived without rerunning tests or changing fixtures.
- Debug assembly/bytes only; no signer, installed identity, version/dependency change, hosted CI, device action, push or publication.
- Earlier APK candidate stays parked; expired screenshot, scanner and other scopes retain their original clocks.

Original clock: **19:24:33–19:54:33 UTC, 3 November 2026**, including discovery,
verification and local closure; no reset. Queue **112: 69 delivered, 32 verified,
3 approval, 7 parked, 1 queued**. All 111 previous task objects/raw suffix and
Weather default remain. Whole-product work stays active.

Structured proof: `mocks/portal-widget-gallery-20261102/evidence/toolkit-input-pixels-verification.json`.

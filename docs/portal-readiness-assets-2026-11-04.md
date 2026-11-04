# Reusable packaged-asset release check — 4 November 2026

The existing local release checker now verifies every main source asset before
renaming its inspected pending APK to `candidate.apk`. Every source path needs
exactly one ZIP entry with the same size and SHA256. Missing, duplicate, stale,
truncated, CRC-invalid or invalid-DEFLATE entries fail with a failed receipt and
retire the pending APK. Successful receipts record sorted `sourceAssets` evidence.
This replaces the recurring separate manual source-asset comparison.

The same final seven actual-CLI cases show seven baseline AssertionErrors and
seven candidate passes. The build and SDK processes are fixtures; the CLI and
ZIP assets are real. The positive case checks compressed nested text, binary and
empty files with exact evidence. All **271 Portal Python regression tests pass**.
The actual helper also checks all **23 main assets** in the existing optimized
**1.160/code154** APK, SHA256
`9a7fa0177937241d1709115479a4aa65b605f0a41021cb29e0f80b5175b75af9`.
The initial five/six-case runs are intermediate; final seven-case evidence governs.

This host-only change uses the existing CLI and CI Python discovery. Versions,
application code, source assets and devices are unchanged. Extra library/generated
assets and Android resources are outside this comparison. No fresh full readiness
orchestration, Android build/UI/device or hosted CI acceptance is claimed. Both
rooms retain 1.160/code154; public1.83/code77 and prior acceptance limits remain.
No phone, household source/account operation, push or publication was performed.

Original discovery **13:35:25 UTC**, selected **13:36:22 UTC**; target13:50:25,
hard14:05:25. The issue clock is not reset. Closure and independent review are
recorded in `mocks/portal-readiness-assets-20261104/evidence/verification.json`.

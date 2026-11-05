# Optimized photo fixture compatibility

The exact optimized **1.205/code199** APK cannot run the existing frozen static
photo fixture unchanged. Host `apkanalyzer dex code` inspections of its actual
Stage and budget classes both exit zero and show these entrypoint differences:

| Frozen fixture requires | Actual optimized APK |
| --- | --- |
| `SlideshowStage.show(…)` | No retained method; mapping places its code in the controller |
| `SlideshowStage.stop()` | No retained method; mapping places its code in the controller |
| No-argument budget constructor | Only the constructor taking a `long` remains |
| `SlideshowPixelBudget.used()` | Wrapper is absent; underlying `total()` survives as `f()` |

Matching the retained budget constructor or calling `total()` would not restore
the fixture's presentation and normal stop/budget-zero retirement contract. A controller-based
harness would introduce additional configuration and ownership requirements.
This larger harness task is parked. No assertion is relaxed, no debug APK is
substituted and no production keep rule or source change is proposed.

The APK is 25,887,623 bytes, SHA256
`f9bbf8fb6eedf889c2a43bfa24848b0619c7adc2d25cef48d47a7b773f6b29a5`.
Independent read-only review agrees with the actual method inventories.
No fixture was compiled, installed or launched; no Android visual, performance,
cleanup or current-room health acceptance follows from this host inspection.
Both rooms retain their last verified 1.205 delivery. No app build, device,
phone, provider, account or publication operation occurred.

The [debug hardware receipt](portal-native-photo-hardware-2026-11-04.md) keeps
its original 4 November **12:15:12–12:45:12 UTC** scope and clock. Its three
debug scenes remain historical evidence. All performance, legacy motion and
black-handoff holds remain. This preflight addresses the explicitly separate
optimized target and does not reopen those units.

Original preflight clock on 5 November: **06:44:25 UTC** start,
**06:49:25** discovery cap, **06:59:25** target, **07:14:25** hard limit.
The incompatibility was established before the discovery cap. Retain this clock
for this gap; do not automatically retry the unchanged fixture.

Selected artifact evidence:
`mocks/portal-optimized-photo-preflight-20261105/verification.json`.

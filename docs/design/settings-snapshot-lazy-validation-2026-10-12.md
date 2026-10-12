# Deferred Settings page snapshots — issue 67

Original interval: 2026-10-12 19:36:42Z to 20:06:42Z. Native admission cutoff 19:56:42Z; new-write stop 19:58:42Z. Clock never reset. Family Area only; Living Area and phone frozen. No GitHub operations or parked candidate reopening.

## Change

The shared ApprovedNativeSettingsPage previously loaded complete ImmortalSettings and ScreensaverConfig snapshots on every detail page and reloaded both on resume. Only Appearance/Weather read the shared app snapshot; only Calendar reads the shared screensaver snapshot. Rich pages manage their own data.

Each shared snapshot now creates its Compose MutableState on first use, with a UI-confined lazy holder. Unused holders perform no configuration reads, including on resume. Loaded holders still refresh on resume and after their existing registry apply boundary. A failed initial load is retryable; failed refresh preserves the prior value. Application context replaces Activity capture, and lifecycle observers remain scoped and disposed with their page. No preference keys, registry specifications, validation, side effects, navigation, styling or UI values were changed.

## Evidence

| Workload | Baseline | Candidate |
|---|---:|---:|
| Two unused shared snapshots / 30 page creations and resume events | 120 loads | 0 loads |
| Focused lifecycle/observability regressions | 5 intended failures; 5 controls pass | 10 pass |
| Full JVM gate | — | 2,018 tests / 232 suites; zero failures/errors/skips |

Debug, signed candidate and matching bootstrap builds pass. Independent source and driver review are clear; 34 offline driver tests pass. Android SDK profile tools decode and map the candidate's complete 8,668-method / 2,576-class inventory, including 285 app methods and 75 app classes. The older accepted APK has 8,667 methods; installed verification requires full canonical equality with the new APK's own complete profile. No cross-APK obfuscated descriptor comparison or specific attribution of the additional entry is claimed. The obsolete six-symbol checklist is not a gate.

These results measure avoided shared-wrapper snapshot reads. Android frame time, heap, GC and physical UI latency gains remain unmeasured. Rich page bodies can still load their own required data.

## Native verification

PASS: actual Home → Settings → Device → About & device health → original Home completed 1/1. The shared detail page loaded and reported `deviceHealthChecked`. The same Settings owner was retained through category/detail navigation, and the original Home owner and geometry were restored. Exact installed APK, APK-derived one-DEX metadata, reference profile, mapped arm64 OAT (`speed-profile` / `install-dm`) and the full canonical 8,668-method / 2,576-class profile inventory were verified. Configuration/source remained unchanged; collector and pin cleanup were idle. Two distinct installs completed through readback after uncertain HTTP acknowledgements; zero POST retries. No permission changes, setting edits, typing or endurance run.

Candidate source SHA-256: `be6224d7bffefd62936bd9debcb458248a7fce3b69ce89c43f68a8358a7531a7` / 879 files. Candidate APK SHA-256: `2c4e54a59be5b0a1cf688d3906bd633f3868a231744635c4f4d9c5780af602db`. Matching bootstrap SHA-256: `7d39c4e463a114efcd4d3bd2eaaba57da2ed1f5b0db456a799919db6c611d3e2`.

The candidate remains installed on Family Area. No whole-app FPS or external website speed claim is made.

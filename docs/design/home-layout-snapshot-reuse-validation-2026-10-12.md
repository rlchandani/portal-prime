# Home layout snapshot reuse — issue 66

Original interval: 2026-10-12 19:15:44Z to 19:45:44Z. Admission cutoff 19:35:44Z; new-write stop 19:37:44Z. No clock reset. Family Area only; Living Area and the phone remain frozen. No GitHub operations or parked candidate retries.

## Change

HomeActivity reads UserLayout assignments and order at tab changes and on resume. AppSwitcher also reads them on resume. All four existing getters previously parsed unchanged preference JSON each time. Each getter now checks the current raw preference and reuses one bounded immutable decoded snapshot. Four entries retain at most 32,768 raw characters each. Larger layouts remain supported without retention. Null clears the corresponding entry; changed raw values replace it. The cache retains no Context or SharedPreferences and does not change saves, keys, serialization, layout ordering or preference storage.

## Controlled evidence

| Workload | Baseline | Candidate |
|---|---:|---:|
| Four layout codecs across 20 visits | 80 parses | 4 parses |
| Concurrent same-value readers | 8 parses | 1 parse |
| Focused regressions | 7 intended failures, 5 controls pass | 12 pass |
| Full JVM gate | — | 2,008 tests / 231 suites, zero failures/errors/skips |

Debug and signed candidate builds pass. Matching signed bootstrap passes. Independent source and driver reviews are clear; 30 offline driver tests pass. Static bundled profile maps all 8,667 methods and 2,576 classes, including 285 app methods / 75 app classes. An obsolete six-symbol checklist is not a gate.

This establishes fewer repeated JSON parses. Android frame time, heap, GC and physical UI latency gains are not measured.

## Native verification

PASS: one Home → Apps → original Home cycle completed in the same owned window with the original dock geometry. Actual Apps opened and the original Home was restored. Configuration remained unchanged; exact installed candidate APK, APK-derived one-DEX metadata, reference profile, mapped arm64 OAT (`speed-profile` / `install-dm`) and full canonical SDK inventory were verified. Collector and pin cleanup were idle. Two distinct APK installs completed through readback after uncertain HTTP acknowledgements; zero POST retries. No setting changes, typing or endurance run.

Candidate source SHA-256: `df53d1e698c173f6fba089ea94e2333ddefcce69b100233e43812301dbb85b03` / 877 files. Candidate APK SHA-256: `d67a74857de77c42d5f793be226136be5db8ab21293b71849b2f688be66e024b`. Matching bootstrap SHA-256: `51a2b45f0d1fd2f398cf0ebc937f6ee41c1d532b63f4dddc7d0496ea10425f99`.

The candidate remains installed on Family Area. No whole-app FPS or external website speed claim is made.

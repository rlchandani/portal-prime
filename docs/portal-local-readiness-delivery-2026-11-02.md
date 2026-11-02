# Local release-readiness delivery

Fresh slot: **2026-11-02 08:18:38–08:48:38 UTC**, including cleanup.
Branch: `codex/local-release-readiness-20261102`, from accepted settings commit
`27d34371`. **Implemented and verified locally; no publication or deployment.**

## What this adds

`scripts/portal_release_readiness.py` runs the existing version, slideshow JS,
bundled JS, Portal Python, JVM and selected APK gates locally. It uses offline
Gradle, a mandatory trusted local baseline and SDK `aapt`/`apksigner`, without
calling the publishing script. It records selected source-input and artifact
hashes, package/version, signing digests, test totals, commands, exit codes and
durations. `candidate.apk` is the inspected copy.

`status=ready` means local artifact readiness. The receipt explicitly retains
`published=false` and `deployed=false`; hardware, phone, provisioning, hosted CI
and clean-main/tag eligibility remain separate. macOS/Linux are supported;
Windows provisioning remains separate.

Output must be a new directory outside source or under `app/build`. Existing
evidence is preserved. Failed commands name their private logs without printing
their contents. Process-group timeout cleanup and failed-receipt behavior are
covered by CLI fixtures. Before the forced JVM task, previous XML reports are
archived into the output, preventing old successes from satisfying this run.

## Tests and review

The core suite and independent edge suite exercise the actual CLI with external
build/SDK fixture processes. They do not substitute for real signing/build checks.
The worker owns twelve edge tests and the
[bounded review](portal-local-readiness-review-2026-11-02.md).

Observed fail-first cases include absent CLI behavior, unsafe source output,
stale APK/report evidence, packaged assets named `build`, SDK propagation and
Node reporter selection. Independent review reproduced readiness from an
unchanged recent prior JVM report. Main added that exact regression and archived
prior reports before Gradle. The old report remains available as archived evidence.

An SDK fixture initially compared `/var` with the canonical `/private/var`
spelling; it now checks the resolved directory, matching the actual SDK contract.

## Actual execution history

1. The first real run verified local metadata and the accepted baseline, then
   stopped after Node **24.13.0** emitted its default human-readable summary.
   All forty slideshow tests exited successfully, but no TAP count was present;
   readiness correctly stayed failed.
2. Main reproduced this with a Node-default-reporter fixture, then explicitly
   requested `--test-reporter=tap`. The regression passed.
3. The full real run in `actual-tap` exited 0 with `status=ready` after
   **152,626 ms** (2m 33s rounded). All fifteen recorded processes exited 0.
   The first failed receipt is preserved.

Private evidence is under `/private/tmp/portal-local-readiness-20261102/`.
Baseline 1.67 / code 61 SHA-256:
`80895ce251f65b9f7a9018d0be974fd1c2c222cf4160539f970c1c9a93400262`.
The running candidate is optimized 1.68 / code 62.

## Boundaries and next task

No app setting, version, Portal state, pairing, private credential, GitHub
authentication, publication or device install was changed in this unit. Source
checks compare selected before/after snapshots; they do not continuously observe
every filesystem write. Local evidence is not a whole-product acceptance claim.

The next fresh unit will reproduce the observed PWA Offline banner during
otherwise successful settings interactions. Previous exhausted performance,
night-motion and black-handoff work stays parked.

## Final real evidence

| Gate | Actual result |
| --- | --- |
| Slideshow JavaScript | 40 passed |
| Bundled JavaScript | 654 passed |
| Full Portal Python | 161 passed, including all 21 checker methods |
| Forced fresh JVM | 3,800 tests / 399 suites, zero failures/errors/skips |
| Optimized APK / R8 / vital lint | Gradle gate passed; cached tasks are identified in its log |
| Prior JVM evidence | 399 reports archived before the forced test task |
| Package/version | com.immortal.launcher.debug, 1.68 / code62 |
| Artifact | 25,860,666 bytes, exact existing 1.68 hash |

APK SHA-256:
`271d3b1dc5b4dab2e57fb686ad7c19bf015d126b22271e536044cadca1e8e343`.
Verified signer:
`4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`,
matching the accepted baseline. The candidate was hashed again independently
after terminal success. No reinstall was necessary.

The checked build-input fingerprint is
`695e67e333ce60655c6b58d60e388078e4f44e7f067fb89394f0ac990ca1e3f1`
over 1,346 selected files. At execution, HEAD was `27d34371` and the new tooling
was uncommitted (`dirty=true`), both recorded honestly in the receipt. This is
local readiness, not proof of clean-main or publication eligibility.
Tools: Python3.13.7, Node24.13.0, Android build-tools36.0.0.
The real performance variant was verified; other variant choices have no fresh
real-build acceptance in this unit.

The subsequent [explicit local preview policy](portal-local-preview-readiness-2026-11-02.md)
permits Gradle/publication-manifest divergence only for opt-in debug/performance
checks. Default and release synchronization remain required. Its separate
receipt records the current actual pipeline; this earlier delivery history is
preserved.

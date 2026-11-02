# Slideshow regression gate coverage — 2 November 2026

## Problem and scope

The CI workflow and local release-readiness checker selected only the native
filmstrip module from `scripts/tests`, alongside the source and label checks.
Nineteen existing slideshow modules were omitted. This unit changes test
selection and release tooling; it does not change the slideshow renderer,
reopen parked native issues, authenticate, publish or install an app.

The fresh independent unit uses a conservative start of15:35:40UTC and a
16:05:40UTC deadline, including review and cleanup. It starts from accepted
1.77 source at `48d712c3`, on `codex/slideshow-regression-gates-20261102`.
Weather remains an unaccepted candidate on its separate branch; its receipt
and roadmap state are retained.

## Implementation

- CI selects all `slideshow_*.test.mjs` and `slideshow_*.test.cjs` modules,
  retaining source/label tests. Python3.12 setup precedes these tests because
  the Cinema asset check invokes a standard-library Python generator.
- Local readiness discovers the same two module formats rather than maintaining
  a partial list. Source, label and native-filmstrip files remain mandatory,
  so discovery cannot silently accept their absence.
- Regression guards verify the actual workflow command, both discovery formats,
  helper exclusion and failure propagation. An omitted failing module must
  prevent readiness before the Android build or candidate artifact promotion.

## Verification

The old workflow failed coverage and Python-order guards. The old readiness
selector failed two executable CLI cases: added modules were omitted, and a
configured failing module still yielded “ready.” These failures were reproduced
before the selector changes.

On Node22.13.1, the old selection passed40 tests; the complete selection passed
354 tests, with zero failures, cancellations or skips. The correction brings
314 existing checks into the gate. This is test coverage, not a new user feature
or native slideshow performance result.

All186 Portal Python regression tests passed locally on Python3.13.7. Workflow
actionlint passed. Independent read-only review of readiness selection and
failure propagation found no important issues; main reviewed the worker's CI
patch and guards.

The complete real readiness command exited0 in295,144ms and recorded:

| Gate | Result |
| --- | --- |
| Slideshow JavaScript |354 passed; no failures, cancellations or skips |
| Remote JavaScript |676 passed; no failures, cancellations or skips |
| Portal Python |186 passed |
| Fresh JVM reports |3869 tests /406 suites; zero failures/errors/skips |
| Optimized APK |Assembly, R8 and vital lint passed |
| Artifact identity |1.77/code71, expected package and original signer |
| Input integrity |Stable source inputs and unchanged baseline APK |
| External actions |`published:false`, `deployed:false` |

This run used Node22.13.1, Python3.13.7 and Android build-tools36.0.0. The private
local candidate has SHA-256
`e31941c245a33c785946b678769072632539b0752b0355cd4a535394dd7c8321`.
It is a freshly built local artifact and was not installed. Both Portals retain
their earlier exact1.77 build; no settings, installed packages or device runtime
state were changed in this unit.

## Limits

Hosted Ubuntu/Python3.12 workflow execution is unobserved. The tests use their
existing fixtures and do not prove optical smoothness, every device lifecycle
or the parked night-clock/black-handoff issues. No runtime APK deployment or
public release is part of this change.

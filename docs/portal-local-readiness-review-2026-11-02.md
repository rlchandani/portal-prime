# Local release readiness: independent edge review

Review date: 2026-11-02. Worktree:
`~/.codex/worktrees/portal-music-player/portal-prime`,
branch `codex/local-release-readiness-20261102`.

This sidecar owns only `scripts/test_portal_release_readiness_edges.py` and
this review. Production, the shared fixture, CI, the app build, and release
documentation belong to the main implementer. No commit is made by this sidecar.

## Evidence boundary

The edge tests invoke the real Python CLI as a subprocess. They reuse
`ReadinessFixture` from `scripts/test_portal_release_readiness.py`; Gradle,
Node, `aapt`, and `apksigner` are local executable fixtures. The fixture APK is
JSON data consumed by those SDK fixtures, not an Android APK. These tests check
the checker's decisions and filesystem/process behavior. They do not establish
real SDK verification, real Gradle gate results, device acceptance, publication,
or deployment. The main implementer owns that separate evidence.

Covered negative cases in the 12 owned tests: failed Gradle with process output
retained in logs; missing, empty, zero-test, failed, and errored JVM reports; unsigned baseline and
candidate; wrong candidate package; missing mandatory baseline; preservation
of an existing receipt and candidate; timeout of a Gradle process with a real
child process; a tracked Kotlin source changed during Gradle; absent SDK; and
nonzero exits from either SDK tool.

Failure assertions require a nonzero CLI exit, a failed receipt with an
actionable error, `published=false`, `deployed=false`, and no exported
`candidate.apk`. An existing output is the exception: its sentinel receipt and
candidate must remain byte-for-byte unchanged. The timeout test requires proof
that the child actually started and rejects its delayed filesystem side effect.

## Commands observed

All commands below ran from the worktree above.

| Phase | Command | Observed terminal result |
|---|---|---|
| Initial red | `python3 -m unittest discover -s scripts -p 'test_portal_release_readiness_edges.py' -v` | Exit 1; 12 tests in 2.070s; `FAILED (failures=15)`. The additional failure entries are subtests. |
| First implementation run | `python3 -m unittest discover -s scripts -p 'test_portal_release_readiness*.py' -v` | Exit 1; 15 tests in 15.501s; `FAILED (failures=1)`. The SDK test's error pattern was too narrow for the actionable `baseline-metadata` diagnostic; the owned test was corrected. |
| Stale-report reproduction | `python3 -m unittest discover -s scripts -p 'test_portal_release_readiness_edges.py' -k missing_jvm -v` | Exit 1; 1 test in 1.774s; `FAILED (failures=1)` for `stale_report=True`. |
| First full Portal guard run | `python3 -m unittest discover -s scripts -p 'test_portal_*.py' -v` | Exit 1; 155 tests in 17.906s; `FAILED (failures=2)`: prior-report acceptance and the timeout child not starting before the deadline. |
| Timeout fixture refinement | `python3 -m unittest discover -s scripts -p 'test_portal_release_readiness_edges.py' -k timeout -v` | Two runs exited 1: 1 test in 1.569s with no child-start marker, then 1 test in 1.537s with `Could not run android-gates: Operation not permitted`. |
| Final full Portal guard run | `python3 -m unittest discover -s scripts -p 'test_portal_*.py' -v` | Exit 0; 157 tests in 17.960s; `OK`. Includes all 12 owned edge tests and the main implementer's 5 core readiness tests. |

The initial run occurred while `scripts/portal_release_readiness.py` was absent.
Each test failed at the CLI contract boundary: the expected receipt or output
rejection diagnostic was missing. This establishes absence-of-implementation
red evidence, not a claim that each individual rejection branch was exercised.

## Implementation review

The final timeout fixture uses fast shell Node/SDK executables and a real shell
child under the sleeping Gradle process. This reserves the one-second global
deadline for the intended process-group boundary. It retains the actual CLI,
`--timeout-seconds 1`, the 30-second Gradle sleep, the required child-start
marker, and rejection of a delayed child side effect. Test-file editing was
declared finished before the main implementer's real checker run.

### Important finding: recent prior JVM evidence can still report ready

In the first implementation review, `jvm_result` reads every existing
`TEST-*.xml` under `app/build/test-results/testDebugUnitTest`. The Android gate
requests `:app:testDebugUnitTest --rerun`, but the checker does not independently
require those report files to have been produced by this invocation.

The reproduction creates `TEST-stale.xml` containing three passing tests,
then uses the shared fixture's `missing-tests` switch. Fixture Gradle exits
successfully and builds the candidate without writing a JVM report. The real
checker returns exit 0 and `status=ready`; the edge assertion fails. With no
stale XML, the same missing-report scenario correctly returns failed.

The main implementer subsequently added report mtime checks against the start
of the Android gate, with one second of filesystem timestamp tolerance. The
core suite owns the freshness and source-output placement regressions; the
overlapping stale-report case was removed from the owned edge file at the
main implementer's request.

A subsequent one-shot fixture probe reproduced remaining wrong-ready behavior
against that updated implementation: a passing report created just before
the CLI run falls within the one-second tolerance. Gradle writes no current
JVM report, but the CLI still reports ready. The prior report's bytes and mtime
remain unchanged. The final 157-test suite is green despite this uncovered
boundary; its status does not resolve this finding.

The exact probe command was:

```bash
python3 - <<'PY'
import json, sys
sys.path.insert(0, 'scripts')
from test_portal_release_readiness import ReadinessFixture
f = ReadinessFixture()
try:
    report = f.write('app/build/test-results/testDebugUnitTest/TEST-prior.xml',
                     '<testsuite tests="3" failures="0" errors="0" skipped="0"/>')
    previous_bytes = report.read_bytes()
    previous_mtime = report.stat().st_mtime_ns
    f.write('missing-tests', '')
    result = f.run()
    receipt = f.receipt()
    print(json.dumps({'cliExit': result.returncode, 'receiptStatus': receipt['status'],
                      'jvm': receipt.get('jvm'), 'error': receipt.get('error'),
                      'priorReportUnchanged': report.read_bytes() == previous_bytes,
                      'priorMtimeUnchanged': report.stat().st_mtime_ns == previous_mtime}))
finally:
    f.close()
PY
```

Terminal exit: 0. Observed output:

```json
{"cliExit": 0, "receiptStatus": "ready", "jvm": {"tests": 3, "failures": 0, "errors": 0, "skipped": 0, "suites": 1}, "error": null, "priorReportUnchanged": true, "priorMtimeUnchanged": true}
```

Recommendation to the main implementer: require current-invocation report
evidence independent of mtime tolerance, for example by clearing the owned
report directory before the unit-test task and rejecting an empty result.
Keep the regression in the core suite. This sidecar does not change production.

### Other boundaries inspected

- Output directory creation uses `exist_ok=False` before writing receipt or
  candidate files. The existing-output test verifies both sentinels remain
  unchanged.
- External process output is written to per-gate logs. Failure diagnostics name
  the gate, exit code, and log; the marker test checks that process output is
  absent from the CLI streams and receipt.
- Timeout starts each command in a new session and sends both SIGTERM and
  SIGKILL to the process group, including after its parent exits. The child
  marker test passed in the first implementation run.
- Both baseline and candidate pass SDK metadata and signature commands.
  Candidate package, version, and signer digests are checked before exporting
  `candidate.apk`; failed SDK commands fail the receipt.
- Source identity hashes selected tracked and untracked app/script/Gradle/CI
  inputs and compares them again after inspection. The tracked Kotlin file
  mutation during Gradle passed its rejection test.
- The pending candidate is inspected as a copy, the built APK and baseline are
  hashed again, and a pending candidate is removed on failure.

### Review limits

This is a bounded read-only review of the checker and its CLI contract tests.
Source detection covers the implementation's explicit input selection and its
before/after snapshots; it is not a continuous monitor of every filesystem
write. A produced candidate and receipt are local files whose hashes record
their identity at inspection time. SDK semantics, Gradle correctness, signing
key custody, real APK contents, network isolation of dependencies, hardware
behavior, and release acceptance are outside this fixture evidence.

## Coordinator correction and final actual gate

The recent-prior-report finding was reproduced as a failing core assertion.
Prior XML reports are now archived before the forced Gradle test task; the exact
recent-prior fixture fails without discarding its archived evidence. Main also
observed and corrected source hashing that excluded an actual packaged asset
folder named `build`, and explicit SDK propagation to the build. The SDK fixture
compares resolved directories because `/var` aliases `/private/var` on macOS.

A real Node24 run exposed the default spec reporter instead of TAP. Its forty
tests exited successfully, but the checker stayed failed without count evidence.
A dedicated reporter-selection fixture failed first; explicit TAP passed. The
second real gate then exited 0: 40/654 JS, 161 Python (all21checker methods),
3,800 JVM/399suites, optimized assembly and SDK artifact/signature inspection.
Main inspected the terminal receipt and independently hashed its saved APK.
The independent worker's initial157-test receipt above is historical, before
these four additional core methods. No second independent review, hostedCI or
physical device/phone check is claimed. See the delivery receipt for exact hashes.

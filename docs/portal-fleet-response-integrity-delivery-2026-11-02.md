# Fleet response integrity — 2 November 2026

## Result

The local fleet CLI now rejects incomplete HTTP replies before reporting success
or writing downloaded files. Failed downloads preserve an existing destination,
do not create a new one, emit no partial file to stdout, and propagate a failure
through multi-room operations. Complete binary and clean close-delimited replies
still work. The minimal JSON parser now requires a complete value rather than
silently displaying only a numeric prefix of diagnostic text.

This changes workstation tooling, not either Portal APK or the phone application.
The source is on `codex/fleet-response-integrity-20261102`, based on
`1500def94a0de1e548b17c7842b2c50bc3381517`. The unit started at10:22:40UTC with
a10:52:40UTC deadline including cleanup.

## Reproduction and correction

The unmodified CLI built successfully and passed its six existing tests.
Five new production-TCP tests then failed: truncated body, incomplete headers,
invalid/conflicting lengths and unsupported transfer encoding were all returned
as200 successes. Five of eight compiled-CLI acceptance tests also failed on
that baseline, including file preservation and multi-room failure propagation.

The shared request reader now validates framing and converts these conditions
to the existing transport-error/status0 boundary. It accepts complete known-size
replies even after a read error, but an unknown-size stream requires clean EOF.
Unsupported chunked encoding fails explicitly instead of becoming corrupted
file contents. No crates or runtime dependencies were added.

Independent review found an important legacy-server compatibility case:
`FleetRoutes.fsRead` advertises `File.length()`; procfs diagnostic files report
zero while delivering content. Family's unmodified CLI downloaded304,746bytes
from `/proc/self/maps`; the first strict candidate rejected that response.
Two new compiled-CLI tests reproduced this regression before correction.
The final reader treats only successful zero-length diagnostic reads under
`/proc/` as close-delimited. It matches the agent's first decoded `path`
parameter for ordinary and `raw` calls, rejects parent traversal, and keeps
ordinary files and positive declared lengths strict.

A subsequent actual `raw` diagnostic read exposed the old JSON parser accepting
only the first number: it printed3bytes while `pull` received305,019bytes.
A Rust regression and a compiled-CLI regression failed before the four-line
whole-input validation fix. The final actual `raw` and `pull` checks each
returned305,019bytes of diagnostic text. Full snapshots remain private.

## Fresh evidence

| Check | Observed result |
|---|---|
| Complete Rust suite | 23 passed;0 failed/ignored |
| Actual compiled-CLI loopback acceptance | 17 passed;0 failures |
| Room-management Python suite | 33 passed;0 failures |
| Calls access Bash behavior | 38 passed;0 failures/skips |
| Three Bash script syntax checks | provisioning, backup and restore passed |
| Windows-executed files | all three checked files were pure ASCII |
| Workflow static validation | `actionlint` passed |
| Optimized CLI build |675,912bytes; rustc1.99.1; aarch64-apple-darwin |
| Real Family/Living CLI identity reads | both exited0; app1.70/code64 |
| Complete room settings profiles/screensaver configurations | unchanged in both rooms |
| Family procfs `pull` and `raw` | both exited0;305,019bytes each |

CLI SHA-256:
`843a71fcbc7123f40fe1121cc3cfbb5a3d17794b53092ef2b41a1386cfe2d327`.
Source SHA-256:
`661b8a5769010bcbeecf19e67d2460bcc4ad746811a4005a188d153a91e8c5b2`.
Private commands, full logs, red/green receipts, artifact and readbacks live in
`/private/tmp/portal-fleet-local-gates-20261102/`. Rust was downloaded over verified
HTTPS from the official distribution, checked against its manifest hashes and
installed only in that private directory; system tool configuration was untouched.

Final independent source review found no remaining concrete defects after the
procfs and whole-input JSON corrections; it did not rerun the parent's tests.
All loopback server threads terminated through the acceptance harness. Generated
provisioning caches and downloaded installer/archive copies were removed; the
accepted private compiler/CLI and receipts were retained. The owner's pre-existing
`scripts/__pycache__` was preserved.

## Continuous verification

The provisioning workflow now triggers for its own file, fleet source, room
helper and both Python suites. An independent job compiles the optimized
standard-library CLI and Rust test executable in `RUNNER_TEMP`, runs the Rust
suite, exercises that actual binary through loopback Python acceptance tests,
and runs room-management tests. Existing Bash, ASCII and PowerShell gates are
preserved. Parent reviewed and extended the parallel worker's workflow patch.

Local equivalent commands:

```bash
rustc -O provisioning/fleet.rs -o /private/tmp/fleetctl
rustc --test provisioning/fleet.rs -o /private/tmp/fleet-tests
/private/tmp/fleet-tests
IMMORTAL_FLEET_CLI=/private/tmp/fleetctl python3 provisioning/test_fleet_http.py -v
python3 provisioning/test_room_fleet.py -v
```

## Limits and next work

Hosted CI, Ubuntu/Python3.12 execution, PowerShell7/Windows5.1 parsing and behavior,
Windows fleet binaries, fresh provisioning, reboot persistence and all-model
hardware acceptance remain unverified. No APK was built, installed or published
for this tooling-only change. No configuration, pairing, photos, provisioning
registry, accessibility service or GitHub authentication was changed. These
checks establish the stated transport behavior, not a general performance claim
or universal HTTP/filesystem implementation.

The next independent data-integrity candidate is interrupted uploads:
`FleetRoutes.fsWrite` opens the destination before the request body completes.
Reproduce that behavior using a public scratch file before calling it a defect,
then preserve the original destination on failure if reproduced. It needs its
own30-minute unit and cleanup. The parked screensaver/performance issues and
cold native companion reconnect are not reopened by this work.

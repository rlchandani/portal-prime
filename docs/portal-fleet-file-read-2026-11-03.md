# Fleet file-read descriptor ownership — 3 November 2026

`/fs/read` measured a path's file length when creating its response, then opened
the path later when streaming. An atomic upload replacement between those steps
could send a different file with the original `Content-Length`. A same-size
replacement silently changed the selected bytes. The current Fleet CLI rejects
size mismatches; the size-changing case is not claimed to corrupt that CLI.

The route now opens one descriptor before creating the response and measures
that descriptor. Ordinary files stream exactly the captured length; appends
cannot exceed it, and premature EOF fails the transfer. Atomic replacement or
unlink cannot switch the open inode. Successful and failed writers close it;
the existing server's `Response.close` also closes an abandoned response.
Setup failure closes the descriptor before propagating.

Procfs diagnostics use close-delimited streaming because their nominal size
does not describe their readable bytes. Ordinary empty files keep fixed length
zero. Existing path/readability gates, authorization, attachment-name encoding
and upload publication policy are unchanged.

## Verification and delivery

| Check | Result |
|---|---|
| Actual private `fsRead` JVM regressions | 12 unchanged methods: seven assertions and one production unlink exception fail originally; all 12 pass after correction |
| File selection | Longer and same-size atomic replacements retain the selected bytes; unlink still serves the opened file |
| Length and cleanup | Append, empty append, truncation, output failure and abandoned-response closure |
| Controls | Ordinary and empty files, missing query, directories and missing files |
| Full JVM gate | 4,138 tests / 431 suites; zero failures, errors or skips |
| Build gate | Debug, optimized performance APK, R8 and vital lint pass in 2m 2s |
| Actual debug APK on Family Android10/API29 | Original 6/14 → corrected 14/14; exactly eight product checks differ |
| Native controls | Loader, ordinary/empty files, path guards and this fixture process's proc status all retain success |
| Native cleanup | Responses closed; owned process and device scratch removed after both runs |
| Room delivery | Exact signed optimized 1.106/code100 installed and full APK hash verified in both rooms |
| Installed procfs smoke | Both authenticated HTTP endpoints return nonempty process-status bodies, omit `Content-Length` and use `Connection: close` |
| Preservation | Complete screensaver/settings/layout/source maps and Home/timer/Sunrise preference bytes unchanged |
| Family state | Current night clock frame visually inspected; font, accessibility services and original Chrome task retained |

The JVM regressions call the actual private route through its normal constructor,
using an inert Context and generated files. Atomic replacement uses the real
`FleetUploadWriter`. The native comparison loads each exact debug APK, constructs
the actual route and Request/Response objects, then invokes its response callback
against generated files. Its own `/proc/self/status` is read-only; raw content is
not published. Seven native body hashes were independently checked.

The native fixture makes no Context service, preference, asset or provider call,
and no network or installer call. Descriptor retirement is checked by rejecting
writer reuse after close or output failure, rather than a direct descriptor census.
The separate installed smoke exercises only positive authenticated procfs reads
through the real optimized HTTP endpoint. It does not establish native optimized
replacement-race or complete authorization/protocol acceptance.

The deployed optimized APK SHA-256 is
`b4b49a9c2c39b3bc59ec7ff5575217cd5e726c3da0a0178374b51f7e9b5027e6`
(25,862,702 bytes). Exact signed optimized 1.105 rollback, both debug comparison
APKs and mappings remain retained privately. Copied Fleet registry records were
removed; original registry hashes match. Owner snapshots and credentials remain
private.

## Retained limits

An open descriptor and captured length do not freeze bytes overwritten in place.
This is not an atomic whole-backup or changing-state snapshot. Native comparison
covers debug APK response callbacks, not socket transport or authenticated
admission. Optimized concurrent replacement, broader virtual filesystems, API28,
Living visuals and fault-injected timeout cleanup remain unverified.

The public manifest remains **1.83/code77**; its version guard deliberately
rejects local **1.106/code100**. No Google phone operation, Home Assistant change,
GitHub authentication, push or publication occurred.

The original **06:27:46–06:57:46 UTC** parallel-discovery window includes tests,
implementation, builds, native comparison, delivery, cleanup, documentation and
commit. The broader product goal remains active; previously parked clocks,
design approvals and human sign-in dependency are retained.

Structured receipt:
`mocks/portal-widget-gallery-20261102/evidence/fleet-file-read-verification.json`.

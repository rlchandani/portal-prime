# Self-update manifests have a response budget

Exact optimized **Portal 1.101/code95** is installed and fully hashed in Family
Area and Living Area. The independent self-updater now uses the existing
**4 MiB actual-byte** metadata reader and disconnects its connection in `finally`.

`StoreCatalog` already used this reader, but `UpdateManager.httpGet` separately
read the entire manifest with `readBytes()` and did not disconnect. Otherwise
valid JSON with trailing whitespace could exceed the limit and still reach
self-update parsing. This is a distinct consumer correction; the delivered
store budget and parked issues are preserved.

Request headers, 10-second connect/30-second read timeouts, redirect behavior,
URL override/cache busting, manifest fields, version comparison, dev-mode pause
and APK download/install paths retain their previous behavior. Existing update
checks convert transport/parser failures to no available update. There is no
new setting, schema or client change.

## Verification

- Nine real loopback TCP methods exercise the actual updater reader and parser.
  The old code reproduced **four failures**: declared/chunked oversize,
  incomplete declared body and recovery after rejected oversize. Five controls
  passed. Final nine pass, retaining ordinary UTF-8, headers, redirects and HTTP
  error recovery. Fixture-thread assertion failures reach the JUnit thread.
- **4,093 JVM tests in 426 suites**, debug and optimized assembly, R8 and vital
  lint pass with zero failures/errors/skips. Final gate: **110 seconds**.
- Family's actual Android reader/parser was loaded from the exact debug APK in
  a separate owned shell process. **7/15 old → 15/15 candidate**: nine generated
  real HTTP cases, one class-loader ownership check and five connection doubles.
  An exact 4 MiB manifest is accepted. A gzip response expands from **4,222**
  transferred bytes to **4,194,305** decoded bytes and is rejected. The old
  Android reader already rejected the truncated-body case; the JVM baseline
  did not. These observations are kept separate.
- The five process-local connection doubles verify disconnect on successful
  read, announced oversize, acquisition failure, read failure and interruption;
  streams close where acquired, and interrupted status is retained. These are
  generated lifecycle checks, distinct from the real HTTP cases.
- Independent review cleared the final source and test hashes. Its private
  runner corrections made cleanup attempts independent, captured uniquely named
  owned process IDs and required explicit absence after a failed `/proc` read.
  Five generated guard scenarios passed. The final native rerun verifies both
  probe processes retired, scratch/reverse/server/worker removed, no faults.
  A real fault-injected native timeout cleanup remains unverified.

The native fixture did not start the application, write application preferences
or call an installer. It proves the debug-APK transport/parser, not the optimized
reader, asynchronous callback or update-offer UI. No real OOM, public update,
APK self-install, HTTPS/private manifest, API28 or total elapsed deadline claim.

## Delivery and preservation

Both complete installed APK hashes match
`7bc1ee8b70f873e23bb35c88983bda6f0843a7bab4c77d2d91134a6f0f931045`
(**25,862,702 bytes**); accepted signing identity retained. Both rooms' complete
settings profiles, layouts, sources, screensaver maps, names and dev mode match
preflight. Home-widget and kitchen-timer preference-file bytes match too.
Family font/accessibility and its original Chrome task are retained; its normal
photo frame is resumed, displayed and visually observed privately.

The copied registry is removed and the primary registry hashes are unchanged.
Owned probe processes, device scratch, reverse and server are gone. Owner
photos, tokens, addresses and raw diagnostics remain private; the existing
local roadmap server and unpublished worktree are retained.

Natural Dream recovery, original Home page and Living visual acceptance remain
unverified. Public `version.json` remains **1.83/code77**; the version guard
correctly exits 1 for the local **1.101/code95** mismatch. No phone operation,
Home Assistant change, GitHub authentication or publication occurred.

The original **05:14:20–05:44:20 UTC on 3 November** clock includes discovery,
implementation, review, tests, rollout, restoration and closure. The sanitized
proof is `mocks/portal-widget-gallery-20261102/evidence/self-update-manifest-budget-verification.json`.

Strict documentation builds with zero warnings. Actual desktop/mobile Chrome
checks preserve the default task and all 73 previous records, render 74 tasks,
select the new updater and previous widget tasks without overflow/errors, and
serve the sanitized proof byte-identically. Selected detail captures were
visually reviewed; full-page/accessibility acceptance is not claimed. Owned
browser contexts closed and the existing roadmap server was preserved. The
first docs command used an incorrect retained-environment path and did not
start a build; the corrected command passed.

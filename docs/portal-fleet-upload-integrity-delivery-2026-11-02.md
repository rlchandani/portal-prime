# Fleet upload integrity delivery — 2 November 2026

Interrupted fleet uploads now preserve the destination and report failure.
Exact optimized **1.71 / code 65** is installed in Family Area and Living Area.
This is a storage/API correction; the companion and browser need no new protocol.

## Reproduced defect and correction

On Family's installed 1.70, a disposable 21-byte file was replaced by six bytes
when the client declared 100 bytes, sent `BROKEN`, and closed its write side.
The API incorrectly returned `ok:true` and `bytes:6`.

The request reader now requires all declared upload bytes and tracks consumed
input before output writes, including failed writes. `FleetUploadWriter` streams
to a unique hidden file beside the canonical destination, closes it, and checks
the rename before acknowledging success. Failure removes the staging file.
The existing destination stays available until completion; new destinations
remain invisible while uploading. The existing 64 KB buffer remains unchanged.
Incomplete uploads return HTTP 400; other write/commit failures return HTTP 500.
Successful replies retain the existing HTTP 200 JSON shape and exact byte count.

## Verification

| Boundary | Observed result |
|---|---|
| Regression baseline | 14 failures in 21 focused JVM tests on the direct-write/lenient-reader baseline |
| Final Android tests | 3,821 tests / 401 suites; zero failures, errors or skips |
| Local release checks | 40 slideshow JS + 676 remote JS + 161 Python tests; optimized APK, R8, vital lint, version and signer checks passed |
| Independent source review | No concrete defects reported; parent owns test/device evidence |
| Family API acceptance | 13 checks passed: interrupted existing/new/empty requests, original visibility, exact complete binary, deliberate empty replacement, directory commit failure, readback hash and no staging leftovers |
| Family deployment | Actual installed APK pulled; byte count, SHA-256, package, version and signer match |
| Living deployment | Actual own-process APK path identified; streamed installed bytes and SHA-256 exactly match |
| Configuration | Complete settings profiles and screensaver configs equal before/after in both rooms |
| Cleanup | Owned Family scratch directory removed; ADB absence exit 1 confirmed |
| Family foreground | Supported paired screensaver dispatch succeeded; `PhotoFramePreviewActivity` resumed |

The actual accepted APK is `com.immortal.launcher.debug`, 25,860,686 bytes,
SHA-256 `e98b6514a9c6f6c21e4151b0fc3e5c0123257019a7ea2097c0c8905a86443883`.
Signer SHA-256 remains
`4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`.
Local release checks completed in 211,222 ms.

Living's deployment helper initially exited 1 with “native install failed or
returned no valid installed result.” The subsequent `/info` already reported
1.71/65; independent installed-APK readback proved the exact candidate was
installed. No second installation was attempted. The helper's false negative
remains a separate tooling finding, rather than a passing install-command claim.

## Bounded performance comparison

Three 8,388,608-byte uploads to the same disposable Family path:

| Sample | Before 1.70 | After 1.71 |
|---|---:|---:|
| 1 | 214.95 ms | 189.06 ms |
| 2 | 148.84 ms | 141.45 ms |
| 3 | 157.54 ms | 157.01 ms |
| Median | **157.54 ms** | **157.01 ms** |

Streamed readback matched the same complete binary SHA-256 in both runs.
These three LAN samples show essentially unchanged upload latency. They do not
establish a statistically significant speedup or whole-app performance.

## Scope and limits

Tests used public synthetic bytes in one confirmed-owned scratch directory.
No owner photo, credentials, registry token or application preferences were
used as corruption-test targets. No GitHub authentication or publication occurred.
The code uses API-24-compatible `java.io`; Android 9 and all-model runtime
acceptance were not performed. JVM tests cover Unicode paths, symlinks,
concurrency, cancellation and output-close failure; these are not claimed as
separate device tests. Power-loss durability and process-kill staging recovery
are not established by this interruption test.

Private baseline, red/green, artifact, deployment, configuration and cleanup
receipts are under `/private/tmp/portal-fleet-upload-integrity-20261102/`.
The broader product goal remains active. Native cold reconnect and exhausted
screensaver/performance units retain their previous status.

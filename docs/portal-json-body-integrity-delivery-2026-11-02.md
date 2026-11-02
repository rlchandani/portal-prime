# JSON request integrity delivery — 2 November 2026

Incomplete JSON bodies now fail before command application. Oversized text
bodies are rejected rather than clipped. Exact optimized **1.72 / code 66**
is installed in Family Area and Living Area.

## Reproduced behavior

Nonmutating tests against Family 1.71 showed:

- `/dev` accepted `{}` after receiving only 2 of 100 declared bytes: HTTP 200.
- A 1,048,577-byte body containing `{}` followed by whitespace was clipped to
  the default 1,048,576-byte limit and accepted: HTTP 200.
- `Content-Length: 2147483648` became zero through integer parsing fallback,
  allowing another HTTP 200 acknowledgement.
- A truncated paired settings request for a nonexistent domain reached domain
  lookup and returned 404 instead of rejecting its incomplete body.

The requests supplied no enabled/name/settings values. Complete room baselines
were captured before testing; no owner preference was used as a mutation target.

## Implementation

`Request.bodyText` requires every remaining declared byte, enforces the byte
limit before allocation/read, and records each consumed chunk immediately.
Complete UTF-8 and deliberate empty bodies retain their behavior. An exhausted
reader returns empty without consuming bytes beyond the declared request.

Explicit lengths must contain decimal digits and fit the server's integer
representation. Invalid values return HTTP 400; numeric overflow returns 413.
An absent length retains the supported empty-request behavior.

One mapper recognizes request-specific incomplete/oversize errors as HTTP
400/413. Companion routes retain their direct-response contract; guarded
diagnostic wrappers preserve the same mapping alongside their existing invalid
JSON responses. Unrelated handler I/O failures retain the server-error path.
No settings registry, persistence, credential, authentication or SDK dependency
was changed.

## Verification and corrective review

| Boundary | Actual result |
|---|---|
| Initial focused baseline | 34 tests / 12 failures |
| First full gate | Failed on one existing direct companion-response contract; not promoted |
| Review regression baseline | 11 tests / 3 failures, including explicit length guards |
| Final focused boundaries | 43 tests passed, including 19 new reader/server cases |
| Final full gate | 3,840 JVM tests / 403 suites; zero failures, errors or skips |
| Other local gates | 40 slideshow JS + 676 remote JS + 161 Python; optimized APK/R8/vital lint, version/signature and source guards passed in 188,686 ms |
| Independent review | Overflow and diagnostic mapping findings corrected; final source review reported no remaining concrete defect |
| Family API acceptance | 22 passed checks: incomplete, complete, empty, UTF-8, exact-limit, oversized, invalid/overflowing length, authentication and paired-domain behavior |
| Binary compatibility | 16,003 exact bytes uploaded/read back; interrupted overwrite still returns 400 and preserves the original; no staging leftovers |
| Deployment | Both actual installed APKs have the accepted byte count and SHA-256; Family package/version/signer verified with SDK tools |
| Configuration and cleanup | Complete profiles/screensaver configs equal before/after in both rooms; owned public scratch removed and absence verified |
| Family foreground | Supported screensaver dispatch succeeded; original `PhotoFramePreviewActivity` resumed |

Accepted APK: `com.immortal.launcher.debug`, 25,860,686 bytes,
SHA-256 `79468c500a64ef934ee490a65a59ec73b46d064169d7186d9b00f8d802fbb0bb`.
Signer SHA-256 remains
`4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`.

Living's helper returned exit 1 after the actual CLI printed `incomplete HTTP
headers` and `(transport error)` after its install marker. Exact installed-APK
readback independently proved successful delivery; no second installation was
attempted. The helper accepts older `read failed:` text but excludes this newer
transport message. Its false negative is a separate queued tooling fix.

## Performance and limits

Three identical two-byte `/dev` HTTP round trips measured 9.50/9.36/9.76 ms
before and 10.75/13.90/13.24 ms after: medians **9.50 → 13.24 ms**.
The observed increase is 3.74 ms. Three uncontrolled LAN samples do not establish
a speed improvement or a statistically confirmed product regression. No general
UI/frame-rate claim is made.

Family Android 10 was the primary runtime target; Living received exact artifact
and configuration verification. Android 9/all-model acceptance, real provider
commands and native diagnostic-build wrapper behavior were not exercised.
Duplicate/conflicting headers and transfer-encoding policies were not changed;
this is not a complete HTTP protocol audit.

No owner photo or preference was used as a mutation-test target; existing
development mode and settings values were preserved during normal deployment.
No token change, GitHub authentication/publication or exhausted native issue
reopening occurred. Private receipts and native
transport logs are in `/private/tmp/portal-json-body-integrity-20261102/`.
The broader product goal remains active.

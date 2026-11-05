# Immich album identity — 5 November 2026

**1.188 / code 182** is installed and fully hashed in both rooms. The shared
album-list parser skips non-object rows and missing, null, non-string or blank
IDs. It keeps the first album for each exact ID, preserving input order and
that album's original metadata. Valid albums survive malformed neighbors;
duplicate IDs cannot reach the native picker's stable row keys. The browser
album endpoint consumes the same result.

Request errors and malformed top-level documents still return null. A successful
array containing no usable albums returns an empty list. Name/count coercion,
defaults, Unicode and nonblank surrounding ID whitespace remain unchanged.
Connection handling, photo loading, provider settings and transport are unchanged.

Eight frozen real-HTTP JVM cases reproduce five intended assertion failures
and three controls; all eight pass afterward. All **4,634 JVM tests / 482 suites**,
debug/optimized builds, R8 and vital checks pass. Build: 96.68s. Independent
source review accepted the exact parser scope, both consumers and fixture.

The same Java/DEX/driver calls the actual public album method from each exact
debug APK on Family Android 10 / API 29. A process-only URL factory supplies
generated in-memory responses and never delegates to network. Five baseline
failures / three controls become **eight passes**. Literal albums and GET/key/
single-open/disconnect/stream-close observations pass; unknown opens remain zero.
Both owned scratch directories retire. This verifies Android JSON parsing, not
live server compatibility, actual HTTP transport or mounted picker rendering.
Optimized parsing, Android 9 and Living native behavior remain separate.

Exact optimized APK: 25,887,039 bytes, SHA256
`5439645104073d0bc001416e17b1acb926c302c2d57ae51cea9644a37a732a12`.
One app install per room; any uncertain Living acknowledgment is reconciled
through reads. All 23 source assets and the photo controller match 1.187 exactly.
All 43 Family / 30 Living preference files, four configuration maps and Family
runtime match. Final captured fatal/ANR markers are zero. Copied registry files
retire; owner registry bytes remain unchanged. No source is selected or connected.

No phone/account/provider/authentication/push/publication; public 1.83 remains.
Held scopes retain their original clocks. Original start: 01:10:43 UTC;
target: 01:25:43; hard limit: 01:40:43. Proof and generated native fixture:
`mocks/portal-immich-album-identity-20261105/`.

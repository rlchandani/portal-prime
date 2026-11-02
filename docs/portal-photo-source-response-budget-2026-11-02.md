# Photo metadata download budget — Portal 1.85

Delivered optimized **1.85 / code 79**, `com.immortal.launcher.debug`, to Family
Area and Living Area. The existing verified deployer confirmed each complete
installed APK hash against the candidate:
`e2c93a3c2d2b187a5f47f45cf7120a69da4321be9590d452671c400c273fe974`.
Size: 25,861,122 bytes; accepted signing identity retained.

The original audit clock is **20:23:05–20:53:05 UTC** on 2026-11-02,
including restoration and cleanup. No exhausted photo or reconnect unit reopened.

## Change

Public-album, Immich and WebDAV metadata previously used unbounded response reads.
Four real TCP Immich regressions accepted oversized, otherwise valid-looking
metadata. The shared `PhotoSourceHttp` reader now bounds decoded metadata to
**16 MiB**, rejects oversized declared lengths before acquiring the input stream,
and rejects incomplete declared bodies. Unknown-length responses consume at most
the budget plus one byte. Streams close on success and failure; each provider
disconnects its own connection in `finally`.

Existing methods, headers, timeouts, cookie reuse, pagination and provider failure
behavior remain. WebDAV retains already discovered root photos if a child fails;
Synology public albums retain an earlier completed page if a later page fails.
Immich error logging reads a bounded diagnostic prefix rather than the full body.
This is an album-metadata limit, not an image-quality or resolution limit.

## Evidence

| Gate | Result |
|---|---|
| Original actual Immich provider regression | 4 failures / 6; ordinary UTF-8 and search controls passed |
| New regression coverage | 33 checks: reader 18, Immich 6, WebDAV 5, public Synology path 4 |
| Focused provider/stream suites | 95 passed / 10 suites |
| Final JVM gate | 3,920 passed / 412 suites; no failures, errors or skipped tests |
| Build gates | Debug and optimized performance assembly, R8 and vital lint passed in 2m 1s |
| Independent review | Separate helper/transport and provider workers; no concrete production defect reported |
| Actual Family Android API | All six generated metadata cases passed; ordinary UTF-8, gzip, declared oversize, chunked oversize, incomplete body and successful retry |
| Final delivery | Existing deployer verified full installed APK SHA256 in both rooms |
| Preservation | Complete profiles, layouts, sources and screensaver settings unchanged in both rooms; Family font/accessibility and original Chrome task retained |
| Cleanup | Normal Family photo preview restored; owned fixture stopped and temporary fleet credentials removed |

The Family checks exercised the installed app through the existing **unsaved**
Immich album-discovery route. They used only generated metadata and a dummy local
key; no account credentials or saved source changes. Single synthetic LAN request
times were 37.460 ms (ordinary), 18.471 ms (gzip), 36.893 ms (declared oversize),
474.961 ms (chunked oversize), 36.704 ms (incomplete) and 33.591 ms (retry).

The first focused incremental compilation could not resolve an existing
photo-source extension. Full-source compilation with `-Pkotlin.incremental=false`
passed; the final combined gates used that option. No extension change was made
and no definitive cache root cause is claimed.

Private command and restoration evidence is retained under
`/private/tmp/portal-photo-source-response-budget-20261102`.
Public structured receipt:
`mocks/portal-widget-gallery-20261102/evidence/photo-source-response-budget-verification.json`.

## Limits

No out-of-memory incident, total heap reduction, frame-rate gain, universal
photo-loading speed or total request deadline is claimed. Existing connection
and read timeouts remain. Actual hardware coverage is the Immich discovery
route; WebDAV and the public Synology path use real TCP JVM fixtures. Google,
iCloud and actual owner cloud accounts were not accepted end to end.

Living received artifact/configuration verification only. No physical-phone
installation, UI redesign, GitHub publication or Home Assistant change.

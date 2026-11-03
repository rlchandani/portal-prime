# Calendar download budget and recovery — 2 November 2026

Delivered in optimized **1.83 / code77**, package
`com.immortal.launcher.debug`, to Family Area and Living Area. Full installed
APK readback matches the candidate in both rooms. The original issue clock
starts at **19:26 UTC**, with a **19:56 UTC** deadline including restoration.

## Problem and correction

`CalendarFeed.httpGet` previously read the complete response into memory before
parsing. The existing calendar cache limits retained data after downloading;
they do not limit the download itself. Real loopback HTTP through that production
entry reproduced three failures before the correction: an advertised oversized
body, an unannounced oversized body, and a valid complete calendar prefix with
an incomplete announced body were all returned.

`CalendarFeedHttp` now bounds response bodies to **8 MiB (8,388,608 bytes)**.
An announced larger body is rejected before acquiring its input stream.
Unknown-length bodies, including Android's transparently decompressed gzip,
are read up to the limit with a one-byte overflow probe. A known length must
match the completed body. Opened streams are closed and connections disconnected
on success, rejection and read failure. Cleanup failures cannot replace a
completed read, and thread interruption retains the existing cancellation path.

The existing worker, parsing, recurrence, exact-source saved-feed fallback and
UI remain in use. A rejected refresh reports the existing unavailable state
while showing saved events. No new setting, background service or retry loop
was introduced. Existing 8-second connect and 12-second read timeouts remain;
this does **not** add a total elapsed download deadline.

## Verification

| Check | Recorded result |
|---|---|
| Original production-entry regressions | 3 failed / 4 before correction |
| Focused final tests | 18 passed |
| Full final JVM suite | 3,887 tests / 408 suites; no failures, errors or skips |
| Build gates | Debug and optimized assembly, R8 and vital lint passed |
| Declared oversize in transport tests | 0 stream acquisitions / 0 body reads |
| Unknown length boundary in transport tests | Exact 8 MiB accepted; limit + 1 byte rejected |
| Family compressed response | Native Calendar rendered the generated event |
| Family advertised 8 MiB + 1 response | Saved event retained; fixture sent 0 body bytes |
| Family incomplete announced response | Existing warning shown; original saved event retained |
| Family valid retry | Warning cleared; event remained available |
| Installed artifact | Full APK hash matches on both rooms |
| Restoration | Complete profiles, layouts and screensaver configuration unchanged |

The first integrated test run also caught a cleanup exception masking a valid
read; the final correction and regression cover it. Independent source review
checked Android's documented transparent gzip behavior; Family's actual Android
10 compressed response supplied runtime proof. No Portal instrumentation,
UiAutomation or accessibility reconnect was used.

The four native HTTP requests used a generated calendar on an owned temporary
server. Family's original calendar URL was restored through the registry before
stopping that server. Family resumed its normal manual
`PhotoFramePreviewActivity`; the original Chrome task, font scale and
accessibility service state are retained. Living received the exact accepted
artifact with configuration and identity checks, without duplicated functional
testing.

Living's self-update interrupted its HTTP acknowledgement. No install was
resent: full installed APK readback established the outcome.

## Artifact and evidence

- Optimized APK: **25,860,990 bytes**.
- SHA-256: `772681507fdf9695810319770db96033ac8225dabed70d5443da0976c3c72371`.
- Signing certificate SHA-256:
  `4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`.
- Structured [verification receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-widget-gallery-20261102/evidence/calendar-download-budget-verification.json).
- Actual native [saved-event recovery crop](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/evidence/calendar-saved-event.png).
- Actual native [valid retry crop](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/evidence/calendar-recovered.png).

The new 8 MiB limit is a compatibility boundary: larger feeds use saved events
when available, otherwise the existing unavailable state. The limit is a
download-body budget, not a total heap budget; buffers, decoded strings and
parsed events also consume memory. No FPS, universal speed, total memory
reduction, physical phone, or natural system Dream recovery claim is made.
Raw screenshots containing personal photos and all configuration/credentials
remain private. No GitHub or Home Assistant action occurred, and no parked
issue was reopened.

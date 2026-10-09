# Mobile browser control-center validation — 9 October 2026

The phone-first browser control center is committed on
`codex/portal-browser-control-center` at `767eb03`. The companion is committed on
`codex/companion-multi-portal` at `bc5f292`, version 1.6.0-control-center / code 10.
Local Git hooks were disabled; no GitHub connection or remote operation was used.

Ongoing product requirement: optimize for phone use first. New layouts, controls,
dialogs and performance checks must start with mobile viewports; desktop support
is secondary. Preserve the mobile checks below when extending any web feature.

## Build and review

- Portal: 954 JVM tests, zero failures/errors/skips; debug APK build passed.
  Log: `/private/tmp/portal-browser-mobile-final.log`.
- Browser: 52 Node regressions passed (16 core, 36 controller).
- Independent review reran all five identified regressions: 5 passed, zero failures.
- APK SHA-256: `d3c4c50e4caaab4bb9ea958c841eba0e990f513fdadb2e5813cd61fb3c7ddca6`.
- The APK's three browser assets exactly match the reviewed source: CSS 22,706 bytes,
  controller 142,627 bytes, core module 18,016 bytes (183,349 bytes total).
- Family Area and Living Area both installed this APK, with installed hashes verified.
  Both subsequently served the exact three assets, correct CSS/JavaScript MIME types,
  and `X-Content-Type-Options: nosniff`.

Static asset transfer observations were 23–54 ms per file on the local network.
Those measurements are server/transfer observations, not phone page-ready times,
rendering latency or a website-speed guarantee.

## Phone browser checks

The actual bundled controller ran in Chromium against two loopback generated-data
API fixtures. No fixture requests forwarded to household Portals, and no household
photo bytes were fetched. Device and photo labels in these previews are synthetic.

All five pages (Home, Remote, Photos, Apps, Settings) passed width/touch checks at
360, 390 and 412 CSS pixels: 15 checks, no horizontal overflow, all bottom-tab
buttons at least 44×44 pixels (67-pixel measured height). Settings puts search,
category and registry controls ahead of room options. Selectors remain on the right.
Photos displays two columns, a full-width Import action and paired secondary actions.

A real browser switch-padding click measured a 50×44-pixel label and changed its
native input once. Offline, expired-pairing and truncated-listing fixture checks
reported Retry, Pair again and the scan-limit warning. A generated PNG import
finished with a named-room confirmed ledger after tab navigation. Node tests also
cover deferred tab/room navigation and upload cancellation races.

At 390×844 and a reduced 390×480 viewport, the import sheet retained its 48-pixel
Start import action inside the viewport (bottom 818 and 454 respectively) without
horizontal overflow. The reduced viewport simulates available-space reduction;
it does not establish iOS/Android keyboard behavior on a physical phone.

Generated preview screenshots are under the primary checkout's `build/artifacts/`:
`browser-control-center-settings-phone-final.png` and
`browser-control-center-photos-phone-final.png`.

## Physical companion checks

The final Pixel APK used the production repository and its saved pairings while
credentials stayed in the phone process. Three live tests passed against both
updated rooms in 3.286 seconds:

1. Captured settings reached both rooms despite a selection change; each room's
   original temperature unit was restored and verified (488 ms confirmation run).
2. A generated UUID PNG reached each room's own folder through two fresh streams,
   then only those generated files were removed and absence checked (558 ms run).
3. Both scene lists returned stable revisions and rejected a stale replacement
   before changing scenes or schedules.

Sources stayed unchanged. The companion passed 488 JVM tests and 35 distinct
emulator UI checks across the full and final targeted suites. The physical Pixel
was securely locked; headless live tests do not prove its visible UI was exercised.

## Navigation measurement

A finite 60-second / 20-cycle Home–Apps test completed on both updated Portals.
The first run overlapped other integration activity and is retained only as an
exploratory record. The second run used the same cadence after those tests finished.
Each owned capture stopped with zero listeners and the collector worker off, then
returned its Portal to the photo-frame preview.

| Home host metric | Family baseline cdaf954 | Family updated | Living baseline cdaf954 | Living updated |
| --- | ---: | ---: | ---: | ---: |
| p50 ms | 13.25 | 12.50 | 12.50 | 12.25 |
| p95 ms | 91.75 | 88.75 | 71.50 | 90.00 |
| p99 ms | 111.75 | 102.75 | 89.00 | 115.00 |
| steady over-budget / frames | 144 / 517 | 133 / 520 | 131 / 517 | 141 / 519 |
| max ms | 133.05 | 115.75 | 110.27 | 145.95 |

Both updated runs had zero callback drops and attachment failures. Family improved
slightly in this sample, while Living was worse. This is not evidence of a fleet-wide
speedup. Mean animation phase remained about 9.5 ms in both updated captures, with
layout only 0.15–0.16 ms. These observations motivate examining animation/recomposition
and queued work next; they do not identify a proven causal bottleneck.

FrameMetrics describe Immortal's Window host, not delivered compositor FPS or
another application's website readiness. Baseline and updated APKs also differ
in the bundled remote and scene safeguards; this is not a controlled A–B–A study.
Full DreamService recovery, physical touch/UI, website readiness and sustained
slideshow rendering remain part of the ongoing performance work.

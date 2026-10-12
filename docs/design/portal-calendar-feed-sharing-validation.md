# Shared calendar-feed downloads — validation

Issue 40 starts at 2026-10-12T10:50:29Z and has a hard 11:20:29Z deadline. The clock includes investigation, code, tests, signed builds, one guarded Family Area trial, possible rollback, local commit and closure. No reset. Living Area, the phone, GitHub and all parked candidates remain outside this issue.

## Change

Today, calendar widgets and the slideshow independently called CalendarFeed.fetch for the same feed. CalendarFeed now delegates to a worker-only CalendarFeedReader. The normalized full URL identifies a shared download. The existing parser runs separately against every caller's supplied wall-clock time and horizon, so expired events and requested windows remain correct.

Only successful feed text is retained for 30 seconds from load completion, with at most four values and at most 262,144 UTF-16 code units per retained value. Larger feeds remain supported and can share an active download, but are not retained afterward. Null and exceptional downloads are not retained. The optional size-admission predicate runs outside the cache map lock; the existing weather cache defaults to retaining successful values. No setting, wire protocol, photo source, Home layout or browser asset changes.

Existing calls remain workers: CalendarWidget and ApprovedHomeCalendar use Dispatchers.IO; Today's HomeActivity reader uses Dispatchers.IO; PhotoFrameController uses metaIo. Network and parser algorithms outside the CalendarFeed delegation are unchanged. No UI-thread caller was introduced.

## Evidence

The saved previous-behavior reference ran all ten new focused tests: nine expected failures. Eight simultaneous generated-feed readers produced eight download invocations. After the change, all ten tests pass and the same workload produces one invocation (87.5% fewer). Twenty warm reads use one invocation. Tests also cover the exact expiry boundary, webcal/HTTPS normalization, complete path/query isolation, caller-specific time/horizon filtering, null recovery, exact exception preservation/recovery, oversized-feed support without retention, and four-entry eviction.

The complete Android regression suite has 1,918 tests in 224 suites, zero failures/errors/skips. The existing weather/cache suite remains green. All 118 browser tests pass. These are controlled or generated local workloads: no real calendar credential was accessed and no device calendar traffic or latency improvement is claimed.

## Deployment

The debug build, complete unit suite and signed optimized candidate/bootstrap builds passed. All 91 standalone offline guards pass. Pre-build, post-build and post-native app source match exactly: 792b530c77e1c63da8cc7b3a389847c1c7ab5a5843b3c1d92f87820a2a4eb4c2 (864 files). R8 output contains CalendarFeedReader and differs from the accepted prior DEX, confirming that the new implementation is present in the optimized APK.

One bounded Family Area trial passed. Two signed installs completed; exact installed candidate SHA-256 is 8884ef9c9961e3a039d7139a1d801cddeea46f03d932dd5c0dd5ae3a3089a19b (25,447,090 bytes), with bootstrap c1ca68d92f87264f32dc2c8bb4a115e5f03c5d4fc80d977337e5274ecce8d9f0. Complete bundled/reference profile audits and served JS/core/CSS match their admitted bytes. Profile counts are 8,667 methods / 2,576 classes / 285 app methods / 75 app classes; this does not prove compilation of each individual app method.

Home → Apps → Home passed three destination checks. Home was ready before and after; configuration, source and inactive-collector state were preserved; no rollback. The Family lease was released. No Living Area, phone, settings, notes, audio, timed capture or GitHub actions occurred. Native checks establish installation/navigation/preservation, not live calendar traffic, device latency, website speed or a universal frame rate.

The original 10:50:29Z investigation clock is preserved; closure includes the local commit and cleanup and is recorded in the ignored issue-ledger.json. The broad performance goal remains incomplete.

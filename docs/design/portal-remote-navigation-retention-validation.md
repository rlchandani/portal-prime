# Portal browser navigation retention validation

The remote controller recreated all five bottom-navigation buttons during every full render, and rebuilt unchanged header children. A delayed app-inventory response detached a focused navigation button. The controller now mounts navigation once, updates only aria-current, and skips header reconstruction when the displayed room/status/model are unchanged. Actions retain only the tab name and use the live session. Refresh is disabled when there is no active room.

## Scope and clock

Family Area only. No Living Area, phone, settings, note, audio, GitHub, hook or paired-browser credential operations. Original start 2026-10-12T09:50:16Z, deadline 10:20:16Z, no reset. The clock includes initial Tools inspection, implementation, builds, tests, deployment, cleanup and local commit.

## Browser evidence

All five new regression tests fail against the previous controller and pass with the change. Full suite: 118 passing. Tests cover delayed inventory rendering, retained navigation/header identity, all five selected-tab ARIA states and unique callbacks, room switching, expired pairing, unpaired refresh state, actual header identity/model/status changes, and unchanged retry-control retention.

Isolated real Chrome, 402 x 874, mobile/touch, fresh context, synthetic Portal responses and fixture-only credentials; all external HTTP and WebSocket requests blocked. A delayed inventory refresh detaches the focused Home button before the change (zero retained navigation buttons); afterwards all five remain connected and Home retains native document.activeElement focus. Five search keys P, Po, Por, Port, Portal: new elements 175 -> 101 (-42.29%); listeners 45 -> 20 (-55.56%). All 24 existing cards and decoded icons remain retained, query focus/value preserved, and Open dispatches the correct synthetic package. No Portal frame-rate or live-browser latency claim.

## Device evidence

The single bounded trial passed and retained the candidate. Two signed APK installations (bootstrap then candidate); Home -> Apps -> Home verified with three destination checks in the existing Home window. Exact served JS (152124 bytes), core (19089 bytes) and CSS (22706 bytes) match admitted assets. Installed candidate SHA-256 7e4a3c349eed172a73e77651aaaa4994af7cc659d1b2ed951826630fbffb38e1. Bootstrap SHA-256 4398d512abf2bdfd8deeff42dcd6c97d18d0e574d94788ff4ce2193c611c3980. Each APK is 25447090 bytes. Exact SDK reference and bundled profile inventories match: 8667 methods, 2576 classes, 285 app methods and 75 app classes. Individual app-method compilation is not proven.

Final Home is ready, the collector is inactive, and configuration/source snapshots are unchanged. No rollback. Frozen app source e1188ab19e9de41fe6e7bfea5849664fe23620d037a8a269f1bb2885c3d2de9a, 858 files. Android gate: 1885 tests / 221 suites, zero failures/errors/skips; offline guards: 63 pass. Independent read-only review found no actionable issue.

Native interaction checks cover the Portal's Home/Apps navigation and deployment preservation. Browser focus behavior was tested in isolated mobile Chrome; a paired browser session on the Portal was not used. The original deadline also covers this validation, local commit and ledger closure.

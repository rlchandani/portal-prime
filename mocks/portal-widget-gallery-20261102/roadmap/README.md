# Current roadmap

Local review page at <http://localhost:8891/roadmap/>. The existing Python server
serves the parent gallery directory. This folder is the entire write scope.

The page is a saved, manually maintained priority queue, not live telemetry,
an exhaustive product inventory or whole-product acceptance. It does not grant
UI redesign approval. The separate gallery remains unapproved.

## Update verified facts

Edit `roadmap.json` and its `updatedAt` / `updatedLabel` fields. Each task separates:

- `status`: `building`, `verified`, `queued`, `approval`, `delivered`, or `parked`.
- `verification`: the evidence level shown visibly on the row and detail pane.
- `deployment`: actual artifact/device state, separate from local gate results.
- `acceptance`, `evidence`, `limits`, and `next`: scoped facts and outstanding work.

`verified` means locally verified maintenance, with no deployment implication.
The delivered/implemented group includes device fixes and CI source; CI is
explicitly “Implemented / locally verified”, with hosted execution unobserved.
Do not infer completion from test counts or infer passing results from running
gates. Update the companion record only after its actual terminal receipt.

Initial facts come from `docs/portal-product-operating-state.md`, the named
delivery receipts and the coordinator's 2026-11-02 terminal native-gate update:
3,796 tests / 398 suites; both APKs, R8, vital lint, version sync and signature
checks passed; maintenance commit `2682e3a99edb4210ea298eff6272c83273a974f5`.
Both rooms now have exact 1.67 / code 61 after the accepted Home timer fix. Native settings maintenance is included, without fresh settings UI acceptance. Phone maintenance remains uninstalled. See docs/portal-home-timer-delivery-2026-11-02.md.

Only these cropped card captures were copied from the private temporary folder:
`world-before-card.png`, `final-three-normal-card.png`, and
`final-three-large-font-cold-card.png`. Full-screen private photos were not copied.

No dependencies, external assets, external requests, polling, synthetic progress,
GitHub authentication/publication, product changes or commits are part of this page.
The single same-origin JSON request loads the saved snapshot.

Main validated desktop 1600 × 1000, mobile 390 × 844, and simulated Portal
2160 × 1440 viewports. Filters, empty-state recovery, text-size image selection,
task details, same-page fragment navigation and mobile focus/return passed.
All thirteen mobile detail panels fit without horizontal overflow; console errors
were zero. Desktop/mobile screenshots were visually reviewed. Main corrected
same-page task-link synchronization and long evidence-string wrapping.
See `validation.json` for the bounded browser receipt. This is not native device
acceptance or a complete accessibility/performance audit.

The later timer snapshot adds the accepted native timer fix and a dialog-only native capture (`images/timer-editor-native.png`). Browser data updates do not imply repeat acceptance of older features.

The visibility snapshot records exact 1.68 / code 62 in both rooms, Family scoped
settings acceptance, 3,800 JVM / 654 JS tests, and the separate committed phone
preflight correction (564 tests, uninstalled). Current version and queue copy
were refreshed. All thirteen mobile detail panels and the desktop update fit
without horizontal overflow; served JSON matched the source file. Earlier
filter/Portal-viewport checks above retain their original snapshot scope.

The local-readiness snapshot adds the verified local-only checker and a queued
browser connection-status investigation. It contains fourteen tasks. The actual
checker passed 40 slideshow JS, 654 bundled JS, 161 Python and 3,800 JVM tests
in 152,626 ms; no installation/publication occurred. The actual performance APK
retains the accepted 1.68 identity. See the local-readiness delivery receipt.
All fourteen mobile detail panels and the desktop update fit without horizontal
overflow after reload; screenshots were visually reviewed and console errors
were zero. Earlier filter and simulated-Portal checks retain their earlier scope.

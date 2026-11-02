# Widget refinement — design checkpoint

Goal: the revised Meta Portal Widget Gallery Refinement objective, not the previous
screensaver investigation. This checkpoint does not complete the full goal.

## Delivered

- Source inventory covering every declared custom kind and installed providers.
- Settings ownership map with concrete navigation and timer-store discrepancies.
- Product/design specification, priorities, sizing rationale, accessibility and release gates.
- Functional browser studio: 15 categories, 78 catalog states, 70 source-backed state branches
  and 8 proposed recovery/setup states. Source-backed means inspected code, not device testing.
- Android/iOS design interpretations and proposed Portal presentation, useful size restrictions,
  dark/light themes, text scaling, state selection, shareable URLs and Back/Forward history.
- Local demonstrations for timers, alarms, tasks and entity values; other actions explain the
  existing destination. No live account or service commands.

## Verified evidence

Evidence is under `mocks/portal-widget-gallery-20261102/evidence/`.

| Check | Result | Scope |
| --- | --- | --- |
| Catalog ↔ source-kind completeness | 15/15 categories; unique IDs; source paths/symbols resolve | Source metadata |
| JavaScript syntax | `node --check app.js` passed | Review tool |
| Layout matrix | 10,272 combinations passed; no page/widget horizontal overflow or missing platform/state | 390/768/1440/2160 CSS px, both views/themes, 100/125/150% text, all catalog states and supported sizes |
| Text contrast | 456 checks passed after fixing one light-theme status label | Weather/Crypto/HA, both themes and shared review chrome; not a full WCAG audit |
| Local interaction checks | Timer duration preserved through pause/resume; calendar refresh returns to sample result; known-Off entity toggles; daily/off/missing forecasts; unsupported alarm sizes disabled | Actual browser clicks |
| Additional interaction checks | Alarm, task and entity toggle; theme; dialog/Escape; URL reload; search; browser Back/Forward; visible enabled controls at least 48 px | Browser tests before final matrix |
| Render scheduling sample | 30 samples; two-rAF median 33.3 ms, p95 34.2 ms | Chrome 156 on this Mac; refresh-cycle scheduling estimate, not native latency |
| Family hardware readiness | Wi-Fi ADB reachable, API 29, QCS605, Adreno 615, 2160×1440, density 180, MemTotal 2,848,792 KiB | Read-only device observations; current foreground was the photo preview |

The layout sweep checks dimensions and presence, not every visual overlap, real data refresh,
service failure, or device runtime combination. The contrast scope is explicitly sampled.
The browser prototype is not an iOS or Android application and is not a physical Portal
performance result. No production source, configuration or installed software changed.

## Next gates

1. User design review is pending; the revised objective requires validating design before handoff.
   Review at <http://127.0.0.1:8891/?view=portal>.
2. Implement accepted native variants and state contracts, reusing existing stores, data sources
   and lifecycle-aware producers. Fix widget-to-editor ownership; determine the timer ownership
   policy before any migration. Keep shared settings in the registry.
3. Verify native functionality and accuracy on Family Area, compare measured performance/memory
   to the same baseline scenarios, and fix regressions within individual 30-minute slots.
4. Bump version, signed build, required tests, release notes and deploy only the accepted iteration.
   Living Area receives the final verified build. Hardware-provider compatibility stays explicit.
5. Engagement/retention/NPS require user observations after release; no fabricated metrics.

No remaining gate is waived by this design checkpoint. The full goal remains active.

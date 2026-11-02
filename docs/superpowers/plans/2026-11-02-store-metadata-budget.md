# Store metadata response budget — bounded implementation

Original clock: 21:43:20–22:13:20 UTC, 2 November 2026; includes discovery, build, verification and cleanup. A separate new defect; no exhausted issue is reopened.

Source evidence: StoreCatalog.httpGet reads all catalog, F-Droid and direct version metadata without bounding allocation or disconnecting the connection. Ordinary catalog is 24,043 bytes. Proposed 4 MiB UTF-8 decoded-body ceiling leaves substantial headroom and does not apply to APK/image downloads.

- [x] Reproduce oversized otherwise-valid JSON through actual StoreCatalog default version transport using generated local TCP data, with ordinary UTF-8 recovery control.
- [x] Add a background metadata-only reader; declared/unknown/incomplete lengths, cleanup and interruption regression coverage. Independent worker owns helper and pure stream tests; main owns provider integration and TCP tests.
- [x] Preserve pins, request headers/timeouts/redirects, fallback catalog and APK transfers. Disconnect metadata connections in finally.
- [x] Run focused/full JVM, debug and optimized/R8/lint build gates, inspect exact signing/artifact and independent source review.
- [x] Where safely possible, verify current store/remote behavior on Family without credentials, saved configuration or installs of catalog apps; do not use Portal instrumentation.
- [x] Record exact release status, limits and cleanup, update unified ledger and commit locally. Native oversized-body acceptance requires an actual safe route; source/JVM evidence does not establish native OOM/FPS.

Final receipt: `docs/portal-store-metadata-budget-2026-11-02.md`. Native acceptance is normal catalog browsing/API only; negative body cases are real TCP JVM and stream fixtures. Both exact artifacts delivered with unchanged profiles.

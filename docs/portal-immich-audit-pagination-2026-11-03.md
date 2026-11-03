# Immich audit pagination progress

The standalone `scripts/immich_audit.py` now rejects a repeated or backward
`nextPage` before another request. Its CLI reports
`error: cannot enumerate Immich: Immich nextPage must advance`, exits 1 and
does not print partial library totals or begin measurements.

The unchanged-source baseline has **three assertion failures / four controls**,
zero errors/skips. The finite transport catches real extra POSTs for a first-page
repeat, backward continuation and later-page repeat through the actual CLI.
It exercises real `Immich._open`, request construction, JSON parsing and
enumeration; only `urlopen` returns generated JSON/BytesIO responses.
The CLI uses a cleared environment and an explicit synthetic URL/key.
No live provider was contacted and no infinite network run was measured.

All **seven frozen methods** then pass. All **248 Python tests** in the existing
CI wildcard pass with zero failures/errors/skips at
**2026-11-03T19:45:05.026864+00:00–2026-11-03T19:45:29.008097+00:00**. The local command adds verbosity
and disables bytecode writes; test discovery matches the configured CI command.
Independent source/result review passes 12/12. Early fixture admission preceded
RED; its later durable report separately binds the original and corrected source.
The initial unquoted shell wildcard was rejected before Python execution and
corrected; this is an inspector event, not a test failure.

Local source commit: `74b7a48afaa4ac217666cf7234feb33146def778`, parent `cdfe7c21162380b41cf2894e6da1ba48cd6a7f4b`, recorded
**2026-11-03T19:47:29.319774+00:00**. Exactly two production fragments change:
validate parsed page progress, then route the resulting `ValueError` through
the existing CLI error exit. The only other source-commit path is the new test.
Fixture SHA-256: `ddd07b62babedafb2cb3a6e606a0660d110cbd92ccdcf99ce6be3ef7b2b880b7`.
Current audit SHA-256: `2565b022de3adff99510d5b5fa76dc8cc610c0bee34b9e22ae43b90b6a80038b`.
No Android build is claimed for this host-script-only change.

- Actual host Immich client and CLI with finite generated JSON and an inert urlopen boundary. No live Immich, NAS, auth, TLS or provider compatibility acceptance.
- Three extra-request AssertionErrors reproduce the baseline through the real request path; no infinite live-server run is measured. Seven fixture methods and bytes stay frozen across RED/GREEN.
- Strictly advancing pages, including sparse jumps, remain allowed. No request, total-time or memory cap; endlessly advancing responses remain outside this fix.
- Empty, missing and malformed-marker stop behavior remains. No cursor migration, asset dedup, broad input-type or partial-data policy change beyond repeated/backward markers.
- Focused cases do not execute real album resolution, asset measurements, compression/load estimates or a successful full audit report.
- The existing CI wildcard discovers the new tests; hosted CI was not observed. Python host-only change; no Android code, version, dependency, workflow, device, push or publication action.
- Old Android Immich pagination retains 01:27:16–01:57:16 and photo redirects retain 01:47:02–02:17:02 UTC. Earlier APK and all expired scopes stay held.
- USB discovery is bounded negative. Toolkit photo expiry guidance is an unselected source/spec observation without a fixture, reproduction or fix under this same clock.

Original clock: **19:38:59–20:08:59 UTC, 3 November 2026**, including discovery
and local closure; no reset. Queue **113: 69 delivered, 33 verified, 3 approval,
7 parked, 1 queued**. All 112 prior task objects/raw suffix and Weather default
remain. Toolkit and its original checkout stay unchanged. Whole-product work
remains active.

Structured proof: `mocks/portal-widget-gallery-20261102/evidence/immich-audit-pagination-verification.json`.

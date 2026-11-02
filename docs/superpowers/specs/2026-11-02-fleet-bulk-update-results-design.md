# Fleet bulk-update result integrity

The current `/update` response sets `ok:true` regardless of individual installer
results. Its blocking discovery also discards whether the completion latch
expired, making an unfinished scan look like a completed empty scan.

## Required behavior

- Overall success requires every selected package to report literal terminal
  `installed`; preserve all ordered package/result entries and the existing
  requested `count`. Add installed/failed counts.
- Retain existing one-attempt-per-selected-package behavior and continue the
  selected batch after a confirmed failure. Do not add automatic retries.
- Read one catalog snapshot for discovery and installation selection. Never
  silently omit a selected missing entry.
- Reject malformed bodies, missing selectors and unready catalogs before
  discovery or installation.
- A discovery timeout returns an explicit unconfirmed error and installs nothing.
  A completed empty scan remains a valid no-op.
- Reuse existing Store/installer and discovery services; no new settings, UI,
  provider, permission or catalog policy.

## Validation and delivery

Extract existing outcome composition for a failing JVM regression, then correct
the production aggregate. Check discovery completion separately and inspect the
route wiring. Run full JVM, debug, optimized/R8 and applicable native gates.
Execute the actual optimized formatter on Family Android 10 using only generated
results in a separate shell process if R8 permits; report its boundary honestly.
Check safe request rejection/dry-run behavior through the installed agent without
installing owner catalog apps. Deploy only a final signed local-preview APK with
exact version/hash readbacks, preserve complete room configuration, and deliver
the accepted final artifact to Living without duplicate native QA.

First-observation clock: **23:38 UTC 2 November–00:08 UTC 3 November 2026**,
including cleanup. Unfinished acceptance is parked under that original clock.
GitHub publication, HA changes, instrumentation, accessibility reconnect and
default Home changes are excluded.

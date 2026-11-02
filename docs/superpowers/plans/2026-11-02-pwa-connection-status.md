# Browser connection-status ownership fix

Fresh unit: **2026-11-02 08:46:02–09:16:02 UTC**, including cleanup.
Branch: `codex/pwa-connection-status-20261102`, accepted parent `22db0e62`.
Previous goal turn: verified progress (local readiness committed with actual gates).

## Evidence and design

Family paired PWA 1.68 reproduced Offline while Settings loaded: hold the initial
companion read, reload, select Settings. The browser cancels the read with
`net::ERR_ABORTED`. `read` correctly ignores cancellation, but `selectRoom` has
a second unowned continuation that marks the current room offline whenever no
companion metadata exists. Page navigation also waits for the next poll to replace
that metadata read.

Keep the existing request/session ownership and real-error/401 handling. Remove
the duplicate status writer. Navigation and visibility resume immediately request
missing/offline status; completed metadata is retained without extra ordinary
requests. No pairing, settings or API contract changes.

## Bounded implementation / verification

- [x] Reproduce on actual paired Family browser; record exact cancellation/status.
- [x] Independent fail-first ownership tests in their own new JS module.
- [x] Correct shipped controller and run focused/full JS plus required build gates.
- [x] Build versioned optimized APK and verify accepted signing identity.
- [x] Family exact artifact acceptance: same delayed-navigation reproduction,
  ordinary tabs, current network/auth failures; preserve pairing/settings.
- [x] Deliver exact final artifact to Living; record readback separately from
  Family physical/browser acceptance.
- [x] Update roadmap/state and prepare the exact local commit; cleanup by the original slot deadline.

No GitHub auth/hooks/publication. No prior exhausted issue reopened.

All bounded gates, exact room readbacks and browser/configuration acceptance completed. Final roadmap/docs and local commit remain part of the original cap.

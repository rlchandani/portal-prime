# Browser Settings row retention

The mobile browser Settings interface rebuilt every displayed registry row on each search keystroke and fresh response, even when its contents were unchanged. It now retains only rows in the current rendered result set. Each row compares complete serialized control metadata, advanced/busy/mixed/help presentation and live input state; changed values/options/limits/read-only state rebuild the affected row. Text drafts remain independent of server values. Secret controls and metadata longer than 16,384 UTF-16 code units render normally without retention.

Unseen rows are pruned after every Settings render. Navigation, room changes, expiry, hidden/pagehide cleanup release retention. Each row's writes require its original session and a connected visible row. Save completion also checks its original session, preventing a late response from clearing a new room's draft. Existing transport preflight, active-only settings policy, mixed-room routing, reset scope and registry persistence/side effects remain authoritative. No Kotlin, registry, core, HTML or CSS change.

## Browser evidence

- Original ten focused checks: eight failures, two passes before implementation.
- Review discovered unapplied live switch/select values with no selected targets; additional focused red test failed before correction. Final eleven focused checks pass.
- Full browser suite: 138 tests pass, zero failures/skips.
- Fake DOM with two fixture rooms, 24 controls, five matching keys: new elements 1,025 -> 305; listeners 200 -> 80; retained switches 0 -> 24.
- Isolated real mobile Chrome, 402 x 874, one fixture room and the same 24-control/five-key pattern: elements 985 -> 265; listeners 185 -> 65; retained switches 0 -> 24. Search stays focused. Identical fresh schema retains all 24 rows; changing one field rebuilds that field while retaining 23. A real switch tap sends exactly one fake write and displays the confirmed value; leaving Settings disconnects the old rows.
- Zero external HTTP/WebSocket requests or page errors; only synthetic pairing/storage/schema. No real user Chrome profile/cookies/Portal session credential access. Screenshot visually checked for the mobile layout.

Debug and both signed APK builds passed. The Android unit-test gate reports 1,918 tests across 224 suites with zero failures/errors/skips; unchanged JVM work was up-to-date. All 104 offline admission checks passed. One Family Area trial completed two signed installs, verified exact installed APK and served JS/core/CSS, complete bundled/reference profiles, three Home → Apps → Home destinations, unchanged configuration/source, final Home readiness and an inactive collector. One admitted Immortal Dream normalization was required; no rollback. The Family lease is released. Specific individual method compilation was not proven. No device latency, OS keyboard, universal frame-rate, peak memory or phone-app improvement is inferred from DOM allocation counts.

## Scope and timing

Family Area only. Living Area and phone frozen. Original clock 2026-10-12T11:53:47Z through 12:23:47Z, no reset; latest native start 12:18:47Z, one bounded trial with rollback allowance. Parked candidates remain closed. No GitHub operation. Exact code scope: assets/remote/control-center.js and the new JS test remote-settings-row-retention.test.mjs. This document is outside the app source fingerprint. Final commit/elapsed/source/lease evidence will be recorded in the local ledger after verification and commit.

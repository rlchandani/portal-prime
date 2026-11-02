# Companion photo-deletion consistency — 2 November 2026

The Android companion now verifies literal success and the exact selected wire
path before treating photo deletion as confirmed. An unreadable/incomplete
positive reply shows review and retains the last known list without an automatic
delete replay or metadata retry. Valid confirmation retains the single refresh;
explicit rejection and401 handling remain intact.

This extends the already delivered browser correction to the phone client.
No Portal API or installed Portal software changed.

| Target | Verified status |
| --- | --- |
| Companion source | New client guard and nine regressions; seven failed before correction |
| Companion local gates | **573 JVM /75 suites**, zero failures/errors/skips; app and test builds passed |
| Rendered native fixture | **4/4 in4.773 seconds**, actual panel/repository/client on owned API34 emulator |
| Companion candidate | **1.7.2-music-preview /code14**, signed, full installed emulator APK matches |
| Physical Pixel | Installation and actual phone/live-network acceptance remain open |
| Family and Living | Full installed exact Portal1.82 APKs and complete settings retained; zero writes/installs |
| QA cleanup | Owned emulator/clone removed; original AVD whole-directory contents and metadata unchanged |

The native tests use generated media and in-memory server responses. Repository
error text is displayed beside the real panel; whole-app snackbar behavior,
real-network Delete, delayed former-room response, physical phone and general
performance are not newly accepted. A retained photo tile is last known state,
not proof that a remote deletion failed.

The original first native run passed3/4; one dialog-placement timing failure
occurred before the request. A bounded displayed-state barrier corrected the
fixture. Final4/4 passed. A test import compile failure was corrected before the
successful full gate. Existing valid-deletion fixtures now echo request identity.

APK **14,722,589 bytes**, SHA256:

`c9f7e2beb6fdd1f95a78993bfbd70558fdc2cec5dde4d9c23fff431a85d54d6a`

Signer:
`4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`

Source/complete receipt:
`~/.codex/worktrees/portal-companion-compat-20261102/portal-toolkit/docs/companion-photo-delete-validation-2026-11-02.md`.
Private execution/retention/cleanup receipts:
`/private/tmp/portal-companion-photo-delete-20261102/`.
The unit starts18:25:05UTC and retains its18:55:05UTC hard deadline.
Fresh final independent source review found no remaining concrete defect;
execution and device receipts are coordinator-owned.
No GitHub or HA action; exhausted issues remain parked.

Companion commit: `00c48d27963638b9b72a5d3cdbb1f4c37b00d4ea`. Both repositories use the isolated
`codex/companion-photo-delete-20261102` branch; no public release was created.

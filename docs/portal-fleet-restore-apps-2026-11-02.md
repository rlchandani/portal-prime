# Fleet app restore selection and confirmation

App restore now selects packages from structured metadata and confirms their
installed presence. The original script selected an app marked **not installed**,
omitted the installed app and reported `done` after its simulated install returned
`ok:false` with process exit zero. This is reproduced without owner/device writes.

## Delivered host behavior

- Prevalidate all saved app metadata before any install or preference write.
- Select independently of JSON formatting/field order; sort and deduplicate.
- Exclude both release and debug Immortal launchers.
- Require literal success, exact selected package and terminal `installed` result.
- Read the same device's app list once and require that package present.
- Stop on unconfirmed results before later apps/preferences; never auto-retry.
- Resolve exactly one safe registry target locally. Reject `all`, unknown or
  ambiguous name/serial selectors before any restore mode can mutate data.
- Reject duplicate JSON fields: Rust chooses the first duplicate, Python normally
  chooses the last. A targeted failing test proved the resulting hidden target.
- Pin real commands to a private copy of one complete registry record, removed
  on exit, so later registry changes cannot expand the operation.
- Keep dry-run local with zero CLI calls.

The shared snapshot helper retains backup validation. Existing provisioning CI
now selects restore changes and runs its tests; hosted execution was not observed.

## Evidence

Final local gates passed in parallel: **24/24 restore methods, 10.768 seconds**,
and **23/23 backup methods, 16.625 seconds**, zero failures/errors. Bash syntax
and whitespace checks passed. macOS Bash **3.2.57** / Python **3.13** were used.
The original 12-method restore fixture run recorded 40 failure entries and one
error. Additional target and duplicate-field failures were reproduced before
their fixes; exact counts remain in the structured receipt.

Independent review caught the target fanout boundary; final review found no
blocking issue in the scoped path. The registry-isolation fixture mutates the
original synthetic registry during install and checks the private copy's full
record/token, `0700`/`0600` permissions, stable selector and exit cleanup.

Final-source Family read-only planning used its **27 catalog entries**. Original
and reversed/minified metadata both selected exactly **6 installed packages**, in
**59.365 ms** and **55.565 ms**, with zero restore CLI calls. Full saved settings,
layout, photo sources and screensaver configuration matched before and after.

## Boundaries

Actual owner app installation, provider availability, original APK/version
restoration, raw preference/config restoration, reboot and cross-package migration
remain unverified. App installs use the current catalog. A later failure can leave
earlier confirmed installs present; inspect partial state before rerunning.
No install transaction, rollback, whole-product completion or general speedup is
claimed.

No Android source, APK, device restore/install, Living native QA, GitHub
authentication/publication or HA change was needed. Portal remains **1.87/code81**.
Owned metadata and synthetic fixture kits are removed during closure; owner
contents and tokens are excluded from public evidence.

Original clock: **2026-11-02 23:15:37–23:45:37 UTC**, including cleanup.
[Sanitized structured receipt](../mocks/portal-widget-gallery-20261102/evidence/fleet-restore-apps-verification.json).

# Fleet CLI command confirmation

Known JSON commands now return failure when the Portal answers HTTP 200 with
`ok:false`, malformed JSON, a missing/truthy success flag, an array or duplicate
fields. The original compiled CLI returned zero for HTTP 200 rejection. Public
loopback fixtures reproduced **58 failures across two focused methods** before
the fix, with zero fixture errors.

## Scope and acceptance

Require HTTP 2xx, complete root-object JSON, literal `ok:true` and unique fields
throughout the response for the existing JSON command paths. Keep opaque raw,
file, logcat and binary responses compatible. Stop each device's `dev update`
before upload when pausing is unconfirmed, and before installation when uploading
is unconfirmed. Retain aggregate fanout failure; do not automatically retry.

The implementation shares one confirmation predicate across JSON reads/writes,
file listing/upload and all three update stages. A per-object hash set avoids
repeated scans while detecting duplicate fields. Existing provisioning CI
already compiles the CLI and runs both Rust and compiled HTTP tests on these
source paths; hosted CI execution was not observed.

## Verification

- Optimized CLI and Rust test binary compiled with existing Rust **1.99.1**.
- **23/23 Rust tests**, zero failures.
- **23/23 compiled HTTP methods**, **1.506 seconds**, zero failures/errors.
  Six new methods cover rejection/acceptance, raw/text compatibility and exact
  update-stage request ordering. Public dummy APK bytes never reached a Portal.
- **47/47 backup/restore methods**, **23.798 seconds**, zero failures/errors.
- Independent static review found no blocker within the scoped behavior.
- Final-source Family acceptance: **7/7 read-only CLI commands**, literal success,
  **219.958–409.510 ms** measured command durations. These are observations, not a
  speedup claim. Complete saved profile/layout/sources/screensaver configuration
  matched before and after. The owned private registry was removed.

No Android code, APK installation, native navigation, Living device operation,
Home Assistant change or GitHub authentication/publication was needed. Both
Portals remain **1.87/code81**.

## Limits

Confirmation validates the server's envelope. It does not independently verify
each server-side side effect, installed APK identity or every item of a bulk
update. `raw` intentionally remains an opaque escape hatch. Real failed
pause/upload/install and multi-device deployment were not exercised against owner
devices; ordering tests use isolated public loopback servers. No universal
performance, whole-product acceptance or rollback guarantee is claimed.

Original observation clock: **2026-11-02 23:15:37–23:45:37 UTC**, including cleanup.
It is inherited from the first observation during the earlier restore unit.
[Sanitized structured evidence](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-widget-gallery-20261102/evidence/fleet-command-status-verification.json).

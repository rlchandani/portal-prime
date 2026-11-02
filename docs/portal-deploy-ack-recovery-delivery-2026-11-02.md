# Deployment acknowledgement recovery — 2 November 2026

The workstation helper now recognizes the fleet CLI's exact `incomplete HTTP
headers` message after a self-update loses its reply. It still requires proof
that the installed APK changed and exactly matches the intended artifact.
This is a tooling correction; no Android release or reinstall was performed.

## Reproduction and change

Living Area's real 1.72 update printed the expected package install marker,
then `incomplete HTTP headers` on stdout and `(transport error)` on stderr,
with exit code 1. Independent installed-APK readback had already proved delivery.
The helper accepted older empty or `read failed:` tails but rejected this new
CLI message before checking the replacement.

The correction adds only an exact match for the new tail. Recovery still needs
exit 1, the expected package's install marker, transport-loss evidence, and an
existing own APK. Explicit top-level `ok:false` remains an immediate failure.
The existing bounded verification then requires a changed own-APK path or
mtime, followed by the intended byte count and SHA-256. It performs no second
installation.

## Verification

| Check | Result |
|---|---|
| New regression baseline | 37 helper tests; one failure reproducing the excluded acknowledgement |
| Corrected helper | 37 tests passed, including four new regression methods |
| Saved actual CLI transcript replay | Old helper fails; corrected helper verifies replacement; unchanged APK still fails |
| Negative coverage | Missing/wrong package marker, absent transport evidence, wrong exit, near-matching tail, explicit rejection, missing/unchanged/wrong/oversized APK all fail |
| Related local gates | 23 Rust tests, 17 actual compiled-CLI HTTP checks, 38 Calls-access checks, Bash syntax, Windows ASCII and actionlint passed |
| Independent review | No concrete introduced regression; 37 tests plus five additional negative probes passed |
| Actual room read-only checks | Both friendly identities, exact installed 1.72 APK and native CLI info verified |
| Configuration | Complete settings profiles and screensaver configuration unchanged in both rooms |

The saved-transcript replay uses simulated installation and HTTP responses.
The room checks use actual read-only requests. These are distinct evidence:
no fresh real install with lost acknowledgement was performed in this unit.

Both actual installed APKs still match 25,860,686 bytes and SHA-256
`79468c500a64ef934ee490a65a59ec73b46d064169d7186d9b00f8d802fbb0bb`.
No owner registry, app setting, photo, foreground activity, credential or
device configuration was intentionally changed.

## Limits and next work

This correction does not claim runtime acceptance of a new self-update,
hosted CI, Windows execution, fresh provisioning or reboot persistence.
Existing broader native and performance gaps retain their separate status.
The companion's unrun native numeric-input case is an independent remaining
acceptance item; its failed cold reconnect and physical Pixel checks remain
open without being silently reopened.

Branch: `codex/fleet-deploy-ack-recovery-20261102`. Private transcripts, replay
and actual room receipts: `/private/tmp/portal-deploy-ack-recovery-20261102/`.
The bounded unit started at 11:18:55 UTC and must close by 11:48:55 UTC,
including cleanup. The broader product goal remains active.

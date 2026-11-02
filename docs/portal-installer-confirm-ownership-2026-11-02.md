# Installer auto-confirm ownership — 2026-11-02

Source `f107d33b`, branch `codex/installer-confirm-ownership-20261102`.
The independent issue began at 14:27:39 UTC with a 30-minute cap including
deployment and cleanup.

## Problem and correction

Different package installs can overlap in Fleet. Their receiver callbacks
already use exact session correlation, but `InstallAutoConfirm` had one global
deadline: either install's `finally` cleared it, disabling confirmation while
another install was still waiting.

A two-worker HeadlessInstaller regression reproduced the lost window after one
attempt failed while the other awaited its own result. The fixture uses mocked
Android APIs and actual concurrent installer lifetimes. An initial fixture clock
mock was corrected before the relevant failing assertion was established.

Each attempt now acquires an identity-based lease and releases only that lease.
Window operations are synchronized. Each lease keeps the existing 30-second
expiry; later acquisition does not refresh older leases. Duplicate, expired or
foreign releases cannot clear another owner. No UI, preferences, settings schema,
R8 configuration or wire contract changed.

## Verification and artifact

The overlap regression changed from failure to passing. Ten independent
fake-clock tests cover both release orders, last release, duplicate/foreign
release, independent expiry boundaries, expired release and reacquisition.
Independent source review found no actionable correctness issue.

Final **3,869 JVM tests / 406 suites** passed with zero failures/errors/skips.
Debug and optimized builds, R8, vital lint, version sync and matching signer
checks passed. The versioned combined build took 110 seconds.

Optimized **1.77 / code71**, 25,860,686 bytes:
`5df871735ada5aae37375a6cd6129aa9fcb13329c982441a0cc90db2fd4f383e`.
This is local delivery, not publication.

## Device delivery

Family's first Fleet update from 1.76 returned `ok:false`. A private screenshot
showed its visible Immortal “Install” prompt, which the existing auto-confirm
service had not pressed. One direct confirmation attempt did not rescue that
failed attempt. Only after the deployment process was terminal did a wireless
ADB in-place install succeed. Full installed APK readback matches 1.77 exactly.
This does not establish automatic confirmation on Family's new build.

On the installed Family 1.77, two simultaneous client requests to the actual
dialog/headless API installed deliberately invalid public APK fixtures. Both
returned the expected failure, in 181.201ms and 187.185ms. Their scratch files
were absent before and removed after the check. Request concurrency is not
proof of two simultaneous valid confirmation dialogs.

The framework-only session probe then passed **15 checks, zero failures**, with
owned sessions **0 → 0**, an unrelated guard preserved and both fresh sessions
removed. The original test APK was fully restored/read back. The full settings
profile and screensaver configuration remain equal after both checks, and the
existing Chrome task is still listed. Foreground, browser process retention,
sign-in and music continuity are not proved by task retention.

Living received one final Fleet deployment. It exited zero and verified the
complete installed APK SHA-256; authenticated identity reports 1.77/code71.
Its full settings profile and screensaver configuration compare equal. No
duplicate native interaction or test-package deployment ran on Living.

## Limits and next action

Native two-valid-install confirmation, real timeout and expiry, API28, physical
phone, general performance and whole-product acceptance remain unverified.
The native terminal-failure timings are smoke evidence, not a speedup claim.

Family's missed automatic confirmation is a newly observed independent
readiness issue: check enabled/bound accessibility-service state and actual
installer events before changing code. The overlap fix does not establish its
cause. Its queued task retains a separate 30-minute boundary; old parked
screensaver/performance issues remain closed.

Private logs, screenshots, payloads and APK copies were not added to the public
repository. No Meta OS configuration, Home Assistant music deployment, account
automation or GitHub authentication/publication was performed.

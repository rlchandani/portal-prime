# Portal+ Calls handoff

Branch: `codex/portal-plus-approved-ux`. Device: connected Portal+,
Android 10 / API 29, 2160 × 1440. Verified October 5, 2026.

## Cause

The original Calls deep link resolves to Meta's actual Home activity:
`com.facebook.alohaapps.launcher/com.facebook.aloha.app.home.touch.HomeActivity`.
It must go through Meta Home because Meta Contacts requires a trusted caller.

Before the fix, three warm launches stayed on Meta Home. Three cold launches
all returned to Immortal and then its photo-frame activity. Logcat identifies
the sequence:

1. Immortal sends `VIEW portal://launcher/home`.
2. Meta initializes its dream flow and sends `MAIN/HOME`, flags `0x14000000`,
   to the default launcher from Meta's UID.
3. Immortal's `HomeActivity.onResume` clears the handoff protection.
4. Dream-stop classification becomes `REDREAM` and launches
   `PhotoFramePreviewActivity`.

The deep link was resolving successfully. The problem was treating Meta's
transient bootstrap redirect as the user's deliberate return.

## Correction

Both Calls entry points use `PortalHomeBridge`. It marks a specifically
identified Calls handoff before launching the existing Meta deep link. The
Portal TV Back fallback is restricted to `ripley`; fallback HOME candidates
are restricted to the two known Meta launcher packages.

Home consumes a newly delivered launch intent once. Restored activity state
and ordinary resumes do not reuse the old intent. Recovery requires a recent
Calls handoff and a `MAIN/HOME/CLEAR_TOP` intent without task-reset or
reorder-to-front flags. The reorder exclusion preserves
`BackHelper.ensureImmortalHome`, which deliberately returns the user.

Meta can emit more than one bootstrap redirect during the same cold launch;
a single retry also failed a repeated device sweep. Each matching redirect
can reopen the same Meta deep link before clearing screensaver protection,
with a maximum of three recovery dispatches per handoff within 30 seconds.
Recovery preserves the original timestamp and does not refresh its budget.
There are no polling jobs or fixed delays in production. A normal return or a
failed dispatch clears the handoff.

The Calls-target marker is internal handoff metadata, separate from
user-facing settings. Jarvis keeps its existing suppression behavior without
becoming eligible for Meta Home recovery.

## Verification

The debug and Android-test builds succeed. All 531 unit tests pass with zero
failures, errors or skips. Recovery tests fail before its implementation;
the explicit return-helper regression fails when its exclusion is removed.
Tests cover the bounded attempt budget, new handoffs, normal Home intents, explicit helper
returns, non-Calls handoffs, missing timestamps and the expiry boundary.
The repeated-bootstrap regression fails with the single-retry implementation.
Fallback tests cover touchscreen/TV preference and rejecting Immortal
variants and unknown HOME packages.

The final debug APK SHA-256 is:

`1793fb48ed4dc9203caee60c37412bfe355394d8cb681c15f09b08b4a1942c94`

The installed APK was read independently and matched that hash. Source
fingerprints remained unchanged through device verification.

| Native check | Result |
| --- | --- |
| Cold Meta launches | Six regular cold starts and one startup-wake stress case settle on actual Meta Home |
| Warm Meta launches | Four launches settle on actual Meta Home, including the retained-intent case |
| Photo-frame takeover | None across all eleven verified handoffs |
| Ordinary user returns | Seven pass: two Android Home-key returns after wake and five direct calls to Immortal's return helper |
| Retained launch intent and background Home recreation | Meta stays foreground; the subsequent explicit user return succeeds |
| Wake during cold bootstrap | Produces two genuine Meta `MAIN/HOME` redirects; both recover within the existing budget, with dream stops classified `SUPPRESSED` |
| Original data | Nine captured layout/settings/widgets/camera/notes/timer data files remain byte-identical |
| Final state | Observers terminate normally; Immortal Home is foreground |
| Independent code review | No blocking findings; retry and process limits acknowledged |

The regular sweep was resumed after fixing its retained-intent preparation:
two passing cold results from the same APK were retained and the remaining
cases completed. That preparation now ends the previous handoff before
setting up the deliberately retained launch intent.

Raw logs, device UI captures and data snapshots stay in private temporary
storage and are not committed. The installed APK includes the pre-existing
uncommitted `TvFocus.kt` candidate; this Calls change leaves that candidate and
the earlier audit documents for their separate work.

## Boundaries

This verifies the handoff into Meta Home without placing a call or selecting
a contact. Meta's cold bootstrap can still briefly change window focus and
start an Android dream session; a first wake action may be consumed by that
session. Meta Home consumed the raw Android Back key during testing, so the
explicit Immortal return helper is tested separately from that key.

The consumed retry budget is process-local; a process restart can allow a
fresh budget inside the original 30-second window. Process-death recovery and
Portal TV behavior are not hardware-verified here. There is no release
sibling installed on this Portal+. Broader UX and frame-time acceptance
remain covered by the separate quality gates.

# Native Settings clock and stepper acceptance — 2 November 2026

One Family Area acceptance unit, started **17:10:31 UTC**, deadline
**17:40:31 UTC**, on `codex/settings-native-steppers-20261102`, based on
`43089aea`. This covers existing controls missing from the earlier
[native scalar editor receipt](portal-settings-native-editors-2026-11-02.md).
It changes no app code, version, credentials, services or installed APK.

Normal Wi-Fi ADB touch/key input operated the installed optimized **1.80 / code 74**
on Family's Android 10 Portal+. No target instrumentation, UiAutomation,
accessibility reconnect or Living Area operation was used. The non-exported
Settings Activity rejected an initial direct shell launch; the actual checks
entered through Home → Settings → Sound & wake → Sounds.

## Observed cases

These are **23 coordinator-observed native cases**, not automated Compose tests.
Each capture also has a private complete profile readback. All scheduled sound
cues stayed off; no sound preview or alarm was enabled.

| # | Native action | Observed result |
| --- | --- | --- |
| 1 | Open Volume | Original 70% appears. |
| 2 | Four plus taps | Draft stops at 100%, including the extra endpoint tap; storage stays 70%. |
| 3 | Eleven minus taps | Draft stops at 0%, including the extra endpoint tap; storage stays 70%. |
| 4 | Cancel and reopen | Original 70% returns; complete profile unchanged. |
| 5 | Plus once, Save | Exactly `chime.volume` becomes 80; dialog closes. |
| 6 | Reopen Volume | Saved 80% appears. |
| 7 | Minus once, Save | 70% restored; complete profile matches baseline. |
| 8 | Open Quiet from | Original 22:00 appears on the dial. |
| 9 | Switch to Keyboard | Hour 22 / Minute 00 are prefilled. |
| 10 | Hour 24 → Save | Dialog stays open with “Enter an hour from 0 to 23”; no persistence. |
| 11 | Blank Hour → Save | Same visible validation; no persistence. |
| 12 | Hour 23 / Minute 60 → Save | Visible minute 0–59 validation; no persistence. |
| 13 | Correct to 23:59 → Clock | Dial shows 23:59, keyboard dismisses, draft remains unsaved. |
| 14 | Save 23:59 | Exactly `chime.quietStartMin` becomes 1439. |
| 15 | Reopen Quiet from | Saved 23:59 appears. |
| 16 | Keyboard 22:00 → Save | Original 1320 minutes restored; complete profile unchanged. |
| 17 | Keyboard 00:00 → Clock | Dial displays the midnight endpoint; storage stays 22:00. |
| 18 | Cancel and reopen | Original 22:00 returns. |
| 19 | Scroll and open Quiet until | Original 08:00 appears; the row is reachable in the existing inner list. |
| 20 | Tap hour zero on the dial | Draft becomes 00:00 and advances to minute selection; storage stays 08:00. |
| 21 | Save midnight | Exactly `chime.quietEndMin` becomes 0. |
| 22 | Reopen Quiet until | Saved 00:00 appears. |
| 23 | Tap hour eight, Save | Original 480 minutes restored; complete profile unchanged. |

Save, Cancel and Close remain visible in the observed dialogs, including
Meta's numeric keyboard and the error states.

![Actual native maximum stepper draft](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/evidence/native-stepper-volume-max.png)

![Actual native invalid-hour validation](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/evidence/native-clock-hour-validation.png)

![Actual reopened saved 23:59](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/evidence/native-clock-saved-time.png)

![Actual reopened saved midnight](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/evidence/native-clock-midnight-time.png)

Only dialog crops are committed. Full captures, private profiles and command logs
remain under `/private/tmp/portal-settings-native-steppers-20261102/`.

## Existing contract and independent review

A disjoint source review confirmed that Volume owns a dialog draft, clamps its
0–100 range in ten-point steps, and submits only on Save. The time-of-day editor
uses a 24-hour dial and strict hour/minute keyboard validation. It accepts every
valid minute, including 23:59; the registry's 30-minute step does not restrict
native time selection. Native clock selection has no plus/minus wrapping action,
so these checks do not claim to verify the separate wire-level wrapping boundary.

Sounds writes asynchronously through its existing registry and reloads after
completion. The observations prove final persisted state and reopen behavior;
they do not establish a visible intermediate “Saving changes…” duration.
The independent reviewer performed source inspection only, with no device tests.

## Automated checks and restoration

**26 tests across six focused JVM suites passed**, with zero failures, errors or
skips: clock drafts, editor selection, number stepping, Sounds save state and
clock spec/domain coverage. The first invocation lacked this worktree's SDK
path and failed before tests; rerunning with the installed SDK at
`/opt/homebrew/share/android-commandlinetools` succeeded.

Complete profile, layout and screensaver JSON match their baseline. Font scale,
enabled accessibility services and empty Binding list match; the original
Chrome task 27174 remains listed. The baseline manual `PhotoFramePreviewActivity`
is resumed again. Natural system Dream recovery was not newly tested.

The full installed APK readback remains:

`988881568224a8bfe69b249789e05ed13395c51fad71adb092ac85a77c2b56d6`

No build, installation, new release or publication was needed. The previous
full 1.80 build/regression gates retain their own scope. A temporary verification
matcher had a whitespace mistake; correcting it confirmed the unchanged actual
accessibility state.

## Limits and next work

This closes the three named controls at normal font scale in dark appearance.
D-pad interaction, other themes/text scales, concurrent native drafts, secret
fields, rich source forms and the full Settings navigation inventory remain
separate acceptance gaps. There is no universal latency or frame-rate claim.

No production defect was reproduced, so no speculative patch was made. Weather
and all exhausted investigations retain their existing boundaries. The next
independent native acceptance unit should check masking and Cancel behavior in
existing source credential forms, using a generated unsaved draft, without
connecting a service or changing the saved source.

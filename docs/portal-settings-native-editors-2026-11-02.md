# Native Settings scalar editor acceptance — 2 November 2026

One bounded Family Area QA unit, started **15:55:54 UTC**, deadline
**16:25:54 UTC**, on `codex/settings-native-editors-20261102`, based on
`80c93495`. This closes selected native interaction gaps in the existing
[editor contract](portal-settings-editor-contract-2026-11-02.md); it does not
change the app or accept every Settings page.

Normal Wi-Fi ADB touch/key input operated the installed optimized
**1.77 / code71** on Family Area's Android10 Portal+. No target instrumentation,
UiAutomation, accessibility reconnect, account operation or Living Area operation
was used. An independent bounded source review found no concrete scalar dialog
save/cancel/validation or live edit-admission defect.

## Observed native checks

The following are twelve coordinator-observed cases, not twelve automated
Compose tests.

| Case | Actual result |
| --- | --- |
| Device name opens | Existing saved room name is prefilled. |
| Text editor with Meta keyboard | Field, Save, Cancel and Close remain visible and reachable. |
| Blank name → Save | Dialog stays open with a validation error; complete profile unchanged. |
| 49-character name → Save | Dialog stays open with a validation error; no saved rename. |
| Cancel edited name, reopen | Original room name returns; complete profile unchanged. |
| Save untouched name | Dialog closes; complete profile unchanged. |
| Photo interval opens/reopens, numeric keyboard | Original30-second value returns after Cancel; numeric keyboard leaves actions reachable. |
| 4-second interval → Save | Visible5–600 validation error; no persistence. |
| 601-second interval → Save | Visible5–600 validation error; no persistence. |
| Blank interval → Save | Visible5–600 validation error; no persistence. |
| Valid35-second interval → Save, reopen | Device API and reopened dialog show35. Exactly one registry scalar changes: `screensaver.intervalSec`. |
| Restore30 seconds through native Save | Complete saved profile matches its baseline again. |

![Actual blank-name validation](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/evidence/native-name-validation.png)

![Actual interval validation](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/evidence/native-interval-validation.png)

![Actual reopened saved interval before restoration](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/evidence/native-interval-saved.png)

These are crops of actual Portal captures. Full captures and private profiles
remain under `/private/tmp/portal-settings-native-editors-20261102/`;
personal photos, accounts and tokens are excluded from the committed evidence.

## Cleanup and installed identity

Complete profile, layout and screensaver JSON match their original baselines.
Font scale is unchanged. Enabled accessibility services match the baseline,
with no pending Binding service. The original Chrome task remains listed; the
owned photo-preview Activity is finished, and the original Immortal
`PhotoDreamService` is running again.

Full installed APK readback matches the accepted original:

`5df871735ada5aae37375a6cd6129aa9fcb13329c982441a0cc90db2fd4f383e`

No app code, version, APK installation or public release changed in this unit.
The preceding full local readiness receipt remains separately scoped;
no fresh app build or performance benchmark is claimed.

The existing roadmap's updated Settings detail passed390×844 and1600×1000
presentation checks:33 task rows, no horizontal overflow, native cases and
limits visible. All three PNGs returned HTTP200/image/png; browser console
messages contained zero errors/warnings. This is saved-page presentation proof.

## Limits and next work

This accepts two representative scalar editors in dark appearance at the
existing normal font scale. Secret masking, time pickers, steppers, rich forms,
worker-backed rows, concurrent native drafts, other themes/text scales and the
complete54-page navigation walk remain unverified by this pass. It makes no
general frame-rate or interaction-latency claim.

No production defect was reproduced, so no speculative app patch was made.
Weather and the other exhausted issues remain parked under their existing
boundaries. Continue with an independent actionable product unit.

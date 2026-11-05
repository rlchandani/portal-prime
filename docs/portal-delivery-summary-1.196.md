# Recorded delivery 1.196 / code 190

Offline summary of supplied evidence; this is not a fresh device check.

Reject queued overnight-start rest entry when the overnight feature is currently disabled; retain cancellation of both alarms.

Recorded UTC: 2026-11-05T03:43:21.044942\+00:00.

Build exit 0 in 112.17 seconds. JVM: 4695 tests across 489 suites; zero failures, errors or skips. Independent review recorded as accepted.

Candidate: 25903487 bytes, SHA-256 `44a3b49678b5062cb79ceeb4dcb08522f2bd5fd1fc16294d9da9ceef21328016`.

| Room | Recorded version | Candidate bytes/hash | Service ready |
| --- | --- | --- | --- |
| Family Room | 1.196 / 190 | Match | Yes |
| Living Area | 1.196 / 190 | Match | Yes |

Original issue clock (recorded time is not a completion timestamp):

- Start: 2026-11-05T03:31:49Z
- Discovery cap: 2026-11-05T03:36:49Z
- Target: 2026-11-05T03:46:49Z
- Hard limit: 2026-11-05T04:01:49Z

Recorded limitations:

- Actual generated receiver/policy acceptance for disabled overnight-start delivery only; no real overnight alarm, physical lock/wake or mounted night-clock acceptance.
- Native API29 debug fixture captures denied rest-entry resolver/DPM attempts, then halts at scheduleOvernight config read before real settings/services/alarms/PendingIntents; no end-to-end secure/lock/cancellation acceptance.
- Host seven and native four vectors differ. Host verifies generated captured alarm/cancellation, disabled night-clock PowerManager inactivity, enabled dark lock and no-admin controls.
- Only current overnightEnabled admission changes; enabled-window timing, user sessions, recurrence, idle, sleep timer, audio and configuration behavior remain. No atomicity guarantee if settings change after the guard read.
- No optimized receiver execution/API28/Living native/phone/provider/account/GitHub authentication/push/publication claim. Discovery overran its cap; original03:31:49 start/03:46:49 target/04:01:49 hard limit retained, as are earlier held clocks and the whole active goal.

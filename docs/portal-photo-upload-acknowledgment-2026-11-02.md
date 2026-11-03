# Photo-upload acknowledgment correction — 2 November 2026

One independent unit, started **18:01:00 UTC**, deadline **18:31:00 UTC**,
on `codex/photo-upload-acknowledgment-20261102`, based on `3c092a4c`.
This addresses a newly reproduced browser-import outcome defect, without
reopening Weather, SMB keyboard, music authentication or exhausted performance
investigations.

## Problem and resulting behavior

The Portal commits an uploaded image before writing its success response.
If the response is empty, truncated or unreadable, the browser previously
reported “Failed,” although storage could already have succeeded.

The importer now keeps such a posted request **Completion unconfirmed** and
asks the user to refresh before retrying. It sends no automatic retries.
A readable server rejection remains Failed. Confirmed storage requires literal
`ok:true`, the exact destination path and the original byte count.

This reuses the existing transfer ledger, error presentation, ownership and
refresh behavior. It adds no polling, timers, dependencies or background work.
It changes no server upload protocol, credentials, photo-source settings or
native screen layout.

## Regression and independent review

Four original UI regressions reproduced unreadable success/gateway replies and
a mixed batch labeled incorrectly. Independent source review identified two
adjacent acknowledgment boundaries; three more assertions reproduced a readable
rejection being reclassified by the first candidate and malformed success flags
being accepted.

The final eight new cases cover truncated/empty success, unreadable gateway
reply, mixed confirmed/unconfirmed outcomes, readable rejection using the same
error code, missing/invalid `ok`, and unreadable 401 stopping queued files.
The 401 fixture initially looked for a Photos panel hidden by pairing expiry;
it was corrected to check retained transfer outcomes and “Pair again.”

**696 browser tests** and **3,869 JVM tests across 406 suites** passed with zero
failures, errors or skips. Debug/optimized assembly, R8/vital lint and signer
verification passed. Final Gradle gate took **43 seconds**.
Independent final source review found no actionable issue; it did not perform
device or build verification.

## Actual Family browser proof

The shipped paired PWA ran at **390 × 844** against Family's real API.
Only generated 8 × 8 gray images were used in a new owned scratch directory.
Fault injection altered the reply **after** a real successful upload; it did
not substitute the upload or metadata server.

| Actual result | Observed evidence |
| --- | --- |
| Original 1.80 | Real success committed a 77-byte image; truncating its acknowledgment made the UI show Failed. |
| Final 1.81, empty reply | Real storage succeeded; UI shows Completion unconfirmed. |
| Final 1.81, invalid success flag | Real storage succeeded; changing `ok` to a string produces Completion unconfirmed. |
| Bounded work | Two files caused exactly two uploads, zero automatic retries and zero metadata reads before manual Refresh. |
| Manual review | One Refresh showed all three then-existing scratch images; the two uncertain transfer outcomes stayed unconfirmed. |
| Explicit rejection | A real duplicate returned 409/collision and stayed Failed. |
| Positive control | A fresh image returned valid success and stayed Stored and confirmed, with one confirmed item in the two-file batch. |

All four stored originals were read back and matched the **77-byte** fixture
exactly, SHA-256
`d25cf32a53ab2f14f71e7f17d0f2890d476352ec9d56882db13502e9224b4195`.
All owned images and their empty directory were then removed. No mobile
horizontal overflow was observed.

![Original wrong Failed outcome](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/evidence/photo-upload-ack-old.png)

![Correct unconfirmed outcomes](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/evidence/photo-upload-ack-new.png)

![Explicit rejection and independently confirmed file](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/evidence/photo-upload-ack-explicit.png)

Only generated-name transfer-panel crops are public. Full private snapshots and
logs remain under `/private/tmp/portal-photo-upload-acknowledgment-20261102/`.
One diagnostic request used an incorrect asset URL and returned 404; it was
unrelated to the app workflow. Artifact inspection verifies that the final
bundled controller bytes exactly match the current source.

## Delivery and preservation

Both rooms have the exact optimized **1.81 / code 75** APK:

- Bytes: **25,860,922**
- SHA-256: `6456706f6648e4da67f22936a681b9e0b7413a191d2eccf90d905483f67a0032`
- Signer: `4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`

Family installed over Wi-Fi ADB. Living's Fleet installation command ended with
an incomplete-header transport error during self-replacement; the subsequent
full installed APK readback proves the actual successful replacement. No repeat
installation was performed.

Complete profile, layout and screensaver readbacks match both baselines.
Family's original Chrome task remains listed; font and enabled/binding
accessibility state match the retained accepted baseline. Its normal manual
`PhotoFramePreviewActivity` is resumed. The original browser pairing still works,
fault routes are removed and the testing browser is back on Home.

This is a verified outcome-correctness fix. It makes no universal latency/FPS,
physical-phone, natural Dream recovery, process-retention or live-provider claim.
Real malformed gateway/authentication responses were covered by controlled
browser regressions rather than deliberately expiring the device's pairing.
The earlier rejected keyboard candidate also used 1.81/code75 locally; it had a
different hash, was withdrawn, and none of its manifest changes are present.

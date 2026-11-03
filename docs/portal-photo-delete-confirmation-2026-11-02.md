# Photo-deletion confirmation — 2 November 2026

Independent unit **18:12:12–18:42:12 UTC**, branch
`codex/photo-delete-confirmation-20261102`, based on `fd8bcdcb`.
This repairs browser deletion acknowledgment validation. It does not reopen
parked Weather, keyboard, music or performance work.

## Reproduced problem and change

The browser previously displayed “Photo deleted” for a readable positive reply
without verifying its returned file path. The server's actual contract is
`{ok:true,path:<selected path>}`. A mismatched or malformed acknowledgment could
therefore claim success for an unconfirmed deletion.

The photo-specific action now suppresses generic success and validates literal
`ok:true` plus the exact selected path. Unexpected replies show:

> The Portal did not confirm this deletion. Refresh and review your photos before retrying.

The existing single forced metadata refresh remains. There is no automatic
delete retry, polling, new timer or background task. The generic mutation
helper still rejects retired page/session results; busy, error and stale
null/undefined sentinels remain unchanged.

Only bundled browser behavior changes. Server API, storage, credentials,
native layout and Android companion are unchanged. Native-phone acceptance is
not implied by the browser checks.

## Automated and independent checks

Four regressions failed before the patch: wrong/missing path and missing/invalid
success flag. Independent source review found three additional falsy-JSON edges:
`false`, `0` and `""` skipped the initial truthiness guard. Those three cases also
failed before its correction to a null/undefined check.

Final **703 browser tests**, including seven new deletion cases, and
**3,869 JVM tests across 406 suites** passed with zero failures, errors or skips.
Debug/optimized assembly, R8/vital lint, signing and exact bundled controller
identity passed. Final Gradle gate took **31 seconds**.

The reviewer inspected the initial diff and identified the primitive edge.
Its final assessment covered the reported guard replacement; the coordinator
inspected the actual final diff and ran all execution/device checks.

## Actual Family browser proof

The shipped paired PWA ran at **390 × 844**. A generated 8 × 8 gray image was
uploaded normally to a previously nonexistent owned scratch folder.

| Check | Actual result |
| --- | --- |
| Original 1.81 with a controlled wrong-path reply | “Photo deleted” appeared, although the owned photo remained listed and its original 77 bytes were intact. |
| Final 1.82 with a controlled wrong-path reply | Review warning appears; one metadata refresh; photo remains. |
| Final 1.82 with JSON `false` | Same review warning; one metadata refresh; photo remains. |
| Real final deletion without response substitution | HTTP 200 returned literal success and the exact selected path; one Delete and one metadata refresh; “Photo deleted” appeared and the folder had zero photo tiles. |

The negative controls supplied replies without executing server deletion. They
prove client validation, not a firmware/server wrong-file-deletion defect.
The positive control performed a real deletion through the normal UI.
No existing user image was deleted.

One early measurement checked an already-visible warning before the second
response settled and counted zero metadata reads. Rechecking with an explicit
response-completion barrier confirmed the one-read behavior. Each settled
action used one attempt, with no automatic replay. Mobile horizontal overflow
was absent.

The original still matched its 77-byte fixture after unconfirmed outcomes:

`d25cf32a53ab2f14f71e7f17d0f2890d476352ec9d56882db13502e9224b4195`

![Original false success](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/evidence/photo-delete-old.png)

![Correct review warning](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/evidence/photo-delete-new.png)

![Success after a real confirmed deletion](https://raw.githubusercontent.com/starbrightlab/immortal/main/mocks/portal-widget-gallery-20261102/evidence/photo-delete-confirmed.png)

Only notice crops are public. Private snapshots, build logs and helper receipts
remain under `/private/tmp/portal-photo-delete-confirmation-20261102/`.

## Delivery and cleanup

Both rooms have exact optimized **1.82 / code 76**, **25,860,990 bytes**:

`25ebd04107d0dfbf0b116a5992a547e83f182c0de98ae43e3bfaf36a9fc11939`

Signer:
`4f9a3b1d0c86028e831add351642a6167d06e9d3ed6ded6dcf5a790a560b45d6`

Family installed over Wi-Fi ADB. Living's Fleet command lost its response during
self-replacement; the full installed APK readback verified successful delivery,
without another installation.

Complete profile/layout/screensaver snapshots match both baselines. The owned
photo was removed by the positive UI check and its empty directory removed.
Family font, enabled/binding services and original Chrome task remain preserved;
the normal manual `PhotoFramePreviewActivity` is resumed. The testing browser
is back on Home with its original pairing usable and fault routes removed.

No universal latency/FPS, natural Dream recovery, process continuity,
physical-phone or new provider-connection acceptance is claimed.

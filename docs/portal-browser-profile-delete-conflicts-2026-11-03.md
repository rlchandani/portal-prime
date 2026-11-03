# Preserve profiles changed during Delete confirmation

Exact optimized **1.96/code90** is installed and fully hashed in Family Area and
Living Area. The earlier Save protection remains included.

An open Delete confirmation originally removed whatever profile currently had
that name. If another tab replaced it before confirmation, the newer settings
were deleted. A changed entry could also be removed from an older rendered row.

The row now supplies its reviewed profile value to Delete. The operation rereads
browser storage and compares that value before writing. A mismatch preserves
the original storage bytes, reports that the profile changed, and rerenders
the current list. A fresh confirmed Delete works; Cancel and the already-removed
path remain unchanged. Unrelated profiles are retained.

The shared `profileRevision` helper supports literal prototype-like names.
The profile format, Save guard, room/session ownership, storage failure handling
and confirmation layout are unchanged. No retry or Portal setting write is added.

## Evidence

All **three added methods failed against the original controller**, then passed.
The final full browser gate passed **724 tests**, including existing Save races,
Delete cancellation, malformed/failed storage, already-removed and special-name
controls. Independent source/fixture review found no concrete blocker; its five
final hashes match the executed sources and fixture. The reviewer did not run
tests or access devices.

Fresh **4,066 JVM tests / 423 suites** passed with zero failures, errors or skips.
Debug and optimized assembly, R8 and vital lint passed in **165 seconds**.

Family's actual installed controller assets were exercised through two tabs in
a new isolated desktop Chrome context, with generated management responses:

| During an open Delete confirmation | Original 1.95 | Final 1.96 |
| --- | --- | --- |
| Other tab replaces 30-second profile with 90-second profile | Newer profile deleted | Newer profile retained |
| Unrelated 77-second profile | Retained | Retained |
| Fresh Cancel, then fresh confirmed Delete | Existing behavior covered by regression gate | Cancel retains 90; fresh Delete removes only the reviewed profile |

Both rendered conflict cases pass at a 390-pixel viewport with normal and 130%
root-font emulation. Computed root/input sizes are checked; fixed-pixel controls
do not establish full system-font acceptance. Fresh visible error/success notices
are checked with mutation markers. Controller hashes match the committed 1.95
baseline and exact 1.96 APK. Every non-GET and foreign-origin request is rejected.
No real browser profile or Portal setting was changed by these fixtures.

Both deployed full APK hashes and complete saved screensaver/profile/layout/
source maps match. Family font, accessibility services and original Chrome task
are retained; its normal photo frame was visually checked. Living presentation
was not separately inspected. Owned contexts and reviewer are closed; private
registry copies are removed and primary registry bytes preserved. No Google
phone, GitHub, Home Assistant or publication action occurred.

This is a continuation of the same **02:35:00–03:05:00 UTC on 3 November** audit
that delivered Save protection. No new clock or reopened slot was used; discovery,
both fixes, verification, rollout, cleanup and commits fit that original window.

This protects stale reviewed values. Equivalent-value ABA changes, truly
simultaneous storage transactions, general FPS and whole-product acceptance are
not claimed. Reload an existing control-center tab for the current controller.

Structured receipt:
[`browser-profile-delete-conflicts-verification.json`](../mocks/portal-widget-gallery-20261102/evidence/browser-profile-delete-conflicts-verification.json).

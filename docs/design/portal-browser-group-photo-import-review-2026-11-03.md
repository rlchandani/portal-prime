# Browser photos: import to multiple Portals

The human approved this functional design for **local browser implementation**
on November 3, 2026. The [verified local implementation](../portal-browser-group-photo-import-2026-11-03.md)
is complete; live device transfer and publication acceptance remain separately gated.
The mock and its evidence below retain their original scope. At review creation both rooms contained exact Portal **1.96/code90**;
see the operating state for subsequent independent deliveries.
No Portal, Google phone, Home Assistant or account is accessed by this unit.

Review: [Photos to your Portals](http://127.0.0.1:8891/group-photo-import-review.html).

## User value and evidence

The user requested the browser remote be useful on a phone and reach companion
capability. They also requested sending photos and settings to both room Portals.
The current companion has captured multi-room photo targets. The browser's
`chooseUpload` captures only `session.capture()` and `startUpload` stores the
single `ticket.room` as its transfer target. Browser group Settings already exists;
photo upload does not have equivalent destinations.

This proposal reuses the existing control-center colors, type and photo-import
rules. It changes the browser photo import flow; it does not redesign Home,
Settings, the widget gallery or the installed native companion.

## Proposed interaction

1. The top Portal picker continues to choose the **library being viewed**.
   There is no second Manage Portals button on Photos.
2. Import photos opens a sheet containing files, **Send to** destination rows
   and each Portal's destination folder. The initial default follows the Portal
   being viewed; subsequent explicit destination choices are kept in memory.
   Select both is explicit. Files and destinations can be changed or cancelled
   before sending.
3. The primary button names the photo and Portal counts. Empty selections and
   invalid files cannot start. The existing supported extensions, 20 MiB cap,
   filename limit and public `/sdcard` folder rules are retained.
4. Start captures the file metadata, target sessions and folder. During the
   operation, switching the viewed Portal changes only the library. Captured
   imports continue to their original destinations.
5. Results show one row per destination and one outcome per file. A confirmed
   response contributes to the stored count; failures and unconfirmed sends do not.
6. Stop remaining imports stops queued copies while letting in-flight requests
   settle. Confirmed copies stay. Closing the browser is a different case:
   transfers cannot be promised to continue after the page closes.
7. Retry failed imports includes only definite failures. It never includes
   confirmed or unconfirmed copies. Offline/pairing examples simulate recovery;
   actual implementation must reconnect or renew pairing through existing flows.
8. A filename collision keeps the existing photo. A new filename can be chosen
   before retrying only that failed copy; the server remains the final authority
   for collision handling.
9. A lost confirmation can be checked against the Portal library. **Finding a
   filename alone does not prove the upload succeeded or that its contents match.**
   The review therefore retains Unconfirmed and offers View that Portal. It
   neither resends the photo automatically nor turns a listing into a success.
10. A new import cannot replace unresolved results. Dismiss results is explicit
    and asks for confirmation if there are failed, unsent or unconfirmed copies.
    Dismissal removes the review ledger; it does not delete any stored photos.

## Implementation choices made for the review

- Two concurrent room lanes, one file at a time per room. These match the
  companion's current bounded fan-out rather than one request per file at once.
  A future browser controller must coordinate overlapping batches per room.
- File selection and per-room results show at most eight file rows per page.
  All selected metadata remains in the ledger; a large batch does not require
  recreating hundreds of DOM rows for every progress update.
- Destination folders are captured independently per room. This review keeps
  those choices in memory for subsequent imports; it does not introduce or claim
  browser persistence equivalent to the native companion's saved-device folder.
- In-memory targets and a single active review batch. No pairing tokens,
  browser profiles, persistent source settings or live endpoints are involved.
- Example files have generated illustrations. A file chooser uses only name
  and size metadata; it never reads file contents. Review results are simulated.
- Theme and root-font controls allow geometry review. They do not establish
  physical phone, Portal, native Android or system-font acceptance.
- The saved photo library displayed here is illustration context. Shared or
  merged multi-room browsing, bulk deletion, slideshow activation, cross-room
  synchronization, background jobs and automatic retries are outside this scope.

## Future production requirements

Use paired destinations from the existing browser roster, but capture immutable
identity/session ownership at Start. Verify each target's current identity,
pairing and photo capability before writes. Report the room-specific failure
without silently switching targets or claiming another room's success.

Preserve existing server APIs, exact path/byte acknowledgment checks,
nonreplacement behavior and safe public folder validation. Reuse existing
thumbnails and retained photo inventories; do not decode every image or read
whole files merely to render the selection sheet. Coalesce transfer progress
and bound concurrent requests. File objects remain owned only by the active page.

Actual pairing renewal, safe retry admission, target removal/session replacement,
malformed or lost replies, no-overwrite races, page visibility/closure and room
configuration preservation need integration tests. Perform Family Area native
acceptance once, then exact artifact/version/configuration checks in Living Area.
Physical phone control remains prohibited until explicitly reauthorized.

Pairing renewal is an explicit existing pairing flow, not a credential silently
substituted into an old operation. After renewal, a fresh retry attempt must
capture the newly paired session at the same endpoint and verify the expected
stable Portal identity before sending. Missing/legacy identity is weaker evidence
and must be shown as requiring review, never claimed as verified. Pairing rejected
during the pre-send check can say No photo was sent; any loss after posting remains
unconfirmed. The review's single button simulates this completed recovery and does
not demonstrate a real authentication UI.

## Design and release gates

Design sign-off was received before production UI/controller implementation,
in line with the user's request to approve functional mocks first. Approval does
not relax the 30-minute issue limit, publication restriction, existing parked
work, phone prohibition or account boundaries.

The review unit retains the discovery clock **03:02:13–03:32:13 UTC on
3 November 2026**, including artifact creation, independent contract/design
review, rendered interactions, responsive checks, documentation and commit.
It does not restart Save/Delete, Weather, screensaver or prior performance work.

The approved browser group controller, regression tests and sheet/result UI are
implemented and locally verified. Actual Family transfer/restore plus Living
rollout verification remain separate acceptance work. No design confidence
or simulated performance result substitutes for that acceptance.

## Rendered review evidence

The final review passed **80 rendered checks** and **24 geometry cases** in an
owned desktop Chrome context. Cases cover valid/empty/invalid selections, public
folder admission and whitespace, captured viewing/recipient separation, two
simulated room lanes, partial failures, failure-only retry, unknown confirmation
retention, explicit collision rename, distinct room folders, queued cancellation,
retired reset completions and eight-row selection/results paging. Repeated imports
keep existing filenames rather than pretending they can be overwritten.

Results and sheet fit at320/390/768 pixels in both themes with100%/130% root-font
emulation; sheet actions stay in the viewport. Visual inspection found and
corrected retained sheet scroll and hidden third-photo context. No file-content
read, persistence, outside-origin request, page error, device command or live
upload occurred. The context was closed and the process exited0. These are mock
interaction/geometry checks, not native/physical performance or transfer proof.

[Structured receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-widget-gallery-20261102/evidence/group-photo-import-review-verification.json).

The complete re-run at **2026-11-03T03:22:08.830Z** additionally verifies recipients
following the viewed Portal until an explicit choice, unresolved-ledger import
guards, Keep/Dismiss confirmation, and 128-character collision renaming with
retry restricted to the explicitly renamed copy. Both refreshed screenshots were
visually inspected. The destination-sheet capture follows explicit dismissal of
the failed ledger. One earlier setup attempt stopped on an ambiguous dialog
selector; its log is retained separately. The corrected whole run completed once.

[Terminal receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-widget-gallery-20261102/evidence/group-photo-import-review-qa.log)
records **80 passed, 24 geometry cases, 4 local GETs, no errors**, with the owned
context closed.

| Verified artifact | SHA-256 |
| --- | --- |
| group-photo-import-review.html | `bf17b109fb62a03fafe26d08e67e28e2af06b2fdc198a87daa4076cb25c85754` |
| group-photo-import-review.css | `9464f1e785422132393f5106579ddeb6a025f48911131f65f1a37f0d7fae9833` |
| group-photo-import-review.js | `b701c0de38f257412f36ab8cd5a34eef7e98c983e53f371d483e2016f1fb2b0d` |
| Private review-qa.cjs | `2dc87a9e3fa21f3d5646faf18e877f10387ff8d476ebf4fb3eba1cc26c2bdc6d` |
| group-photo-import-partial-failure.png | `dfa04369187cada0072c2ae5f3d47f529522e8967900fc0994d145a8fc6546be` |
| group-photo-import-destination-sheet.png | `3b0ab49fa1db9ea37fb69f135a7c646743755be537599f8272acc9c4c3b22c6e` |
| group-photo-import-review-verification.json | `741480daed56989f6c7e47158c30bb4207fc3c84bd15b835bf4cbbc72e05183c` |

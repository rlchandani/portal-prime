# Browser group photo import: review contract

Date: 2026-11-03. Status: source inspection and proposed mock behavior only.

This review covers mobile-first photo import to several Portals, preserving the
native companion's separation between the Portal being viewed and the Portals
receiving changes. No production implementation, build, browser session, network
request, credential access, device operation, or deployment was performed.
Google phone inspection and control remain prohibited.

## Evidence locations

The references below identify the inspected worktrees, not a release or a claim
about installed devices. A link points to the first relevant line; ranges in the
text delimit the inspected behavior.

| ID | Source |
|---|---|
| B1 | [Browser control center](~/.codex/worktrees/portal-music-player/portal-prime/app/src/main/assets/remote/control-center.js:3978), `chooseUpload`, lines 3978–4015 |
| B2 | [Browser transfer](~/.codex/worktrees/portal-music-player/portal-prime/app/src/main/assets/remote/control-center.js:4016), cancellation, sending, acknowledgment and results, lines 4016–4158 |
| B3 | [Browser room selection](~/.codex/worktrees/portal-music-player/portal-prime/app/src/main/assets/remote/control-center.js:681), lines 681–711; page exit cancellation at 6373–6383 |
| B4 | [Browser core](~/.codex/worktrees/portal-music-player/portal-prime/app/src/main/assets/remote/core.mjs:36), roster 36–58, target capture 66–74, session ownership 103–145, request client 195–259, folder validation 480–495 |
| B5 | [Browser photo controls](~/.codex/worktrees/portal-music-player/portal-prime/app/src/main/assets/remote/control-center.js:3559), lines 3559–3680; capability predicate at 345; settings target selection at 2382–2386 |
| N1 | [Native group controller](~/.codex/worktrees/portal-companion-compat-20261102/portal-toolkit/app/src/main/java/com/portalinstaller/data/companion/CompanionGroup.kt:22), models 22–46, planning/retry/cancel 121–189, scheduling 198–229, execution 231–317 |
| N2 | [Native repository](~/.codex/worktrees/portal-companion-compat-20261102/portal-toolkit/app/src/main/java/com/portalinstaller/data/companion/CompanionRepository.kt:60), pairing guard 60–63, selection defaults 79–94, viewing 186–205, write-target capture and group photo upload 383–433 |
| N3 | [Native photo preparation](~/.codex/worktrees/portal-companion-compat-20261102/portal-toolkit/app/src/main/java/com/portalinstaller/data/companion/CompanionPhotos.kt:27), lines 27–63 and 85–135 |
| N4 | [Native upload client](~/.codex/worktrees/portal-companion-compat-20261102/portal-toolkit/app/src/main/java/com/portalinstaller/data/companion/CompanionClient.kt:210), lines 210–237 |
| N5 | [Native photo picker and panel](~/.codex/worktrees/portal-companion-compat-20261102/portal-toolkit/app/src/main/java/com/portalinstaller/ui/controlcenter/ControlCenterScreen.kt:168), picker 168–175, photo panel 478–503; captured picker targets declared at 142 |
| N6 | [Native target menu](~/.codex/worktrees/portal-companion-compat-20261102/portal-toolkit/app/src/main/java/com/portalinstaller/ui/controlcenter/CompactPortalHeader.kt:140), lines 140–205 |
| N7 | [Native group result UI](~/.codex/worktrees/portal-companion-compat-20261102/portal-toolkit/app/src/main/java/com/portalinstaller/ui/controlcenter/ManagePortalsPanel.kt:49), lines 49–99 |
| S1 | [Portal upload implementation](~/.codex/worktrees/portal-music-player/portal-prime/app/src/main/java/com/immortal/launcher/RemoteCompanionPhotos.kt:23), path/name policy 23–49, publication 54–85, availability 129, upload 159–184 |
| S2 | [Portal companion admission](~/.codex/worktrees/portal-music-player/portal-prime/app/src/main/java/com/immortal/launcher/RemoteCompanionRoutes.kt:30), authorization 30–32, capability advertisement at 77 |
| S3 | [Portal HTTP preflight](~/.codex/worktrees/portal-music-player/portal-prime/app/src/main/java/com/immortal/launcher/FleetHttpServer.kt:155), CORS methods/headers 155–168, OPTIONS handling 221–227, response origin header at 354 |

## Observations: factual parity map

**Native grouping really does cover uploads.** This is an implemented call chain:
the UI captures `CompanionTargets` before launching the picker (N5:483–486);
its callback calls `uploadToTargets` (N5:168–173); the view model delegates to
`uploadSelectedUris`
([ControlCenterViewModel.kt:100–104](~/.codex/worktrees/portal-companion-compat-20261102/portal-toolkit/app/src/main/java/com/portalinstaller/viewmodel/ControlCenterViewModel.kt:100));
the repository calls `groups.photos` (N2:420–428); the controller creates one
item per source per captured session and calls `client.uploadPhoto`
(N1:144–157, 267–281). It is not merely grouped settings UI.

| Concern | Current browser | Current native companion |
|---|---|---|
| Viewing versus writing | `chooseUpload` captures the current viewing session and one destination. `startUpload` sends only to `ticket.room` (B1; B2:4028–4068). Settings already have separate scope capture, but photo import does not use it (B5:2382–2386). | “View Portal” radios and “Send changes to” checkboxes are independent (N6). Changing the viewed device retains `selectedTargetIds` and group results (N2:186–191). |
| Target snapshot | Captures viewing room before the sheet opens; refuses a stale ticket at start (B1:3979; B2:4029). | Captures copied device/session data before the picker opens; later selection changes do not retarget the picker result (N2:400–401; N5). |
| Destination | One editable folder. All-photo browsing defaults import to `/sdcard/Pictures/Immortal`; otherwise uses `photoPath` (B1:3980–3999). | Each captured device's saved `photoFolder`, with default `/sdcard/Pictures/Immortal` (N1:153–157, 275; N3:42). The menu shows each destination (N6:202). |
| Destination persistence | Browser roster retains only `base`, `token`, `name`; `sanitizeRoster` discards additional fields (B4:36–58). No per-room import-folder identity is carried by this roster. | Browsing can save the per-device folder after an owned metadata read, so “saved folder” is a companion preference, not necessarily the Portal's current slideshow source ([CompanionRepository.kt:729–743](~/.codex/worktrees/portal-companion-compat-20261102/portal-toolkit/app/src/main/java/com/portalinstaller/data/companion/CompanionRepository.kt:729)). |
| Fan-out and scheduling | One sequential file loop; one running transfer globally; results retained in an in-memory map by room base (B2:4029–4047). | Per-Portal FIFO across operations/retries; separate upload semaphore permits at most two group upload-lane tasks concurrently (N1:131–134, 198–221). |
| File preparation | Browser `File` body, supported extensions, nonempty and at most 20 MiB, name/path validation (B1; B2:4056–4078). | Fresh preparation for each room/file attempt; URI streams with known size, or bounded private-disk staging for unknown size; cleanup in `finally` (N3:85–135; N1:267–287). |
| Stored confirmation | Requires `ok === true`, exact destination plus filename, and exact file byte count (B2:4080–4084). | Client checks positive-response acknowledgment types, exact path and numeric byte count; group success follows successful client return (N4:222–233; N1:280–292). |
| Result granularity | Per-file outcome for one captured room; completed count and bytes include only confirmed files (B2:4042, 4082–4084, 4122–4158). | Per-room/per-file ledger with destination, status, detail and bytes sent (N1:28–36, 153–157); room summaries and eight-row result paging (N7:53–90). Bytes sent alone do not confirm storage. |
| Cancel | Aborts the active request, marks queued files not sent and sending files unconfirmed; room switch and page exit cancel (B2:4016–4025; B3). | “Cancel queued work” sets an operation flag; posted requests are allowed to finish. Checks before posting prevent further queued work from being sent (N1:179–185, 211–215, 245, 271; N7:93–94). |
| Retry | No failure-only retry action in the inspected import panel; advises refresh before retrying uncertain files (B2:4116–4119, 4122–4158). | Explicit retry schedules only `FAILED` items; `SUCCEEDED`, `UNCONFIRMED` and `CANCELLED` are excluded (N1:44–45, 168–177). |
| Refresh and viewing changes | Accepted results can refresh only the owned viewing session; switching room stops the transfer (B2:4104–4114; B3:694). | Group transfers retain captured targets across viewing changes. Only the captured active read ticket is refreshed if it still owns that view (N2:423–427). |

Neither implementation equates importing files with activating a slideshow.
Native activation is an active-device source write (N5:500–501). Browser activation
is a separate confirmation and source mutation (B5:3654–3677). Deletion and
photo browsing remain viewing-Portal operations.

## Observations: capability, session and preflight boundaries

These are distinct checks, with different guarantees:

1. **Capability metadata.** Browser import is disabled unless the viewed
   Portal advertises `photos`; `photoLibrary` controls all-photo browsing and
   the default import folder (B5:345, 3577–3582, 3645–3649; B1:3980).
   Native top-level “Add photos” is gated by selected targets and target-selection
   saving, independently of the active Portal's connection; the active library
   panel is separately gated by `photos` (N5:483–488).
2. **Native group “Checking support.”** Each room/file execution rechecks that
   the captured pairing still exists, requests `/remote/companion`, and compares
   a nonblank server ID to the captured stable ID (N1:237–244). A companion
   404 is tolerated, blank identity is tolerated, and `legacy:` IDs bypass the
   stable-ID comparison. The photo branch does **not** inspect
   `capabilities.photos`, list the destination, or reserve storage before posting
   (N1:239–244, 267–280). Do not present this as a complete upload preflight.
3. **Session ownership.** Native pairing validity compares device ID, endpoint
   and token before work and again before sending (N2:60–62; N1:238, 272).
   Browser `Session.current` checks generation, base and token, plus page
   generation by default; transfers relax page ownership after starting
   (B4:135–143; B2:4048, 4104). Browser roster identity is currently an
   endpoint, not a stored stable Portal ID (B4:53).
4. **Browser transport preflight.** Cross-origin authorization/content-type
   requests can require CORS OPTIONS. The server advertises GET/POST/OPTIONS
   and Authorization/Content-Type, responds to OPTIONS before route auth,
   and emits `Access-Control-Allow-Origin: *` (S3). This provides no proof of
   pairing, photo support, destination availability, free space, or upload success.
   Actual photo routes require enabled remote access and a valid remote session
   (S2:30–32); the browser sends the captured bearer header with cookies omitted
   (B4:213–228). Browser-specific origin/LAN restrictions remain unverified.
5. **Actual server acceptance.** POST validates method, path, filename,
   Content-Length, MIME, folder availability and free space; it verifies image
   signature and decode bounds before publishing (S1:159–184). Server checks
   are authoritative and can still fail after an earlier successful metadata read.

The existing native group path performs checks per item, not an all-rooms barrier.
One room may already be confirmed while another is offline or rejects a file.
There is no group transaction or rollback. This inspection does not establish a
server-side idempotency key, per-upload reconciliation endpoint, hash acknowledgment,
or durable import queue.

## Observations: outcomes, cancellation and retry

| Evidence after a room/file attempt | Truthful outcome | Retry implications |
|---|---|---|
| Exact positive acknowledgment | Stored and confirmed on that room, at that path, for that byte count | Retain success; do not resend this pair. This does not prove slideshow activation. |
| Local validation failure before POST | Not sent / invalid selection | Correct the selection, then retry only the affected pairs. |
| Explicit valid server rejection | Rejected, with the returned reason | Native records `FAILED`; explicit retry can target only failed pairs after correction. A collision needs review first. |
| Transport loss or unreadable/mismatched positive acknowledgment | Completion unconfirmed; the file may have arrived | Refresh the exact room/folder. Exclude from automatic and “failed only” retry. |
| Queued work cancelled before sending | Not sent | Keep separate from failures; native failure-only retry does not resume it. |
| Request already posted when native queued cancellation is requested | Pending until its request resolves; may become confirmed | Cancellation does not undo it. |
| Browser abort after sending begins | Completion unconfirmed | Aborting the client does not establish absence on the Portal. |
| Local cleanup or subsequent metadata refresh fails | Preserve the upload's confirmed/unconfirmed outcome; show an additional review notice | Do not turn a confirmed upload into a resend candidate because readback or cleanup failed. |

Native `uploadPhoto` converts non-`CompanionException` IO loss to
`upload_unconfirmed` (N4:214–237), and the group controller uses `UNCONFIRMED`
for that explicit code (N1:300–316). Source code also has a catch-all edge:
an unexpected non-IO exception after POST can be labeled `FAILED` while its
detail says completion is unconfirmed (N1:311–315). A future browser contract
must classify uncertainty from the sending phase and evidence, not merely copy
the native status enum.

Native coroutine cancellation has another distinction: interrupted posted work
can receive `CANCELLED` with an “already posted work is not undone” detail
(N1:293–297). This is different from the ordinary UI queued-cancellation path.
The mock must never label both cases “not sent.”

The server refuses an existing destination filename and uses hidden staging,
exact length verification, image validation and exclusive publication
(S1:54–85). Collision is HTTP 409 (S1:175). Repeating an uncertain request is
therefore not an idempotent operation: it can collide with an already stored file.
Automatically deleting, overwriting, or renaming a collision would change the
operation's meaning. A matching listing name and size is useful evidence, but
without a content hash it does not prove that this particular request created
that file. Keep original uncertainty and any later observed presence separate.

Group plans, source providers and results are held in memory; results can be
dismissed only when finished, and at most 32 operations can be retained
(N1:129–151, 187–189). Browser transfer results are also in memory
(B2:4044–4045). Persistence of target selection is not persistence of
selected files, upload jobs or outcome ledgers.

Existing native test source corroborates the intended boundaries, but was not run:

- [CompanionGroupTest.kt:182–234](~/.codex/worktrees/portal-companion-compat-20261102/portal-toolkit/app/src/test/java/com/portalinstaller/data/companion/CompanionGroupTest.kt:182)
  covers captured exact per-room folders, fresh streams, failure-only retry,
  bounded fan-out and queued cancellation retaining posted confirmations.
- [CompanionPhotoUploadAcknowledgmentTest.kt:48–153](~/.codex/worktrees/portal-companion-compat-20261102/portal-toolkit/app/src/test/java/com/portalinstaller/data/companion/CompanionPhotoUploadAcknowledgmentTest.kt:48)
  covers acknowledgment shape and transport-loss uncertainty, including exclusion
  of unconfirmed group items from retry.
- [CompanionPhotoUploadAcknowledgmentTest.kt:188–283](~/.codex/worktrees/portal-companion-compat-20261102/portal-toolkit/app/src/test/java/com/portalinstaller/data/companion/CompanionPhotoUploadAcknowledgmentTest.kt:188)
  covers mixed confirmed/unconfirmed/rejected results and preserving outcomes
  when local cleanup fails.

## Proposed mock and future browser contract

Everything in this section is proposed behavior, not a claim about current
browser code or device acceptance.

### Mobile review flow

1. Keep a visible **Viewing: Living room** context for the photo library.
   Provide a distinct **Send photos to** selection with room checkboxes,
   select-all and clear-selection controls. A view change must not silently
   change recipients. An empty recipient selection blocks starting an import.
2. Before opening the file picker, capture recipients and their destination
   folders. Show the chosen recipients again in a review sheet after selection.
   Changing a recipient or destination requires an explicit revised capture;
   it must not redirect an existing operation.
3. Show file count, selected room count, each room's destination and the
   resulting number of room/file attempts. Display the 20 MiB limit and
   accepted formats. Mark unsupported, unavailable or expired rooms
   individually, with the reason and an explicit way to revise selection.
   Do not silently omit them or substitute the viewed Portal.
4. Display readiness as **checking**, **ready**, **unsupported**, **reconnect
   needed** or **unreachable** per room. Readiness cannot promise later storage
   success. Keep browser CORS failures distinguishable from a confirmed server
   rejection when the browser provides enough evidence; otherwise show
   unreachable/unconfirmed according to whether sending began.
5. Start with a concrete summary such as “Send 3 photos to 2 Portals.”
   Use stacked room cards and a readable per-file ledger on narrow screens.
   “2 of 3 confirmed · 1 unconfirmed” is preferable to a generic “Import done.”
   Keep errors and unknown outcomes visible, with accessible status updates
   and controls that do not require horizontal scrolling.
6. Make **Cancel queued work**, **Refresh this Portal** and **Retry failed
   only** distinct actions. A cancel notice must explain that sent requests may
   finish and confirmed files remain. A retry preview must identify the exact
   failed room/file pairs; confirmed and unknown pairs remain untouched.

### Implementation constraints to preserve

- Model the operation independently of the viewing session: immutable captured
  recipients, endpoint/session identity, destination, file identity and attempt
  identity. Pairing removal or replacement invalidates queued/retried work.
  Never adopt replacement credentials into an existing operation silently.
- Preserve per-room destination identity, including meaningful directory
  whitespace; normalize only documented shared-storage aliases and trailing
  separators. Do not copy the viewed room's folder to every target implicitly.
  Server path/image validation remains authoritative.
- Preserve native identity compatibility boundaries explicitly: stable identity
  mismatch blocks sending; legacy or missing identity is weaker evidence, not
  “identity verified.” Any stronger browser policy must be described as new.
- Keep per-room FIFO and bounded fan-out, with at most two simultaneous group
  upload-lane requests for native parity. Browser `File` bodies should be reused
  per attempt without loading every image fully into application memory.
- Maintain a stable room/file ledger even when filenames repeat. Native uses
  source position × target; filenames alone are insufficient operation keys.
  Byte progress describes sending; only exact acknowledgment increments stored
  and confirmed counts.
- Keep viewing, deletion, previews and slideshow source activation separate
  from group import. Viewing another Portal may refresh its library but cannot
  retarget, discard, or falsely confirm a captured group operation.
- Preserve all confirmed results through partial failure, cancellation,
  cleanup failure and readback failure. Never infer “all rooms succeeded” from
  one successful room or from the active room's photo count.
- Unknown completion is terminal for automatic replay. Safe explicit retry
  concerns known failed pairs after renewed pairing/support checks and review
  of collisions. Resuming cancelled pairs is a separately reviewed action.
- Do not claim crash recovery, background completion after page closure, or
  durable retry without a new persistence design. Do not persist image bodies
  or expose pairing values in mock output, logs, URLs or result rows.

### Concrete conflicts for the coordinator

1. Reusing current `startUpload` unchanged cannot provide grouped import:
   it accepts one viewing ticket, globally gates a running upload, and cancels
   when viewing changes. Native grouped upload instead retains captured
   recipients while viewing changes. The mock should demonstrate the latter;
   production work would require a separate operation owner.
2. A single “Destination folder” copied across rooms is not native parity.
   Native uses each captured saved folder; the current browser roster has no
   saved-folder or stable-device-ID fields. The mock may use explicit fixture
   destinations, but must label that model as proposed.
3. “Preflight passed” cannot currently mean all native recipients support
   upload and have sufficient storage: the native group photo branch checks
   pairing and identity, then relies on the POST for acceptance. A mock showing
   richer readiness is proposing additional checks.
4. “Cancel all” implying rollback, or “Retry remaining” including unknown
   completions, conflicts with the current native contract. Preserve confirmed
   files, stop queued work, and review unknown pairs before any new send.

Review acceptance remains local and simulated: demonstrate a view/target mismatch,
different per-room destinations, empty selection, pairing replacement before send,
one unsupported room, mixed confirmed/rejected/unconfirmed files, a collision,
cancellation during sending, failure-only retry, and viewing changes during an
operation. This document supplies the contract; the coordinator owns mock
construction and its local functional review.

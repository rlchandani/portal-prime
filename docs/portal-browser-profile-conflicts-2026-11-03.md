# Preserve browser profiles changed during Save

Exact optimized **1.95/code89** is installed and fully hashed in Family Area
and Living Area. This fixes a settings-profile data-loss case in the browser
control center.

Two tabs share browser profile storage. Originally, tab A could begin a slow
capture for a new “Evening” profile, tab B could save its own “Evening,” and A
would then overwrite B without replacement confirmation. A confirmation opened
before another tab edited or removed an existing profile also remained valid.

Save now reads current storage when clicked and captures the selected name's
presence and serialized value. The same reviewed value must still match before
capture and when capture finishes. A conflict preserves the stored profile and
typed name, releases the pending state, and asks the user to review and save
again. The next Save presents the existing replacement confirmation. Cancel
keeps the newer profile; a fresh confirmed replacement still works.

Only the selected profile is compared. Unrelated concurrent additions, edits
and removals are retained from the final storage read. Literal prototype-like
names remain supported. The profile format, complete-settings GET, secret
filtering, room/session ownership and existing confirmation layout are unchanged.
No Portal setting is applied by profile capture, and no retry is automatic.

## Verification and release

Six focused methods tested the original controller: **five failures and one
passing unrelated-name control**. Final results:

| Gate | Evidence |
| --- | --- |
| Browser regression tests | **721/721**, including **374/374** workflow tests and all six added cases |
| Independent review | No blocking source finding; **45** profile methods and **4** additional private contract methods passed; reviewed source hashes match |
| Android regression gate | **4,066 tests / 423 suites**, zero failures, errors or skips |
| Builds | Debug and optimized assembly, R8 and vital lint passed in **156 seconds** |
| Family-served browser assets | Both generated two-tab cases originally overwrote **90→30**; both final cases preserve **90**, then allow a fresh confirmed replacement |
| Release identity | Same signing lineage; exact full installed APK digest matches in both rooms |
| Preservation | Complete screensaver/profile/layout/source maps unchanged in both rooms; Family font, accessibility services and original Chrome task retained |

Rendered checks used two tabs in a new isolated desktop Chrome context at a
390-pixel viewport, with normal and 130% root-font emulation. Computed root and
input sizes were checked. Fixed-pixel buttons/headings/notices do not enlarge
with that override; this does **not** establish full system-font accessibility
acceptance.

HTML, CSS and controller assets came from the actual installed Family app;
their controller hashes match the committed baseline and exact candidate APK.
Management replies were generated in memory, with A's capture deliberately
held until B finished. The fixtures reject every non-GET and foreign-origin
request. They establish the rendered conflict/Cancel/replacement behavior,
including fresh visible error/success notices, without changing real profiles
or Portal settings. They do not prove live provider timing or simultaneous
atomic browser-storage transactions.

Family's final normal photo frame was visually checked. Living's final full APK
hash, version and complete saved maps were verified; its presentation was not
separately inspected. The owned browser contexts and reviewer are closed;
private registry copies are removed and original registry bytes preserved.
No Google phone command, GitHub action, Home Assistant change or publication
occurred. Reload an already-open control-center tab to load the new controller.

The original **02:35:00–03:05:00 UTC on 3 November 2026** issue clock includes
discovery, implementation, review, all checks, both deployments, cleanup and
commit. Earlier exhausted issues remain parked.

The scope is pending Save/replacement capture. Delete/Apply confirmation
semantics, equivalent-value ABA changes and truly simultaneous storage writers
are not newly accepted. A related Delete-confirmation staleness observation
is source-only and keeps this same issue clock; it does not authorize a fresh
slot. No general responsiveness, FPS or whole-product completion claim is made.

Structured evidence:
[`browser-profile-conflicts-verification.json`](../mocks/portal-widget-gallery-20261102/evidence/browser-profile-conflicts-verification.json).

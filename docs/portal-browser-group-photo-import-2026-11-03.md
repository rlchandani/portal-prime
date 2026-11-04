# Browser group photo import: local implementation

The human approved local browser UI/controller implementation on November 3, 2026. The browser now imports selected photos to one or both paired Portals, with a separate folder and result for every destination/file. Changing the viewed library keeps the captured import destinations. File and result lists show eight rows per page; the primary action names both counts.

`PhotoImportBatch` owns at most two Portal lanes with sequential files per lane. Before each upload it verifies the captured stable device identity, current pairing and photo capability. Only an exact positive path/byte acknowledgement confirms a copy. Parsed rejections are definite failures; missing, malformed or lost replies remain unconfirmed. Retry admits only definite failures and retains the original folder/device identity while explicitly capturing the current pairing at the same endpoint. Collision rename retries only that selected copy; existing server files are never overwritten by the browser.

Stop cancels queued work and lets an in-flight response settle. Pairing retirement and page closure abort the relevant work while preserving uncertainty. Pending sheet inspections cannot outlive dismissal, change folders after Start, replace a newer ledger or revive after transient pairing removal. Existing results require explicit dismissal before another batch starts; uncertain results require a confirmation before dismissal. Folder and recipient choices remain in page memory.

## Verification

- All **743 JavaScript tests** pass, including 19 new group controller/UI cases. The baseline had 724 passing tests. Earlier view-cancellation tests were updated to continuation; explicit Stop and pairing retirement remain covered.
- All **4,358 fresh JVM tests / 453 suites** pass with zero failures, errors or skips. Gradle reran the test task; its compile prerequisites were cached.
- Debug APK assembly passes. Its browser controller, core and CSS bytes exactly match the verified source assets. No install was performed.
- Isolated Chromium passes **16 leaf cases**: four workflow cases and 12 layouts at 320/390/768 pixels, light/dark themes and 100%/130% root-font emulation. Generated intercepted replies only; no server, outside request, page error or WebSocket. Context/browser closed. The counted-action RED and corrected final run use the same strengthened test.
- Independent review reproduced five ownership/cancellation issues; regressions cover their corrections and the final review has no remaining actionable finding. Four generated screenshots were inspected by QA; the two retained here were also inspected by the implementing agent.

[Structured verification](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-widget-gallery-20261102/evidence/group-photo-import-production-verification.json) records exact source and APK hashes. [Selection sheet](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-widget-gallery-20261102/evidence/group-photo-import-production-sheet.png) and [320px enlarged-text sheet](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-widget-gallery-20261102/evidence/group-photo-import-production-sheet-small.png) show the actual shipped UI.

To rerun local browser QA with an installed Playwright/Chromium runtime:

```sh
IMMORTAL_REMOTE_CHROME=1 node --test scripts/tests/remote_photo_group_browser.test.mjs
```

## Remaining acceptance

This is verified local implementation, not device delivery. Generated payloads exercise transport and outcomes rather than image decoding. Real Family/Living transfers, storage/no-overwrite behavior, live LAN/CORS/authentication, physical phone behavior and hardware performance remain unverified. Device transfers and publication retain their existing gates. No background continuity is promised after the page closes.

The approved implementation unit runs **00:42:19–01:12:19 UTC on November 4, 2026**, including discovery, code, tests, review, records and local commit. The original mock unit **03:02:13–03:32:13 UTC on November 3** remains historical and is not reset. Other parked work, phone/account boundaries and the broader product objective remain.

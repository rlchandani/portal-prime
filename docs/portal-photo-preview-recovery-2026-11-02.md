# Browser photo preview recovery — 2 November 2026

Bounded unit: started16:18:12UTC; hard deadline16:48:12UTC, including cleanup.
Branch `codex/photo-preview-recovery-20261102`, based on `858675a7`.
The closure receipt records the actual finish time separately.

## Delivered behavior

The browser remote could remain on “Loading preview…” after backgrounding
cancelled its photo download. A successful Retry also kept the previous error
styling. Both defects were reproduced against the shipped controller.

The modal now owns its pending load. Returning resumes only that pending preview
while the same dialog, photo and Portal session remain current. A completed
preview keeps its decoded image without another preview download. Loading resets
the error presentation; successful Retry returns normal feedback.

Independent review reproduced a deferred-request race: the replacement could be
queued, pairing could expire during the resumed listing read, and the replacement
could subsequently start. The loader now checks ownership again at dispatch, and
both callbacks use the same guard. Closing, navigating, changing room or expiring
pairing cannot revive the preview. Delete admission still requires a freshly
confirmed photo collection.

`ImagePool.limit` bounds entries; `concurrency` bounds executing loaders. Both are
one for the preview pool. The existing gallery pool and its viewport admission
are unchanged. An aborted loader retains its slot until it settles.

## Verification

| Check | Result |
|---|---|
| Initial regression sensitivity | 7 cases:2 failed /5 passed before the fix |
| Independent deferred-expiry sensitivity | 8 cases:1 failed when the dispatch guard was omitted in memory |
| Final focused preview suite | 8/8 passed |
| Full browser JavaScript gate | 684/684 passed;14,126ms |
| Full slideshow source/label/style gate | 354/354 passed;6,790ms |
| JVM gate | 3,869 tests /406 suites;0 failures, errors or skips |
| Debug and optimized assembly | Passed; optimized R8 and vital lint passed |
| Final asset packaging check | Both APKs contain the exact final controller bytes |
| Version and signing checks | 1.79/code73; installed signing identity retained |
| Family shipped PWA,390×844 | Interrupted preview recovered and decoded32×32;0 horizontal overflow |
| Completed-preview return | 2 interrupted-preview requests remained2 after another hide/show; decoded image retained |
| Failed preview → Retry | 2 requests;1 error-styled node before,0 afterward; successful feedback |
| Two-room identity | Both actual installed APKs exactly match the candidate SHA-256 |
| Persisted state | Complete profile, layout and screensaver configuration unchanged in both rooms |

The first manual request-count run included gallery reloads. The final count
isolated the preview by suppressing gallery viewport admission in the test page.
It did not change shipped code.

The real Family page and thumbnail server were used. Visibility events and the
503 response were controlled browser test inputs: this headless browser kept
documents visible during tab switching. Natural browser/phone backgrounding and
other browser engines remain unverified. The eight controller regressions cover
late completions and retired owners at the transport boundary. These results
establish bounded preview work, not universal latency or native slideshow speed.

The first slideshow invocation used incorrect source/label filenames and failed;
the exact CI command above was then discovered and passed. Promotion used that
successful gate, not the shell wrapper's earlier exit status.

## Installation and cleanup

Optimized APK:25,860,802bytes; SHA-256:
`c106703c63c93977dc748d77d4f106c1ab1b7814b8c636618405fdc5a2766bce`.
Package `com.immortal.launcher.debug`.
Both Fleet install connections closed during self-replacement. Neither initial
command acknowledgement was treated as success: version/code, complete installed
APK readback and served controller verification established installation.

Family's generated32×32gray PNG and its isolated public folder were removed.
The test modal and extra browser tab were closed; routes and injected browser
properties were restored. Family's font scale and enabled accessibility services
are unchanged, with no pending Binding; its original Chrome task is retained.

Installation interrupted display state. Family's original PhotoDream service
was initially restored, but the display subsequently reported OFF; Living's
normal Sleep operation also reported OFF. Both were then woken and returned via
the product's normal Screensaver action to `PhotoFramePreviewActivity`.
This is the manual photo-frame host, not proof of natural system Dream recovery
or a new optical night-clock/slideshow verdict. Saved sources and settings were
preserved. No global Meta configuration, saved device settings, account or credential was
changed; the normal wake/sleep commands add operation-history records.

No native companion update, Home Assistant change, GitHub authentication,
publication or Weather candidate deployment occurred. Held Weather1.78/code72
is separate; this delivery starts from the accepted1.77 lineage.

## Next confirmed defect

Independent review found that selecting the already-selected Portal under
“Your Portals” cancels a running two-photo import: `selectRoom` cancels uploads
before checking that the selected room/session is unchanged. This is a separate
UI selection defect, queued for its own30-minute fix/verification unit. It does
not reopen the parked partial-upload transport investigation.

Public evidence:
`mocks/portal-widget-gallery-20261102/evidence/photo-preview-recovery-verification.json`
and the generated-image-only `photo-preview-recovered.png`.
Private raw receipts remain in
`/private/tmp/portal-photo-preview-recovery-20261102/`.

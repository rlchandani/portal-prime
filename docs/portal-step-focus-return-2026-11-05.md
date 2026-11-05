# Step editor keyboard focus

Exact optimized **1.205/code199** is installed and fully read back in both rooms,
one install each. APK SHA256 `f9bbf8fb6eedf889c2a43bfa24848b0619c7adc2d25cef48d47a7b773f6b29a5` (25,887,623 bytes).
Both rooms serve the exact packaged controller.

An in-flight apps read can rebuild the scene or saved-preset editor while its
step dialog is open. Closing that dialog then left keyboard focus on the page.
The existing step's stable ID now identifies its replacement Edit button.
A deferred return checks the original session, draft, editor, visibility and
surviving step; an open replacement dialog keeps its own focus.
Callbacks, labels, reorder behavior, saving, transport and settings are unchanged.
Add-step dialogs retain their existing behavior.

Four frozen host cases reproduce two focus failures and two retirement controls;
all four pass after, with 17 previous action/keyboard controls also passing.
Six unchanged Chrome cases use assets extracted from actual 1.204/1.205 APKs:
two refreshed-editor failures and four controls become six passes. The fixture
captures the button actually clicked, proves it mounted before releasing the
held apps read and detached afterward, then awaits the native close event and
queued microtasks before checking focus. Stable and replacement-dialog controls
pass. Zero intercepted POSTs, outside requests or page errors in all six cases
against each artifact. These are six unique cases, not twelve.

The initial discovery observed transient hidden Close-button focus; an initial
browser setup missed the preset apps read, and a pre-click handle could be stale.
Those attempts and a cancelled host object-inspection attempt remain private.
Only the final frozen actual-click/close-event fixture supports browser acceptance.
It completed the definitive baseline at 06:20:24 UTC, after the discovery cap.
No clock or scope was reset.

Required debug/optimized/R8/vital checks passed in 96.84s.
All 4,739 JVM tests/493 suites and JavaScript gates passed: 404 slideshow and 786
remote Node-reported records, without a unique-leaf coverage claim.
All 23 main assets match source; the controller is the sole asset difference
from each like-variant baseline. Package/version and original signer are verified.
Independent source review accepted; root rehashed 42 source and 18 browser bindings
before promotion. The source review is scoped, not a whole-controller audit.

Family preservation: 43/43 byte-identical preference files.
Living preservation: 30/30.
Both preference file sets and four complete maps/revisions are unchanged.
No internal WebView difference was observed in this final comparison.
All captured app configuration is preserved.
Room names/Dev Mode and scoped Family font/accessibility/original Chromium
activity/resumed awake frame remain. Normal frame requests passed.
Temporary fleet records were removed; owner records are byte-exact.

Original 5 November clock: start 06:08:51 UTC, discovery cap 06:13:51,
target 06:23:51, hard limit 06:38:51. Final completion timing is recorded separately.
No physical phone, other browser/Android WebView, spoken accessibility,
real backend scene/preset Save/Run or full screensaver acceptance is claimed.
Public publication remains 1.83; the whole-product goal remains active.

Structured receipt: `mocks/portal-step-focus-return-20261105/verification.json`.

## Local Save step follow-up

Under the same 06:08:51–06:38:51 UTC issue clock, two additional generated
Chrome cases check Save step after the held apps read rebuilds the editor.
Both return focus to Edit step 1, retain the new 300ms wait and preserve the
second 200ms step. The expanded fixture passes all eight cases against the
same packaged 1.205 assets in 6.69s. The two new cases fail on focus against
packaged 1.204 in 1.79s. The original six-case baseline and evidence remain
unchanged; their fixture is available at commit `bf235910`.

Every request is intercepted; no POST, outside page request or page error
occurred. Save step only changes a generated local draft. Backend Save/Run,
physical phones and Android WebView remain untested by this fixture.
This follow-up adds test coverage only: no app version, build, device operation,
publication or roadmap status changes. Recorded build/device checks above
were not rerun. Evidence: `mocks/portal-step-focus-return-20261105/save-followup.json`.

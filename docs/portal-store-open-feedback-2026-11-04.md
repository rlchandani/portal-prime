# App Store launch failure feedback

App Store Open now reports **This app couldn’t be opened.** when resolving or
launching its destination fails. It uses the launcher’s existing message and
Toast duration. Normal launch intents and the existing Android app-details
fallback for an installed app without a launcher entry remain. Get, Update,
busy/compatibility policy, catalog and installer code are unchanged.

One Open callback replaces two discarded Results with one error boundary.
No mirrored test was added for this reversible feedback change. All **4,565 JVM
tests / 475 suites**, including 12 existing Store action-policy cases, and debug,
optimized, R8 and vital-lint gates pass in **107.38 seconds**. Independent review
reconstructs the exact whole-file replacement and verifies final APKs and all
XML results. Root fully read the review and rehashed 18 bindings and all475XML
before promotion. Source review establishes the failure-message call; it does
not establish visible Toast or actual Android launch acceptance.

Exact optimized **1.170/code164** is installed and fully hashed in both rooms,
one install each. Living’s uncertain acknowledgment was reconciled by six
read-only observations. Both installed APKs are25,886,927bytes with SHA256
`2fe4afcabd146cdef1867b41d5ad157321dfe7258b9752c5d766673603bc3e8a`.
All23source assets match1.169; only generated optimized baseline.prof changes.
All43Family/30Living preference files and four complete maps match preflight.
Saved Shuffle/fit, names and Developer Mode remain. Family font/services,
original Chromium Activity, resumed frame and awake state remain. Both frame
requests succeed; captured fatal/ANR markers are zero.

Actual app launch, app-details screen and Toast visibility are untested; no
household app was deliberately failed or launched. Installer/AppSwitcher/Jarvis
policies and prior held scopes remain separate. Camera/folder audits found no
fresh defect; the hypothesized To-do race lacked an active concurrent producer.
No phone, household media/source/provider write, account operation, GitHub push
or publication; public1.83/code77 remains.

Original clock **16:53:39UTC**, target **17:08:39UTC**, hard **17:23:39UTC**,
including discovery and closure; no reset. Evidence:
`mocks/portal-store-open-feedback-20261104/evidence/`.

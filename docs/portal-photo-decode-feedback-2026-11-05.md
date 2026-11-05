# Photo preview decode recovery

Exact optimized **1.204/code198** is installed and fully read back in both rooms,
one install each. APK SHA256 `09c938a52734e91134d17b189e040655ef345468c872af63514e68c3243da0b9`
(25,887,275 bytes). Both rooms serve the exact packaged controller.

When a browser cannot decode a downloaded thumbnail, the gallery now hides the
broken image and shows “Preview unavailable · Tap to retry”. The existing tile tap
opens a fresh preview. The preview dialog hides its broken image, shows an error
and reveals **Retry preview**; a valid response clears the error. Two image error
listeners reuse existing ownership checks. Request limits, caching, transport,
source selection, deletion admission, visible layouts and backend code are unchanged.

Four frozen controller cases reproduce two missing-recovery assertions before,
with two retired-view controls passing; all four pass after. Two unchanged Chrome
cases use assets extracted from actual 1.203/1.204 APKs. Both observe real browser
decode failures before their recovery assertions: two failures before, two passes
after, including decoded generated 2×2 PNG recovery. Zero intercepted POSTs,
outside page requests or page errors. These are two cases tested against two
artifacts, not four distinct cases.

The malformed HTTP 200 image/png response is a generated client failure input.
The current backend validates originals and encodes JPEG thumbnails, so ordinary
corrupt source handling is not alleged as a new backend defect. Gallery recovery
opens the modal; clearing its retained tile without rerender is not asserted.
Other browsers, phones, native rendering and full screensaver visual acceptance
remain unverified by this slice.

Debug/optimized/R8/vital checks passed in 99.96 seconds.
All 4,739 JVM cases in 493 suites passed. JavaScript gates passed with 404 slideshow
and 782 remote Node-reported records; no unique leaf coverage claim. All 23 main
assets match their sources; only the controller differs from each like-variant
baseline. Identity and the original signer passed SDK36 inspection. Independent
source review passed; root rehashed 17 source and eight browser bindings.

All 43 Family and 30 Living preference files are byte-exact in the final readback.
The first read caught three WebView cache bookkeeping fields changing; that
unsettled comparison was rejected, and a fresh complete read verified preservation.
Both file sets, four complete maps and revisions, names/Dev Mode
and scoped Family font/accessibility/Chromium activity and resumed awake frame
are preserved. Normal frame requests passed. Temporary fleet copies were removed,
and owner records remain byte-exact.

Original clock: **05:48:09 UTC**, discovery cap **05:53:09**, target **06:03:09**,
hard limit **06:18:09**, 5 November 2026. Actual Chrome baseline completed at
05:52:23, before the cap. Earlier task objects and clocks are retained.
Public publication remains 1.83; the whole-product goal stays active.

Structured receipt: `mocks/portal-photo-decode-feedback-20261105/verification.json`.

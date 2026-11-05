# Webpage screensaver swipes — 5 November 2026

Portal **1.184/code178** is installed and fully hashed in both rooms. Webpage
swipes now reach the document instead of starting the built-in photo feed.
Short taps retain tap-to-exit, and generic Next/Previous controls leave the
webpage active. Night-clock touch priority is retained in the controller and
child WebView. Other renderer navigation stays unchanged.

The same generated native fixture runs against the exact baseline1.183 and
candidate1.184 debug APKs on Android10/API29. It calls the actual controller
and WebView gesture entries using inline HTML and an executor that counts
submissions without running provider bodies. Both page gesture counts change
from zero to one each; controller photo submissions change from1 to0. Direct
WebView counts are cumulative (second gesture increments1 to2). Tap-exit1,
retained WebView identity and successful stop remain controls. Explicit
Next/Previous photo submissions change from1 to0. The corrected fixture
source is retained under the linked proof directory for repeatable regression
checks; its Java and manifest hashes stay identical before/after.

All **4,616 JVM tests /481 suites** pass with no failures, errors or skips.
Debug, optimized, R8 and vital lint pass; the final corrected build took89.27s.
Source review caught and resolved a night-priority issue before the final build
and native gate. All23 source assets are unchanged. Both installed optimized
APKs are25,886,947 bytes, SHA256
`7a77548d2ccdf1ec2b14fb7db4609e2dfea4634ee5db97e37cce6e980dbd305d`.
Living's uncertain acknowledgment resolved through7 read-only observations;
there was one app install per room.

Final43 Family and30 Living preference files are byte-identical, and all four
configuration maps—including layout revision—match. An earlier Family capture
failed the exact-byte assertion on two WebView cached flag removals; the later
read-only capture matches without restoration writes. Original room names,
Dev Mode, Family font/accessibility/Chromium task, resumed frame and awake state
remain. Final captured diagnostics have zero fatal/ANR markers. Generated
fixtures are uninstalled and copied credential registries retired.

This is generated gesture evidence at the private WebPage entry with real
fixture-owned Classic configuration loading. Normal startup, mounted Dream/
Preview dispatch, native night input, actual dashboards, optimized gesture
execution, API28 and Living native interaction remain unverified. The first
fixture's default native renderer setup was excluded from the photo-submission
witness; Classic setup was corrected and frozen before production edits.
No phone, household provider, account, authentication, push or publication work.
Public release metadata stays1.83.

Original discovery clock **23:48:21 UTC4 November**, target **00:03:21**, hard
**00:18:21 UTC5 November** retained. The15-minute target was missed. Fixture
preparation exceeded its cap and was cut; no held issue clock was reset.
Issue264's old executor-crash witness was discarded because current active
main-thread guards and the terminal callback queue block it. This delivery
addresses the distinct public issue222. Web zoom and other held screensaver
work remain parked.

Machine-readable evidence: `mocks/portal-web-page-touch-20261105/verification.json`.

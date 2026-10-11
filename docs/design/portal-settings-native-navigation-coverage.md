# Family Area: native Settings navigation coverage

This follow-up extends the existing read-only UI inspector to recognize fixed
Settings category and embedded-page titles. It enables native navigation checks
on the optimized benchmark APK. It does not change Settings rendering,
animation, persistence, layout, navigation or Home input eligibility. It is
verification work, not a new measured performance improvement.

## Implementation and build gates

`HomeUiDiagnostics` exports only predefined keys and native bounds. Unknown
labels, editable/password fields and stored values remain excluded. Four new
JVM tests cover static titles, shared category/page labels, unchanged input
eligibility and private-value exclusion. The full gate passes **1,732 tests in
198 suites**, with zero failures, errors or skips. Debug, benchmark and
hash-pinned bootstrap builds succeed.

The benchmark retains native R8 and disabled Settings UI/pane observers. It has
one DEX, the same package/certificate and non-debuggable build type. UI resources
and assets match the accepted APK except for the compilation profile rewritten
for its changed DEX. Bundled and device reference profiles resolve to the APK's
own DEX: 8,596 methods, 2,553 classes, including 246 app methods and 64 app classes.
Mapped OAT170 output uses `speed-profile`, reason `install-dm`. This structural
verification does not prove compilation of every individual method.

Candidate SHA-256:
`40b9bedef7384ae33cd88871421df380502c79d57fbef4d5b2b2d7ca9aa9f389`.
Prior accepted SHA-256:
`a622b1faa1c31fa1329c33929464bef735e227361c728451f9c0a2d4064d2aae`.

## Native check scope

The bounded plan visits seven normal category overviews and 23 embedded pages.
It verifies a native page heading, Back returning to the category, and the same
Settings window within each execution segment. Advanced overview is visited
only if already enabled. It does not edit settings, open system-settings links,
start calls/audio, enter credentials, capture timings or operate Living Area or
the phone. It does not establish every setting's functionality, all conditional
states, long-run stability or performance of every category.

Three interrupted reports remain preserved with `completed:false`:

- `preflight-only`: no foreground window while the idle device reported DREAMING;
  zero device mutations. A narrowly guarded Home action normalizes this native
  QA admission; the app's screensaver recovery policy is unchanged.
- `transition-stop`: the first observation after entering Settings landed during
  the activity handoff. Read-only navigation waits now defer only known handoff
  states; dispatched taps and owner checks remain strict.
- `wallpaper-heading-stop`: three native checks passed before Wallpaper's page
  title and section label made the test's single-heading requirement ambiguous.
  The corrected selector requires a unique topmost fixed title. The continuation
  reuses those three checks and does not repeat them. Each segment has its own
  Settings window; it is not one uninterrupted session.

Eight focused offline checks pass, including ambiguous recovery never being
repeated, lease-owner publication, restricted idle-Dream admission, handoff
classification, and duplicate-heading disambiguation. Original reports and
runner inputs are pinned by hashes. Any failed sweep restores the exact accepted
APK, compilation profile and unchanged configuration.

Evidence root:
`~/portal-prime/build/fleet/diagnostics/settings-native-navigation-coverage-2026-10-11/`.

## Result

The terminal report is **completed:false**. Sixteen checks pass: four category
overviews and 12 embedded page/Back visits. Three checks are reused from the
hash-pinned earlier segment; 13 execute in the continuation. The pending case is
Sound & wake → Welcome greeting, which stops on `heading_not_verified`. This is
a validation gap, not an established app defect. Three normal overviews and 11
page visits remain unverified. Advanced overview was not reached. The continuation
including recovery completes in 138.94 seconds; no further attempt is started.

The read-only-inspector candidate is **not retained**. Family Area is restored
to the prior accepted `a622…` APK with exact SDK/bundled profile verification,
`speed-profile`/`install-dm` OAT output, unchanged settings, Home restored and
collector inactive. There is no restoration failure. Living Area and phone
operations, setting edits and timing captures are all zero.

No additional frame measurements were taken.
The historical R8 gain remains the matched result documented in
[the R8 comparison](portal-settings-native-r8-candidate.md); it must not be
attributed to this inspector change. The six-hour deadline remains missed and
the broader performance goal remains incomplete. Cascading columns remain a mock.

## Completed follow-up on the remote-Back fix

The original incomplete reports above remain unchanged. A subsequent functional
revision routes paired Back through the existing local Settings history for a
resumed, focused subpage. A fresh sweep on that revision passes all 31 checks:
23 page/Back visits, seven normal category overviews and the already-enabled
Advanced overview. It completes in 102 seconds and verifies final Home, unchanged
configuration, inactive collector and exact APK/profile. The new `c862…` artifact
is retained on Family Area. This establishes the planned navigation coverage,
not all settings functionality or a new speed gain. See
[the implementation and native record](portal-settings-owned-back-validation.md).

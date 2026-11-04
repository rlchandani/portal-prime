# Screensaver exit destination label

When a saved Home Assistant exit target is no longer installed, the picker’s
main row now says **Immortal launcher**, matching its existing explanation and
launch fallback. The correction uses the current successfully loaded app
inventory; initial loading, failed loads and pending revisions retain the
previous label. Installed Home Assistant, plain-app choices, saved settings,
selection actions and launch behavior remain unchanged.

This is one conditional insertion in `ScreensaverDismissAppActivity.kt`.
No new mirrored test was added for this reversible text correction. The five
existing app-inventory tests pass, including absent HA, installed HA without a
curated launcher entry, invalidation and failed inventory reads. All **4,565 JVM
tests / 475 suites**, debug and optimized builds, R8 and vital lint pass in
**111.13 seconds**. Independent review verifies the exact source delta, all
475 XML results and APK payloads. Root read the complete review and rehashed
its 17 direct bindings and complete XML aggregate before delivery.

Exact optimized **1.169/code163** is installed and fully hashed in both rooms,
one install each. Living’s uncertain acknowledgment was reconciled through
seven read-only observations. Both installed APKs are 25,886,927 bytes with SHA256
`56a31c43d56bf6bd3ad3cf70f3189afccfddbbafd422376ec8cf3a8ca2370087`.
All 23 source assets match 1.168; only the generated optimized baseline profile
changes. All 43 Family and 30 Living preference files and four complete maps
match preflight. Saved Shuffle/fit, names and Developer Mode remain. Family’s
font, accessibility services, original Chromium Activity, resumed frame and
awake state are retained. Frame requests succeed in both rooms; captured
fatal/ANR markers are zero.

The label and loading branches are source reviewed. No rendered picker,
actual app uninstall/reinstall, HA dashboard, fallback launch, API28 or Living
visual acceptance is claimed. The separate plain-app “Chosen app” fallback is
unchanged: absence from the curated inventory alone does not prove an exact
saved component is unavailable. No phone, household media/source/provider write,
account operation, GitHub push or publication occurred; public 1.83/code77 remains.

Original clock **16:39:11 UTC**, target **16:54:11 UTC**, hard deadline
**17:09:11 UTC**, including discovery and closure; no reset. Earlier scopes retain
their clocks. Evidence: `mocks/portal-dismiss-fallback-20261104/evidence/`.

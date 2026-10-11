# Family Area: reliable remote Back in Settings

The paired remote previously sent Android accessibility Global Back even while
Immortal owned the focused Settings subpage. Welcome greeting twice remained
visible after an acknowledged request. A targeted observation showed its title
was visible; it rejected the title-scroll hypothesis. The source confirms that
platform acceptance does not establish delivery to the activity. The exact
platform-delivery failure remains unidentified.

Remote Back now uses the same local history handler as the visible Settings
Back button when a live, focused Settings host owns a subpage. It adds no Activity,
rendering observer, setting, animation or retry. This is a navigation reliability
fix, not a new measured frame-time improvement.

## Routing and safety

The pinned manager still chooses whether to dismiss a cover first. Only its
existing main-thread fallback can dispatch local Settings Back. Admission reuses
`ApprovedHomeHostRegistry` and the existing window eligibility policy: resumed,
focused, not finishing/destroyed, no owned dream surface or stock handoff. Pause
unregisters the weak host; replacement during admission is rejected. With no
eligible host or on a Settings overview, the previous Android fallback remains.
A handled subpage Back does not also send a global Back.

The four new tests exercise one-level history navigation and overview fallback,
missing/paused/ineligible hosts, replacement during admission, and no repeat after
a failing handler. The initial test build failed because the dispatcher was absent.
The first full build caught two existing pinned-route JVM fallback tests reaching
an unnecessary Android Looper call before live-host admission. Moving that check
inside admission fixed both; the existing fallback tests remain unchanged.

Full unit gate: **1,736 tests in 199 suites**, zero failures, errors or skips.
Debug, native-R8 benchmark and target-pinned bootstrap builds pass. An independent
source review found no actionable issue; native verification remains separate.

## Native result

The new APK completes a fresh Family Area sweep in **102 seconds**, including
installation and compilation verification. All **31 checks pass**: seven normal
category overviews, 23 page/Back visits, and the already-enabled Advanced overview.
Each page receives exactly one paired-remote Back request; its expected category
heading and navigation row then reappear in the same Settings window. Welcome
greeting passes. No uncertain input is retried and no prior APK's checks are reused.

Final Home is verified through two fresh matching native observations with a
visible dock. Exact installed APK, complete bundled/reference profile sets and
DEX checksums, unchanged configuration/photo sources, and inactive collector are
verified. The candidate remains installed. Living Area operations, phone-device
operations, setting edits and timed captures are all **zero**. Remote requests use
the same API as the phone; this does not retest the phone client's entire UI.

An earlier UI-Back continuation passed all 31 navigation checks but remained
`completed:false` after its Home guard compared against a stale Settings window
identity obtained immediately after requesting Home. It restored the accepted
APK. The new driver verifies the actual Home dock and stable current window;
nine offline policy checks pass. The original report remains incomplete and is
not promoted retroactively.

This verifies the planned scalar/embedded-page routes, not every setting value,
conditional control, popup, credential editor, external Activity, keyboard case,
physical system Back button or long-run behavior. Native cascading columns remain
unimplemented. The broader performance goal is incomplete and the original
six-hour deadline remains missed.

## Artifact and evidence

Installed APK: 25,386,112 bytes, one DEX, non-debuggable,
`com.immortal.launcher.debug`, same signing certificate as the accepted build.
SHA-256: `c8625fa9a4461626a3825a325d97b0da98508cb4a584ad48cb1914f17083a81a`.
Bootstrap SHA-256: `d8ecffd3bb63bbcc18fb91bb94e8f60cc66af1f8d9833a7bb593be2faa3218a1`.
UI assets/resources match the accepted build except for the rewritten compilation
profile. Its own profile resolves to 8,595 methods and 2,553 classes, including
246 app methods and 64 app classes. Mapped arm64 OAT170 output uses
`speed-profile`, reason `install-dm`; individual method compilation is not proven.
Native R8 remains enabled and Settings UI/pane rendering observers remain disabled.

The historical 27.779 ms R8 median belongs to the earlier measured `a622…` APK.
No new frame comparison was run for this functional revision.

Private evidence under `~/portal-prime/build/fleet/diagnostics/`:

- `settings-welcome-focus-observation-2026-10-11`: fixed-key title/focus observation
  and exact accepted recovery.
- `settings-native-ui-back-coverage-2026-10-11`: all 31 UI-navigation checks,
  incomplete final Home guard and exact accepted recovery.
- `settings-owned-back-2026-10-11`: frozen build/source admission, unit reports,
  complete native remote-Back result and retained installed APK/profile verification.

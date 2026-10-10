# Pinned app session validation — 2026-10-09

The feature preserves an already-running external app window beneath Immortal's
photo screensaver. It never relaunches that app on an ordinary return. The pin is
an in-memory package/window identity, not a persisted app-launch preference or
Android kiosk lock. The user can pin/unpin from the revealed system top bar;
Home or deliberate app navigation ends the pin.

## Final candidate

Branch: `codex/portal-pinned-session`, based on `cedaa0c`.
Package: `com.immortal.launcher.debug`, version code 58 / version 1.64.

| Artifact | SHA-256 |
| --- | --- |
| Installed benchmark APK, both rooms | `1056709de5e94cd9831b6ad29dd270596719330034f1cf007a63cfafad4ceae9` |
| Debug APK, disposable emulator | `4c91ef054a1a84848ae817d69aa7a29120f1a10050f6ea1c4c5ce868bb3784c1` |
| Android-test APK | `2d65123c1f0fd3e02e39bdfffab822fd6ab628993ab56cbdb3e76ca4016c7dfe` |

Both hardware installations verified the installed APK hash. No GitHub
connection, push or authentication was used. Fleet credentials and private
snapshots remain outside version control.

## Automated checks

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark
:app:assembleDebugAndroidTest --offline` passed. All 1,098 JVM tests passed,
with zero failures, errors or skipped tests. Tests cover metadata-only window
reading and pooled-object recycling, exact identity changes, lost/expired guards,
main-thread admission and cancellation, navigation races, dream ownership,
bounded surface retirement and failed cleanup, and dismiss-app bypass.

`PortalUxInstrumentation` with `command=pinnedApp` passed 176 assertions and
three cover/wake cycles in 18.647 seconds on an owned API 34 emulator. The external
test Activity/WebView retained its document UUID, Activity/task identity, form
value and scroll position without a second creation or new intent. Covered wake
taps did not reach its native or DOM handlers; ordinary uncovered taps did.
Cleanup restored generated factories, secure settings and photo-source settings.
The emulator was then stopped and its disposable state discarded.

The emulator explicitly skips native-Dream parsing on newer Android versions.
Native-Dream coverage comes from the physical API 29 Portals below. The test's
existing default public photo feed may request network data; it is not a household
photo fixture and does not establish photo-source correctness.

## Physical Portal checks

Both rooms are Portal+ devices at 2160 × 1440 on Android 10/API 29. Generated
Chromium documents were used rather than exporting household content.

- Ten final continuation-cover/wake cycles per room, alternating actual injected
  tap and paired-remote Back: all 20 passed. Each preserved the browser window,
  document boot/load identity, form value and scroll position, with zero underlying
  DOM clicks and zero pending cleanup jobs after return.
- A missing app launch left the pin and cover intact. Deliberate Apps navigation
  cleared both without a recovery loop. A fresh generated document then provided
  the baseline for the native idle-Dream checks.
- Actual pin/unpin button taps passed in both rooms, preserving their browser
  window identity. A temporary persistent system-bar setting made the control's
  geometry measurable and was restored exactly afterward. This check proves the
  control accepts taps; it does not claim that injected swipe gestures reproduce
  every physical system-bar-reveal gesture.
- Both naturally entered Immortal's native photo Dream within the normal idle
  window. Living Area passed direct native tap return. Family Area passed
  system-Back interruption, owned continuation cover and subsequent tap return.
  Both retained the same browser window/document, form value and scroll position,
  with no underlying DOM click. These checks ran on the final installed APK.

The user's original dashboard URL was restored immediately after each explicit
fixture launch. No household photo bytes or accessibility text/tree were exported.
Window diagnostics read package, window ID and focus/active metadata only. Pin
control diagnostics inspect only Immortal's own View geometry and selection.

## Performance scope

The ordinary reveal path does no browser launch, network request, shell command
or photo decode. The nonfocusable continuation cover becomes hidden before its
captured renderer/window cleanup runs. Only one live cover is owned; failed
removal remains visible to diagnostics and blocks creation of additional covers.

| Room | Hide code path median / max | Cleanup median / max |
| --- | --- | --- |
| Family Area | 2.242 / 3.699 ms | 18.243 / 21.841 ms |
| Living Area | 2.580 / 3.601 ms | 18.429 / 33.169 ms |

These are in-app dispatch/cleanup measurements across ten final cycles per room,
not compositor frame timing, visible input latency or a guarantee about external
browser performance. HTTP observation time includes polling and network delay.

## Findings resolved during development

Independent review identified window recycling and eligibility, stale remote
navigation decisions, uncertain admitted commands, known-loss return barriers,
native-Dream ownership, retirement tracking and night/morning cover policy
issues. Dedicated regression tests cover the corrected cases. Final read-only
review of the control/selection changes found no actionable regression.

The first overlay removal did synchronous renderer/window cleanup before reveal;
hardware observations motivated hide-first retirement. A Family Area control test
then found that the overlay was positioned below a persistent system bar, and an
API pin command did not immediately publish selected state. The final build
anchors the control to the screen edge and updates selection immediately. Actual
button taps and the new runtime selection assertion pass.

Earlier failing attempts remain in private evidence: guessed tap coordinates,
tests entered while a native Dream was covering the control, and a check that
incorrectly assumed a pin survived installation. The latter was rerun after
explicitly pinning again. None is counted as a passing final test. The standalone
runtime fixture also needed its own correctly loaded test process and an explicit
newer-Android native-Dream scope guard; the final test result above includes those
repairs.

## Cleanup and limitations

Final comparisons on both rooms confirmed the full original screensaver JSON
unchanged and the original dashboard URL restored. The pin-button checks restored
the complete Immortal settings domain after the temporary bar change. Cleanup
returned both devices Home with no pin, native/continuation cover, pending or failed
retirement. Both performance collectors were inactive, with zero active listeners.
The normal dashboard destination was deliberately opened before returning Home,
without inspecting its private webpage. The generated fixture server and the owned
emulator were stopped. Test APKs were installed only on that disposable emulator.

Android may kill/recreate an external app, and a browser may independently reload
its renderer. Immortal prevents its own relaunch on return; it cannot promise
external process immortality. Pin state expires on Immortal/device restart or loss
of accessibility ownership. A known lost/expired session shows a local Go Home
message instead of reopening the app automatically.

The change uses Immortal's photo Dream and its continuation curtain; it does not
claim to change unrelated clock Dreams or Meta's own screensaver. No physical
Pixel test was performed. MkDocs strict rendering could not run because the
available local Python environments do not contain MkDocs. App/test builds,
Markdown links and diff checks are verified separately.

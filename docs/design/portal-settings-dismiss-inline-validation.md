# Slideshow exit destination: use the existing Settings pane

The `dismiss` route previously declared `ScreensaverDismissAppActivity` as its launch destination.
The local Settings navigator therefore launched another Activity for the registry's
"Open when you tap to exit" row. That created a second window/shell instead of continuing
inside the approved two-pane Settings layout.

The route now has an inline destination while retaining the standalone Activity identity for
registry routing and direct launches. `ScreensaverDismissContent` extracts the existing editor
body; the Settings host and standalone wrapper both call it. The inline path uses the host's
existing title and Back/Forward history, without a nested shell or its own BackHandler.
The existing target, installed-app and advanced HA-path dialogs, setters, curation, search,
selection behavior and launch behavior are unchanged. No preference, remote scalar or schema
was added. Dialogs still use their normal app-owned windows; that is separate from a second
settings Activity.

The existing app enumeration/icon batch stays on Dispatchers.IO and uses the application Context.
The rich pane disposes on departure, cancelling its coroutine and preventing a late result from
publishing. The synchronous batch does not cooperatively interrupt each PackageManager call;
this revision does not claim to improve its scan algorithm or make every service call cancellable.
A fixed body marker is published only after the real app enumeration/icon result is available.
Readiness on the registry navigation row is separated from its clickable static title, avoiding
ambiguity between a loaded value and an actual navigation target. Public markers export no chosen
app, app-list text, saved path or credentials and do not grant Home input eligibility.

## Evidence

| Requirement | Result |
| --- | --- |
| Additional settings Activity launch on this route | Before: one from the route/dispatcher; after: zero, verified with the same native Settings window |
| Debug and optimized non-debuggable benchmark builds | Passed |
| Full JVM gate | 1,786 tests / 204 suites; zero failures, errors or skips |
| New regressions | Inline route/registry/search agreement and fixed public-marker privacy; both failed before implementation |
| Native sequence | 9 checks passed in 57.14 seconds, including deployment/profile verification |
| Loaded registry destination / actual app choices | 2 / 3 verified visits |
| Chooser dialogs | Target dialog and installed-app dialog opened and closed without selecting a destination |
| Return and history | Original Settings window after chooser close, Back to Slideshow, Forward to the inline editor |
| Setting edits / Living Area / phone operations | 0 / 0 / 0 |
| Final state | Candidate retained; configuration/source snapshots unchanged; Home restored; collector inactive |

The initial bounded native trial passed six checks, then rejected the still-closing app chooser
immediately after its close tap. It restored the prior accepted artifact, confirmed unchanged
snapshots/Home/inactive collector, and remains preserved at `settings-dismiss-inline-2026-10-11`.
The runner had conflated gesture dispatch with completion: the app uses `dispatchGesture` with a
null completion callback, whose boolean only confirms dispatch. Android documents this behavior:
https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture
and https://developer.android.com/reference/android/accessibilityservice/AccessibilityService.GestureResultCallback.

The corrected return wait admits only the particular app-owned dialog window already observed
and just closed, for the bounded deadline. It waits for two stable observations of the original
Settings window plus actual editor readiness. An unknown window is still rejected immediately;
a dialog that never closes times out. Three additional offline tests cover those cases, bringing
the preserved guard suite to 38 passing checks. On the sole retry, the known closing dialog was
observed once before the original window returned. The same immutable APK/build/source was reused;
there was no further app change or timing experiment.

## Artifact and scope

Candidate: `a2a8d10a7c39bef22f7a5fc7839a25dfe8b41619ce215fe31f16e9472d5e942f`.
Bootstrap: `e0c883367aa0eea81b76c8482567f2ebae1cdba18b6e01c75d08eb2943cc4c98`.
Successful postflight: `88a2763446bd5c4b54de87303b8b61f3babff783289827bdc4453304895ccfe1`.
Initial failed-trial postflight: `38a3cd1b8ad32332e00a04a10fa2e8b25766d8bd0aa9912862493e0dbce8693c`.

The APK remains 25,402,496 bytes, one DEX,
`com.immortal.launcher.debug`, and signed with the existing certificate. Its profile maps
8,648 methods / 2,569
classes, including 285 app methods /
75 app classes. Exact installed/bundled/reference
profile sets and DEX mapping passed; own OAT metadata reports speed-profile / install-dm.
Individual app-method compilation is not proven. The historical six-symbol mapper gate retains
old R8 names and is not the admission gate; complete resolved sets are checked instead.

This is a bounded Settings continuity improvement. No new launch-latency, frame-time percentage,
universal 60 FPS, destination-selection/HA-path functionality, direct standalone launch, or
long-run stability claim is made from this sweep. Those unchanged selection paths were deliberately
not invoked because the sequence preserves the selected destination.

The issue began at 22:03:22 UTC and completed inside its 30-minute bound. The broader performance
goal remains incomplete. Evidence roots:
`~/portal-prime/build/fleet/diagnostics/settings-dismiss-inline-2026-10-11` and
`~/portal-prime/build/fleet/diagnostics/settings-dismiss-inline-return-2026-10-11`.

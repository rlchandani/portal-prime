# Slideshow destination label: resolve Android services outside composition

The registry's `screensaver.dismissTarget` navigation row previously called its current-value
getter directly during Compose rendering. `ScreensaverDismiss.chosenLabel` reads the selected
configuration and may query PackageManager and load another application's label/resources.
For an installed configured app, its existing resolution path performs two `getActivityInfo`
queries and one `loadLabel`. With no selected app there are no such package queries. The native
trial does not export the selected destination or claim which conditional branch ran.

`NavSpec.valueOnWorker` is an opt-in UI execution policy, defaulting to false. Only this
service-backed destination label opts in. Its existing getter, Activity destination,
visibility predicate and saved configuration remain unchanged. Snapshot-only navigation labels
still render immediately. `NavSpec` does not publish a remote scalar or metadata, so the wire
schema and apply boundary are unchanged.

The Compose renderer uses `produceState`, keyed to the immutable snapshot, owner and pane
activation. A resumed active pane resolves the real label with `Dispatchers.IO`; it displays
Loading while awaiting that result, and Unavailable on an uncaught lookup failure. It refreshes
when the owner resumes and does not poll while idle. Leaving cancels the producer: a blocking
Android service call may finish in the background, but cancellation prevents its late result
from publishing into the departed pane. The application Context avoids retaining an Activity
inside that service operation. No global label cache is introduced.

Fixed public accessibility markers identify the actual Slideshow list viewport and the label's
Ready/Unavailable state. They export no chosen app name, dashboard path, stored value or image.
The bounded device runner allows navigation only, with at most three vertical gestures in an
observed viewport per ready visit and three to restore the heading; this successful run needed
no gestures. It never taps the destination picker or changes a setting.

## Verification

- The caller-thread and opt-in policy tests failed before implementation.
- Five new JVM tests cover the worker thread, policy scope, error state, cancellation propagation,
  and prevention of an in-flight result publishing after departure.
- Full debug and optimized/non-debuggable benchmark builds passed.
- Full JVM gate: 1,784 tests in 204 suites;
  zero failures, errors or skips.
- The native driver passed 27 offline tests, including nine preserved safety guards.
- Family Area passed 5 native checks in 73.28 seconds, including
  deployment/profile verification. It verified the actual loaded destination twice, initial
  entry and after Back/Forward. All transitions retained the same Settings window.
- Configuration and photo-source snapshots remained unchanged. The candidate is retained,
  Home restored and the collector inactive. There were zero setting edits and zero Living Area
  or phone operations. The previous accepted Wallpaper build was pinned for at most one rollback;
  no rollback was needed.

## Artifact

Package remains `com.immortal.launcher.debug`, signed with the existing key. The candidate is
25,402,496 bytes and has one DEX.

Candidate SHA-256: `71f1c26045f00466aa42295c5e029582c966ae7a52502724efdce4761b06dada`.
Bootstrap SHA-256: `439e4d505cb5a55e74b5cf7e9e1dad8e17da5845c2dea27baa3cac2f7f54004d`.
Postflight SHA-256: `5d75507a148545acf838f47f18d97461afc52da2508277a8126647081622d1c2`.

Its profile resolves 8,646 methods and
2,568 classes, including
285 app methods and
75 app classes. Exact bundled/reference profile
sets and DEX mapping were verified against the installed candidate. Own OAT metadata reports
speed-profile / install-dm; compilation of individual app methods is not proven. The historical
six-symbol profile gate uses old R8 names and is not the admission gate for these renamed symbols.

The change removes this getter from the rendering thread; no new measured frame-time reduction
or universal 60 FPS claim is made. It does not change navigation of the destination picker.
The approved two-pane layout and distinct category/editor names are preserved; see
`portal-settings-history-two-pane-validation.md` for their earlier 20 native checks.

Evidence: `~/portal-prime/build/fleet/diagnostics/settings-nav-values-2026-10-11`.
This issue began at 21:48:21 UTC and finished within its 30-minute bound. The overall performance
goal remains open.

# Retained Home navigation candidate

API Home/Apps requests formerly sent a HomeActivity Intent even while that same host was
resumed and focused. CLEAR_TOP | SINGLE_TOP reuses the instance but still delivers onNewIntent
and an Activity pause/resume cycle. Home's resume hooks refresh preferences and widgets and
run the existing handoff, screensaver and sleep policy. Finger dock taps already select the
retained tab directly. This candidate makes foreground API requests use that state path.

The supplied physical baseline for a Home/Apps-only 60-second, 20-cycle run was Home host p95
91.75 ms (Family) / 71.5 ms (Living), mean layout 0.12 / 0.15 ms, and mean animation 10.45 /
8.87 ms. Those figures motivate investigating the navigation boundary; they do not prove this
candidate improves rendering. No physical after measurement was taken by this sidecar.

Before, every request crossed the Activity boundary:

```kotlin
startApprovedInternalActivity(Intent(this, HomeActivity::class.java)
    .putExtra(APPROVED_HOME_TAB, tab.id)
    .putExtra(APPROVED_HOME_SEARCH, searchApps)
    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
```

After, a main-thread eligibility decision chooses direct retained state or the original Intent:

```kotlin
ApprovedHomeNavigation.dispatch(approvedNavigationActivity(), tab, searchApps) {
  startApprovedInternalActivity(Intent(this, HomeActivity::class.java)
      .putExtra(APPROVED_HOME_TAB, tab.id)
      .putExtra(APPROVED_HOME_SEARCH, searchApps)
      .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
  true
}
```

Direct selection changes only launcherTab and, for search, increments appsSearchOpenRequest
once and selects Apps. Intent consumption shares that state application. The existing page
lifecycle and slide animation still run when a tab changes; their visuals and duration are
unchanged. Full Home resume hooks remain owned by real Android resumes.

## Admission and ownership

- A weak registration is published after a real Home onResume completes its existing policy
  hooks. Real onPause and onDestroy invalidate it. A recovered Meta bootstrap resume never
  registers a target. Old lifecycle callbacks cannot clear a replacement host.
- Direct selection requires the captured registration to remain current, lifecycle RESUMED,
  window focus, and an Activity that is neither finishing nor destroyed. An active stock
  handoff also excludes it. An Activity caller must be that same Home; service callers may
  select the foreground host. A prior registration cannot become valid after pause/resume,
  even when the Activity object is the same.
- An unfocused Home under another app, owned frame, Meta surface or dialog uses the existing
  Intent fallback. Services retain NEW_TASK; Activity and wrapped Activity callers retain
  their caller task. CLEAR_TOP | SINGLE_TOP and both navigation extras are preserved.
- Owned Dream/preview dismissal stays at Intent consumption through
  ImmortalDreamNavigation.dismissForNavigation. This candidate adds no system/Meta Dream
  dismissal and does not clear Calls suppression from a direct tab request.
- Requests with a retained target marshal preparation and navigation through a main Handler.
  The decision has no navigation side effects. A monotonic two-second deadline bounds the
  worker wait. Rejection, interruption or expiry cancels queued/preparing work and removes its
  callback; even an already-dequeued callback cannot navigate after cancellation.
- Admission happens once before the deadline. A request already admitted returns accepted
  (true) if its caller's wait ends before Android returns, so it cannot invite a timeout retry
  or launch a second fallback. A completed navigation failure returns false. No fallback is
  attempted after a direct action begins. A cold/paused host with no registration keeps the
  original Android startActivity boundary, which can be called from a service/HTTP worker.

The foreground route also skips the incidental preference/widget refresh formerly caused by
sending an Intent. It therefore behaves like a dock tap. Home's existing real-resume refresh
and the presentation observer remain intact; this candidate adds no new settings or widget
refresh policy.

## Validation and handoff

The initial focused RED run compiled and failed 12 of 23 tests, including direct retained
selection, search and queued cancellation. A later RED test reproduced acceptance of an old
registration during a pause/resume of the same Activity; checking registration generation
fixed that edge. A subsequent Gradle run lost its in-progress results file while the shared
results directory was replaced with web-worker test results; it is not counted as validation.

Final focused verification passed all 50 tests (28 new dispatcher/boundary tests and 22
existing navigation/continuity tests), with zero failures or errors. It used the checkout lock
wrapper, offline:

```sh
python3 -B /private/tmp/portal-companion-gradle.py \
  ~/.codex/worktrees/portal-companion-api/portal-prime \
  /private/tmp/portal-companion-home-navigation-verified.log \
  :app:testDebugUnitTest \
  --tests com.immortal.launcher.ApprovedHomeNavigationDispatcherTest \
  --tests com.immortal.launcher.ApprovedHomeNavigationBoundaryTest \
  --tests com.immortal.launcher.RemoteAppsLaunchTest \
  --tests com.immortal.launcher.RemoteRoutesNavigationTest \
  --tests com.immortal.launcher.ImmortalDreamNavigationTest \
  --tests 'com.immortal.launcher.Approved*ContinuityTest' --offline
```

The new tests execute the production dispatcher and Home's real Compose state application.
Only Android lifecycle/window/Intent/Handler boundaries are doubled; no Activity lifecycle
callbacks are simulated. Coverage includes fallback task flags, owned-frame handoff, stale
identity and registration, queued focus loss, rejected/expired/interrupted dispatch, expiry
while checking focus, admission races, and exactly one search increment per request.

The parent-requested full verification also passed on 2026-10-09: 946 unit tests across 139
classes, zero failures/errors/skips, and :app:assembleDebug. Both tasks ran together through the
same wrapper with --offline; the log is /private/tmp/portal-companion-home-navigation-full-build.log.
The APK is app/build/outputs/apk/debug/app-debug.apk (46,746,231 bytes), SHA-256
0dae37a3248801fca8ffe80eecf946d96075f910267d844de0caa54a5d15952c. This debug artifact
includes the checkout's concurrent PWA work and uses the existing .debug application ID.

Parent owns deployment and the physical comparison against cdaf954 using the same
60-second/20-cycle tabs script. Physical validation must establish actual Home
focus/lifecycle registration, retained host and Apps visibility,
preview/Dream fallback, Calls continuity, and the same 60-second/20-cycle measurement on the
same Portals. If any foreground ownership or cancellation invariant fails on hardware, retain
the Intent path and do not ship the fast path. Focused JVM tests alone establish neither a
physical speedup nor Android window ordering. No known production ownership/cancellation
blocker remains in the focused candidate.

Changed source is limited to HomeActivity.kt, ApprovedHomeNavigation.kt and the new
ApprovedHomeNavigationDispatcher.kt. New tests are ApprovedHomeNavigationDispatcherTest.kt
and ApprovedHomeNavigationBoundaryTest.kt. This new note is the only documentation change.

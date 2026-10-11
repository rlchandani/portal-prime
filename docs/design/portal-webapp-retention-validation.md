# Music: retain the existing WebView task when returning Home

WebAppActivity previously used the default standard Activity and launcher task affinity.
Both existing Music launchers send NEW_TASK. HomeActivity uses singleTask, so returning Home
could clear the Music Activity above it; another tile activation could create a new Activity,
WebView and document. A remembered Java/Kotlin object alone cannot prevent Android task clearing.

The Music Activity now uses singleTask with its own `${applicationId}.web` affinity.
Its onNewIntent compares the requested entry URL with the previous requested entry. A repeated
entry reveals the existing view without loadUrl; a different entry navigates the same view once.
The comparison deliberately ignores the current WebView URL, because redirects and subsequent
in-site navigation must not turn a normal tile return into a reload. Only one web-app task/view
is retained. Existing Back behavior still follows page history before closing the Activity.

WebView pause/resume calls stay on its creation thread. Global pauseTimers is never used, since
it would also stop other WebViews in the app. Actual destruction stops loading, detaches and
destroys the view. Process death or Android memory eviction still requires a new cold document;
this change does not promise to retain a page against the OS's memory policy.

## Verification

- The three entry-policy regressions failed against the previous always-load behavior, then passed.
- Full debug and optimized benchmark builds passed: 1,803 JVM tests / 208 suites,
  zero failures, errors or skips. Two further tests constrain fixture URLs/actions.
- The compiled APK manifest confirms a non-exported singleTask WebAppActivity with the independent
  `com.immortal.launcher.debug.web` affinity.
- Five native Family Area checks passed: generated page ready, generated form/scroll/history seeded,
  Home and same-entry return, changed-entry navigation, Home and second-entry return.
- Both Home returns retained the same Activity and WebView identities and their existing document.
  Additional loadUrl calls for each same-entry return: **0**. Changed entry: **1** call, same view.
  Page one retained the generated form value, scroll and history through Home.
- The fixture was closed, Home restored, full configuration/source snapshots unchanged and
  collector inactive. Living Area and the phone received zero operations. No setting was edited.

The fixture uses a fixed bundled local page, not a signed-in user website. Its authenticated
Fleet endpoint requires the existing opt-in functional diagnostic build and Dev Mode and refuses
work while a performance collector is busy. Commands accept only four fixed actions, never a URL
or JavaScript payload. A weak Activity reference cannot keep the view alive. Actual page URL and
requested entry must both be known fixture URLs before any generated DOM is read or seeded;
real web-app content blocks mutation. Responses include only fixture identity and generated state.
Queued command cancellation/uncertain-dispatch handling prevents blind replay after a timeout.
The first native trial stopped before its first check at a foreground-owner guard, then restored
its previous accepted build, Home and unchanged configuration. A separate runner adds a bounded
wait for missing Android focus metadata around the known task transition. It requires matching
ownership before/after actual probe reads and never admits a foreign owner or fabricates content.
Three added offline regressions failed before this helper, then passed; 17 new-runner and 32 reused
offline checks passed. The first trial and its original frozen inputs remain preserved.

This verifies task/document retention on the Portal's actual Android 10 WebView. It does not prove
website first-load speed, actual YouTube Music audio continuity, renderer-crash recovery, a new
latency percentage, delivered FPS or long-run memory stability. External Chromium dashboard
navigation is unchanged; it cannot safely be assumed to remain on the originally configured URL.

## Artifact

Candidate: `c4e49f7601f8bdd5ced1e21a7f3fe43a16746ec19d682bdb93bd7cffc4c22e46` (25,419,408 bytes).
Matching bootstrap: `9f515bf8c5ed61106e15d404f87c8858f868f36861e242c9af5dfce7fde38c7f`.
Source: `8c25eaff49bffc6a8e60d8356725a27c4e95b64870f999791e612bfd12763218`.
Postflight: `c61160ad3e39097bfe81494055b66dd9ca6479f4663a5c23f16cf9ad6120ea3a`.

Exact SDK/bundled/reference profile sets and DEX mapping passed:
8,658 methods / 2,573 classes, including
285 application methods / 75 application classes.
Own OAT metadata reports speed-profile/install-dm; individual method compilation is not proven.
The legacy six-symbol profile-name coverage gate is not the admission gate; complete resolved
sets are verified. Source stayed frozen across candidate/bootstrap and the device trial.

Issue began 23:05:34 UTC on 2026-10-11, with a 23:35:34 UTC deadline.
Evidence is under the primary workspace's ignored
`build/fleet/diagnostics/webapp-retention-2026-10-11/` (build and first attempt) and
`build/fleet/diagnostics/webapp-retention-owner-retry-2026-10-11/` (successful guarded retry).
The broader performance goal remains active.

Android references:
- https://developer.android.com/guide/components/activities/tasks-and-back-stack
- https://developer.android.com/reference/kotlin/android/webkit/WebView#onPause()

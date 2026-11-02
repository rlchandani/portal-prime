# Home timer editor: independent bounded source review

**Result: no concrete production regression identified in the reviewed diff and supporting paths. Bounded source review only.** This does not establish native UI acceptance or prove the reproduced bug fixed on an installed APK.

## Reviewed identity and scope

- Worktree: `~/.codex/worktrees/portal-music-player/portal-prime`.
- Branch: `codex/portal-timer-editor-20261102`.
- Base and observed HEAD: `d0709c6e639d5396430db7ba5811cf0d32617835`.
- Reviewed the actual uncommitted diff against that HEAD: `ApprovedHomeNavigation.kt`, `HomeActivity.kt`, `app/build.gradle.kts`, and `version.json`. The two metadata changes consistently specify version code `61` / name `1.67`; delivery remains the main task's responsibility.
- SHA-256 of `git diff d0709c6e -- app/build.gradle.kts app/src/main/java/com/immortal/launcher/ApprovedHomeNavigation.kt app/src/main/java/com/immortal/launcher/HomeActivity.kt version.json`: `180c799f5ea963566d79eb1c7a25a27e50d5519453b9e2a5ccf6b8b0722d9eee`.
- All Git reads used `git -c core.hooksPath=/dev/null --no-optional-locks`. `diff --check d0709c6e` produced no diagnostics.
- This review writes only this document. No product/source edits, new source files, JVM tests, Gradle runs, device operations, API calls, or GitHub operations.

The coordinator supplied the native reproduction on Family running `1.66`: before the operation there were no kitchen timers and no existing shared countdown; Timer widget Open hosted `TimersOverlay`; selecting five minutes left Home showing Ready, `TimerStore.running=false`, and kitchen count `1`. This review did not independently reproduce those observations. The temporary widget was added after a layout backup; restoration within the coordinator's `07:53:23 UTC` cap remains owned by the main task, with no restoration claim here.

## Source evidence

Locations below are in `app/src/main/java/com/immortal/launcher/`.

| Concern | Precise locations and assessment |
| --- | --- |
| Correct store ownership | `ApprovedHomeNavigation.kt:72–76` replaces the hosted Tools shell/`TimersOverlay` with `ApprovedHomeTimerEditor`. Both Home entry points still reach that wrapper: widget Settings at `HomeActivity.kt:691–693,745`; widget Open at `3530–3531,3575`. The new actions at `3603–3611,3628–3642` use `TimerStore` / `TimerAlarm`, with no kitchen-store mutation or migration. |
| Context and listeners | `HomeActivity.kt:3581–3591` loads from the current Compose context, keys the snapshot/effect to that context, refreshes state and time on store notification, and removes the same listener on disposal. `TimerStore.kt:58–91` supplies the existing listener channel. Alarm PendingIntent creation uses the application context at `136–140`; alarm audio/stop use it at `TimerAlarm.kt:44–45,150–165`. No new long-lived Activity owner is introduced by the editor. |
| Lifecycle and clock | The wrapper guards both launcher activity and widget-page input (`ApprovedHomeNavigation.kt:75`); retained widget pages derive selection at `ApprovedWidgetPageScope.kt:22–28`. The lifecycle provider gates foreground state at `ApprovedLauncherTabs.kt:229–246`, while native tab admission updates input at `ApprovedHomeUi.kt:157–159`. The editor's tick loop at `HomeActivity.kt:3593–3600` follows running state/end time/activity and stops at zero without clearing the countdown. Expiry remains receiver-owned (`TimerAlarmReceiver.kt:20–23`). Its wall-clock model matches the unchanged widget loop at `3518–3525` and `TimerStore.State.remaining` at `51–52`; no new clock broadcast listener or scheduling model is added. |
| Action admission | Presets, pause/resume, reset, stop and custom start use existing `TimerChip` (`HomeActivity.kt:4377–4389`); duration steppers use `StepButton` (`4364–4372`); Close uses `ApprovedHomeTextButton` (`ApprovedHomeUi.kt:386–395`). These pass through `tvFocusable`, whose callback checks the inherited input gate at `TvFocus.kt:58–78`. The editor does not add an unguarded alternate click path. This source tracing does not prove native gesture/focus behavior. |
| Back and modal transitions | `ApprovedHomeDialog` at `HomeActivity.kt:968–988` owns the existing Android Dialog dismissal callback and Close action. At `3602–3614`, the custom picker replaces the parent dialog in an `if/else`; custom Cancel, Close or dismissal returns to the parent through `showCustom=false`. Parent dismissal invokes the caller's callback. The removed Tools-specific Back callback/tab navigation is not reintroduced. Native Back routing, outside taps and window/focus transitions still need APK QA. |
| Countdown actions and ring stop | `HomeActivity.kt:3630–3638` calls existing stop/pause/resume/clear/start methods. `TimerStore.kt:95–132,143–158` retains persistence and exact-alarm ownership. Ring Stop calls `TimerAlarm.stop`, which removes auto-silence callbacks, releases audio/wake resources and resets shared state (`TimerAlarm.kt:150–165`). Custom start at `HomeActivity.kt:3604–3609` also stops a countdown that became ringing while the custom picker was open before starting its replacement. |
| Custom duration | Existing picker bounds minutes to `0..180`, wraps seconds in five-second steps, and computes duration as `Long` (`HomeActivity.kt:4321–4338`). The new callback accepts only positive duration (`3605`); zero closes the picker without changing timer state. Custom duration while running or paused replaces the single shared countdown through existing `TimerStore.start`, rather than creating a second countdown. |
| Kitchen timers and screensaver | The diff leaves kitchen configuration, receivers, scheduling and Tools content unchanged. `HomeToolOverlays.kt:311–389` still operates on `TimerConfig`; retained Tools dispatch at `ApprovedToolsHost.kt:128–132` still selects `ToolsTimersContent`, whose add/cancel calls remain at `ApprovedToolsEveryday.kt:281–287,355–360`. Separate storage remains `immortal_timer` versus `immortal_timers`. Screensaver reads the shared store at `FaceRenderer.kt:704–723`. |

Both modified Kotlin files retain their existing `Copyright (c) 2026 Starbright Lab.` headers.

## Reviewed file hashes

SHA-256 hashes fingerprint the entire files; supporting review was limited to the relevant portions above and their immediate context, not an exhaustive audit of every function. Kotlin filenames below are relative to `app/src/main/java/com/immortal/launcher/`.

| File | SHA-256 |
| --- | --- |
| `HomeActivity.kt` | `e9306d050a53d69d2652d6fcb9a3ff9159b59f3311682807232efe6a0e60f83b` |
| `ApprovedHomeNavigation.kt` | `cdba2e8a505449ba7b1fe9d9feadce8ab67f68c0b225227af096ffc9662f4644` |
| `TimerStore.kt` | `f5b276abb31ec583338d85e6ee591172914e106200dd95588071a6de6eefe0c8` |
| `TimerAlarm.kt` | `3a267f0a685ad2a875db1f01d92a3c109d167d0bf70da213c1d72a3040a5ddba` |
| `TimerAlarmReceiver.kt` | `f744e7df2dc122f50eaeef7d578b52cee9862fdf5f752328560acd664d2029d6` |
| `TimerConfig.kt` | `9d4ef06537940a67c00cba8b6af70dba01ff723dc88213b68dea8a22142616de` |
| `TimerScheduler.kt` | `371d48234235f0f0654b97957565ba47aea7bb6bf0633eaa99312fe22714039e` |
| `TimerReceiver.kt` | `9ca74a11b61670b587a991dd01c67994b1447de831c78cddc47aae8923f5981a` |
| `HomeToolOverlays.kt` | `61a3ac8dd6af319bc8c3d5b48cc26c3c85052c1db84adf9fb118b2a84ec9179c` |
| `TvFocus.kt` | `c178b73f2f262b880031bdecba954cedbb44b2b9e1b3dff7eabe647d93df5117` |
| `ApprovedLauncherTabs.kt` | `3325d14bcb665da4c80f3bfe085871a1f98372ae382961d3d927ad574dfa5976` |
| `ApprovedWidgetPageScope.kt` | `0a4b04d60773120ef81fa994ddcdd3948bbcee9fb1c5dee3766e20d0354e1ae3` |
| `ApprovedHomeUi.kt` | `472265e5747d2478f203182289445aa8dc78ce66c40826b95050de5a85f71a2f` |
| `FaceRenderer.kt` | `b4b0c8519daa1af0061c1ad6492e627cf2e85b68dd5e0b75880672faa3e3ced5` |
| `ApprovedToolsHost.kt` | `c81eb51548eb2e8f43cc3aa295822f389bfd2104c10a1d4b435d6f8db06069d0` |
| `ApprovedToolsEveryday.kt` | `fb1c8eda1d978018c36af1f56323007908e9840c42c010cf28d026d2bcd19dfb` |
| `ApprovedAppsChrome.kt` | `dabb1ec543763bdf95f13129b8ba11d5ac727aa05d4f5ba9f6d32bea8a169318` |
| `ApprovedScreenLifecycle.kt` | `d8df00075c2e3910a54ab673fd24c2c499f0fbd26078a2596d2d1d8e2b6fd5e2` |
| `app/build.gradle.kts` | `5fbdb5159d9fbcb94af0ddb6dc30467b3ada1b0c625aa22e7ccfedfc3edfb7df` |
| `version.json` | `cb486f166d81fa1bcd41c7216b264d07406beb8f1d0da438a89da90f0c61a45e` |

## Limits and handoff

This review makes no general test-coverage, timing, confidence-score, build-success or performance claim. No new JVM test was added or treated as proof of a native renderer. The supplied native red case and post-APK QA are separate evidence.

The main task owns full unit/debug/performance gate results, private QA, artifact identity, installation/delivery, post-install verification, version release work, operating-state/roadmap updates and restoration. In particular, source inspection does not establish dialog layout, D-pad focus, native Back behavior, audible ring/stop, background alarm delivery or screensaver handoff on Portal hardware. The old full-page shell's keep-awake/immersive handling is replaced by the existing Home dialog behavior; idle/window behavior is a native acceptance limit, not a demonstrated defect in this review.

Revalidate the source identity if any reviewed file changes after these hashes were recorded.

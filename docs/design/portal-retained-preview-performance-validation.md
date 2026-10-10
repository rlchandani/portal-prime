# Retained preview suspension validation

## Change

`PhotoFramePreviewActivity` can survive underneath QuickBar's opaque AppSwitcher and external apps. Previously its owned clock/refresh/dwell callbacks remained scheduled until destruction. The new reversible host state suspends those callbacks and renderer work without destroying or recreating the existing preview. User pause remains independent.

The shared `PausableFrameCallbacks` queue freezes remaining deadlines, keeps late native decode delivery in the loader's existing budgeted ready slot, rejects obsolete dispatch wrappers, and closes permanently at terminal teardown. Native dissolves and motion, legacy fades/Ken Burns, bundled-engine admission/motion and clock overlays receive reversible suspension. A resumed clock shows current time rather than replaying missed flips. Video position is captured before Activity pause can tear down its surface.

Activity start/stop and screen-power broadcasts drive host activity. This slice deliberately does not infer visibility from focus loss or Meta's screensaver. Owned engine JavaScript pauses explicitly; external website documents receive per-view WebView lifecycle calls. No process-wide `pauseTimers()` is introduced.

## Verification

- Offline debug/benchmark/test APK builds passed.
- Unit gate: 1,160 tests, zero failures/errors/skips. Includes 27 callback-queue deadline/reentrancy/race tests and seven renderer-work ownership tests.
- Bundled JS: 14 source fixtures and six Framed lifecycle fixtures passed. The extended initially-hidden-page regression rejects the previous index implementation (13/14 expected baseline result).
- Actual desktop Chrome: all 12 slideshow styles reached readiness using generated SVGs. Initially hidden Framed startup held admission, and two pause/resume cycles preserved the document and both print-slot samples. The motion snapshot starts after two animation frames to allow CSS pause state to settle; this is not a compositor FPS measurement.
- Isolated API-34 emulator, Portal display dimensions: native smartFrame and Framed passed four opaque AppSwitcher/system-Back cycles, each covering longer than the five-second dwell. Preview/controller/native bitmap or WebView/document nodes retained identity; covered playback did not advance; callback delivery resumed exactly once; user pause survived return; terminal stop made 24 stale wrapper dispatches inert and rejected future posts.
- The emulator had no attached live QuickBar control. All four cycles used its exact AppSwitcher NEW_TASK intent fallback. This verifies real stopped/resumed activity wiring and Back, not literal QuickBar button interaction on Portal firmware.
- All modified test settings/source/secure settings/presence state were restored; owned hosts and generated fixture folders were removed.
- Two initial probe attempts failed fixture assertions: Android app-data path aliasing and 480-pixel images below the engine's 512-pixel admission limit. Correcting only those fixtures yielded the passing run; production code did not change between attempts.

## Physical-device results

The candidate benchmark APK was installed in both Family Area and Living Area, with installed bytes verified against SHA-256 `4ef8dec8223540904d36e681d40436ec83106995493b1872647bb9a689c0e687`.

The first stress attempt stopped at the first foreground assertion in each room (zero completed cycles). Home was accepted, but no application-window identity was reported. Later metadata showed cached `DREAMING` screen state and still no application root; a fresh Home request restored `INTERACTIVE` state and the expected Home window in both rooms. This establishes a readiness precondition, not a proven cause for the earlier missing identity. A separate explicit wake operation was rejected before dispatch because this fleet-token route is not a paired companion session; no successful wake operation is claimed.

After Home readiness, both rooms passed five complete Home/Apps/browser/Back/screensaver navigation cycles, with 30 verified application-window checks per room. Each then pinned Chromium, showed the cover, dismissed it with a real remote touch, and retained the same Chromium window identity. Going Home cleared the pin and cover. Hide-dispatch time was 5,993 microseconds in Family and 4,328 microseconds in Living; these measure dispatch only, not touch-to-visible latency or document preservation. Slideshow configuration was unchanged, cleanup barriers were empty, and performance collectors were inactive with zero listeners.

The repeated-navigation run then completed 30 cycles in approximately five minutes per room: 270 accepted actions (240 navigation commands and 30 empty-text checks), 180 verified foreground-window checks, zero failures, zero frame-metric callback drops and zero listener-attachment failures. Its fixture required initial Home readiness and recorded bounded additional readiness wait separately from acceptance; the initial failed result remains preserved.

| Room | Home p95 ms | Preview p95 ms | Home steady over-budget / frames | Preview steady over-budget / frames | Action request median / max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Family Area | 68.25 | 78.50 | 356 / 1,582 | 1,420 / 2,441 | 24.07 / 96.93 |
| Living Area | 67.00 | 78.50 | 329 / 1,549 | 1,455 / 1,991 | 31.62 / 196.15 |

Readiness was sampled after each configured settle delay (0.5–1.2 seconds for destinations with assertions). The maximum additional wait, including the status query, was 59.74 ms in Family and 44.49 ms in Living. Request timings and this sampled readiness bound do not measure touch-to-visible latency. These mixed-workload p95 values are aggregate Window duration histogram bounds; the steady-frame counts exclude first draws. They remain above the 16.67 ms screening target, and there is no matched baseline/candidate frame-time comparison for this slice.

Final readback independently confirmed Home foreground identity, no pin or cover, no pending/failed cleanup, and collectors stopped with zero active listeners in both rooms. Original slideshow configuration matched. The result verifies navigation and resource cleanup, not completion of the broader performance goal. A source review also found no remaining actionable defect in the retained-host change.

## Limits

This proves stopped-host suspension and retention, not an overall frame-time gain. The focused emulator probe uses generated still images and a no-clock face; arbitrary remote website behavior, video surface recreation, live now-playing artwork, native Dream coverage without activity stop, and optional camera/ambient-audio suspension still need their own runtime verification. Current controller source discovery/prefetch already in flight may finish while covered. Broader device performance work remains active.

The pin behavior is unchanged: leaving the pinned app for Home or another app clears the pin. While staying pinned, the screensaver covers the existing application window and tap reveals it without a launch request.

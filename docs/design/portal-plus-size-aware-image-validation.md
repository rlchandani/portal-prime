# Size-aware legacy images and retained phone navigation

Branch: `codex/portal-rendering-navigation`. Date: 2026-10-09. Base: `b039964`.
The base already contains the WebView timer recovery fix for the stuck loading screen.

## Changes

The legacy scheduler passes a requested image edge for each scene slot, but its photo URL
helper discarded that size. It also permanently memoized a prepared file URL that the native
disk budget could later evict. The helper now passes the bounded edge through a compatible
`scaledUrlForSize` bridge and revalidates each load. Old hosts retain `scaledUrl` support.
A miss continues using the source while a prepared copy is made off the load path.

Copies round up to 600/1200/1800/2400-pixel tiers, capped by the configured target. A ready
larger tier is an acceptable fallback; a smaller tier is never selected for a larger request.
Full-screen requests keep their zoom headroom. Originals remain untouched, and cache identity
includes their path, size, modification time and the requested tier. Extreme source headers
may need extra subsampling to stay within the pixel bound; that can limit output resolution.

One shared low-priority decoder has at most one active job and 64 queued jobs. Owners deduplicate
requests and invalidate/remove queued work on exit without joining the decoder on the UI thread.
Warm-up queues one step at the tail, so newly visible requests do not sit behind a full warm-up
batch. Warm-up enumeration and hashing stay off the UI thread. Header, pixels and EXIF read the
same pinned file stream; changed identities and canceled owners cannot publish stale copies.
Input sampling targets at most 12 Mi pixels plus one bounded output. Only complete atomic JPEG
writes are exposed; temporary files are removed and the completed disk cache is trimmed to
160 MiB. These bounds do not include every WebView texture or other app allocation.

The phone's key/preset Apps routes now select the retained Home Apps page, matching the dock.
Real Android testing also exposed a cold-Home task boundary: bringing Home forward alone can
leave a preview in another task. The existing weak owned-Dream navigation bridge now explicitly
finishes Immortal's preview as well, marking an intentional exit before either surface finishes.
It never owns Meta's screensaver. A read-only selected-tab getter lets tests observe navigation
without incorrectly simulating an Android save-state lifecycle callback.

## Regression evidence

- Full debug application, Android-test APK and all **858 JVM tests** passed, with zero failures,
  errors or skips. New tests cover size/key/pixel bounds, bounded work, cancellation, warm-up
  priority, real remote route dispatch and replacement-safe owned-preview navigation.
- The actual `photos.js` VM regression had four failures before the fix and now passes all
  **five tests**, including size forwarding, eviction/rebuild, invalid values and older hosts.
  This regression is included in the existing tests workflow.
- The owned API 34 arm64 emulator passed **17 native derivative checks**: real bitmap/JPEG
  codecs, 90-degree EXIF, size tiers, larger fallback, eviction/rebuild, edit identity, cancellation,
  completed cache retention, orphan-partial cleanup and StrictMode proving warm-up does not
  perform disk work on the calling UI thread.
- The actual WebView legacy recovery probe again passed **97 checks**: four fresh sessions
  (Ken Burns twice, filmstrip twice), three re-entries, a seeded shared timer pause and an
  independent WebView heartbeat.
- Retained remote Apps/Home/preset/preview navigation passed **216 checks twice**, in separate
  fresh test processes across dark and light themes. The checks require the same Home creation
  and native window, correct rendered chrome, correct content, resumed focus, destroyed previews,
  settled observations and exact test-fixture/settings restoration.

An initial navigation test falsely retained translated accessibility bounds even after direct
screenshots showed the Apps page settled. Fresh UiAutomation cache reads on API 33+, and per-node
refresh on older APIs, corrected observations without changing the production animation. A
separate harness mistake had invoked save-state on a resumed Activity; the read-only getter
removed that lifecycle mutation. These failures and corrected runs remain in private diagnostics.
Optional real OS-Dream initiation did not attach on this emulator through the normal SystemUI
entry point, so **real Dream cases are not claimed**. No privileged permission was added to bypass
that limitation. The emulator's Home role, display overrides and test auth/tour preferences were
restored, and that owned emulator was shut down. The phone was not changed.

## Family Area evidence and limitations

The same-key APK was installed over Wi-Fi using the existing Fleet path; deployment verified
its installed SHA-256 as `725097535e28a15f008a9cf2ea6c4ef09aa69310d5707caf8a016f24ca585a99`.
Only Family Area received this build. Its existing source and 12-photo library were preserved.
Finite samples temporarily selected filmstrip with the same interval, then restored the saved
mode/interval through the normal API.

| 120-second filmstrip host sample | Prior build | Candidate |
| --- | ---: | ---: |
| Observed preview Window frames | 175 | 429 |
| Frames over the approximately 16.67 ms display budget | 9 | 260 |
| p50 total host time (histogram upper bound) | 12.25 ms | 26.75 ms |
| p95 | 17.00 ms | 47.00 ms |
| p99 | 25.25 ms | 161.50 ms |
| Maximum | 28.44 ms | 213.82 ms |
| Android callback drops | 0 | 0 |

Both collectors expired with no active listeners or collector thread. The sample outcomes
**do not establish a performance improvement**. They have different animation activity, randomized
scene choices, cache/process warm-up and frame counts. A warm candidate repeat was interrupted
by a logged `onExit (tap)` and returned to Home; it is excluded as a complete slideshow comparison.
Captured logs showed no fatal exception, Immortal ANR or OutOfMemoryError markers. The candidate
process PID stayed unchanged; the final read sampled RSS about 125.1 MiB and 68 threads. A final
30-second capture sent no navigation, observed only one Home Window frame during active use,
and verified automatic expiry with no listeners or collector thread. It is a cleanup check, not
a slideshow benchmark. These short observations do not establish leak freedom.

Readiness events on the candidate included saved shuffle in 139 and 784 ms, and filmstrip in 556 ms,
measured from engine page start only. Those timings exclude source/view setup and are not
end-to-end visible readiness. Window metrics cover host rendering work and are **not delivered
WebView/compositor FPS**. The derivative bounds are verified, but legacy animation smoothness
still needs optimization and controlled measurements. Household photo bytes were not exported.
Raw measurements/logs remain private under ignored `build/fleet/diagnostics/` paths.

Physical unattended OS-Dream transitions, Settings/browser/website performance, the older-API
accessibility refresh fallback and full phone/fleet UX remain broader-goal validation work.
Family was actively used during part of the sample, so no uninterrupted second candidate soak
or new four-cycle physical navigation claim is made here. The prior recovery validation remains
recorded in [Wi-Fi frame diagnostics](portal-plus-wifi-frame-validation.md).

## Reproduce the owned regressions

```sh
node --test scripts/test_slideshow_sources.mjs
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
adb shell am instrument -w -e command engineDerivatives \
  com.immortal.launcher.debug.test/com.immortal.launcher.PortalUxInstrumentation
adb shell am instrument -w -e command remoteAppsNavigation \
  com.immortal.launcher.debug.test/com.immortal.launcher.PortalUxInstrumentation
adb shell am instrument -w -e command slideshow -e legacyOnly true \
  com.immortal.launcher.debug.test/com.immortal.launcher.PortalUxInstrumentation
```

Navigation requires an already provisioned test device. The probe refuses to create/rotate a
Fleet token or approve a first-run tour as part of its execution. Fixtures are owned synthetic
images; the test restores exact settings, preferences and runtime markers in cleanup.

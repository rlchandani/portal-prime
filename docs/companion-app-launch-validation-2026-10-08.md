# Companion app launch from the slideshow — 2026-10-08

The native companion Apps page now opens the selected app directly from
Immortal's idle slideshow. It no longer requires pressing Home first. The patch
is installed on the Portal+ over Wi-Fi, preserving the signing identity, paired
session, settings, and photos.

## Reproduction and correction

Before the fix, the phone's Jarvis Open button sent a launcher intent accepted by
Android, but `PhotoDreamService` remained active and covered the app. Launching
from the manual `PhotoFramePreviewActivity` worked. Home used the owned-Dream
navigation bridge; `RemoteApps.launch` did not.

The shared remote app launcher now resolves the app's ordinary/Leanback intent
first, dismisses only Immortal's Dream on the main thread, then launches the app
on the requesting thread after dismissal completes. The bridge records an
intentional exit before `finish()`, preventing interrupted-Dream recovery from
reopening photos over the selected app. It bypasses the configured tap-dismiss
app because the caller already selected its destination.

Main-thread dispatch has a two-second bound. A queued timeout is cancelled; a
running dismissal that times out cannot launch the selected app afterward,
because that launch is outside the queued task. No sleep, repeated wake gesture,
or Home hop is added to production navigation. Missing apps leave the Dream
running. With no owned Dream, the launch still proceeds without marking a
system/Meta Dream as an intentional exit.

## Verification

- Full Portal JVM suite: **797 passed**, zero failures, errors, or skips; debug APK
  build succeeded. Nine launch regressions cover ordering, missing apps,
  Leanback fallback, ordinary launches, start failure, worker dispatch, rejected
  dispatch, queued timeout, and running timeout.
- The original seven targeted launch tests had four failures before the fix and
  passed afterward. The two timeout regressions were included in the final suite.
- Physical Portal+ API 29 and Pixel 9 Pro Fold API 37, USB disconnected:
  **five launch checks passed** using the native companion Apps Open buttons.
  Three fresh idle-Dream Jarvis launches, one idle-Dream Material Files launch,
  and one manually opened slideshow-to-Jarvis launch all reached the selected
  foreground app without pressing Home between slideshow and launch.
- Each physical launch verified Dream dismissal and the selected resumed
  activity, then sampled stability five times over at least 2.5 seconds. Photos
  did not recover over the selected app during those observations.
- Observed tap-to-foreground checks: Jarvis idle 1,878 / 522 / 617 ms; Material
  Files idle 513 ms; Jarvis manual slideshow 263 ms. These include phone command,
  API dispatch, Android launch, and state polling; they are not guaranteed input
  latency or isolated renderer timings.
- All **six existing live phone/Portal integration tests passed**, zero skips,
  in 4.452 seconds against the updated Portal. Settings/source changes restore
  their baseline and generated test images are removed by the existing fixtures.
- The companion APK and UI were unchanged by this Portal-side patch. Its earlier
  422 JVM and 76 Android test results remain previous evidence, rather than being
  presented as a new full companion run.

## Scope

Native Apps, the paired web remote, and remote preset/scene launches share
`RemoteApps.launch`. The direct ADB `monkey` launcher in advanced Device tools is
separate and is unchanged by this patch. Meta's screensaver is not dismissed by
the owned-Dream bridge. Jarvis was opened; a cloud voice conversation was not
started as part of this test.

The original checkouts are preserved; source and report are committed locally
on `codex/portal-companion-api`. No public release is published.

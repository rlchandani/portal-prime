# Paused music-photo capture — Family Area

**Focused continuity check passed; the earlier timeout was not reproduced.**
This is a test-observer change, not a production player fix. The bounded item
started at **08:23:21 UTC**, with a **08:53:21 UTC** hard limit.

## Physical result

The real photo controller ran inside the silent native music fixture on
Family Area's Android 10/API 29 Portal+. Photo motion, videos, burn-in shifting
and the night clock were temporarily disabled; the photo interval was five
seconds. The original configuration was restored afterward.

| Check | Result |
| --- | --- |
| Focused functionality | **10/10 checks passed**, nine native captures |
| Paused photo after a six-second hold | Maximum RGB channel difference **0** in the measured photo region |
| Full → Photos return | Same controller, pause choice and photo; pixel difference **0** |
| Manual Next while paused | Different photo, same controller, Resume remains available |
| Complete capture workflow | **1.845–2.668 seconds** per capture |
| Pause capture phase timings | Accessibility root **28 ms**, tree **92 ms**, metadata **0 ms** at clock resolution, screenshot **94 ms**, PNG **852 ms** |
| Workflow including restoration | **76.964 seconds** |

The complete capture timings include ADB requests, accessibility traversal,
screenshot capture, PNG encoding and artifact retrieval. They are **not**
tap latency, animation performance or delivered panel FPS. Phase timings
include the small opt-in tracing overhead.

The same observer handled all nine requests; no stall report fired.
The historical paused-view timeout remains unexplained, rather than being
declared fixed after a passing run.

## Observer safeguards

`PortalUxInstrumentation` accepts `traceCapture: true` on an observer request.
It records accessibility-root, tree, fixture-metadata, snapshot JSON,
screenshot, PNG and response stages. A one-shot nine-second probe samples
only the main and observer threads' states and bounded stacks.

Tracing is test-only and opt-in. Phase/probe file failures cannot abort the
capture. An acquired screenshot is protected by immediate `try/finally`
recycling. An atomic completion flag suppresses obsolete sampling, without
waiting for probe file I/O. Every trace has a unique capture ID and probe
filename, referenced by the phase file; a delayed write cannot overwrite a
new request's report when names are reused. The snapshot response also carries its capture ID. Consumers must match
response, phase and probe IDs and tolerate missing/partial files. A late write is not claimed impossible.
Normal untraced requests do not start the probe executor.

If a future request exceeds the external observation deadline, inspect that
request's phase/stack and continue polling its matching response. Do not
rewrite `request.json` or restart the observer solely because observation
timed out.

Android 10's upstream implementation supports this distinction:

- [Instrumentation.runOnMainSync](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/app/Instrumentation.java#435)
  posts to Main and waits without an overall timeout.
- [AccessibilityInteractionClient](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/view/accessibility/AccessibilityInteractionClient.java#316)
  uses synchronous Binder calls followed by bounded callback waits; those
  waits do not bound the entire capture.
- [UiAutomation.takeScreenshot](https://android.googlesource.com/platform/frameworks/base/+/android-10.0.0_r1/core/java/android/app/UiAutomation.java#807)
  reaches the compositor; [SurfaceFlinger's capture path](https://android.googlesource.com/platform/frameworks/native/+/android-10.0.0_r1/services/surfaceflinger/SurfaceFlinger.cpp#5830)
  contains waits without a finite timeout.

These are upstream API 29 references, not proof of the cause on Meta firmware.

## Validation, restoration and limits

The Android test APK build passed. Review found and corrected
diagnostic-write propagation, stale reports, unsafe bitmap cleanup and a
completion lock that could wait behind probe I/O. The normal traced path was
exercised physically before the final change to unique probe identities and
non-waiting completion; that final test-only revision receives a separate
build/review check. The nine-second stall probe's firing path was **not**
exercised by the passing run.

The first private helper attempt incorrectly parsed an absent stall file.
It had completed one capture and restored all device state; its failure is
retained in the receipt. The corrected helper tolerates absent or partially
written diagnostic files. Neither run is hidden or counted as an app fix.

The optimized APK **b79b56c7…**, all **97 settings**, **22 photo-source
records**, persistent pin state, exact accessibility list, test APK and
original Dream were restored. Android's system process stayed unchanged,
and the performance collector ended inactive with zero listeners.
Living Area was not touched.

Household images, credentials, profiles, rollback APKs and raw traces remain
outside Git. See the [sanitized receipt](../mocks/portal-premium-player-20261030/evidence/family-paused-capture/verification.json).
The natural room-light comfort check remains pending because the user cannot
test it now. Cold startup, animated-image residency, parked timing outliers
and the full goal remain incomplete; provider/audio wiring stays deferred.

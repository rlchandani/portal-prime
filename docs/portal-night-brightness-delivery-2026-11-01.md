# Hardware-aware night brightness delivery

**Delivered on Family Area:** near-dark fades now avoid window updates that
Android 10 would convert to the same integer brightness command. The continuous
three-second fade, exact endpoints and return to Android's brightness policy are
preserved. This accepts the reduction in redundant window updates; physical
smoothness and comfort remain pending because the user cannot test them now.

Source commit **fa84f968**, branch `codex/night-brightness-delivery-20261101`.
Started **09:50:10 UTC**, hard limit **10:20:10 UTC**. Optimized delivery and fresh
health checks passed at **10:04:44 UTC**, **14 minutes 34 seconds** after starting.
Living Area was not changed.

## Why this changed

Android exposes `WindowManager.LayoutParams.screenBrightness` as a float, but
[Android 10's RootWindowContainer](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/services/core/java/com/android/server/wm/RootWindowContainer.java)
converts an override with `int(value * PowerManager.BRIGHTNESS_ON)` before
dispatching it. `BRIGHTNESS_ON` is 255. Family Area's display diagnostics report
the corresponding integer override and driver brightness.

The old fade could issue approximately 85 distinct floats over three seconds.
Near dark, many consecutive floats produce the same integer command. Each still
changed the Dream window attributes and reached its WindowManager update path.
The float API alone therefore does not establish finer backlight control on this
hardware.

`NightBrightnessDelivery` keeps two values: continuous logical fade progress and
the last delivered window override. On API 29 and earlier it suppresses an
intermediate write only when both values are finite, within 0–1, and truncate to
the same integer command.
[Android 11's RootWindowContainer](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android11-release/services/core/java/com/android/server/wm/RootWindowContainer.java)
passes float brightness through its handler; API 30 and later keep distinct float
writes. An external
window override invalidates the logical cache, even within the same integer
level.

Final settlement flushes the exact float or the negative default-policy sentinel.
Reversal and hidden-window resume start from the latest logical progress. The
native Dream/preview and pinned-app overlay use the same delivery boundary.
Clock/photo crossfade timing and direct tap exit are unchanged. No user-facing
setting, global brightness preference or photo-source configuration was added
or changed.

[Android's window-brightness documentation](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness)
defines the negative value as the default policy. It must reach the window
explicitly after the return ramp; it is not a numerical brightness endpoint.

## Actual Family Area comparison

The diagnostic used the attached Immortal Dream window and delegated its real
`onWindowAttributesChanged` callback. It exercised the production
`NightWindowBrightness` owner, then restored the original callback, override
and owner. It did not simulate light-sensor input or export photos/screenshots.

| Window brightness transition | Baseline updates | Fixed updates | Reduction |
| --- | ---: | ---: | ---: |
| 3% → 1% | 85 | **6** | **92.9%** |
| 1% → 3% | 85 | **6** | **92.9%** |
| 1% → system policy, selected level 9/255 | 84 | **7** | **91.7%** |

The fixed native gate passes **18/18 assertions**. Exact float endpoints, the
default sentinel, driver-reported integer endpoints, original callback/override,
the same Dream/controller and unchanged clock mode all pass. The baseline passes
**14/17**: its three redundant-update gates fail. The fixed probe adds a same-Dream
identity check and strengthens the earlier controller-presence check to object
identity; the assertion totals are therefore different.

Native endpoint observations occurred approximately 3.25 seconds after each
request, including the deliberate wait. These are observation windows, not a
measured fade-duration improvement. The native test verifies driver-reported
endpoints, not an optical brightness curve.

## Regression checks and delivery

- **3,715 unit tests / 392 suites**, zero failures, errors or skips; 18 new tests.
- Paired real `NightBrightnessFade` instances preserve the logical values,
  scheduled callbacks and requested integer brightness sequence at every callback.
  Tests cover reversal, cancellation/resume, close, external overrides, invalid
  values, exact settlement and modern API behavior.
- Debug, optimized performance and Android test APK builds pass. Review found
  no concrete blocker in the final implementation.
- The first full run caught a reversal-fixture mistake: 600 ms placed the last
  callback on an integer-level crossing, rather than within an unchanged level.
  The corrected 300 ms fixture exercises suppressed progress; the final full
  gate passes. This was a test-fixture correction, not a reported product fix.
- Both native runs restored the original optimized app/test APKs, all **97**
  settings, **22** source records, pin state, exact accessibility configuration
  and the original Dream. Android's system process stayed unchanged.
- Optimized non-debuggable APK **6c1adacc…**, with the existing signing
  certificate, is installed on Family Area. Deployment took **17.160 seconds**
  and preserved the same settings and state.
- Fresh installed-hash verification passes. No active instrumentation or
  performance collector/listener remains, and the original screensaver is active.

## Acceptance limits

This reduces actual window-update work; it is not a measured CPU, GPU, battery,
slideshow FPS or optical-comfort improvement. The hardware still has finite
brightness steps near black. The logical fade's continuous float values do not
prove continuously variable panel brightness.

The new native probe exercises the attached Dream window, not a separate pinned
accessibility overlay. That overlay's shared-helper wiring was reviewed; its
physical presentation is not newly accepted by this probe. Runtime behavior on
other Android versions was not measured. The sensor-driven clock/photo transition
and comfortable panel brightness still need the user's physical check.

Before this item, optional wave-camera verification was parked after inspection:
Family Area has camera devices, but Immortal's CAMERA permission is ungranted.
It was not an active performance outlier; no camera code was changed.

The broader optimization/screensaver/music goal remains incomplete. Music
provider/audio wiring remains deferred.

[Sanitized verification receipt](https://github.com/starbrightlab/immortal/blob/main/mocks/portal-premium-player-20261030/evidence/family-night-brightness-delivery/verification.json).
Credentials, configuration snapshots and rollback APKs remain private outside Git.

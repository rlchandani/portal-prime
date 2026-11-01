# Physical Portal Dream brightness fix — 2026-10-31

Family Area's real Android 10 screensaver ignored the previous brightness ramp.
Its local window attributes changed, but the system window stayed at `sbrt=-1`
(adaptive brightness). A live change to 15% reproduced this before the fix.

Android 10's `DreamService.onWindowAttributesChanged` is empty. Unlike an
Activity, assigning `Window.attributes` does not forward those attributes to
WindowManager. `PhotoDreamService` now forwards them through `updateViewLayout`,
guarded by `decor.isAttachedToWindow`. The existing finite three-second fade,
retained photo, content transition and immediate tap exit remain the owners.

[Android 10 source](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r47/core/java/android/service/dreams/DreamService.java)
also explicitly updates WindowManager in its own window-flag helper.

## Physical verification

Installed optimized APK:
`199aa8a171f400eb852ea4cac0e879ae179a623999f705fa09555f9d4b95ce29`,
25,842,558 bytes, same signing certificate. USB authorization and Wi-Fi ADB were
restored. No global display setting was changed for this fix.

The actual `PhotoDreamService` on the 2160 × 1440 API 29 Portal passed:

| Observation | Result |
| --- | --- |
| Sensor-triggered dark entry | 3.001-second brightness ramp, 11 sampled intermediate values |
| Live clock brightness 3% → 15% | 3.077 seconds, 11 intermediate values |
| Live clock brightness 15% → 3% | 3.065 seconds, 11 intermediate values |
| Registered night-clock disable | 3.204 seconds, 12 intermediate values |
| Native photo/clock content | 19 intermediate video samples each way at 12 fps; 10–90% range 1.5 seconds each way |
| Retained photo | Same visible photo near fade onset and after return; video RGB cosine similarity 0.99991 |
| Tap exit | Home reached; no Dream reappearance in the one-second check |
| Restoration | All 97 setting values and 22 source fields unchanged |
| Settled night display | System display-power override 7; adaptive brightness disabled while the night override owns the window |

The private recording includes personal photos and is not published. Its startup
fallback image is not a valid retention baseline. Video compression and small
pose changes prevent an exact-pixel retention claim.

The final read-only natural-light watch saw 94 samples over 59.91 seconds, all
bright at 97–101 lux. It did not capture the clock-to-photo transition itself.
Human comfort and a complete natural light-on/light-off cycle remain pending.
Sysfs backlight is unreadable: system display-power delivery is verified, not
optical luminance or delivered panel FPS. The scoped, intrusive recording cohort
is not steady-state performance acceptance.

Fresh gates passed: debug and optimized builds, **3,615 tests / 381 suites**,
zero failures, errors or skips. Final collector state was inactive with zero
listeners. This closes the reproduced Dream attribute-delivery bug; the wider
performance, slideshow and music goal remains incomplete.

The guarded physical verifier is
`mocks/portal-premium-player-20261030/family-dream-brightness-checks.py`.
It uses the Portal's resolved SystemUI `Somnambulator` to enter a real Dream;
the remote screensaver action enters a preview Activity and cannot verify this bug.

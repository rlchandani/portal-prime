# Physical pinned-app night transition

The current optimized build passed the production pinned-overlay night gate on
Family Area's actual Android 10 / API 29 Portal+, 2160 × 1440 at 180 dpi.
The app APK remained `199aa8a1…`; no production source or device permissions
were changed in this issue. The combined gate took **72.594 seconds**; the
parent recording and restoration completed in **78.292 seconds**.

| Brightness-command ramp | Duration | Distinct intermediate values |
| --- | ---: | ---: |
| Real dark entry | 3.009 s | 17 |
| Registered 3% → 15% | 2.976 s | 17 |
| Registered 15% → 3% | 2.965 s | 17 |
| Registered night-mode disable | 2.960 s | 17 |
| Second real dark entry | 2.982 s | 17 |

The real sensor read **5 lux**. A temporary registered threshold of 20 lux
triggered entry after the production dark dwell. Natural bright-room exit
could not be exercised in this lighting; registered disable uses the same
content/brightness exit path.

The actual fullscreen `TYPE_ACCESSIBILITY_OVERLAY`, owning process, pinned
Files Activity/window/process and valid pin were checked throughout. One tap
from photos and another from the settled night clock revealed that same app,
with no relaunch performed by the verifier and zero failed cover cleanups.
The default-photo interior matched byte-for-byte after return. The test used
whole-photo mode, disabled motion and a 120-second interval; it does not
independently prove pause behavior across short autoplay intervals.

The private native recording contains **22 intermediate blended frames in each
direction**, sampled at 12 fps. The 10–90% range spans **1.75 seconds** both ways,
with no intermediate flash under the recorded probe's criteria. This measures
rendered content, not optical luminance or delivered panel FPS.
The time, weekday and date were visually reviewed on the native clock capture,
with no observed clipping. Accessibility time/date semantics were not tested.

The [browser review](../mocks/portal-premium-player-20261030/family-night-review.html)
contains an approximately 15.7-second cover-only recording excerpt and clock crop.
The [receipt](../mocks/portal-premium-player-20261030/evidence/family-pinned-night/verification.json)
records command ramps, content analysis, limits and restoration. Raw full video,
app windows, source fields and settings remain private.

## Verifier and restoration

`family-pinned-night-checks.py` guards the physical serial, API, display density,
installed APK and matching HTTP/ADB APK path. It uses the existing registry,
pin API and native window dumps. It neither installs an APK nor invokes
UiAutomation, which could disable the accessibility service being tested.
Startup waits for stable native photo captures. The parent owns source changes,
recording and restoration of the original Dream.

The first host preflight stopped because the shell Python lacked Pillow.
No overlay was started; parent source/Dream restoration passed. The terminal
failure is retained privately. The successful gate used the bundled Python
runtime with Pillow. Subsequent verifier refinement separates pre-fade sample
gaps from active-progression gaps; all five captured ramps satisfy the stricter
timing bound when rechecked against their retained native samples.

All **97 setting values and 22 photo-source fields** were restored exactly,
including the original folder source. The original unpinned state and Dream
were restored. The APK hash was unchanged, the recording was removed from
Android, and the collector remained inactive with zero listeners.

This closes the recorded physical overlay-path gap. Natural lighting/optical
comfort, browser document retention, short-interval autoplay pause, physical
panel timing and the broader platform/slideshow acceptance remain open.

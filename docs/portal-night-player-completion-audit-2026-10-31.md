# Portal night/player completion audit — 2026-10-31

**Overall status: incomplete.** Current source and recorded checks support substantial delivery across all four requested areas. Family Area’s repeated physical check now passes for clock appearance, comfortable brightness and smooth same-photo return. This audit combines recorded checks with that human report; the full performance/music goal remains incomplete.

**Subsequent user feedback:** brightness and clock changes still jumped.
The [new coordinated night transition](portal-night-crossfade-2026-10-31.md)
fixes the immediate clock visibility switch and pinned-overlay brightness
assignment, adds a three-second fade in both directions, and preserves direct
tap exit. All 3,615 JVM tests and 430 native API 34 checks pass; Family's installed
APK is independently verified with all 97 settings unchanged. The earlier
physical comfort report does not verify this newer transition's panel behavior.

Status is scoped to each row: **verified** means the stated evidence supports that result; **incomplete** means implementation/evidence exists but required acceptance remains open; **missing** would mean an absent required deliverable. Recorded test results are not fresh runs. API 34 emulator pixels, browser fixtures, source inspection and deployment receipts do not establish physical API 29 presentation, backlight comfort or delivered FPS.

| Actual requirement | Authoritative current evidence and limits | Status |
| --- | --- | --- |
| Base optimization decisions on actual Portal hardware and benchmarks | [Hardware budget](portal-hardware-budget-2026-10-30.md) traces `cipher` to QCS605 / Adreno615v2, Android 10/API 29, 2160×1440 and approximately 2,782 MiB kernel-visible RAM. It separates historical Portal measurements, current supplied memory readings, external QCS605 benchmarks and calculated budgets. External scores are not Portal FPS; the retail-generation label remains uncertain. | verified |
| Reduce avoidable work across the platform | [Progress record](portal-night-player-progress-2026-10-30.md) documents the shared Home/Apps/Tools animation clock, retained widget-page scope, minute-driven clocks/calendar, hidden media-work suspension and optimized performance build. Current [calendar scheduler](../app/src/main/java/com/immortal/launcher/CalendarMinuteRefresh.kt), [widget scope](../app/src/main/java/com/immortal/launcher/ApprovedWidgetPageScope.kt) and [photo controller](../app/src/main/java/com/immortal/launcher/PhotoFrameController.kt) implement those boundaries. Scheduling/ownership reductions establish implementation, not CPU/FPS savings. | verified |
| Complete whole-platform performance improvement | [Family Home comparison][home] accepts the optimized build: p95 **37 ms**, p99 **53.75 ms**, versus control p95 **60/62.75 ms**. Known Home/Apps tails remain above the existing 16.667 ms frame target. Later settings-snapshot, producer-lifecycle and resume-admission trials were withdrawn, not delivered improvements. These are bounded Window callback cohorts on earlier artifacts, not current-build or whole-platform FPS acceptance. | incomplete |
| Upgrade existing screensaver styles and options | [Screensaver configuration](../app/src/main/java/com/immortal/launcher/ScreensaverConfig.kt) includes native Gallery print/spread and shared photo motion. Current [style implementations](../app/src/main/assets/screensaver/mode-impls.js) and [scheduler](../app/src/main/assets/screensaver/scheduler.js), with the [progress record](portal-night-player-progress-2026-10-30.md), support refinements to Diptych, Ken Burns, Framed Print, Push, Gallery Wall, Hero + Sidebar, Memories, Panorama, Mosaic, Polaroid and Cinema. Browser/native fixture evidence covers composition and pause/cleanup behavior; it does not establish physical acceptance of every style. | verified |
| Keep screensaver rendering within the hardware budget | Current [decode limits](../app/src/main/java/com/immortal/launcher/SlideshowDecodeBudget.kt) bound each bitmap to **12 MiB**, transform pixels to **24 MiB**, encoded input to **32 MiB**; [shared pixel admission](../app/src/main/java/com/immortal/launcher/SlideshowPixelBudget.kt) is **48 MiB**. [Filmstrip observation][filmstrip] passes its warmed 90-second cohort: p95 **12.50 ms**, p99 **14.75 ms**, **2/837** callbacks above 16.667 ms, retained pixels **31.74 MiB**. These are native decoder/admission budgets; WebView fallback textures and complete GPU residency are outside them. Cold startup, DreamService and other changed styles are outside the Filmstrip pass. | incomplete |
| Present upgraded photos reliably at startup and through return | [Startup/orientation receipt][startup] and the progress record establish recoverable failed orientation metadata, fixed-style probe bypass and deferred legacy face warming. The first decoded composition now avoids the redundant 1.6-second parent fade; later composition dissolves remain unchanged. Browser checks pass 73 assertions, with 255 broader renderer checks. Family reports decoded-scene admission in 650/630 ms including Fleet navigation. This does not measure first visible pixels, total startup improvement or pristine cold startup; physical startup presentation and stacked-Dream tap return remain open. | incomplete |
| Darkness produces a dim premium time + weekday/date surface | Current [light policy](../app/src/main/java/com/immortal/launcher/DarkRoomPolicy.kt) uses **20 s dark / 5 s bright** dwell; [clock view](../app/src/main/java/com/immortal/launcher/QuietNightClockView.kt) draws time and local weekday/date, warm/red ink, date alpha **180**, with minute and time-change refresh. Host brightness is window-scoped **1–15%**. [Native retention checks][retention] record **231** passing API 34 assertions. This verifies software presentation/lifecycle, not physical comfort. | verified |
| Make that clock actually appear on Family Area and return to retained media | [New human physical check][physical-night]: **“Clock appeared; brightness comfortable; same photo returned smoothly.”** This resolves the earlier failed appearance check for that tested sequence. Covered lux, precise dwell, physical FPS and direct date readability were not measured by this reply. The new 1.8-second gradual fade is implemented and deployed; its physical feel has not yet been reported. | verified |
| Apply clock settings live without recreating retained media | Current [notification](../app/src/main/java/com/immortal/launcher/NightSettingsChanges.kt), [settings hook](../app/src/main/java/com/immortal/launcher/SettingsGuard.kt) and photo controller apply only `darkRoomClock`, `darkRoomLux`, `nightClockBrightness`, `nightClockTone`. Observer replacement preserves visible night and requires fresh bright dwell; receiver identity and teardown protect ownership. **40e48686** has the [deployed `e08f21e…` receipt][live-deployment]; [live-control checks][live-controls] pass **136**, and **bed6c821** adds [108 passing toggle checks][toggles]. These software checks support the new scoped physical appearance/comfort/return pass; they are not direct heap-identity proof. | verified |
| Retain media and pause choice through night/hidden/bright transitions | Native retention/live-control records support unchanged photo regions, process/window/activity, ready-scene count and explicit Resume behavior. [Actual API 34 Dream checks][dream-return] additionally retain a deliberately paused viewer through a real Dream: same activity/process, Paused / Resume visible, maximum photo-region channel difference **one**. Current controller suspends retained renderers/video/callbacks and unregisters its sensor on hidden hosts. The basic Family night-to-photo continuity check passes by human report. Physical API 29 tap/wake return and relevant optional camera/video cancellation remain unaccepted; already admitted work may finish into the paused queue. | incomplete |
| Deliver premium music UI: artwork, buttons, playlists, full and compact | Current [player UI](../app/src/main/java/com/immortal/launcher/MusicPlayerUi.kt) supplies artwork-led full/compact/photo layouts, transport, queue, seek/volume and guarded controls; [browse UI](../app/src/main/java/com/immortal/launcher/MusicBrowseUi.kt) supplies host-fed library, playlists and collection detail. [Native UI checks][music-ui] pass **106**; [continuity checks][music-continuity] pass **18** for retained Players selection/track across layouts in both palettes. Silent API 34 fixtures and generated artwork verify presentation, not provider data or audio. | verified |
| Accept the premium music UI on the target Portal | [Music continuity deployment][music-deployment] and the progress record explicitly leave actual Portal music pixels/interaction open. Fleet acknowledgments and installed APK identity do not prove artwork, buttons, playlist presentation or full/compact interaction on the physical panel. | incomplete |

## Necessary remaining work

1. **Physical fade acceptance remains scoped:** the 1.8-second down/up transition is implemented, tested and deployed to Family Area. [Brightness verification][brightness] records 3,524 passing unit tests, 479 native assertions, exact APK readback and unchanged configuration. The user has accepted clock appearance/comfort/basic photo return; the physical feel of the new fade has not yet been reported.
2. **Startup delivery is scoped:** the extra first-scene parent fade is removed and deployed within **01:02:44–01:32:44 UTC**. [Deployment/runtime receipt][first-reveal] retains the two-cycle helper’s stale three-cycle completion failure and independently validates the planned two observations without repeating the capture. [Actual API 34 Dream checks][dream-return] now verify continuation retirement, one retained-Home return, deliberate paused-viewer retention and both `singleTask` intent-mode transitions. The recording has zero near-black frames or post-Home reappearance. This closes the native emulator lifecycle gap; actual API 29 cold/panel presentation and stacked-Dream return remain open.
3. **Address the documented platform performance gaps**, led by Home/Apps tails and the affected screensaver paths. [Local WebView misses][bounded-photos] wait off Main for prepared copies. [Remote loading][remote-photos] now streams authenticated input with a **32 MiB** cap, prepares static copies through the shared decoder, preserves PNG transparency and cancels retired owners. Fresh gates pass **3,561 tests**. Actual API 34 WebView checks verify a **31-megapixel** remote photo delivered as **2400×1800**, alpha preservation, corrupt/oversized 404, plus a final local regression. [Remote Family deployment][remote-deployment] verifies the installed APK and all **97 settings** unchanged. These resolve the identified local/remote static loading gaps; physical startup presentation, animated GIF/WebP pixel residency and complete GPU residency remain open. Use the existing hardware budget and matched accepted baselines; retain only supported improvements. The warmed Filmstrip pass does not require another speculative renderer/layer experiment.
4. **Complete physical music UI acceptance** for the delivered artwork, controls, playlist presentation and full/compact continuity. Backend/provider installation, credentials, real audio and provider-backed library wiring remain deliberately deferred; they are outside this goal’s remaining work.

The [Canvas size fix][clock-size] now honors selected size and parent constraints:
12 API 34 landscape/portrait captures show distinct Big/Bold sizes and at least
13 pixels of glyph margin. The default clock/date were visually checked.
Some legacy font/format options remain ignored by the active renderer; this
size-only patch does not claim those controls are complete.

**Latest bounded delivery:** the [metadata regression][metadata] reduces unchanged
date/battery/weather writes from **180 to 3 over 60 ticks**, preserving all 60
clock updates and one-second callbacks. Actual API 34 labels update correctly
for discharging/charging while preserving the date and viewer process.
[Family deployment][metadata-deployment] verifies the new optimized APK and all
97 settings/source configuration unchanged. Fresh gates pass **3,565 tests /
373 suites**. These establish reduced label writes and native correctness;
they do not close the remaining physical FPS or brightness-feel acceptance.

**Newest software improvement:** [clock work][clock-work] skips unchanged
Canvas clock invalidations (**60 → 1 during a tested steady minute**) and hidden
seconds formatting, with blink/seconds behavior preserved. Localized date
formatters use an eight-entry per-thread cache; 100 changing-date calls resolve
the ICU pattern once. Fresh gates pass **3,584 tests / 375 suites** and native
API 34 label/retention checks. [Family delivery][clock-pending] was pending after
its first preflight timed out; no device write occurred during that issue. The
following size delivery installs these changes too. Animated-image pixel
residency remains open: the native drawable API does not directly supply resized
encoded animation to the current WebView pipeline.

**Clock-size delivery:** [clock-size checks][clock-size] pass the 12-case native
geometry matrix in 70.622 seconds; fresh gates pass **3,591 tests / 376 suites**.
[Family installation][clock-size-deployment] verifies optimized APK `f6a50378…`,
all 97 settings/source configuration unchanged, the previous signing certificate
and zero active performance listeners. That build includes the gradual brightness
fade and prior date/clock work. Exact emulator preferences were restored and
synced, independent of the native helper's live API rollback acknowledgment.
Actual Portal brightness feel, geometry/FPS and legacy font/format controls remain
outside this software verification.

**Cached-artwork delivery:** [artwork-cache checks][artwork-cache] verify cached covers
can return while an unrelated network response is held. All **16 actual API 34
checks** pass; the observed cached fetch takes **1 ms**, reuses the bitmap and
makes no duplicate request. Cold loads remain serialized with the existing
8 MiB body, 1024 px edge and 16 MiB cache caps. Final gates pass **3,596 tests /
377 suites**, and both builds pass. [Family installation][artwork-deployment]
verifies APK `dc80c3af…`, all 97 settings/source configuration unchanged,
reclaimed upload and zero active listeners. Exact emulator preferences were
restored/synced and the emulator exited. Physical Portal cache latency/FPS is
unmeasured; whole-platform acceptance remains open.

**Latest delivery:** [local-artwork checks][local-artwork] establish one local
read/decode producer, interruptible queued cancellation and cleanup of results
discarded during UI delivery. All **22 actual API 34 provider checks** and
**16 network regression checks** pass; the cancelled path opens zero times.
Receiver-blocked JVM coroutine tests verify disposal exactly once before
publication, and retention after successful publication. Fresh full gates pass
**3,605 tests / 380 suites**, with both builds passing.
[Family installation][local-artwork-deployment] verifies APK `1b56603f…`,
all 97 settings/source configuration unchanged, upload reclaimed and zero
active listeners. Eight emulator preference files were restored exactly and
synced; both receipts were removed and the emulator exited.
This bounds concurrent live producer work, not the entire heap; an already
blocked provider can retain admission until its read returns. Physical heap,
CPU/FPS and artwork-loading improvement remain unmeasured. The full goal
remains incomplete.

One subsequent **40.181-second**, profiled Family Home/Apps attribution block
reported **133 frames**, **p95 41.50 ms** and **p99 155.00 ms**, including first
Apps visit work. Sampling has overhead and incomplete stack admission; this
is not a matched acceptance comparison or a causal proof. No justified new
rendering change was identified, so the investigation was parked early with
no source trial. The rejected/parked Home experiments remain closed. Installed
APK and all 97 settings/source configuration were verified unchanged; the
screensaver restore was acknowledged and the collector has zero listeners.
See [the bounded attribution receipt][home-tail-attribution].

The separate typography candidate at `b2bafb7d` is preserved on its original
branch, parked after its final native harness timed out. Its earlier captures
do not verify the final candidate. This latest deployed build starts at verified
`848c4246` and excludes that candidate.

[artwork-cache]: ../mocks/portal-premium-player-20261030/evidence/native-artwork-cache/checks.json
[artwork-deployment]: ../mocks/portal-premium-player-20261030/evidence/family-artwork-cache/verification.json
[local-artwork]: ../mocks/portal-premium-player-20261030/evidence/native-local-artwork/checks.json
[local-artwork-deployment]: ../mocks/portal-premium-player-20261030/evidence/family-local-artwork/verification.json

[clock-size]: ../mocks/portal-premium-player-20261030/evidence/native-clock-size/checks.json
[clock-size-deployment]: ../mocks/portal-premium-player-20261030/evidence/family-clock-size/verification.json

[home]: ../mocks/portal-premium-player-20261030/evidence/family-optimized-home/measurement.json
[home-tail-attribution]: ../mocks/portal-premium-player-20261030/evidence/family-home-tail-attribution/measurement.json
[filmstrip]: ../mocks/portal-premium-player-20261030/evidence/family-native-filmstrip/measurement.json
[startup]: ../mocks/portal-premium-player-20261030/evidence/family-startup-orientation-recovery/verification.json
[retention]: ../mocks/portal-premium-player-20261030/evidence/native-night-retention/checks.json
[live-deployment]: ../mocks/portal-premium-player-20261030/evidence/family-night-live-settings/verification.json
[live-controls]: ../mocks/portal-premium-player-20261030/evidence/native-night-live-settings/checks.json
[toggles]: ../mocks/portal-premium-player-20261030/evidence/native-night-live-toggle/checks.json
[music-ui]: ../mocks/portal-premium-player-20261030/evidence/native/checks.json
[music-continuity]: ../mocks/portal-premium-player-20261030/evidence/music-continuity-native/checks.json
[music-deployment]: ../mocks/portal-premium-player-20261030/evidence/family-music-continuity/verification.json

[physical-night]: ../mocks/portal-premium-player-20261030/evidence/family-night-physical/verification.json
[first-reveal]: ../mocks/portal-premium-player-20261030/evidence/family-first-scene-reveal/verification.json

[brightness]: ../mocks/portal-premium-player-20261030/evidence/family-night-brightness/verification.json
[bounded-photos]: ../mocks/portal-premium-player-20261030/evidence/native-bounded-local-photos/checks.json
[bounded-deployment]: ../mocks/portal-premium-player-20261030/evidence/family-bounded-local-photos/verification.json
[remote-photos]: ../mocks/portal-premium-player-20261030/evidence/native-bounded-remote-photos/checks.json
[remote-deployment]: ../mocks/portal-premium-player-20261030/evidence/family-bounded-remote-photos/verification.json
[dream-return]: ../mocks/portal-premium-player-20261030/evidence/native-dream-tap-return/checks.json
[metadata]: ../mocks/portal-premium-player-20261030/evidence/native-face-metadata/checks.json
[metadata-deployment]: ../mocks/portal-premium-player-20261030/evidence/family-face-metadata/verification.json
[clock-work]: ../mocks/portal-premium-player-20261030/evidence/native-clock-work/checks.json
[clock-pending]: ../mocks/portal-premium-player-20261030/evidence/family-clock-work/pending.json

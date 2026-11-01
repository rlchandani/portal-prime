# Portal night/player completion audit — 2026-10-31

**Overall status: incomplete.** Current source and recorded checks support substantial delivery across all four requested areas. Family Area’s earlier physical check passed for clock appearance, comfortable brightness and smooth same-photo return on the older tested artifact. This audit combines recorded checks with that scoped human report; the full performance/music goal remains incomplete.

**Latest delivery, November 1 — Framed Print colors.** The
[actual Family print check](portal-framed-print-native-2026-11-01.md) passes
**22/22 native image assertions** and **3,722 unit tests**. The lower blue
portrait marker improves from **29,54,104 to its original 64,120,232**.
A measured overlay guard removes the clock shade only when the photo/mat
are clear; overlapping or unknown overlays retain protection. Optimized APK
**625e9f5d…** is installed, with all settings/source fields, brightness policy,
accessibility configuration and original Dream restored. Final native health
was confirmed **14 minutes 8 seconds** into the original 30-minute slot.
Centered/wide overlay fallback is unit-tested, not pixel-captured. Optical
comfort, other legacy layouts and whole-platform performance remain open.

**Latest verification, November 1 — music dragging.** The
[actual Family drag check](portal-music-drag-native-2026-11-01.md) passes
**429/429 assertions across 20 gestures**. Enabled controls emit one UI action
after release: **384 MOVE events / 16 actions**; four disabled gestures produce
zero actions across **96 MOVE events**. Source already had this behavior, so this
closes a verification gap rather than adding a runtime optimization. The original
optimized APK **e4f8b0ce…**, settings/sources, brightness policy and Dream are
restored, with fresh health by **13 minutes 45 seconds** into the original
30-minute slot. The first observer timeout is retained; one bounded rerun passes.
This accepts paused silent-fixture dragging, not panel FPS or provider audio.

**Latest delivery, November 1 — music slider touch area.** The
[actual Family touch check](portal-music-touch-native-2026-11-01.md) found that
48 dp outer spacing left an 18 px inner gesture area. The fix increases that
area to **54 px**, retaining the 18 px painted thumb. Previously ignored edge
taps improve from **0/16 to 16/16**; **112/112 focused native assertions** and
**3,717 unit tests** pass. Optimized APK **e4f8b0ce…** is installed, with fresh
health verified **18 minutes 36 seconds** into the original 30-minute slot.
All settings, sources, pin/accessibility configuration, font scale and brightness
policy are preserved, with the original Dream restored. This accepts sampled
silent UI interaction, not audio, dragging, panel FPS or brightness comfort.

**Previous delivery, November 1 — Gallery photo colors.** The
[actual Family Gallery check](portal-gallery-native-2026-11-01.md) found and fixed
the clock gradient darkening Gallery's photos. Optimized APK **e47d6e71…** is
was installed before the music follow-on. **80/80 native image/geometry assertions** cover landscape and portrait
prints, a matched portrait spread, and its pinned overlay. Both spread photos
return with byte-identical pixels after the night clock; one tap preserves the
same Files activity/window/process. **3,717 unit tests** pass. Delivery and fresh
health verification finished **15 minutes 29 seconds** into the original
30-minute slot. All 97 settings, 22 source fields and original Dream are restored.
This accepts those compositions and retention, not panel FPS or physical
brightness comfort. Earlier APK references below describe historical checks.

**Current-build pinned-overlay verification, November 1:** the
[actual Family overlay gate](portal-pinned-brightness-delivery-2026-11-01.md)
passes **five brightness-command ramps and two retained-app tap returns** on
installed optimized APK **6c1adacc…**. Live request-to-endpoint observations are
**3.164, 3.159 and 3.196 seconds**. The same Files activity/window/process
survives both taps, and the default-photo interior returns with identical
pixels. All 97 settings, 22 source records, pin/accessibility/global brightness
state and original Dream are restored. The run took **75.734 seconds**, without
an install or instrumentation. This closes the changed overlay path's native
verification gap; it adds no runtime feature, optical-comfort or FPS claim.

**Latest delivery, November 1 — hardware-aware brightness updates.**
[Actual Family verification](portal-night-brightness-delivery-2026-11-01.md) passes
**18/18** checks. Near-dark fades send **6 window updates instead of 85** in
each direction; default-policy return sends **7 instead of 84**. The logical
three-second fade and exact endpoints remain intact. **3,715 unit tests** pass;
optimized APK **6c1adacc…** is installed with settings, sources, pin/accessibility
state and original Dream preserved. This accepts redundant-update reduction,
not optical smoothness, comfort or FPS. The user cannot perform the physical
check now; that acceptance remains pending. Earlier artifact references below
describe historical deliveries.

**1 November follow-on — HTTP source startup cancellation delivered.**
[Matched Family verification](portal-http-source-discovery-2026-11-01.md) passes
**36/36** actual controller/HTTP checks: four stalled Immich/WebDAV requests went
from no observed disconnection within **2,000 ms** to **2–4 ms** after stop.
**3,697 unit tests** pass. Optimized APK **6f413f87…** is installed with all
settings/source records, pin/accessibility state and the original Dream preserved.
This accepts those local HTTP discovery paths; it does not close DNS/TLS/live NAS,
camera, optical comfort or whole-platform FPS acceptance. The full goal stays open.

**Video night-return delivery, November 1:** the
[actual Family video gate](portal-video-night-retention-2026-11-01.md) passes
**92/92** checks. Returning to a retained player removes an extra seek
(**1 → 0**), its position stays fixed through the clock hold, and real surface
recreation recovers **1,118 → 1,118 ms** with one required seek. User pause is
preserved. All **3,689 unit tests** pass and optimized APK **e80660a4…** is
installed with configuration and original Dream preserved. This accepts the
tested synthetic local-video path; remote/camera cancellation, optical comfort
and panel FPS retain their limits.

**Filmstrip admission delivery, November 1:** the
[bounded file scanner](portal-filmstrip-file-admission-2026-11-01.md) avoids
whole-file buffering before unsupported-format fallback. The JVM 16 MiB GIF
probe falls from **16,778,976 to 10,024 allocated bytes**; one matched actual
Family decoder probe falls from **57.78 to 0.39 ms**. The ART counter's recorded
zero is not proof of zero allocation. All **3,684 unit tests** pass and optimized
APK **eafade20…** is installed with settings, sources and original Dream preserved.
Animated pixel/GPU residency, panel FPS and natural-light comfort remain open.

**Paused music-photo verification, November 1:** the
[focused Family check](portal-paused-capture-2026-11-01.md) passed **10/10**
checks across **nine captures**; paused pixels remained identical and manual
Next responded. This adds opt-in test-observer diagnostics. The earlier
capture timeout did not reproduce, so its cause is not declared fixed.
Natural room-light comfort on the current transition remains pending; the
user cannot perform that physical check now.

**SMB startup delivery, November 1:** the
[preparation-failure guard](portal-smb-startup-failure-2026-11-01.md) releases
connections on listing/handoff failure and routes recoverable exceptions to
fallback photos. The focused gate passes **53/53**, the full unit gate passes
**3,678 tests**, and optimized APK **eb6296d3…** is verified on Family Area.
No NAS source is configured there; actual NAS recovery, blocked-operation
cancellation and measured FPS gains are not accepted by this delivery.

**Subsequent user feedback:** brightness and clock changes still jumped.
The [new coordinated night transition][night-crossfade] at **df4405bb**
fixes the immediate clock visibility switch and pinned-overlay brightness
assignment, adds a three-second fade in both directions, and preserves direct
tap exit. Recorded checks pass all 3,615 JVM tests and 430 native API 34 checks;
Family's installed APK `f602ba…` is independently verified with all 97 settings
unchanged. The earlier
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
| Accept the final physical night-clock fade and retained-media return on Family Area | [Earlier human physical check][physical-night]: **“Clock appeared; brightness comfortable; same photo returned smoothly.”** This resolves the earlier failed appearance check only for that older artifact and tested sequence; covered lux, precise dwell, physical FPS and direct date readability were not measured. Subsequent user feedback reported continued brightness/clock jumps, prompting the [three-second coordinated transition][night-crossfade] at **df4405bb**. Recorded software checks and verified Family delivery do not establish physical API 29 fade comfort/smoothness or rendered pinned-overlay acceptance. | incomplete |
| Apply clock settings live without recreating retained media | Current [notification](../app/src/main/java/com/immortal/launcher/NightSettingsChanges.kt), [settings hook](../app/src/main/java/com/immortal/launcher/SettingsGuard.kt) and photo controller apply only `darkRoomClock`, `darkRoomLux`, `nightClockBrightness`, `nightClockTone`. Observer replacement preserves visible night and requires fresh bright dwell; receiver identity and teardown protect ownership. **40e48686** has the [deployed `e08f21e…` receipt][live-deployment]; [live-control checks][live-controls] pass **136**, and **bed6c821** adds [108 passing toggle checks][toggles]. These recorded software checks accompany the earlier scoped physical appearance/comfort/return pass on the older artifact; they are not direct heap-identity proof or acceptance of the final fade. | verified |
| Retain media and pause choice through night/hidden/bright transitions | Native retention/live-control records support unchanged photo regions, process/window/activity, ready-scene count and explicit Resume behavior. [Actual API 34 Dream checks][dream-return] additionally retain a deliberately paused viewer through a real Dream: same activity/process, Paused / Resume visible, maximum photo-region channel difference **one**. Current controller suspends retained renderers/video/callbacks and unregisters its sensor on hidden hosts. The basic Family night-to-photo continuity check passed by human report on the older tested artifact. Physical API 29 tap/wake return and relevant optional camera/video cancellation remain unaccepted; already admitted work may finish into the paused queue. | incomplete |
| Deliver premium music UI: artwork, buttons, playlists, full and compact | Current [player UI](../app/src/main/java/com/immortal/launcher/MusicPlayerUi.kt) supplies artwork-led full/compact/photo layouts, transport, queue, seek/volume and guarded controls; [browse UI](../app/src/main/java/com/immortal/launcher/MusicBrowseUi.kt) supplies host-fed library, playlists and collection detail. [Native UI checks][music-ui] pass **106**; [continuity checks][music-continuity] pass **18** for retained Players selection/track across layouts in both palettes. Silent API 34 fixtures and generated artwork verify presentation, not provider data or audio. | verified |
| Accept the premium music UI on the target Portal | [Physical Family checks](portal-music-physical-ui-2026-10-31.md) pass **223/223 assertions** with 59 native captures. Actual Android 10 components cover dark/light, larger text, offline, collection/search navigation, transport and full/compact state continuity. Eight representative scenarios were visually reviewed without observed overlap. The [touch follow-on](portal-music-touch-native-2026-11-01.md) passes **112/112**, closes the sampled slider edge-touch gap and verifies queue checkable/checked state. The [drag gate](portal-music-drag-native-2026-11-01.md) passes **429/429**, verifying progress and release-only UI actions in 20 paused-fixture gestures. Silent data does not accept live provider/audio, actual photo integration, every touch pixel, playing-timer interaction or panel FPS. | verified for the delivered silent UI scope |

## Necessary remaining work

1. **Natural lighting and optical comfort remain open:** continued brightness/clock jumps prompted the [three-second coordinated transition][night-crossfade] and subsequent [Android 10 Dream delivery fix](portal-dream-brightness-2026-10-31.md). The current optimized APK `199aa8a1…` has recorded physical Dream and [pinned-overlay checks](portal-pinned-night-physical-2026-10-31.md), including native content fades, command ramps, retained photo regions and taps to the retained app. All **97 settings and 22 source fields** were restored. The earlier human appearance/comfort report applies to an older artifact; natural bright-room exit, optical comfort, short-interval autoplay pause and delivered panel timing on the current build remain unaccepted.
2. **Startup delivery is scoped:** the extra first-scene parent fade is removed and deployed within **01:02:44–01:32:44 UTC**. [Deployment/runtime receipt][first-reveal] retains the two-cycle helper’s stale three-cycle completion failure and independently validates the planned two observations without repeating the capture. [Actual API 34 Dream checks][dream-return] now verify continuation retirement, one retained-Home return, deliberate paused-viewer retention and both `singleTask` intent-mode transitions. The recording has zero near-black frames or post-Home reappearance. This closes the native emulator lifecycle gap; actual API 29 cold/panel presentation and stacked-Dream return remain open.
3. **Address the documented platform performance gaps**, led by Home/Apps tails and the affected screensaver paths. [Local WebView misses][bounded-photos] wait off Main for prepared copies. [Remote loading][remote-photos] now streams authenticated input with a **32 MiB** cap, prepares static copies through the shared decoder, preserves PNG transparency and cancels retired owners. Fresh gates pass **3,561 tests**. Actual API 34 WebView checks verify a **31-megapixel** remote photo delivered as **2400×1800**, alpha preservation, corrupt/oversized 404, plus a final local regression. [Remote Family deployment][remote-deployment] verifies the installed APK and all **97 settings** unchanged. These resolve the identified local/remote static loading gaps; physical startup presentation, animated GIF/WebP pixel residency and complete GPU residency remain open. Use the existing hardware budget and matched accepted baselines; retain only supported improvements. The warmed Filmstrip pass does not require another speculative renderer/layer experiment.
4. **Physical music UI acceptance is now recorded** for the delivered artwork, controls, playlist presentation and full/compact continuity; see the [223-check physical record](portal-music-physical-ui-2026-10-31.md). Backend/provider installation, credentials, real audio and provider-backed library wiring remain deliberately deferred; they are outside this goal’s remaining work. Photos used a test placeholder, so this gate does not accept its actual photo integration.

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
do not verify the final candidate. The historical deployment referenced here
started at verified `848c4246` and excluded that candidate; the current night
transition is **df4405bb**, documented in the [crossfade record][night-crossfade].

[night-crossfade]: portal-night-crossfade-2026-10-31.md

**Physical Dream follow-up:** USB authorization and wireless ADB are restored.
The [Android 10 Dream fix](portal-dream-brightness-2026-10-31.md) is installed as
optimized APK `199aa8a1…`. Actual Dream brightness ramps measured 3.001–3.204
seconds, recorded content faded both ways, the same visible photo returned and
one tap reached Home without Dream reappearance in a one-second check.
All 97 settings and 22 source fields were restored; the collector has zero
listeners. Fresh gates passed 3,615 tests / 381 suites and both builds.
System display-power delivery is verified; optical comfort, complete natural
light-cycle acceptance, pinned-overlay physical acceptance and panel FPS remain
open. The physical production music empty state was also inspected, but ready
library/playback acceptance remains open. The full goal is still incomplete.

**Physical music UI follow-up:** the
[actual Portal verification](portal-music-physical-ui-2026-10-31.md) passes
**223/223 assertions**, with 59 native captures across dark/light, offline,
larger text, navigation, state continuity and search. Eight representative
scenarios were visually reviewed without observed overlap. This is silent
native UI acceptance, not live library/provider/playback acceptance or a new
performance improvement. The original optimized build, all 97 settings and
22 source fields were restored, with zero active collector listeners.
The full goal remains incomplete.

**Physical pinned-overlay follow-up:** [the actual Android 10 gate](portal-pinned-night-physical-2026-10-31.md)
passes real dark entry, live 3% → 15% → 3% command ramps, registered disable,
exact photo-region return and taps from both photo and night clock to the same
pinned Files Activity/window/process. All five ramps had 17 sampled intermediate
values and lasted 2.960–3.009 seconds. Recorded content has 22 blended frames
in each direction at 12 fps. Settings, sources, original pin/Dream state and APK
were restored exactly. Natural bright-room exit, optical comfort, browser
document retention, short-interval autoplay pause and panel FPS remain open;
the broader goal is incomplete.

**Physical cold-start follow-up:** [the Family observation](portal-cold-start-physical-2026-11-01.md)
records approximately **3.42 seconds** of blank photo area after the native
Dream clock appears. A separate sampled first-renderer run attributes
**422.994 ms** to Main-thread WebView construction and **6.815 ms** to off-Main
metadata preparation; these are not a before/after comparison. Unsafe
force-stop attempts exposed an Android accessibility-manager system crash
and were excluded. Successful observations temporarily detached only
Immortal's accessibility components and verified exact restoration plus an
unchanged system process. The tap helper's window assertion failed despite
visual Home return; it remains a qualified failed receipt. No production
speed improvement was delivered in this bounded issue. Cold Shuffle startup
is parked and remains incomplete.

**Short-interval night follow-up:** [the actual Family gate](portal-night-autoplay-physical-2026-11-01.md)
passes native Whole Photo and legacy Ken Burns at **five-second intervals**.
After settled clock holds of **22.429 / 22.404 seconds**, both returned photo
regions match exactly, with **zero pixel difference**, and autoplay resumes.
Native logs show zero scene admissions while covered. All 97 settings and
22 source fields were restored, with the optimized APK unchanged and zero
collector listeners. Static local-photo retention is accepted for these two
cases; moving-photo/video behavior, natural bright exit and optical comfort
retain their existing qualifications. The recordings revealed a separate stale
minute in the returning photo clock, addressed by the subsequent fix below.

**Clock-reveal fix:** [the subsequent production change and physical checks](portal-clock-reveal-physical-2026-11-01.md)
close the stale-minute finding for static local photos in Whole Photo and
Ken Burns. Native recordings show the current minute from the earliest legible
photo-clock frame throughout the return fade. Both retained photo regions match
exactly, with zero pixel difference. **3,625 unit tests passed**, debug and
optimized builds passed, and optimized APK `36188b2c…` is installed on Family
Area. Time/date snapshots refresh without resuming hidden media or timers.
All settings, sources, accessibility services and original Dream state were
restored. The user cannot do the natural room-light check now, so that check
and optical brightness comfort remain unverified. The full goal remains
incomplete.

**Real music-photo pause fix:** [the Family Area physical gate](portal-music-photo-pause-2026-11-01.md)
passes **17/17 checks** using the real retained photo host rather than the earlier
placeholder. Paused photo-region difference after Full falls from **66 to 0**;
Full/Compact round trips retain the controller, photo and pause choice. Manual
photo navigation and fresh Resume dwell pass. All **3,652 unit tests** and builds
pass; optimized APK `8cd58604…` is installed on Family Area. Settings, sources,
permissions, original Dream and test APK are restored, with no collector listeners.
Provider/audio and physical FPS remain outside this gate. Ignored-setting recreation
and pending SMB ownership remain parked; the broader goal is incomplete.

**Music settings continuity:** [the configuration gate](portal-music-config-continuity-2026-11-01.md)
closes unrelated-settings controller recreation at **c88d9c91**. Family Area
passes **14/14 checks**: calendar and night-tone edits retain the controller and
exact photo pixels, while an interval edit still reconfigures playback. All
**3,657 unit tests** and builds pass. Optimized APK `a85761a7…` is installed with
settings, sources, persistent pin state and accessibility preserved. Two
paused-view observer attempts timed out before settings edits, so that capture
remains unverified. SMB ownership and the broader goal remain incomplete.

**Queued SMB ownership:** [the callback cleanup fix](portal-smb-callback-cleanup-2026-11-01.md)
at **aac4e9e8** releases connected sources whose pending UI deliveries are
discarded. The targeted gate goes from **10 failures to 38/38 passing checks**,
including two actual loopback TCP peer-EOF checks. All **3,668 unit tests** and
builds pass. Optimized APK `b79b56c7…` is installed on Family Area with its
configuration and original Dream preserved. No SMB server is configured there,
so actual NAS behavior and establishment/listing cancellation remain unverified.
This does not close the broader goal.

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

**Native legacy-style survey and still Mosaic fix:**
[the bounded Family Area delivery](portal-slideshow-style-matrix-2026-11-01.md)
at **8645cbcc** captures **31 static cases** and fixes a reproduced stopped-pan
crop. The delivered portrait and wide panorama retain all four corners with
aspect error below **0.06%**. The portrait's one border-pixel strict failure
remains recorded separately from its accepted whole-photo geometry; the
panorama passes strictly. **279 slideshow JavaScript tests pass**, both Android
builds pass, and optimized APK **bfc9430d…** is installed with all settings,
sources and original Dream restored. The full Kotlin gate was up-to-date,
not rerun. The original matrix has **9 strict passes, 16 marker-area failures,
5 cases outside its axis-aligned contract, and 1 reproduced crop**; these are
not 31 passing layouts. Other-style motion acceptance, natural brightness
comfort and the broader goal remain incomplete.

# Portal+ UX quality gate

Evidence checkpoint: **2026-10-05**. Branch: `codex/portal-plus-approved-ux`;
source checkpoint supplied for this review: `dfadc3b`.
Device measurement owner: **main**. This document defines acceptance and the next
measurement protocol; writing it does not execute or pass the device gates.

The Apple-quality aspiration means predictable navigation, direct manipulation,
prompt feedback, readable controls and uninterrupted motion on the actual Portal+.
It is an Immortal product target informed by Apple guidance, **not an “iOS
equivalent” certification**. Android window continuity, passing fixtures, or one
good frame percentile cannot establish that aspiration.

## Evidence baseline and decision

Use [native validation](portal-plus-native-validation.md) for artifact-specific
results and the [approved UX plan](../superpowers/plans/2026-10-04-portal-plus-approved-ux.md)
for the interaction contract. The plan contains older test counts; the validation
report is the newer evidence checkpoint.

| Item | Latest reported evidence | What it establishes |
|---|---|---|
| Installed debug APK | SHA-256 `5f1de1a9f9d657ceca15099cda34f791c1ec2e6799fdaada6e1a49bc4689b87f`; independently matched installed bytes | Identity of the current functional widget artifact |
| Physical viewport | Android 10/API 29; 2160 × 1440 px; density 180; 1920 × 1280 dp; active display mode reports 60.000004 Hz | Recorded geometry and reported display mode; usable IME bounds and observed presentation cadence still need evidence |
| JVM | 516 passing tests | Tested model/logic behavior; no native timing, visual or exhaustive interaction acceptance |
| Current widget device run | 62 assertions, including two-page mode parity, release-only persistence, repeated photo moves, held edge scrolling, short scrolling, drag/resize ACTION_CANCEL and two injected two-pointer handoffs; nine original widgets restored byte-for-byte | Scoped LTR/injected-pointer editing evidence; no physical RTL, arbitrary multi-pointer, provider-wide or frame-time pass |
| Browser preview | 101 assertions, zero browser errors | Preview behavior with sample fixtures; no native geometry, input or rendering pass |
| Historical timing | Settings p95 34.14 ms on `93a6c45d…`; paging p95 58.75 ms and tabs p95 62.14 ms on `7f9a9065…` | Earlier debug trials only; these exceed the historical assumed 60 Hz proxy budget. They cannot pass or fail final-APK timing |
| New Settings timing | `409bd1f3…` debug p95 65.91 ms; `484c3cc4…` release-style p95 43.89 ms; observer-free shell-input debug p95 61.90 ms | All fail the measured 60 Hz proxy target; single trials are not matched comparative certification |
| Current-APK performance | Functional widget artifact `5f1de1a9…` unmeasured | Rendering, input latency, startup and cadence acceptance remain pending |

**Overall gate: PENDING.** Preserve the reported passes above without extending
their scope. The report's 48 expanded-routing assertions belong to `df4c6c28…`,
not a complete final-APK interaction census. The separate pairing/discovery
approval request remains pending after automatic review blocked those actions;
the broad quality-testing authorization does not substitute for its response.
The [source control inventory](portal-plus-control-inventory.md) maps all 53
routes, all 95 registry entries, 76 supplemental IDs and 60 action families.
Route and registry ID sets were independently compared with source and matched.
It records source discrepancies and runtime unknowns rather than inheriting
earlier smoke-test results. Main owns live execution and records
actual outcomes; this documentation task performs no device operations.

## Sources and what transfers to Portal+

All sources below were accessed **2026-10-05**. Apple HIG and technical articles
were consulted through indexed official-source text; the Apple Tech Talk through
its official transcript. Indexed text can lag the live page. Android sources were
read through Google's official developer-documentation corpus. Recheck source
revisions when changing a numerical policy.

| ID | Exact primary source | Guidance used here |
|---|---|---|
| A1 | Apple HIG — [Motion](https://developer.apple.com/design/human-interface-guidelines/motion) | Motion should have a purpose, follow gesture expectations and be optional; essential meaning needs another cue |
| A2 | Apple HIG — [Loading](https://developer.apple.com/design/human-interface-guidelines/loading) | Show useful content promptly, load in the background, permit other actions and communicate ongoing work |
| A3 | Apple HIG — [Feedback](https://developer.apple.com/design/human-interface-guidelines/feedback) | Keep status near its context; make feedback accessible; match interruption to consequence; explain unsuccessful commands |
| A4 | Apple HIG — [Accessibility](https://developer.apple.com/design/human-interface-guidelines/accessibility) | Audit the actual interface; support larger text, adequate contrast, non-color cues and reduced motion |
| A5 | Apple — [Improving app responsiveness](https://developer.apple.com/documentation/xcode/improving-app-responsiveness) | Rough guidance: discrete main-thread work below 100 ms; continuous work within a refresh interval; roughly 5 ms of main-thread update work helps leave headroom |
| A6 | Apple — [Understanding hangs in your app](https://developer.apple.com/documentation/xcode/understanding-hangs-in-your-app) | Perceived response spans input delivery, main-thread work, rendering and display; many Apple tools report hangs above 250 ms, which is a detection threshold |
| A7 | Apple — [Understanding hitches in your app](https://developer.apple.com/documentation/xcode/understanding-hitches-in-your-app) | A late presentation interrupts motion; hitch rate is pause milliseconds per second. Indexed article bands: ≤10 good, ≤25 warning, ≤50 critical, >50 immediate attention |
| A8 | Apple Tech Talk — [Explore UI animation hitches and the render loop](https://developer.apple.com/videos/play/tech-talks/10855/) | Transcript defines hitch time ratio and recommends <5 ms/s as good, 5–10 investigation, >10 immediate investigation |
| D1 | Android — [Jetpack Compose performance](https://developer.android.com/develop/ui/compose/performance) | Measure real journeys; use release mode, R8 and documented compilation/profile conditions; debug overhead changes interpretation |
| D2 | Android — [Capture Macrobenchmark metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics) | Separate startup and frame metrics; `frameDurationCpuMs` measures CPU production; `frameOverrunMs` is available only on API 31+ |
| D3 | Android — [App startup time](https://developer.android.com/topic/performance/issues/launch-time) | Distinguish cold, warm and hot launch; TTID is first display, TTFD needs a meaningful fully-drawn signal |
| D4 | Android — [Overview of system tracing](https://developer.android.com/topic/performance/tracing) | System traces investigate scheduling, app threads and rendering work; Android 10 supports Perfetto, subject to firmware/tool availability |
| D5 | Android Compose — [API defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) | Minimum 48 × 48 dp touch targets, meaningful semantics, long-click labels; expanded targets must not overlap ambiguously |
| D6 | Android — [Make apps more accessible](https://developer.android.com/guide/topics/ui/accessibility/apps) | Text contrast guidance: 4.5:1 for small text, 3:1 for larger text using its 18 sp / 14 sp bold cutoff; descriptions and actual accessibility testing |
| D7 | Android Compose — [Focus in Compose](https://developer.android.com/develop/ui/compose/touch-input/focus) | Predictable Tab/D-pad order, visible focus, focus restoration and scrolling focused controls into view |

A7 and A8 publish different hitch bands. Record the source/version when using
either; do not present the older <5 ms/s band as Apple's universal current rule.
This gate chooses <5 ms/s as an **internal aspiration**, only where presentation
evidence supports that metric. Apple tools, APIs, platform point sizes, haptics
and iPad keyboard conventions are not Portal requirements. Apply Android input
and accessibility behavior on this no-GMS device.

## G0 — Build the interaction census before claiming “all”

**PENDING.** A source-derived inventory and current UI evidence are both mandatory.
A fixture list, screenshot gallery, registry unit test or Activity count alone is
not the denominator. The revision 04 handoff
(`mocks/portal-plus-complete/IMPLEMENTATION-HANDOFF.md`) and its coverage/state
files are reference inputs; reconcile them with current
native source and the installed artifact.

Create one inventory row per **entry → control/action → state → destination/return**.
Multiple entries to the same body still need route checks; different visibility,
save, cancellation or input behavior needs separate state rows.

| Inventory input | Launcher package source to inspect (manifest path noted separately) | Required reconciliation |
|---|---|---|
| Entry points and handoffs | `app/src/main/AndroidManifest.xml`; `HomeActivity`, `ApprovedHomeNavigation`, `ApprovedLauncherTabs`, `ApprovedAppsChrome`, `AppSwitcherActivity`, `ToolsActivity`, Store/APK entry code | Dock, header, favorites, widget/library, search, direct Activity/intent and external return paths |
| Settings shell and routes | `ApprovedSettingsNavigation`, `ApprovedSettingsHistory`, `ImmortalSettingsActivity`, `ApprovedSettingsShell` | Every category/page and local-body versus real Activity ownership; nested Back and cache eviction |
| Registry scalars | `settings/SettingsDomains`, `SettingSpec`, `SettingsPresentation`, `SettingsRenderer`, `ApprovedSettingsLazyProjection` | Every key, type, limits/options, tier, `visible` predicate, `onApplied`, rendered row and explicitly excluded/derived key |
| Native/rich exceptions | `ApprovedSettingsSearch` supplemental fields; `*SettingsActivity`, `*ConnectActivity`, URL/folder pickers, `ApprovedEditors*`, `ApprovedSources*`, Display/Wallpaper and tool bodies | Controls outside generic rows, atomic groups, immediate-save fields, previews, forms and service ownership |
| Dialogs and text input | Native `Dialog`, `AlertDialog`, menu/popup, `TextField`/`BasicTextField`, `BackHandler`, focus/key/IME declarations across the package | Every actual popup window, dismiss path, commit boundary, keyboard field and accessibility action |
| Home editing | `HomeWidgetStore`, `ApprovedWidgetPacking`, `ApprovedPackedWidgetGrid`, widget bodies and library | Every native widget family, provider exceptions, size constraints, page bounds and gesture alternatives |

Each row records: stable ID; source file/symbol and source revision; entry;
prerequisites/tier/theme; observed label and semantics; before/after data
fingerprint; commit rule; expected host/window and Back/Cancel result; evidence ID;
artifact hash; result **PASS / FAIL / PENDING / N/A**. Record reason and source for
N/A (for example, hardware has no battery), not “couldn't find it.”

Audit both directions: every declared action maps to a row, and every observed
action maps back to source. Walk lazy lists to the end, open popup windows and
exercise parent states; one hierarchy snapshot misses unmounted/hidden rows.
Publish covered/applicable counts by surface and state. A source/UI mismatch
creates an unresolved row. A previously passing row becomes pending after a
relevant implementation or artifact change unless equivalence is demonstrated.
Representative timing samples must be labeled representative, never exhaustive.

### Source seed to expand

The source read for this checkpoint declares seven everyday categories plus
Advanced and **53 `ApprovedSettingsPage` IDs**. This is a navigation seed, not
53 validated screens or a complete control inventory.

| Category / route family | Seed IDs to reconcile |
|---|---|
| Appearance | `display`, `appearance`, `wallpaper` |
| Photos & screensaver | `screensaver`, `sources`, `styles`, `faces`, `clock`, `dismiss`, `folder`, `album`, `immich`, `albums`, `smb`, `dav`, `web` |
| Home & widgets | `widgets`, `cameras`, `weather`, `calendar`, `calendar-url`, `world`, `countdowns`, `crypto`, `todo`, `news`, `stocks`, `alpaca`, `options` |
| Sound & wake / shared entries | `touch`, `chime`, `welcome`, `sunrise`, `alarm`, `lamp`, `quickbar` |
| Connections | `multiroom`, `mqtt`, `ha`, `ha-entities`, `ha-dashboard`, `phone`, `intercom`, `speed` |
| Input & accessibility / Device | `system`, `boot`, `device`, `sleep`, `health`, `apk`, `help` |
| Advanced-only pages beyond the families above | `managed`, `debug`; tier policy also moves technical routes above into Advanced |

Category membership overlaps: test each visible entry, not just this grouping.
Registry domains are `screensaver`, `calendar`, `immortal`, `mqtt`, `quickbar`,
`fleet`, `chime`, `digitalclock`, `welcome`, `sunrise`; rich/native fields extend
them. Current Apps source shows Store, Music, Calls and Settings built-in tiles;
Tools lives in the dock. Do not assume the older handoff's five built-in tiles
are the current UI denominator.

## Required native interaction map

All rows below are **PENDING as complete gates**. Apply the common measurements
in the next section to each applicable row. Evidence already reported is retained
in the baseline, including the limited editing pass.

| Gate | Required interactions and states | Observable acceptance / evidence |
|---|---|---|
| G1 Home and launcher navigation | Header/dock/search/favorites/library entries; Home → Apps → Tools → Home; page arrows/swipes; first visit/revisit; Photos and Settings exit/return; Store/APK Tools/Apps/search returns, repeated incoming search | One retained `HomeActivity`/native window for local tabs; correct selected tab and retained page/query/scroll; dark launcher unaffected by Settings theme. Photos is full-screen. Record origin and return identity; inactive tabs cannot intercept touch, Back or semantics |
| G2 Apps | Tap installed/built-in tile; long press and accessible action menu; movement that cancels long press; scroll; search/type/clear/no match; folders/open/rename/membership; favorites and Recents actions; external launch/return | Exactly one intended action; long press never launches the app; scrolling never opens a menu; Tools dock remains reachable without duplicate tile/options links. Cancel preserves drafts where applicable; immediate membership changes follow the documented commit rule. Installed-app internals are separately scoped |
| G3 Tools | Alarm, Cameras, Countdowns, Lamp, Bedtime story, Intercom, Timers, Leave a note, Converter, ISS passes, Aurora outlook; Advanced Speed test; every tile and nested Back/close; retained Notes | Embedded tools return to Tools in the same launcher window; Cameras/Lamp use their declared handoffs. Validate calculator/text/timer controls as actions, not titles. Loading, empty, failure, retry, cancellation and audio/network teardown have native evidence; no preview can stand in for a real operation |
| G4 Settings shell and scalars | All seven categories, Advanced off/on, light/dark; every scalar row; first mount/revisit; revisit after >4 scalar panes; nested navigation, search result and Home button; long lists | Fixed sidebar/header; correct title/parent/highlight; only details animate. One Settings host for declared local routes. Toggle/stepper/enum/string exposes the correct value, limits, wrap and errors; apply triggers the domain effect once. Revisit/recreation restores route/scroll/value, with no stale async read overwriting a newer edit |
| G5 Rich editors and internal/external handoffs | Style/face/source selection, clock; world/crypto/countdown/to-do/news/stocks/options collections; alarm/welcome/chime/sunrise/sleep; HA, MQTT, multi-room, boot, health, Intercom; source/album/calendar/web/folder and credential forms; HA chooser/dashboard, phone, Help, APK, system screens | Existing editor behavior and validators remain. Local bodies retain shell/parent; actual Activity handoffs match the inventory and return correctly. Save/Apply, immediate-save, Done, Cancel and Back are distinguished. Invalid form stays editable; cancelled requests cannot apply late results. No credential draft is restored after recreation |
| G6 Conditional and Advanced controls | Every `visible` predicate and bespoke UI condition, both false and true; combined conditions; tier consent accept/cancel; search into hidden/dependent/managed fields | Correct rows are reachable only with their prerequisites; hiding never clears values, switches source, disables services or removes widgets. Search explains prerequisites and lands at the actual control. Consent is not inferred from search. Managed fields stay read-only; unavailable hardware stays unavailable |
| G7 Dialogs, menus and confirmations | Apps menus/folder/favorites; widget Size/Move/Remove; timer; collection remove; Advanced consent; Updates/install confirmation; each discovered native popup; Back, Cancel, outside tap, explicit confirm; reopen | Popup is included in UI evidence, fits usable bounds and owns input/focus. Background cannot activate through scrim. Dismiss restores origin/focus; no save from Cancel unless source explicitly defines prior immediate commits. Confirm acts once on the correct item. Back from Home timer returns Home; Tools action selects Tools |
| G8 Widget editing | Every native family/size; Customize/Done; drag, two consecutive equal-size moves, resize, Move across pages, Remove; cancel mid-gesture and mid-dialog; RTL; final-page/viewport edges; provider widget exceptions | Normal/edit bounds and order match until release. No preference write during pointer movement; one final layout commit on release, no draft commit on cancellation. Stable IDs, neighbors and first-photo hero survive; targets reachable without covering dock/favorites. Numbered Move/Size/Remove/Settings provide non-drag access. Compare before/after fingerprints and restore originals |
| G9 Keyboard and accessibility | Apps/Settings search, Notes, device name, folder rename, URL/credential/camera/collection fields; keyboard open/close/Next/Done; physical Tab/Shift-Tab/D-pad/Enter/Back; semantics; large text; reduced motion | Focused text, error and commit/cancel remain reachable above IME; no clipped dialog or trapped focus. First Back dismisses IME when appropriate, then follows declared route. No lost/duplicated text or accidental submit. Only active pane is reachable. Each action has meaningful role/state/label; long-press and drag alternatives work |
| G10 Loading, feedback and refresh | Home photo/data arrival; Apps inventory/icons; Store catalog/icons/updates; source tests and discovery; Tools network/audio/progress; cached/uncached, empty, slow, failure, retry and cancel | Useful shell/cache remains usable while loading; stable content geometry, contextual progress/errors and truthful completion. Old results cannot overwrite new edits or reappear after cancel. Separate service completion from UI acknowledgement. Refresh obeys declared cadence and lifecycle, including return to an inactive tab |

### Conditional cases that must not disappear in a generic-row audit

Enumerate complete predicate truth tables from source; these are required seeds:

- **Screensaver:** master off/on, Classic versus engine Fit, overnight times,
  sleep duration/actions, ambient sound volume; Immich/WebDAV caching versus
  local/default sources; configured technical source while Advanced is off;
  battery-only setting unavailable on this batteryless Portal+.
- **Clock/calendar/quick buttons:** clock master and face-dependent fields;
  calendar link absent/present × widget off/on; quickbar parent and dependent
  option; prerequisite search must not bypass these conditions.
- **Display:** permission absent/present, auto/manual brightness, Night Shift
  off/on and From/Until/Warmth; async snapshot arrival during a user edit.
- **Bespoke editors:** Sunrise editors stay available while disabled; standalone
  Sleep pause-audio/close-app presentation differs from registry visibility,
  with tier policy also applied. Verify the actual source/UI intersection.
  Chime's three sound parents give eight combinations for five preview actions.
  Include welcome TTS, MQTT/multi-room parents and connection/discovery states.
- **Atomic forms:** correct validators and defaults, including calendar
  `webcal://`, supported shared-album providers, camera `rtsp://`, empty Immich
  album list/Whole library, SMB/WebDAV test → count/Done, HA name/entity-ID search
  and expansion beyond twelve rows. Preserve existing IDs and normalization.

Use temporary entries and a private before/after snapshot for mutation checks.
Document each editor's true commit boundary before testing Cancel: link editors
may save immediately, while credential groups and drag drafts have atomic rules.
Restore original values and configuration; compare fingerprints without publishing
credentials, household content or device tokens.

## Measurement definitions and acceptance policy

**Numerical targets marked I are Immortal internal targets**, chosen for this
aspiration and repeatable screening. They are not Apple requirements. The 160 ms
transition and geometry values marked C come from the approved contract. D marks
Android guidance. Missing measurements stay pending; do not relax targets after a
bad result without documenting a new product decision and its rationale.

### Keep the six quantities separate

| Quantity | Definition and evidence | Never substitute |
|---|---|---|
| Viewport geometry | Physical px, density, dp, font scale, orientation, system/IME insets; native bounds and screenshots of settled UI | Resolution for refresh rate; browser CSS px for Android dp |
| Rendering frames | App frame-production intervals and, where available, actual presentation deadlines/timestamps during active motion | A frame elapsed proxy for input latency; idle zero-frame samples for smoothness |
| Input acknowledgement | Action's input timestamp → first visible pressed/state/loading response; also record event delivery/handler separately if available | Test-command duration, idle synchronization or async task completion |
| Action readiness / latency | Input → destination usable or local result committed; physical input-to-visible response requires synchronized display observation | Same-window identity or `FrameCompleted` for input-to-photon |
| Startup state | Cold: new process; warm: existing process with recreated Activity; hot: retained Activity resumed. Record actual lifecycle evidence, TTID and usable-content endpoint separately | Repeated tab selection for warm launch; first-ever/JIT sample for warmed steady state |
| Refresh cadence | Data producer deadlines, request start/finish, cache age and lifecycle behavior over wall time | Screen Hz, frame count, timer tick or recomposition count |

On the recorded density, `px = dp × 180/160 = dp × 1.125`.
Thus a 24 dp gutter is 27 px, a 48 dp target is 54 px, a 32 dp inset is
36 px. These conversions describe geometry only. Set frame budget
`B = 1000 / measured_Hz` ms; 16.67 ms applies **only if 60 Hz is verified**.
Record display mode and observed vsync cadence during motion. An assumed rate
supports screening, not a deadline-based pass.

### G11–G15 quantitative gates

| Gate / origin | Acceptance target | How to decide |
|---|---|---|
| G11 Geometry (C/I/D) | C: four-column shared Home/Customize packing; 24 dp gutters; bounded content, 24 dp navigation gap, 32 dp bottom inset; favorites 88 dp tiles / 56 dp icons / 22 sp at target scale. I: settled shell/edit bounds differ by ≤1 physical px after coordinate alignment; zero clipping, occlusion or unreachable actions. D: touch targets ≥48 × 48 dp | Measure actual bounds at default scale, each page and dialog, then keyboard and enlarged-text variants. Reference comparisons use matched content; mask only named dynamic regions. Typography/wrapping/color differences are logged, not hidden by a blanket pixel tolerance |
| G12 Motion/rendering (C/I) | C: local tab/detail and approved internal fade duration 160 ms. I: measured start-to-settled duration within one B of contract under normal motion; zero black/blank flashes or wrong-direction jumps. Active-frame proxy: p95 ≤B, p99 ≤2B, ≤1% proxy over-budget, no sample >100 ms. Presentation aspiration: hitch time ratio <5 ms/s where measurable | Every trial/interaction family must meet its applicable targets. Count actual misses separately when deadlines exist. Repeated visible hitch fails even if aggregate passes. Proxy success alone leaves presentation acceptance pending |
| G13 Input/feedback (I, informed by A3/A5/A6) | Discrete acknowledgement p95 ≤100 ms, maximum ≤250 ms; local navigation/dialog/local apply usable p95 ≤300 ms; continuous drag/resize/scroll visible tracking p95 ≤3B; zero lost, duplicated or misdirected actions | Measure from the defined event boundary; include errors and cancellation. The 250 ms ceiling is an internal guardrail, not permission for ordinary actions to feel delayed or Apple's quality threshold. Network finish is reported separately |
| G14 Startup (I, informed by D3) | Cold p95 TTID ≤1500 ms / usable shell ≤2000 ms; warm ≤500/750 ms; hot ≤250/300 ms. No unexplained blank frame or state loss | Independent cold/warm/hot cohorts. Usable shell means real local navigation and cached/fallback content work; separately time remote-content readiness. Do not label a manual usable-shell marker Android TTFD unless the fully-drawn signal is verified |
| G15 Loading/cadence/resources (I, informed by A2/D1) | Ongoing-work state visible by 500 ms if incomplete; existing useful content remains. Every operation has a source-derived timeout/retry policy and visible recovery. Zero new UI-owned requests while its owner is inactive after teardown; no cadence reset solely from paging/tab visits | Inventory each producer's interval/cache/backoff/cancel deadline and ownership before observation. Across at least two intervals/backoff steps, match actual starts to that policy; distinguish global services from UI-owned work. Data arrival and retries must also meet G12/G13 |

G12 proxy limits retain the [existing performance audit](../superpowers/plans/portal-plus-performance-audit.md)
screening policy. G13–G15 numerical limits are new internal product targets; no
current timing evidence passes them. A smooth 160 ms animation can still start
late, and timely acknowledgement can precede a hitch: pass both gates separately.
For reduced motion, use a documented instant/fade alternative and apply the
same input/geometry gates; do not require a 160 ms slide.

Accessibility acceptance also requires D6 contrast for actual foreground/background
pairs in both Settings themes and the dark launcher, plus distinguishable focus,
selection, error and disabled states without color alone. Test the largest
font scale exposed by the firmware and an internal 1.3× case if available;
record exact scale, unavailable modes and any content loss. With animations
disabled/reduced, navigation and essential feedback must still work. Verify
available assistive technology on this Portal; a semantics dump alone cannot
claim TalkBack usability, and an unavailable reader is a remaining coverage
limit. Do not install Google services to manufacture support.

## Main's measurement protocol

1. **Freeze identity and conditions.** Record installed APK SHA-256, source
   revision, package/build type, R8/profile/compilation state, Android/firmware,
   viewport/insets/font scale/layout direction, theme, measured Hz, thermal
   state, network/cache state and sanitized content counts. Keep widget/layout
   configuration comparable. Main alone controls the device; this document
   does not run Gradle, adb, installation or UI automation.
2. **Finish G0 and functional coverage first.** Expand the seed into every
   applicable control/state and handoff. Capture current UI, bounds, semantics,
   origin/return identity and data effects. Include empty/populated, enabled/
   disabled, Advanced off/on, light/dark, loading/error and IME states where
   applicable. The frozen references remain read-only. A missing fixture title
   is not a native failure; a missing source-mapped native action needs diagnosis.
3. **Separate observation from timing.** Stop the screenshot/UI observer before
   rendering runs, including popup inspection. Log any accessibility client
   that remains attached. Avoid per-frame hierarchy polling and on-device video
   capture during timing. Use separate functional captures and timing runs;
   disclose profiler overhead and reproduce issues with minimal instrumentation.
4. **Measure distinct cohorts.** Keep first route mount, warmed revisit, >4-pane
   eviction/rebuild, recreation, cold/warm/hot startup, cache miss and data
   arrival separate. Record first-install/JIT effects independently. Startup
   samples need verified process/Activity state; do not clear user data or infer
   cold start from slow navigation.
5. **Cover every body and interaction family.** For each inventory action,
   perform success/error/cancel paths and at least five repetitions of applicable
   local acknowledgement/readiness checks. For G13 percentiles, collect at
   least 30 events per family per trial in three independent trials; five checks
   are coverage only. Capture three trials with ≥120 valid active-motion frames
   per family: tabs, Home paging, drag, resize, Apps grid/search, Tools, scalar
   lists/details, rich editors, dialogs, keyboard and Store. Expand to each
   distinct implementation/data-heavy body and conditional variant; do not
   transfer one generic-row result to all custom panes. These sample counts are
   internal minimums; short 160 ms transitions need multiple bounded captures.
6. **Collect short, complete windows.** Reset counters immediately before the
   named action window and collect before leaving it. Preserve each chunk before
   the finite frame ring overwrites early samples; tag origin/destination/popup
   windows and transition versus settled scroll. Keep slow arrival/error/retry
   windows too. Count valid, excluded, duplicate and missing samples. Zero-frame
   or static samples are “not measured,” not a smoothness pass.
7. **Pair input with visible output.** For discrete actions define `t0` as
   actionable release/key event; for long press use the recognition boundary
   and separately report hold duration; for continuous tracking use pointer
   movement. Timestamp handler/first submitted response where available, and
   correlate with actual visible output. An external high-frame-rate camera
   showing input and screen can avoid on-device capture overhead. Record camera
   rate, synchronization/error bounds and display scan position; classify
   ambiguous samples. A 240 fps capture quantizes time to about 4.17 ms.
   Automation injection timestamps omit physical touch acquisition and must be
   labeled injected-input latency. Screenshots and host sleeps cannot prove it.
8. **Measure startup and sustained behavior.** Collect at least ten verified
   starts per cold/warm/hot cohort; publish all timings, median, p95 and maximum,
   noting coarse tails with ten samples. Observe each refresh producer for two
   declared intervals/backoff steps, then leave/return and cancel mid-load.
   Run five navigation cycles after warmup and inspect process PSS/requests:
   monotonic growth or a sustained increase >both 10% and 20 MiB versus a matched
   baseline requires investigation (internal screen, not a leak diagnosis).
   Rich bodies must release owned polling/audio/TTS/transports; inactive Home
   pages must lose input without duplicating producers on return.
9. **Use API-29-compatible evidence and investigate tails.** The existing
   `scripts/portal_ux_probe.py` can supply legacy frame data, but its automatic
   Home mode does not exercise widget gestures and its Activity launches do
   not prove retained-tab paths. Main supplements actual routes and gestures.
   Pair traces/observations with offending timestamps; distinguish scheduling,
   main-thread composition/layout, RenderThread/GPU and IO. Perfetto availability
   and presentation data must be verified on this firmware; root is not assumed.
10. **Repeat on the shipping configuration.** Debug runs diagnose regressions.
    Shipping performance requires the actual release artifact or a demonstrated
    equivalent profileable configuration with release optimizations/compilation
    recorded. Preserve debug and release cohorts separately. Match baseline and
    candidate workloads across three paired trials for improvement claims;
    absolute acceptance can pass without an old APK, comparative claims cannot.

### API 29 interpretation rules

Legacy `gfxinfo` elapsed time
`(FrameCompleted − IntendedVsync) / 1e6` is a **rendering proxy**. It includes
pipeline/scheduling effects; it is not GPU execution time, compositor presentation,
physical input-to-photon time or Apple's hitch metric. Report CPU-stage proxies
separately. If `FrameDeadline`/`FrameInterval` is absent, actual miss counts are
**unavailable**, not zero. D2's API-31+ `frameOverrunMs` cannot be assumed here.

Compute a presentation hitch ratio only with defensible expected and actual
presentation times: sum the non-overlapping late-display intervals in ms divided
by the active interaction duration in seconds. Exclude idle time; do not
double-count a repeated frame or dilute transitions with long waits. If firmware
traces lack that data, use calibrated external display observation and document
uncertainty; leave the quantitative presentation gate pending if neither works.
Do not manufacture hitch ratio by summing `max(gfxinfo_elapsed − B, 0)`.

Report each trial's sample count, p50/p95/p99/max, proxy-over-budget fraction,
actual-deadline denominator if available, visible-hitch annotations, acknowledgement,
ready time and measurement uncertainty. With 120 frames p99 has limited tail
resolution; retain outliers and add samples when verdict is borderline.
Never average trial percentiles into a pooled percentile. Pool only raw samples
from explicitly comparable phases/artifacts, retaining individual trial results.

## Evidence ledger and release decision

Use a compact row such as:

```text
ID | source revision/symbol | APK SHA-256/build | entry/state/input | expected return
   | metric/endpoints | trial/sample count/Hz | raw evidence ID | result | limitation
```

Evidence IDs resolve to private timestamped screenshots/UI captures, event/video
annotations, trace/counter chunks and before/after fingerprints. Public summaries
contain sanitized results and source links; no tokens, credentials or personal
screen contents. The ledger records main as collector and a reviewer for the
verdict. A test count without this mapping is supporting evidence only.

| Gate | Status at this checkpoint | Evidence still needed |
|---|---|---|
| G0 Complete source/current-UI census | PENDING | Source route/registry sets mapped; installed-UI truth tables, dynamic instances and runtime coverage counts still need reconciliation |
| G1–G7 Navigation, Apps, Tools, settings, conditions, dialogs | PENDING | Complete final-artifact state/entry/return evidence, beyond earlier smoke runs |
| G8 LTR editing subset described in baseline | REPORTED PASS, limited scope | 62 current-APK assertions; preserve exact two-page/nine-widget, temporary oversized-fixture and named injected-pointer scope |
| G8 Full editing gate | PENDING | Every family/size, RTL, interrupted/cancelled gestures and provider exceptions |
| G9 Keyboard/accessibility | PENDING | IME/focus/semantics, text scale/contrast/reduced motion and available reader evidence |
| G10/G15 Loading, cadence and resource behavior | PENDING | Actual failure/cancel/arrival/cadence/teardown observations and timings |
| G11 Complete native geometry/visual acceptance | PENDING | All mapped destinations, popup states and keyboard/scale variants |
| G12 Rendering/motion; G13 input; G14 startup | PENDING | Final-artifact measurements, verified cadence and shipping-build evidence |

**PASS requires every applicable row and metric to pass with scoped current
evidence.** FAIL means an observed acceptance violation; PENDING means evidence is
missing, insufficient or from a different artifact. N/A requires a demonstrated
hardware/product exclusion. Missing input, destructive unintended changes, wrong
returns, unreachable controls, reproducible blank flashes or visible hitches
block the affected gate regardless of averages. Retest the corrected action,
its shared component and dependent routes after a fix.

Order the next work: finish the census; measure final Home tabs/paging/editing and
Settings first-mount/revisit/eviction; cover Apps/Tools/dialog/keyboard latency;
then rich/conditional/loading/service states and shipping-build repeats. Main
can proceed within the user's existing testing authorization. Until the ledger
supports the full decision, report **“Portal+ acceptance pending; these named
interactions passed on this artifact”**, not “Apple-quality complete.”

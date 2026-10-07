# Portal+ G8 widget size inventory — source audit

Source baseline supplied by main: `0017d61`. This audit reads the working-tree
source; it does not independently verify Git state or installed artifact identity.
`ToolsActivity.kt` and `TvFocus.kt` are main-owned, uninstalled candidate source.
They are preserved, and no observation here certifies those candidates on a device.
The only output owned by this sidecar is this document. No Gradle, adb, Git
operation, application edit, mock edit, or device test was performed.

User acceptance goal: every widget should retain the same bounds, placement,
identity and body in normal Home and Customize, with usable content and controls
at every supported resize, and acceptable interaction/rendering performance.
Shared source paths support geometry parity; they do **not** establish typography
fit, input reachability, absence of clipping, or performance acceptance.

## Source map and active call chain

References below are repository-relative source links with one-based line anchors.
Line numbers describe the source read for this audit and can move during main's work.

| Reference | Source responsibility |
|---|---|
| [Store:22–63](../../app/src/main/java/com/immortal/launcher/HomeWidgetStore.kt#L22), [Store:70–172](../../app/src/main/java/com/immortal/launcher/HomeWidgetStore.kt#L70) | Kinds, identity, default spans, migration, size cycle, normalization and persistence |
| [H:1155–1179](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L1155), [H:907–944](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L907) | Home viewport and the same paged widgets for both modes |
| [Presentation:26–79](../../app/src/main/java/com/immortal/launcher/HomePresentation.kt#L26) | Four ordinary widgets per page, shared first-photo hero, saved-order projection, effective spans and move |
| [H:703–836](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L703) | Grid/cell entry, retained pages, move dialog and removal |
| [Packing:29–65](../../app/src/main/java/com/immortal/launcher/ApprovedWidgetPacking.kt#L29), [Grid:47–195](../../app/src/main/java/com/immortal/launcher/ApprovedPackedWidgetGrid.kt#L47) | First-fit packing, fixed measurements, scroll viewport, held move draft and release |
| [H:579–699](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L579) | Shared surface, body, Customize overlay/footer, resize draft and commit |
| [H:2733–2792](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L2733) | Actual built-in dispatcher and shared scrolling shell |
| [H:840–878](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L840) | Actual Android provider host and dimension options |
| [H:1047–1099](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L1047), [H:1204–1223](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L1204) | Store owner, built-in add, provider bind/configure results, library and Customize transitions |
| [H:4938–5000](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L4938), [Library:66–173](../../app/src/main/java/com/immortal/launcher/ApprovedHomeWidgetLibrary.kt#L66) | All 14 built-in choices plus Android provider selection; Options choice is Advanced-only |

The active chain is `HomeActivity.onCreate → LauncherScreen → HOME →
ApprovedHomePagedWidgets → ApprovedWidgetArea → ApprovedWidgetPage →
ApprovedWidgetGrid → ApprovedPackedWidgetGrid → ApprovedWidgetCell →
ImmortalWidgetContent` or `ApprovedHostedWidget`. Toggling Customize changes
`editMode` on this chain; it does not select a second packing system.

Do not substitute nearby legacy bodies for this chain. `ImmortalWeatherWidget`,
`PhotoMemoryStrip`, `WidgetTile`/`WidgetCell`, the older picker and
`updateWidgetSizeOptions` remain in `HomeActivity.kt`, but approved Home calls
`ApprovedHomeWeather`, `ApprovedHomePhoto` and `ApprovedHostedWidget`.
In particular, the old provider helper's 16/20 dp gutter math
([H:4779](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L4779))
does not describe the approved 24 dp grid.

## Span contract, defaults and actual measurements

There are **14 built-in kinds + 1 Android provider kind** in
`HomeWidgetStore` (`weather`, `world_clock`, `timers`, `calendar`, `alarm`,
`crypto`, `todo`, `news`, `stocks`, `options`, `ha`, `today`,
`quick_controls`, `photo_memory`; `appwidget`).
Unknown custom kind strings can deserialize with a nonblank `customId`; their
fallback is `UnknownImmortalWidget`, not a fifteenth built-in
([Store:150–166](../../app/src/main/java/com/immortal/launcher/HomeWidgetStore.kt#L150),
[H:4139](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L4139)).

Every kind has the same host-supported range: `spanX = 1..4`, `spanY = 1..4`.
Persistence clamps each axis, packing clamps width to four and height to four,
and resize drag clamps both to four. There is no built-in-specific minimum,
maximum, aspect ratio or span rejection. Options' library/editor Advanced gate
does not remove already saved Options widgets from the dispatcher.

| Family | Saved span on library add; `explicitSize=false` | Effective initial display in BOTH modes | Explicit size choice |
|---|---|---|---|
| Weather, World clock, Timers, Calendar, Alarm, Crypto, To-do, News, Stocks, Options, HA (11) | `2×2` | `1×2` | Exact chosen `X×Y`, including `2×2` |
| Today, Quick controls (2) | `2×1` | `1×2` | Exact chosen `X×Y`, including `2×1` |
| Photo memory (1) | `2×1` | `2×4` | Exact chosen `X×Y`, including `2×1` |
| Android provider | Each axis estimated from provider `minWidth/minHeight` using 128 dp units, clamped then raised to at least 2: `2..4` on each axis | Exact saved dimensions; providers bypass legacy remapping | Host allows every `1..4` pair |

These are presentation mappings, not store rewrites
([Presentation:53–66](../../app/src/main/java/com/immortal/launcher/HomePresentation.kt#L53),
[H:1205–1208](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L1205)).
`HomeWidgetStore.custom()` alone defaults to `2×2` for every custom kind; library
add and one-time dashboard migration supply `spanY=1` for the three former
dashboard cards. Any saved nondefault pair is already honored even without
`explicitSize`; matching a legacy default requires `explicitSize=true`.

Provider estimates use `appWidgetMinDp`: nonpositive becomes zero, values below
1000 are treated as plain dp, larger values are decoded as Android complex
dimensions. `estimateWidgetSpan` then applies integer-like ceiling math with
128 dp units ([H:1638](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L1638),
[H:4748–4808](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L4748)).
This estimate is not calculated from the measured Home cell width/height.
Approved resizing does not consult `resizeMode`, `minResizeWidth` or
`minResizeHeight`; provider compatibility requires actual provider evidence.

For measured grid viewport `W×H` in dp, when the `coerceAtLeast(1.dp)` floor is
not active:

```text
gap = 24 dp
column = (W - 3*gap) / 4
row    = (H - 3*gap) / 4
cellWidth(X)  = X*column + (X-1)*gap
cellHeight(Y) = Y*row    + (Y-1)*gap
```

Actual cell measurements use the corresponding pixel step, rounded and floored
at one pixel. `Layout` supplies `Constraints.fixed(width,height)` to every cell.
The scrollable grid has at least four rows and grows to the last packed rectangle
([Grid:66–86](../../app/src/main/java/com/immortal/launcher/ApprovedPackedWidgetGrid.kt#L66),
[Grid:119–123](../../app/src/main/java/com/immortal/launcher/ApprovedPackedWidgetGrid.kt#L119),
[Grid:181–193](../../app/src/main/java/com/immortal/launcher/ApprovedPackedWidgetGrid.kt#L181)).
Thus four **span rows** describe one viewport's height, but a page can pack beyond
that height and scroll. Pages are divided by item count, not by rectangle area:
up to four ordinary widgets plus the shared first photo.

Width/height change with the real viewport (orientation, insets, shell and
offline chrome). Home allocates the grid after header, greeting, pager/favorites
label, favorite tiles and dock. Do not derive a 1×1 height from screen resolution
alone. No `contentMinHeight` identifier occurs in the audited active widget
bodies; actual risks come from padding, headings, fixed line heights and
`heightIn(min=...)` children. A child's minimum height is not a span constraint.

## Actual bodies: responsive behavior and small-size screening

**Shared shell S:** `ImmortalWidgetShell` applies 30 dp padding on all sides,
a fixed 32 dp heading, then a 22 dp gap, then a weighted vertically scrolling
Column. Under ordinary bounds this leaves approximately `cellHeight−114 dp`
for the body viewport. It provides scrolling, not compact font/layout
breakpoints. The heading has a 27 dp icon, two 12 dp gaps, an expanding title
at 24 sp/32 sp line height and a fixed `68×32 dp` Open box
([H:2786–2791](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L2786),
[UI:116–128](../../app/src/main/java/com/immortal/launcher/ApprovedHomeUi.kt#L116)).
That costs 119 dp before any title width, plus the 60 dp shell padding.
Exact semantics/touch expansion needs measurement; the source explicitly
measures the Open box at 32 dp high.

None of the 14 active built-in bodies chooses a layout or type scale from
`BoxWithConstraints`, measured width, measured height or `spanX`. Five list
families receive a row **cap** from saved `spanY`; HA receives a tile-row cap.
The others fill/weight/scroll/crop within the parent's constraints.
This is the precise extent of current content responsiveness.

| Kind / actual body call chain | Width/height adaptation proved by source | Fixed content/minimums and estimated 1×1 risk |
|---|---|---|
| `weather` → `ApprovedHomeWeather` ([Weather:25–84](../../app/src/main/java/com/immortal/launcher/ApprovedHomeWeather.kt#L25)) | Same 30 dp/32 dp/22 dp shell anatomy; body vertically scrolls. Temperature gets Row weight; forecast takes five equal-weight columns with 8 dp gaps. Off omits forecast strip; Hourly/Daily changes content. No size-driven count or type scale. | 94 sp temperature, 100 sp line height beside a 72 dp icon or 60 sp emoji; city 26/34 sp, forecast 18–24 sp. Narrow width can severely constrain temperature/forecast columns; short height may show little beyond heading. Test unavailable, negative/three-digit temperature, long city and five forecasts. |
| `world_clock` → `ImmortalWorldClockWidget` → S ([H:3271–3304](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L3271)) | First four selected zones only, at every span; stacked clocks scroll. No width adaptation or height-based clock count. | Time 48 sp with **100 sp** line height; city/weekday 22/30 sp, 12 dp top gap and 22 dp between clocks. Long 12-hour time/city can wrap; even one clock consumes substantial scroll content. AnalogClock is not called by this body. |
| `timers` → `ImmortalTimersWidget` → S → `TimerChip` ([H:3367–3441](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L3367), [H:4121](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L4121)) | Scroll body; FlowRow wraps controls with 8 dp gaps. Ready/running/paused/ringing states choose different chips. | 48/100 sp readout; 22/30 sp secondary line; chips min 52 dp high, 16 dp horizontal/11 dp vertical padding. Wrapping is real responsiveness, but short cards may require scrolling to start/pause/stop. |
| `calendar` → `ApprovedHomeCalendar` ([Calendar:31–82](../../app/src/main/java/com/immortal/launcher/ApprovedHomeCalendar.kt#L31)) | 30 dp shell, fixed heading/gap, weighted `LazyColumn`, keyed event rows. Does **not** use `listRows`; content preparation supplies rows independently of span. | Kicker 18/24 sp, 10 dp gap, event rule `4×67 dp`, 18 dp horizontal gap, title 24/32 sp max two lines; 23 dp inter-item gaps. Setup copy and Add calendar use the same small viewport. |
| `alarm` → `ImmortalAlarmWidget` → S ([H:3450–3520](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L3450)) | Scroll body; 12/24-hour conditionally adds AM/PM. No span-specific typography. | 48/100 sp time plus 22/30 sp suffix; status max two lines at 22/30 sp; 20 dp gap; toggle `64×38 dp` with 30 dp thumb. Readout and toggle reachability need separate small-size checks. |
| `crypto` → `ImmortalCryptoWidget` → S → `CryptoRow` → `Sparkline`/`ChangePill` ([H:3530–3599](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L3530)) | Height cap `3,6,9,12` quotes for saved Y `1,2,3,4`; scroll body. Symbol has weight; price does not. 16 dp row gaps. | Symbol/price 26/35 sp; 64×22 dp sparkline beside 18/24 sp change pill with min 56 dp width after padding. Large price can dominate the available width; no compact price/spark variant. |
| `todo` → `ImmortalTodoWidget` → S → `TodoRow` ([H:3640–3701](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L3640)) | Height cap `3,6,9,12`; unfinished first; scroll body. Text has weight and one-line ellipsis. | Rows min 52 dp with 8 dp vertical padding, 26/35 sp glyph/text, 12 dp gap; 8 dp between rows; footer 22/30 sp after 16 dp. Three rows at Y=1 is a cap, not a promise that three are visible together. |
| `news` → `ImmortalNewsWidget` → S ([H:3710–3762](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L3710)) | Height cap `3,6,9,12`; interleaved feeds; scroll body; full-width clickable headlines. | 26/35 sp headlines, max two lines/ellipsis, 16 dp between them. Long/unbroken titles and small viewport need article-action reachability checks. |
| `stocks` → `ImmortalStocksWidget` → S → `StockRow`/`ChangePill` ([H:3781–3825](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L3781)) | Height cap `3,6,9,12`; scroll body; weighted symbol and unweighted price, 16 dp row gaps. | 26/35 sp price and symbol; pill below with 8 dp gap. Large formatted price and long symbol have no width breakpoint. Test disconnected/empty/loading/no-quotes as well as populated. |
| `options` → `ImmortalOptionsWidget` → S → `OptionRow` ([H:3835–3897](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L3835)) | Height cap `3,6,9,12`; scroll body; two-line contract label. Library add/editor require Advanced; existing body still renders. | Always adds 18/24 sp delta band with 12 dp bottom gap; label 26/35 sp max two lines, delta/mid price 18/24 and 22/30 sp in one Row. Tall row content and large strikes/premiums need checks. |
| `ha` → `ImmortalHaWidget` → S → `HaTile`/`HaSensorTile` ([H:3916–3995](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L3916), [H:4004–4060](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L4004)) | Takes `Y` rows of two entities, i.e. at most `2,4,6,8`; scroll body. Always two columns, 14 dp gaps, spare half-width for odd last row. | Tiles min 150 dp high, 20 dp padding; control icon 32 dp, name 23/30 sp, state 20/27 sp; sensor value 26/35 sp, name 22/30 sp max two lines. A single entity still occupies half body width. No one-column compact layout. |
| `today` → `TodayStripWidget` → S → `TodayRow` ([H:2911–3044](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L2911)) | Scroll body; optional event, alarm, open-to-do count, countdown; weather/sun/moon copy. No span-driven row count. | Event title 24/32 sp max two lines and 67 dp rule; weather 22/32 sp; secondary rows 26/35 sp with 30 dp glyph, trailing 22/30 sp max 160 dp. Source hides absent optional data, not data based on size. |
| `quick_controls` → `QuickControlsWidget` → S ([H:2851–2900](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L2851)) | Installed-app-dependent zero to five tiles; always chunked in twos, vertically scrolls; odd last tile remains half-width. | Tiles min 150 dp, 20 dp padding, 32 dp icon, 23/30 sp label (one-line ellipsis), 20/27 sp description without maxLines. Narrow half-tile descriptions can grow vertically. Header Open goes to Display, while Customize Settings goes to Quick buttons. |
| `photo_memory` → `ApprovedHomePhoto` ([Photo:75–129](../../app/src/main/java/com/immortal/launcher/ApprovedHomePhoto.kt#L75)) | Image fills cell with `ContentScale.Crop`; gradient fills cell. Caption bottom-aligned, no content scroll or size breakpoint. First photo is shared across pages; additional photos ordinary paged widgets. | Caption inset 36 dp horizontal/34 dp vertical, title 36/46 sp, subtitle 22/30 sp with 12 dp gap, neither has maxLines. One-line nominal caption needs about 156 dp total vertical budget; wrapping/font scale increases it. Cropping the image is intentional source behavior, not evidence that caption is clipped. |
| `appwidget` → `ApprovedHostedWidget` ([H:840–878](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L840)) | Actual `BoxWithConstraints` width/height rounded to dp, equal MIN/MAX options sent on dimension change; `AndroidView` fills cell with zero padding. Provider owns its body. Editing adds a scrim and disables host/descendant focus. | No launcher compact typography or provider minimum validation. Missing-info branch has 30 dp padding, 26/35 sp unavailable text and package 22/30 sp max two lines. Need live provider-specific content and minimum-size certification; class/category coverage alone is insufficient. |

`listRows(Y) = (Y*3).coerceIn(2,12)` actually produces **3**, not two, at Y=1
([H:2759–2764](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L2759)).
All row caps are applied before rendering (`take`), so scrolling cannot reveal
items beyond the selected cap. HA still fetches states for all pinned IDs
([H:3927–3934](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L3927)).
Resizing therefore changes rendered quantity, not necessarily fetch quantity.

## Source-proven findings versus unmeasured risks

| ID | Source-proven fact / gap | What is NOT established |
|---|---|---|
| S1 | Normal and Customize use the same page projection, packing, exact cell constraints and body dispatcher. Customize adds an input overlay, footer, resize handle, semantics clearing and provider scrim. Its footer does not reserve space inside the body. | Identical visible pixels/content or unobscured controls in Customize. Geometry parity is distinct from the editing overlay intentionally covering body area. |
| S2 | All 16 span pairs are accepted, but Size cycles only `1×1 → 2×1 → 2×2 → 1×1`; any other effective pair jumps to `1×1`. There is no full-range non-drag size picker in this path ([Store:84–89](../../app/src/main/java/com/immortal/launcher/HomeWidgetStore.kt#L84), [H:630–633](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L630)). | A failure of drag sizing. The source gap is that the named Size alternative reaches only 3 of 16 pairs; the other 13 require drag or an external layout write. |
| S3 | Built-in bodies do not switch typography/layout by measured size; list/HA caps use saved Y, not the effective span argument passed to the cell. Scroll/crop/weight/FlowRow are the actual adaptation mechanisms. | A guarantee that any given small widget is unusable or clipped. Measuring actual 1×1 bounds and scrolling/actions is required. |
| S4 | Every cell's resize release builds a **whole replacement list** with `widgets.map`, then `saveWidgets` assigns and saves it. There is no key-based mutation applied against current owner state, revision check or global resize-owner gate ([H:667–686](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L667), [H:1052–1055](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L1052)). `ResizeBadge` uses latest callbacks, but still receives composition snapshots ([H:4440–4465](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L4440)). | That two independent cells actually recognize simultaneous drags, or that updates were lost on hardware. If two release callbacks consume the same pre-recomposition list, the second replacement can restore the first widget's old span. Test both release orders within a frame and with recomposition between them. |
| S5 | The shared heading Open box is explicitly `68×32 dp`; the inline alarm toggle is `64×38 dp`; footer actions have min 52 dp height but weighted widths. Their measured dimensions alone do not provide a 48×48 dp guarantee. | Actual hit rectangles, overlapping expanded targets, physical misses or focus behavior. Compose minimum touch expansion and the main-owned TvFocus candidate must be measured. The current TvFocus source adds click/focus treatment, not a size-specific widget layout. |
| S6 | Timers inline controls and inline Custom timer use `TimerStore` (`immortal_timer`), but heading Open and Customize Settings host `ApprovedHomeTimersPage → TimersOverlay`, which uses `TimerConfig` (`immortal_timers`). These are separate persisted stores and alarm paths ([Navigation:65–89](../../app/src/main/java/com/immortal/launcher/ApprovedHomeNavigation.kt#L65), [Overlay:288–368](../../app/src/main/java/com/immortal/launcher/HomeToolOverlays.kt#L288), [TimerStore:29–35](../../app/src/main/java/com/immortal/launcher/TimerStore.kt#L29), [TimerConfig:20–23](../../app/src/main/java/com/immortal/launcher/TimerConfig.kt#L20)). | A native failure or a mandated unification patch. The concrete integration discrepancy is that the page reached as this widget's editor does not read/write the widget countdown. Main should verify expected user behavior before deciding scope. |
| S7 | Quick controls heading Open navigates to `display`; its Customize Settings maps to `quickbar`. Tiles launch installed apps and do not consume QuickBarConfig. | That either route crashes or user expectations require a particular new setting. Main should verify the two handoffs explicitly. |
| S8 | Provider dimensions are advertised from the actual cell, but host resizing ignores provider resize permissions/minimums. `remember(appWidgetId)` caches info; missing provider metadata uses a fallback and is not automatically pruned by the approved path. | Any specific provider rendering/input failure, or timely recovery after uninstall/reinstall. The legacy `filterLiveWidgets` helper is not called by this approved chain. |

### Typography and content estimates, especially 1×1 and font scale 1.3

These are screening estimates; **none is an observed clipping or runtime failure**.

* Customize footer is a single full-width Row with 6 dp start and **58 dp end**
  padding for the 48 dp handle. It has four equal-weight actions, each with
  6 dp horizontal padding and 18 sp text/26 sp line height, `maxLines=1`, no
  width breakpoint and no explicit ellipsis
  ([H:624–658](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L624),
  [H:694–699](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L694)).
  For a cell width `C`, each action's nominal text budget is
  `(C−64)/4−12 dp`. At font scale 1.3, 18 sp is nominally 23.4 dp and
  26 sp line height is 33.8 dp; widths do not expand. Measure **Settings** and
  **Remove**, all four hit rectangles, the reserved handle and body occlusion.
  For context only, 4×48 dp actions plus 64 dp reserved width need 256 dp
  cell width; actual label fit depends on native glyph metrics.
* The shared shell's nominal body budget is `cellHeight−114 dp`. The heading
  remains 32 dp while its 32 sp line height is nominally 41.6 dp at 1.3 scale.
  Padding and heading/control widths remain fixed. This predicts pressure on
  short/narrow widgets but does not determine the measured text layout.
* Weather's 94 sp number and 72 dp icon, the 100 sp readout line height on
  World clock/Timers/Alarm, HA/Quick controls' half-width 150 dp tiles, and
  Photo memory's non-scrolling bottom caption are the first inspection targets.
  Also inspect list prices versus weighted symbols and setup buttons with long copy.
* At a nominal 1.3 scale, 100 sp line height becomes about 130 dp; a 150 dp
  tile minimum does not become a larger span or automatically switch to one column.
  These conversions are estimates; record actual density, font scale, typeface,
  locale and native Text layout. Do not infer glyph fit from a browser mock.

## Editors, dialogs and commit boundaries

Body data/config is generally shared per family, not per widget ID. A layout
resize/removal commits `immortal_home_widgets`; it does not delete coins, tasks,
feeds, timer/alarm configuration, photo source or HA entities. Removal deletes a
provider's allocated widget ID only on confirmation.

| Entry / source | Draft, confirmation and persistence boundary |
|---|---|
| Customize / Done / Back ([H:1112](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L1112), [H:1133](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L1133)) | Changes mode. Done is not a global transaction save and Back does not undo already committed layout edits. |
| Resize ([H:667–686](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L667), [H:4433–4465](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L4433)) | Start captures effective display span and measured grid units. Drag changes local accumulator/draft outline only; snap step is `sign(delta)*floor(abs(delta/unit)+.35)`, threshold about .65 unit. RTL negates horizontal delta. Release saves once only if final pair differs from start, marking explicit. Cancel clears draft; detector disposal cancels an active gesture. Body does not resize live during draft. |
| Size button | Immediate whole-list save on each click; no modal draft/Cancel. Three-state cycle uses effective display span. |
| Held move / numbered Move ([Grid:91–168](../../app/src/main/java/com/immortal/launcher/ApprovedPackedWidgetGrid.kt#L91), [H:721–727](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L721)) | Grid move uses an ownership token and ghost; finish returns destination once, then owner callback saves reorder. Cancel/invalidation resets. Move dialog commits on destination selection; Close/Back/outside dismissal does not reorder. Numbered dialog reaches positions on other pages; held drag targets current page bounds with vertical edge scrolling. |
| Remove ([H:824–833](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L824)) | Cancel/dismiss leaves store and provider ID. Confirm deletes provider ID when applicable and saves `without(key)`. |
| Library built-in/provider add | Built-in selection immediately appends a fresh `customId`, saves and closes; choosing an existing family can add another instance. Provider allocates ID, binds, optionally configures; only successful completion appends/saves. Bind/configure cancellation/launch failure deletes pending ID. Configuration metadata and installed-provider set are external. |
| Common Home dialogs ([H:882–902](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L882)) | Render only when Home and widget-page input are active; full-width fraction .8, max 880 dp width/1096 dp height, 36 dp padding, vertically scrolling content. Dismiss callback decides draft cancellation. Runtime focus restoration/scrim behavior is unverified. |
| Weather / Calendar / Today ([Settings:328–335](../../app/src/main/java/com/immortal/launcher/ImmortalSettingsActivity.kt#L328), [Calendar URL:23–41](../../app/src/main/java/com/immortal/launcher/CalendarUrlEntryActivity.kt#L23)) | Weather and calendar scalar rows apply registry domains immediately. Today Settings maps to Calendar. Add calendar opens URL Activity: Use this calendar validates then registry-applies; Cancel/Back discards draft; Remove connection confirms before applying empty URL ([URL:58–120](../../app/src/main/java/com/immortal/launcher/UrlEntryScreen.kt#L58)). |
| World clock ([Settings:1237–1293](../../app/src/main/java/com/immortal/launcher/ImmortalSettingsActivity.kt#L1237)) | Selected-city click immediately saves list; no whole-editor Save/rollback. First four shown; re-picking changes order. |
| Timers inline ([H:3412–3441](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L3412), [H:4065–4085](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L4065)) | Preset/Start/Pause/Reset/Stop act immediately on TimerStore. Inline Custom timer drafts minutes 0..180 and seconds in five-second steps with wrapping; Start calls TimerStore only for positive duration; Cancel/Close discard. |
| Timers Open/Settings → tool page | Separate TimerConfig model (S6). Presets immediately add/schedule a named timer. Tool Custom dialog drafts hours/minutes/seconds; valid Start adds/schedules. Running timer Cancel asks Keep timer/Cancel timer; confirmation removes it. System Back from hosted page dismisses to Home; explicit Tools action selects Tools. |
| Alarm ([Alarm editor:71–138](../../app/src/main/java/com/immortal/launcher/AlarmSettingsActivity.kt#L71)) | Editor Enabled, hour/minute steppers and weekdays save/reschedule immediately; no Cancel undo. Inline widget toggle separately calls setEnabled/reschedule immediately. |
| Crypto ([Crypto editor:51–96](../../app/src/main/java/com/immortal/launcher/CryptoSettingsActivity.kt#L51)) | Manual fields draft; Add validates nonblank ID then persists; popular choices immediately add. Remove dialog confirms collection deletion. Cancel clears unsaved fields and returns, leaving prior adds/removes. |
| To-do ([Todo editor:35–77](../../app/src/main/java/com/immortal/launcher/TodoSettingsActivity.kt#L35)) | Add drafts then persists nonblank item; Complete/Undo and widget row toggle immediately persist. Remove/Clear completed confirm. Editor Cancel does not roll back prior toggles/adds. |
| News ([News editor:47–90](../../app/src/main/java/com/immortal/launcher/NewsSettingsActivity.kt#L47)) | Manual name/URL draft; valid HTTP(S) Add persists. Popular choices immediately add; removal confirms. Widget headline opens article link, blank link opens editor. Cancel discards unsaved fields only. |
| Stocks / Alpaca ([Stocks editor:50–107](../../app/src/main/java/com/immortal/launcher/StocksSettingsActivity.kt#L50), [Alpaca:16–48](../../app/src/main/java/com/immortal/launcher/ApprovedEditorsAlpaca.kt#L16)) | Manual Add/popular symbols persist; remove confirms. Advanced gates Alpaca navigation. Credential form Save validates effective key+secret then writes pair atomically; blank means keep saved; Cancel discards draft, not previous saves. |
| Options ([Options editor:38–127](../../app/src/main/java/com/immortal/launcher/OptionsSettingsActivity.kt#L38)) | Advanced-only editor; type picker/number dialogs edit outer draft. Number dialog Save does not persist scan config. Save scan settings or Add writes validated band/type/expiry (Add also ticker). Remove ticker confirms. Cancel restores unsaved outer draft and returns. Shared remove/number mechanics: [Editors UI:177–227](../../app/src/main/java/com/immortal/launcher/ApprovedEditorsUi.kt#L177). |
| HA ([HA editor:52–129](../../app/src/main/java/com/immortal/launcher/HaSettingsActivity.kt#L52), [HA editor:151–258](../../app/src/main/java/com/immortal/launcher/HaSettingsActivity.kt#L151)) | URL/token draft; Save & connect persists atomic connection only after successful discovery and current-request check; Cancel/stop cancels request. Entity selection immediately pins; removal confirms. Widget controls call HA REST with local optimistic override; scenes activate; read-only sensors open dashboard. Resizing cannot be judged by state text alone—verify no service call through editing overlay. |
| Quick controls / Photo memory | Customize Settings maps to Quickbar/Screensaver. Quickbar scalar rows immediately registry-apply ([Settings:959–970](../../app/src/main/java/com/immortal/launcher/ImmortalSettingsActivity.kt#L959)); photo source selection/credential forms remain their existing Settings/Activities, with source-specific commit semantics. Photo tap launches preview; no photo-specific resize editor. Source-form census is main's control audit scope. |
| Provider Settings ([H:638–646](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L638)) | Opens provider configure Activity with widget ID, otherwise app details; failure shows toast. Existing-provider configure edits have no launcher rollback/result transaction. Provider owns its configuration's Save/Cancel semantics. |

### Empty-list behavior: intentional remove-all versus initial migration

`load()` returns empty for an absent stored list; JSON `[]` deserializes as empty.
Malformed JSON also returns empty, so absence, intentional empty and corruption
are not distinguished by this loader. `save(emptyList())` serializes `[]`
([Store:91–104](../../app/src/main/java/com/immortal/launcher/HomeWidgetStore.kt#L91),
[Store:124–171](../../app/src/main/java/com/immortal/launcher/HomeWidgetStore.kt#L124)).

With `dashboard_cards_migrated=true`, migration returns before loading or seeding.
Removing the last widget therefore stays empty on resume/recreation; there is
no approved Home `if empty then reseed` branch. With the flag absent/false,
first composition seeds Today + Quick controls + Photo memory around the current
list, even if that current list is `[]`
([Store:70–81](../../app/src/main/java/com/immortal/launcher/HomeWidgetStore.kt#L70),
[H:1047–1051](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L1047)).

An empty layout gets one page, no hero, no cells, and an empty grid with minimum
four-row height. Customize remains in the header; Customize exposes Add widget,
and Settings' widgets route opens the library
([Presentation:26–32](../../app/src/main/java/com/immortal/launcher/HomePresentation.kt#L26),
[H:932](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L932),
[Navigation:133](../../app/src/main/java/com/immortal/launcher/ApprovedSettingsNavigation.kt#L133)).
This is source-proven state behavior, not a report of a remove-all bug. Test both
migration flag values without clearing real user preferences.

## Applicable 1..4 state matrix and counts

`P` means **applicable, native measurement pending**. No cell is a runtime PASS.
The common shape matrix applies to every built-in and to each provider accepted
by the host. These are explicit user sizes (`explicitSize=true`); legacy defaults
must additionally be checked using the mapping above.

| Y \\ X | 1 | 2 | 3 | 4 |
|---|---|---|---|---|
| 1 | `1×1 P` | `2×1 P` | `3×1 P` | `4×1 P` |
| 2 | `1×2 P` | `2×2 P` | `3×2 P` | `4×2 P` |
| 3 | `1×3 P` | `2×3 P` | `3×3 P` | `4×3 P` |
| 4 | `1×4 P` | `2×4 P` | `3×4 P` | `4×4 P` |

Family coverage below is the expansion of that matrix. Each Y column includes
X=1,2,3,4 and **both normal/Customize**; `4P` is four sizes pending, not four
tests executed. Fixture branches are additional requirements, not already
covered by the geometric count.

| Kind | Y=1 | Y=2 | Y=3 | Y=4 | Size pairs / mode states | Candidate fixture branches |
|---|---|---|---|---|---|---|
| weather | 4P | 4P | 4P | 4P | 16 / 32 | unavailable; populated C/F; Off/Hourly/Daily; long city, negative/three-digit temp |
| world_clock | 4P | 4P | 4P | 4P | 16 / 32 | zero/one/four/>four cities; 12/24-hour; long city/weekday |
| timers | 4P | 4P | 4P | 4P | 16 / 32 | ready/running/paused/ringing; inline custom; hosted tool page model |
| calendar | 4P | 4P | 4P | 4P | 16 / 32 | no URL; no events; populated timed/all-day/multi-day/long-title |
| alarm | 4P | 4P | 4P | 4P | 16 / 32 | disabled/enabled; 12/24-hour; long label; repeat/one-shot |
| crypto | 4P | 4P | 4P | 4P | 16 / 32 | loading/empty/populated; spark/change absent/present; large price; >12 quotes |
| todo | 4P | 4P | 4P | 4P | 16 / 32 | empty/open/mixed/all-complete; long text; >12 tasks |
| news | 4P | 4P | 4P | 4P | 16 / 32 | no feeds/loading/no-headlines/populated; two feeds; long title; link/no link |
| stocks | 4P | 4P | 4P | 4P | 16 / 32 | unconfigured/no tickers/loading/no quotes/populated; large price; >12 quotes |
| options | 4P | 4P | 4P | 4P | 16 / 32 | Advanced off/on; unconfigured/empty/scanning/no hits/hits; long contract; >12 hits |
| ha | 4P | 4P | 4P | 4P | 16 / 32 | unconfigured/no entities/loading/known; toggle/scene/sensor; odd/even; >8 entities |
| today | 4P | 4P | 4P | 4P | 16 / 32 | calendar absent/empty/event; weather unavailable/available; optional alarm/todo/countdown |
| quick_controls | 4P | 4P | 4P | 4P | 16 / 32 | zero/one/four/five installed-target tiles; long descriptions |
| photo_memory | 4P | 4P | 4P | 4P | 16 / 32 | fallback/loading/failure/cached/populated; web page; wide/tall image; first/additional photo |
| appwidget host category | 4P | 4P | 4P | 4P | 16 / 32 per provider | configure/no configure; resize metadata variants; external click/focus; missing info separately |

Counts:

* Built-ins: **14 × 16 = 224 family/size pairs**, **448 normal/Customize states**.
* Adding one representative live provider: **240 pairs / 480 mode states**.
  This is 15 host categories, not certification of all installed providers.
* With `N` actual live provider components: **224 + 16N pairs** and
  **448 + 32N mode states**. Provider-specific unsupported content must be
  recorded as an exception with metadata/evidence, not silently dropped.
* Running both font scales 1.0 and 1.3 doubles those mode-state counts:
  **896 + 64N** (960 with one live provider). Locale, offline chrome, fixture
  branches, RTL, touch/TV, transitions, dialogs and performance trials are
  additional axes. Do not call this their exhaustive Cartesian product.
* Missing-provider fallback adds **16 pairs / 32 mode states**, or 64 with both
  font scales, if treated as a separate content branch. Unknown-kind fallback
  is another optional sentinel branch; neither changes the built-in count.
* This sidecar executed **0 native size states and 0 tests**. Reading existing
  test source or prior audit reports does not increment measured coverage.

## Exact candidate checks for main

These are concrete test requests, not speculative product patches. Keep main's
candidate-source/installed-artifact identity separate, fingerprint layout/config
before mutation, and restore original user data afterward. G8/G11–G15 acceptance
criteria remain those in [quality gates](portal-plus-quality-gates.md#required-native-interaction-map);
this audit supplies no new runtime acceptance.

| Candidate | Required action and assertions |
|---|---|
| T1 — 16-pair body/geometry census | For each built-in and each selected live provider, persist explicit X/Y fixtures from the matrix, settle Home, then Customize and Done. Record physical bounds, viewport/density/font scale/insets, order, key/appWidgetId, rendered body, scroll range and action bounds. At the same scroll/page/network state, compare outer bounds/order across modes (existing G11 ≤1 px target). Inspect text, footer and end-of-scroll controls separately; don't require all capped rows to be simultaneously visible. Run 1.0 and 1.3; retain screenshot/semantics evidence for each measured state. |
| T2 — small footer and heading | At `1×1`, also `1×2` and `2×1`, font scale 1.3, tap/focus Move, Size, Settings, Remove and Resize using measured rectangles. Verify labels and targets, no overlapping/misdirected action, and no widget-body activation through Customize overlay. Check 32 dp Open/38 dp alarm control separately in normal mode. Record actual failures only after observation. |
| T3 — all size transitions and boundaries | Starting at each explicit pair, resize both axes at/below/above the .65-unit threshold; reverse direction, return to original, overshoot min/max, finish at viewport edges and while grid is scrolled. Verify draft does not change stored list/body/provider dimensions; release commits requested pair once with explicitSize=true; no-op release and ACTION_CANCEL do not write. Repeat horizontal direction in RTL. Size button must exercise its exact three-state cycle and outside-cycle fallback; explicitly record lack of non-drag access to other 13 pairs. |
| T4 — competing resize release snapshots | Put two distinct widgets/keys on the same active page, begin independent handle drags, release A then B within one frame before a composition refresh; repeat B then A, then separated by a frame. If input dispatch admits only one gesture, record that observation instead of claiming concurrency covered. When both run, assert both spans survive, no resurrection/removal/reorder and expected write counts. Also mutate another widget by Size/Move while a resize draft exists, then release/cancel. This tests S4's whole-list snapshot boundary. |
| T5 — interrupted gesture ownership | Resize then cancel, Done/Back, switch tab/page, open Settings, background/foreground and recreate/process interruption. Verify no stale callback later persists a draft. Repeat drag two consecutive equal-size moves, scroll/held edge scroll, owner handoff and nonowner cancellation; assert one release commit, zero cancel commits. Physical RTL and distinct two-handle recognition remain separate from synthetic one-handle handoff. |
| T6 — legacy spans, hero and packing | Exercise the three saved-default classes with explicitSize=false, then choose an explicit pair equal to the old default. Verify only targeted widget's effective span changes and saved values of neighbors do not. Test first-photo sharing/order on every page, multiple photos, moving hero twice, hero removal/promotion, cross-page numbered Move, and up to five large rectangles causing grid scroll without covering favorites/dock. |
| T7 — remove-all and recovery | With migration=true, confirm each removal until `[]`, Done, resume and recreate; verify no reseed and Customize→Add works. Cancel last removal first and verify unchanged list. In an isolated preference fixture, test missing/false migration flag plus `[]` produces exactly three dashboard cards once; true flag plus absent/corrupt/empty data remains empty under current loader semantics. Restore original migration flag and layout together. |
| T8 — editors/dialogs | From each kind, open heading/body action and Customize Settings; verify actual route, own window/focus/input and origin return. Exercise Cancel/Back/outside tap, then explicit confirm. Distinguish immediate commits from drafts in the table above. Particularly test TimerStore inline countdown versus TimerConfig hosted page, Quick controls Display vs Quickbar, Options number/type drafts vs outer Save/Add, HA in-flight cancellation, and provider bind/configure cancel ID cleanup. Compare layout fingerprints separately from family-config fingerprints. |
| T9 — provider exceptions | Enumerate actual provider component, min/resize metadata, estimated add span and configure capability; test all 16 host-admitted pairs, option values versus measured cell, dimensions after Done, hosted clicks/focus, missing metadata/uninstall and return from provider settings. Verify no provider action while editing/inactive. Classify unsupported provider body sizes explicitly; no native provider set was discovered here. |
| T10 — rendering/input/resource performance | Measure draft drag/resize, release/repack, list scroll, page change, Customize/Done and modal open with small/large/populated bodies and providers. Capture measured Hz/frame budget and G12 proxy/deadline counts; input→visible acknowledgement/tracking and G13 latency separately. Count state/layout writes (not only distinct final values), provider option updates and fetch/decode starts. Do not equate zero idle frames, cached content or a passing JVM packing test with a motion/performance pass. |
| T11 — inactive widget-page work | With at least three pages and first-photo hero, record fetch/tick/decode starts on active and retained adjacent pages, then switch Home→Apps/Tools and background. Observe two intervals/backoff steps; distinguish Home-tab inactivity from widget-page inactivity. Test page revisit without cadence reset, repeated provider options/hero loads and partial failures. See source ownership caveat below. |

### Existing test entry points and limits

Main can run the following existing JVM classes with the repository unit-test
gate; they were only **read**, not executed here:

* [ApprovedWidgetPackingTest](../../app/src/test/java/com/immortal/launcher/ApprovedWidgetPackingTest.kt):
  `mixedSpans_haveNoCollisionsAndAlwaysChooseFirstAvailablePosition`,
  `repeatedCalls_areStableAndDoNotRetainPriorOccupancy`,
  `invalidSpans_areNormalizedWithoutChangingInput`.
* [ApprovedWidgetPageGeometryTest](../../app/src/test/java/com/immortal/launcher/ApprovedWidgetPageGeometryTest.kt):
  `defaultDashboard_preservesHeroAndTwoColumnsWithoutRewritingSavedSizes`,
  `explicitSizeMatchingLegacyDefault_isHonoredWithoutResizingNeighbors`,
  `resizingOtherPage_doesNotChangeCurrentPageGeometry`,
  `sharedPhoto_preservesSavedRelativeOrderOnEveryPage`.
* [ApprovedHomePresentationPagingTest](../../app/src/test/java/com/immortal/launcher/ApprovedHomePresentationPagingTest.kt):
  `multiplePhotoWidgetsAndAppWidgets_allRemainReachable`,
  `removalFromLastPage_clampsWithoutLosingWidgets`,
  `reorderingAcrossPages_retainsEveryIdAndSavedSize`.
* [ApprovedWidgetDragStateTest](../../app/src/test/java/com/immortal/launcher/ApprovedWidgetDragStateTest.kt):
  `secondOwnerCannotStartMoveCancelOrFinishTheFirstDrag`,
  `invalidationPreventsStaleReleaseFromFinishingANewerDrag`,
  `releaseReturnsDestinationOnlyOnce_andEmptySpaceDoesNotMove`,
  `rtlPhysicalCoordinates_findTheCardToTheLeftWithoutInvertingTheFinger`.
  This pure **move** ownership state is not a global ResizeBadge owner.
* [HomeWidgetStoreTest](../../app/src/test/java/com/immortal/launcher/HomeWidgetStoreTest.kt):
  `serialize_deserialize_roundTripsWidgets` and
  `deserialize_ignoresInvalidEntriesAndDuplicates`;
  [ApprovedHomePagerNavigationTest](../../app/src/test/java/com/immortal/launcher/ApprovedHomePagerNavigationTest.kt):
  `parentClampingSuppressesOldPagerEventsUntilTheClampedTargetArrives`.

Existing tests do not render every widget at every span, assert native text
fit/hit regions, verify two-cell resize commits or prove remove-all migration
behavior. Main should add/extend meaningful coverage for those boundaries as
needed; this sidecar writes no tests.

The existing [PortalUxInstrumentation observer](../../app/src/androidTest/java/com/immortal/launcher/PortalUxInstrumentation.kt#L303)
supports `widgetGesture` with `source`, `resize`, `dx/dy` or `target`, `holdMs`,
`moveMs`, `edgeHoldMs`, `cancel`, `handoff`, and phase reporting
([instrumentation:374–448](../../app/src/androidTest/java/com/immortal/launcher/PortalUxInstrumentation.kt#L374)).
Its handoff adds a second pointer near the **same source gesture**, not two
independently targeted ResizeBadge drags. T4 needs distinct-target input coverage.
Its generic `observe` window snapshots and screenshots can support T1/T2/T8;
semantic-label presence is not a glyph-fit/visible-content assertion.
Its `touchPaging` benchmark exposes `paging`, `noop`, `tabs`, `settings`
([instrumentation:113–125](../../app/src/androidTest/java/com/immortal/launcher/PortalUxInstrumentation.kt#L113));
it has no existing all-widget-span/resize performance scenario.

### Performance ownership proved by source; measurements outstanding

Packing is remembered by spans, move preview translates a stable keyed cell,
and resize paints a local outline until release. Grid recomposition/repack after
release and actual GPU/input/frame costs remain unmeasured. Layout pages compose
all their cells, not a lazy widget list; current item-count paging bounds this
to at most five cells per page.

`produceHomeState` pauses by `LocalApprovedLauncherPageActive`, retains its value
and refresh deadline, and serializes re-entry through a per-producer Mutex
([Tabs:65–94](../../app/src/main/java/com/immortal/launcher/ApprovedLauncherTabs.kt#L65)).
It does **not** read `LocalApprovedWidgetPageInput`. Touch Home retains previous,
current and next pages; their input eligibility differs, but they share active
Home-tab producer eligibility
([H:770–797](../../app/src/main/java/com/immortal/launcher/HomeActivity.kt#L770)).
Timers' inline tick likewise reads Home-tab active, not widget-page input.
Do not claim offscreen widget-page requests/ticks are stopped merely because
inactive input/focus/semantics are disabled.

Source refresh policies: Weather current/forecast, Crypto/News/Stocks use
30-minute success / 1-minute retry; Options uses 60-minute success / 2-minute
retry; HA polls at 30 seconds; World clock at the next minute; inline TimerStore
countdown ticks at one second while running. Calendar and Today's event use
30-minute deadlines; Today's forecast/sun are one-shot producers with helper
cache window. Empty/failure results may take the retry path; these source
intervals are not observed request rates or network performance.

Photo uses page lifecycle `RESUMED`, a process cache keyed by source settings,
30-minute TTL, failure backoff 1/2/5/30 minutes, cancellation-aware source work,
sampled decoding bounded to 1200 on each dimension and a 16 MiB transfer cap
([Photo:44–106](../../app/src/main/java/com/immortal/launcher/ApprovedHomePhoto.kt#L44),
[Photo:169–227](../../app/src/main/java/com/immortal/launcher/ApprovedHomePhoto.kt#L169)).
The per-launcher-tab lifecycle wrapper pauses hidden tabs, not each retained
widget page ([Tabs:173–195](../../app/src/main/java/com/immortal/launcher/ApprovedLauncherTabs.kt#L173)).
Shared cache avoids many repeated loads; separate hero instances can still enter
the cache-miss path, and method-level synchronization is not proof of one
cross-instance in-flight load. Count real fetch/decode starts.

## Audit boundary and unresolved evidence

This inventory establishes kind completeness, active body chains, host span
admission, default/effective mappings, source layout policies and commit sites.
It also identifies concrete test gaps: full-range non-drag sizing, fixed small
content/footer budget, two-release whole-list replacement, split timer editor
storage, different Quick controls routes, provider minimum handling and retained
widget-page producer ownership.

Actual cell/footer/glyph/hit bounds, scroll reachability, native clipping,
physical RTL/multi-pointer behavior, installed provider universe/configuration,
artifact identity, runtime parity and frame/input/resource acceptance remain
**unverified**. Prior scoped device/preview evidence in other documents is not
expanded into a G8 all-widget/all-size PASS here. Main owns source-color and
conditional-control changes, installation, test extensions and runtime evidence.

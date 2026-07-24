# Home-Screen Edit Mode, Widgets, Dock, Icons & Chrome — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the static "Liquid Glass" home screen into an iPhone/iPad-style editable dashboard: long-press edit mode (jiggle), reorder icons, minus-delete apps, editable dock, resizable/multiple left-column widgets incl. a native Calendar widget, a WiFi signal-strength indicator, a fading center divider, nicer app icons, and two new action tiles (Portal dashboard, Portal calling).

**Architecture:** The shipping home is `LauncherScreen` (HomeActivity.kt:365-633) — static: `LazyVerticalGrid(Fixed(5))` of `installedApps` on the right, fixed `LiquidGlassWidget` cards on the left, `LiquidGlassDock` at the bottom (dock built from a hardcoded `preferredPkgs`, HomeActivity.kt:760-771). Orphaned edit machinery already exists (`jiggle` :2871, `RemoveBadge` :2910, `HomeWidgetStore`, `UserLayout`) but has zero call sites. We wire an `editMode` state driven by long-press into `LauncherScreen`, reuse/extend the persistence stores, add new stores for dock + icon overrides, add resizable widgets in a left-column widget grid, and add an `IconRenderer` for consistent squircle icons. No third-party `AppWidgetHost` binding (out of scope; device has no widget providers) — widgets are Immortal-native custom kinds.

**Tech Stack:** Kotlin, Jetpack Compose, existing SharedPreferences stores (`UserLayout`, `HomeWidgetStore`, `Curation`), `CalendarFeed` (iCal parser), `WifiManager`, JUnit + Mockito (JVM unit tests, no Robolectric).

## Global Constraints

- Copyright header on every NEW file, verbatim:
  ```
  /*
   * Copyright (c) 2026 Starbright Lab.
   *
   * This source code is licensed under the MIT license found in the
   * LICENSE file in the root directory of this source tree.
   */
  ```
- Never use the Meta Platforms copyright template.
- `minSdk 24` / `targetSdk 36`; device Portal+ Android 10 (API 29), arm64, TOUCH screen (also has a TV remote/D-pad path — keep tvFocusable where existing code uses it). No Google Play Services.
- Match existing style; edit over rewrite; keep changes focused.
- Build: `./gradlew :app:assembleDebug --offline`. Compile check: `./gradlew :app:compileDebugKotlin --offline`. Unit tests (first run needs network for deps): `./gradlew :app:testDebugUnitTest`.
- **Glyph safety:** only use glyphs with coverage on Portal Android 10. Prefer stroke-drawn shapes (the existing `RemoveBadge` draws its mark with strokes) and basic characters. (Lesson: `⌄` rendered as a stray "v" in the settings pass.)
- Device serial: `FAMILY_PORTAL_SERIAL`. `HomeActivity`/settings are `exported=false` — relaunch the app or tap the dock; don't rely on `am start` for settings.
- On-device Compose behavior verified manually (repo convention); pure logic is unit-tested.

## Interaction model (from iOS research — applies to all icon/tile gestures)

- **Tap = launch** (on release). **Long-press = enter edit mode directly** (no context menu).
- **No grow-on-press.** Optional subtle *depress* to 0.96 via `detectTapGestures(onPress = { animateTo(0.96f); tryAwaitRelease(); animateTo(1f) })` so scale ALWAYS restores (fixes the current stuck-enlarged bug). Never scale above 1f.
- **Delete affordance:** a gray **minus "−" in a gray circle at the TOP-LEFT** corner of each editable tile.
- **Exit edit mode:** tap empty space, hardware/gesture Back, or an on-screen "Done" pill.
- Delete semantics: user-uninstallable apps → fire `ACTION_DELETE` (real uninstall). System/undeletable apps → "remove from home" (hidden in layout store), never a dead button.

---

## File Structure

- **Create** `DockStore.kt` — persist the editable dock package list (default = current `preferredPkgs`).
- **Create** `IconRenderer.kt` — adaptive-icon → consistent squircle `ImageBitmap`.
- **Create** `CalendarWidget.kt` — `ImmortalCalendarWidget` + pure helpers.
- **Create** `PortalBridge.kt` — extract the existing stock-home bridge into a reusable `launchPortalHome(context)` used by the Dashboard + Call tiles. (If a bridge fn already exists inline in HomeActivity, move/expose it here.)
- **Modify** `HomeWidgetStore.kt` — add `KIND_CALENDAR`; widget size model already has `spanX/spanY`.
- **Modify** `Curation.kt` — add an `iconOverride(pkg): Int?` map (drawable res) for hero apps.
- **Modify** `HomeActivity.kt` — the big one: `editMode` state, gesture rewrite, reorder, minus-delete, dock editing, left-column widget grid with resize, WiFi bars, fading divider, new tiles, use `IconRenderer`.
- **Tests:** `CalendarWidgetTest.kt`, `DockStoreTest.kt`, `WidgetGridTest.kt` (resize/reflow logic), `UserLayoutTest.kt` (reorder contract).

---

### Task 1: Calendar widget data helpers (pure, TDD)

**Files:**
- Create: `app/src/main/java/com/immortal/launcher/CalendarWidget.kt` (helpers only here)
- Test: `app/src/test/java/com/immortal/launcher/CalendarWidgetTest.kt`

**Interfaces:**
- Consumes: `CalendarFeed.Event(startMillis, endMillis, title, allDay, location)` (CalendarFeed.kt:42-48).
- Produces:
  - `internal fun calendarEventLine(e: CalendarFeed.Event, nowMillis: Long, is24h: Boolean): String`
  - `internal data class CalendarDayGroup(val label: String, val events: List<CalendarFeed.Event>)`
  - `internal fun groupEventsByDay(events: List<CalendarFeed.Event>, nowMillis: Long, maxDays: Int = 3): List<CalendarDayGroup>`

- [ ] **Step 1: Write the failing test**

```kotlin
/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CalendarWidgetTest {
  private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
    val c = Calendar.getInstance(); c.set(y, mo, d, h, mi, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
  }
  @Test fun timedEventLine_12h() {
    val now = at(2026, 6, 24, 8, 0)
    val e = CalendarFeed.Event(at(2026, 6, 24, 9, 30), at(2026, 6, 24, 10, 0), "Standup", false)
    val line = calendarEventLine(e, now, is24h = false)
    assertTrue(line.contains("Standup")); assertTrue(line.contains("9:30"))
  }
  @Test fun allDayEventLine() {
    val now = at(2026, 6, 24, 8, 0)
    val e = CalendarFeed.Event(at(2026, 6, 24, 0, 0), at(2026, 6, 25, 0, 0), "Holiday", true)
    assertEquals("All day  Holiday", calendarEventLine(e, now, is24h = false))
  }
  @Test fun groupByDay_labelsAndCaps() {
    val now = at(2026, 6, 24, 8, 0)
    val a = CalendarFeed.Event(at(2026, 6, 24, 9, 0), at(2026, 6, 24, 10, 0), "A", false)
    val b = CalendarFeed.Event(at(2026, 6, 25, 9, 0), at(2026, 6, 25, 10, 0), "B", false)
    val c = CalendarFeed.Event(at(2026, 6, 30, 9, 0), at(2026, 6, 30, 10, 0), "C", false)
    val g = groupEventsByDay(listOf(a, b, c), now, maxDays = 2)
    assertEquals(2, g.size); assertEquals("Today", g[0].label); assertEquals("Tomorrow", g[1].label)
  }
}
```

- [ ] **Step 2: Run — verify fail.** `./gradlew :app:testDebugUnitTest --tests "com.immortal.launcher.CalendarWidgetTest"` → FAIL (unresolved).

- [ ] **Step 3: Implement helpers** in `CalendarWidget.kt`

```kotlin
/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal fun calendarEventLine(e: CalendarFeed.Event, nowMillis: Long, is24h: Boolean): String {
  if (e.allDay) return "All day  ${e.title}"
  val pattern = if (is24h) "HH:mm" else "h:mm a"
  return "${SimpleDateFormat(pattern, Locale.getDefault()).format(Date(e.startMillis))}  ${e.title}"
}

internal data class CalendarDayGroup(val label: String, val events: List<CalendarFeed.Event>)

internal fun groupEventsByDay(
    events: List<CalendarFeed.Event>, nowMillis: Long, maxDays: Int = 3,
): List<CalendarDayGroup> {
  fun midnight(millis: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); return c.timeInMillis
  }
  val base = midnight(nowMillis)
  fun dayIdx(m: Long) = ((midnight(m) - base) / (24L * 60 * 60 * 1000)).toInt()
  fun label(idx: Int, m: Long) = when (idx) {
    0 -> "Today"; 1 -> "Tomorrow"
    else -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(m))
  }
  return events.sortedBy { it.startMillis }.groupBy { dayIdx(it.startMillis) }.toSortedMap()
      .entries.filter { it.key in 0 until maxDays }
      .map { (idx, evs) -> CalendarDayGroup(label(idx, evs.first().startMillis), evs) }
}
```

- [ ] **Step 4: Run — verify pass.** Same command → PASS (3).
- [ ] **Step 5: Commit** `git add app/src/main/java/com/immortal/launcher/CalendarWidget.kt app/src/test/java/com/immortal/launcher/CalendarWidgetTest.kt && git commit -m "feat(home): calendar widget data helpers"`

---

### Task 2: KIND_CALENDAR + widget size model helper (TDD)

**Files:**
- Modify: `app/src/main/java/com/immortal/launcher/HomeWidgetStore.kt:44` (add constant)
- Test: `app/src/test/java/com/immortal/launcher/WidgetGridTest.kt`

**Interfaces:**
- Produces: `HomeWidgetStore.KIND_CALENDAR = "calendar"`; a pure `internal fun nextWidgetSpan(current: Pair<Int,Int>): Pair<Int,Int>` cycling iOS-style sizes Small(1×1)→Medium(2×1)→Large(2×2)→Small, clamped to `MAX_SPAN`.

- [ ] **Step 1: Failing test** `WidgetGridTest.kt`

```kotlin
/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetGridTest {
  @Test fun sizeCycle_smallMediumLargeWrap() {
    assertEquals(2 to 1, HomeWidgetStore.nextWidgetSpan(1 to 1)) // small -> medium
    assertEquals(2 to 2, HomeWidgetStore.nextWidgetSpan(2 to 1)) // medium -> large
    assertEquals(1 to 1, HomeWidgetStore.nextWidgetSpan(2 to 2)) // large -> small
  }
}
```

- [ ] **Step 2: Run — verify fail.** `./gradlew :app:testDebugUnitTest --tests "com.immortal.launcher.WidgetGridTest"` → FAIL.

- [ ] **Step 3: Implement** in `HomeWidgetStore.kt` — add after line 44:

```kotlin
  const val KIND_CALENDAR = "calendar"

  /** iOS-style size cycle for a left-column widget: Small 1x1 -> Medium 2x1 -> Large 2x2 -> Small. */
  fun nextWidgetSpan(current: Pair<Int, Int>): Pair<Int, Int> = when (current) {
    1 to 1 -> 2 to 1
    2 to 1 -> 2 to 2
    else -> 1 to 1
  }
```

- [ ] **Step 4: Run — verify pass.** → PASS.
- [ ] **Step 5: Commit** `git add app/src/main/java/com/immortal/launcher/HomeWidgetStore.kt app/src/test/java/com/immortal/launcher/WidgetGridTest.kt && git commit -m "feat(home): KIND_CALENDAR + widget size cycle"`

---

### Task 3: ImmortalCalendarWidget composable

**Files:** Modify `CalendarWidget.kt` (append composable).

**Interfaces:**
- Consumes: `ScreensaverConfig.load(context).calendarUrl` (ScreensaverConfig.kt:142,283); `CalendarFeed.fetch(url)` (:119); Task 1 helpers. Follows `ImmortalWeatherWidget` pattern (produceState + IO fetch, RoundedCornerShape glass card).
- Produces: `@Composable internal fun ImmortalCalendarWidget(modifier: Modifier = Modifier)`.

- [ ] **Step 1: Append composable** (reads feed, groups events, renders glass card with "Today/Tomorrow" sections; hint text when no URL / no events). Use the same imports `ImmortalWeatherWidget` uses; render a `Color(0x1EFFFFFF)` glass card with `RoundedCornerShape(24.dp)`, a "Calendar" title (20sp SemiBold white), and a `LazyColumn` of grouped `calendarEventLine` rows (15sp white 0.9a; group label 12sp white 0.55a uppercase). Empty states: `url.isNullOrBlank()` → "No calendar connected. Add a calendar link in Settings → Screensaver."; else → "No upcoming events."

```kotlin
@androidx.compose.runtime.Composable
internal fun ImmortalCalendarWidget(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val is24h = remember { android.text.format.DateFormat.is24HourFormat(context) }
  val url = remember { ScreensaverConfig.load(context).calendarUrl }
  val events by androidx.compose.runtime.produceState(
      initialValue = emptyList<CalendarFeed.Event>(), url) {
    value = if (url.isNullOrBlank()) emptyList()
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CalendarFeed.fetch(url) }
  }
  val now = System.currentTimeMillis()
  val groups = remember(events) { groupEventsByDay(events, now, maxDays = 3) }
  // ... glass Box + Column("Calendar" title) + when{ url blank -> hint; groups empty -> hint;
  //     else LazyColumn { groups.forEach { group label item + event line items } } } ...
}
```
(Full body mirrors `ImmortalWeatherWidget`; implementer completes rows using `calendarEventLine`.)

- [ ] **Step 2: Compile check.** `./gradlew :app:compileDebugKotlin --offline` → SUCCESS.
- [ ] **Step 3: Commit** `git commit -am "feat(home): ImmortalCalendarWidget"`

---

### Task 4: DockStore (persist editable dock, TDD)

**Files:**
- Create: `app/src/main/java/com/immortal/launcher/DockStore.kt`
- Test: `app/src/test/java/com/immortal/launcher/DockStoreTest.kt`

**Interfaces:**
- Produces:
  - `object DockStore { const val MAX = 5 }`
  - `fun DockStore.load(context): List<String>` (returns saved pkgs, or `DEFAULT` if unset)
  - `fun DockStore.save(context, pkgs: List<String>)`
  - `internal fun DockStore.serialize/deserialize` (JSON array, tolerant — mirrors UserLayout)
  - `internal fun addToDock(current: List<String>, pkg: String, max: Int): List<String>` (append if room + not present)
  - `internal fun removeFromDock(current: List<String>, pkg: String): List<String>`
  - `val DockStore.DEFAULT: List<String>` = the current preferredPkgs (HomeActivity.kt:762-768).

- [ ] **Step 1: Failing test** `DockStoreTest.kt`

```kotlin
/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class DockStoreTest {
  @Test fun add_appendsWhenRoomAndAbsent() {
    assertEquals(listOf("a", "b"), DockStore.addToDock(listOf("a"), "b", max = 5))
    assertEquals(listOf("a"), DockStore.addToDock(listOf("a"), "a", max = 5)) // dup ignored
  }
  @Test fun add_respectsMax() {
    assertEquals(listOf("a", "b"), DockStore.addToDock(listOf("a", "b"), "c", max = 2)) // full
  }
  @Test fun remove_dropsPkg() {
    assertEquals(listOf("a"), DockStore.removeFromDock(listOf("a", "b"), "b"))
  }
  @Test fun serializeRoundTrip() {
    val list = listOf("x", "y")
    assertEquals(list, DockStore.deserialize(DockStore.serialize(list)))
  }
}
```

- [ ] **Step 2: Run — verify fail.** → FAIL.
- [ ] **Step 3: Implement `DockStore.kt`** (mirror `UserLayout` codec style; `load` returns `DEFAULT` when key unset; `addToDock`/`removeFromDock`/`serialize`/`deserialize` pure).
- [ ] **Step 4: Run — verify pass.** → PASS (4).
- [ ] **Step 5: Commit** `git add app/src/main/java/com/immortal/launcher/DockStore.kt app/src/test/java/com/immortal/launcher/DockStoreTest.kt && git commit -m "feat(home): DockStore for editable dock"`

---

### Task 5: UserLayout reorder contract test (guard existing logic)

**Files:** Create/append `app/src/test/java/com/immortal/launcher/UserLayoutTest.kt`.

- [ ] **Step 1: Tests** for `moveOrder`/`applyOrder` (functions exist UserLayout.kt:145-163):

```kotlin
/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class UserLayoutTest {
  @Test fun moveOrder_movesSourceToTargetPosition() {
    assertEquals(listOf("b", "c", "a", "d"), UserLayout.moveOrder(listOf("a","b","c","d"), "a", "c"))
  }
  @Test fun moveOrder_noopWhenSameOrMissing() {
    assertEquals(listOf("a","b"), UserLayout.moveOrder(listOf("a","b"), "a", "a"))
    assertEquals(listOf("a","b"), UserLayout.moveOrder(listOf("a","b"), "x", "b"))
  }
  @Test fun applyOrder_knownThenRest() {
    assertEquals(listOf("c","a","b"), UserLayout.applyOrder(listOf("a","b","c"), listOf("c","a")) { it })
  }
}
```

- [ ] **Step 2: Run — verify pass** (logic already exists). If red, adapt reorder wiring in Task 8 to real behavior; do NOT change `UserLayout`.
- [ ] **Step 3: Commit** `git add app/src/test/java/com/immortal/launcher/UserLayoutTest.kt && git commit -m "test(home): lock UserLayout reorder contract"`

---

### Task 6: IconRenderer — consistent squircle icons

**Files:**
- Create: `app/src/main/java/com/immortal/launcher/IconRenderer.kt`
- Modify: `HomeActivity.kt:3043-3072` (`loadApps`) to use it; drop redundant per-tile `.clip()` at :734 and dock :3012 region.
- Modify: `Curation.kt` — add `fun iconOverride(pkg: String): Int? = ICON_OVERRIDES[pkg]` + map (empty or a couple of bundled hero drawables if provided; otherwise return null so the rendered app icon is used).

**Interfaces:**
- Produces: `fun renderAppIcon(context: Context, drawable: android.graphics.drawable.Drawable, sizePx: Int = 144): android.graphics.Bitmap` — composites `AdaptiveIconDrawable` fore/background on API 26+, applies a squircle mask + safe padding; falls back to plain draw for legacy drawables.
- Consumes in `loadApps`: change `ri.loadIcon(pm)` → keep the `Drawable`, prefer `Curation.iconOverride(pkg)?.let { ContextCompat.getDrawable(...) }`, then `renderAppIcon(...)`, then `.asImageBitmap()`.

- [ ] **Step 1: Implement `IconRenderer.kt`** (adaptive composite + squircle mask via `Path`/`PorterDuff`, ~1 focused file).
- [ ] **Step 2: Wire into `loadApps`** and remove the now-redundant tile-level rounded clips (the mask lives in the bitmap).
- [ ] **Step 3: Add `iconOverride` to `Curation.kt`** (map may start empty — the renderer alone already fixes the transparent-corner inconsistency).
- [ ] **Step 4: Compile + install + eyeball on device**

```bash
./gradlew :app:assembleDebug --offline && adb -s FAMILY_PORTAL_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s FAMILY_PORTAL_SERIAL shell am start -n com.immortal.launcher.debug/com.immortal.launcher.HomeActivity
adb -s FAMILY_PORTAL_SERIAL exec-out screencap -p > /tmp/portal_shots/home_icons.png
```
Expected: every app icon is a uniform squircle (no transparent corners on circular Meta icons).

- [ ] **Step 5: Commit** `git commit -am "feat(home): IconRenderer for consistent squircle app icons"`

---

### Task 7: WiFi signal bars + fading center divider

**Files:** Modify `HomeActivity.kt` — status bar (:456-473) and main content Row (:476-483).

**Interfaces:**
- Produces: `@Composable private fun wifiLevel(): Int` (0–4, polling `WifiManager`); `@Composable private fun WifiBars(level: Int)`; a `FadingDivider()` composable.
- Consumes: `ACCESS_WIFI_STATE` (already granted), `WifiManager` (already used FleetAgentService.kt:65).

- [ ] **Step 1: `wifiLevel()`** — `produceState` polling every ~10s: `WifiManager.connectionInfo.rssi` → `WifiManager.calculateSignalLevel(rssi, 5)` (0–4). Returns -1 if WiFi off.
- [ ] **Step 2: `WifiBars(level)`** — draw 4 rising bars (Canvas/Row of rounded rects); filled bars white, empty bars white@25%. Replace `Text("WiFi", …)` at :468 with `WifiBars(wifiLevel())` (show a small "off" glyph or dim bars when -1).
- [ ] **Step 3: `FadingDivider()`** — a `Box(Modifier.width(1.dp).fillMaxHeight().background(Brush.verticalGradient(listOf(Transparent, White@30%, Transparent))))`. Insert between the left widget column and the right icon column in the main content `Row` (:476-622), e.g. as a sibling with small horizontal padding.
- [ ] **Step 4: Compile + install + verify**

```bash
./gradlew :app:assembleDebug --offline && adb -s FAMILY_PORTAL_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s FAMILY_PORTAL_SERIAL shell am start -n com.immortal.launcher.debug/com.immortal.launcher.HomeActivity
adb -s FAMILY_PORTAL_SERIAL exec-out screencap -p > /tmp/portal_shots/home_wifi_divider.png
```
Expected: top-right shows signal bars (not "WiFi" text); a soft vertical divider separates widgets from icons.

- [ ] **Step 5: Commit** `git commit -am "feat(home): WiFi signal bars + fading center divider"`

---

### Task 8: Edit mode — long-press, jiggle, minus-delete, reorder (right grid)

**Files:** Modify `HomeActivity.kt` — `LauncherScreen` body, `LiquidGlassAppIcon` (:688), grid (:607-620).

**Interfaces:**
- Consumes: `Modifier.jiggle` (:2871), `onUninstall` (:372, currently unused), `UserLayout` reorder (Task 5), the `onPress`/`tryAwaitRelease` gesture pattern.
- Produces: `editMode` state; `LiquidGlassAppIcon(app, editMode, onClick, onLongPress, onRemove)`; a `MinusBadge` (gray "−" in gray circle, top-start) — add near `RemoveBadge` or reuse `RemoveBadge` restyled gray.

- [ ] **Step 1: `editMode` state + exits.** In `LauncherScreen`: `var editMode by remember { mutableStateOf(false) }`; `BackHandler(enabled = editMode) { editMode = false }`; wrap content `Column` with `if (editMode) Modifier.pointerInput(Unit){ detectTapGestures { editMode = false } }`. Add a small "Done" pill (top-right of the right column) visible only in edit mode that sets `editMode = false`.
- [ ] **Step 2: `MinusBadge`** — gray circle (`Color(0xE6C7C7CC)`), stroke-drawn "−", ~28dp, `Alignment.TopStart`, own `clickable`.
- [ ] **Step 3: Rewrite `LiquidGlassAppIcon` gestures** to the research pattern:

```kotlin
private fun LiquidGlassAppIcon(
    app: AppEntry, editMode: Boolean,
    onClick: () -> Unit, onLongPress: () -> Unit, onRemove: () -> Unit,
) {
  val scale = remember { androidx.compose.animation.core.Animatable(1f) }
  Box(modifier = Modifier.jiggle(editMode, app.component.packageName.hashCode())) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .pointerInput(editMode) {
              detectTapGestures(
                  onPress = { scale.animateTo(0.96f, tween(90)); tryAwaitRelease(); scale.animateTo(1f, spring()) },
                  onTap = { if (!editMode) onClick() },
                  onLongPress = { if (!editMode) onLongPress() },
              )
            },
    ) { /* existing icon Box (drop the RoundedCornerShape clip if IconRenderer masks) + label */ }
    if (editMode && app.uninstallable) MinusBadge(Modifier.align(Alignment.TopStart)) { onRemove() }
  }
}
```
Add `val uninstallable: Boolean` to `AppEntry` (:164) — true when the app is not a system app (`ApplicationInfo.FLAG_SYSTEM == 0`) OR is updatable; compute in `loadApps`. System apps show no minus.

- [ ] **Step 4: Reorder.** Derive `orderedApps = UserLayout.applyOrder(installedApps, orderState) { it.component.packageName }`; grid `items(orderedApps)`. On each icon in edit mode add `detectDragGesturesAfterLongPress`; on drag-end compute target index from accumulated delta ÷ cell size, `UserLayout.moveOrder`, `UserLayout.saveOrder`, update `orderState`. Keep index math in a small pure helper `reorderByDelta(order, from, dx, dy, cols, cellPx)` with a unit test in `WidgetGridTest` or a new `ReorderTest`.
- [ ] **Step 5: Wire grid call site** — `onLongPress = { editMode = true }`, `onRemove = { onUninstall(app.component.packageName) }`.
- [ ] **Step 6: Compile + install + verify on device** (long-press → jiggle + gray minus top-left; tap app in edit mode does NOT launch; drag reorders and persists across relaunch; minus on a user app → uninstall dialog; Back/tap-blank/Done exits; **press no longer sticks enlarged**). Screenshot `/tmp/portal_shots/home_edit.png`.
- [ ] **Step 7: Commit** `git commit -am "feat(home): long-press edit mode, minus-delete, drag-reorder icons"`

---

### Task 9: Editable dock (add/remove, drag in/out)

**Files:** Modify `HomeActivity.kt` — `LiquidGlassDock` (:753-817), `DockIcon` (:820).

**Interfaces:** Consumes `DockStore` (Task 4). Dock list becomes `DockStore.load(context)` (state), not hardcoded `preferredPkgs`.

- [ ] **Step 1: Drive dock from `DockStore`.** Replace the hardcoded `dockApps` build (:770-772) with `DockStore.load(context)` mapped through `appsByPkg`; keep the Settings gear pinned last.
- [ ] **Step 2: Edit-mode dock affordances.** Pass `editMode` into `LiquidGlassDock`/`DockIcon`; in edit mode each dock icon jiggles and shows a `MinusBadge` (top-start) that calls `DockStore.removeFromDock` + save + refresh state. Reuse the `onPress`/`tryAwaitRelease` gesture (no grow-on-press) for `DockIcon` too — this also fixes the dock's stuck-scale bug (:830 `dockScale`).
- [ ] **Step 3: Add-to-dock.** In edit mode, a grid app's context = long-press already in edit; add a small "+dock" affordance: simplest v1 = when in edit mode, tapping a grid app's icon body (not its minus) calls `DockStore.addToDock(load, pkg, MAX)` + save (respects MAX, dup-safe) and shows a brief toast. (A full drag-into-dock is deferred; note this in a comment + `log`-style toast so behavior isn't silently surprising.)
- [ ] **Step 4: Compile + install + verify** (dock persists; remove via minus; add via edit-mode tap up to MAX; no stuck-big dock icon). Screenshot `/tmp/portal_shots/home_dock.png`.
- [ ] **Step 5: Commit** `git commit -am "feat(home): editable persisted dock (add/remove in edit mode)"`

---

### Task 10: Left-column widget grid — Calendar widget, add/remove, resize

**Files:** Modify `HomeActivity.kt` — left column (:486-599).

**Interfaces:** Consumes `HomeWidgetStore` (load/withAdded/without/custom/save, :50-79), `nextWidgetSpan` (Task 2), `ImmortalCalendarWidget` (Task 3), existing `ImmortalWeatherWidget`.

- [ ] **Step 1: Left column reads widget store.** `var homeWidgets by remember { mutableStateOf(HomeWidgetStore.load(context)) }`. Render each stored widget by kind (`KIND_WEATHER`→`ImmortalWeatherWidget`, `KIND_CALENDAR`→`ImmortalCalendarWidget`, etc.) inside a `FlowRow` so a Medium (2×1) fills the row and two Small (1×1) sit side-by-side — this delivers "shrink one, add another next to it". Height scales with `spanY`.
- [ ] **Step 2: Seed defaults** if store empty (e.g. one weather Large) so existing users see no regression.
- [ ] **Step 3: Edit-mode widget controls.** In edit mode each widget shows: a `MinusBadge` (top-start) → `HomeWidgetStore.without` + save; and a small resize control (bottom-right, e.g. a "⤢"-style stroke-drawn glyph or a plain "◱") → `HomeWidgetStore` update span via `nextWidgetSpan(spanX to spanY)` + save. Add a "+ Add widget" tile in edit mode listing Calendar/Weather/World Clock/Timers → `HomeWidgetStore.custom(kind)` + `withAdded` + save. Refresh `homeWidgets` after each mutation.
- [ ] **Step 4: Compile + install + verify** (add Calendar; resize weather Small↔Medium↔Large; two smalls sit side by side; remove via minus; persists across relaunch; needs a calendar URL in Settings→Screensaver for events). Screenshots `/tmp/portal_shots/home_widgets.png`, `home_widget_resize.png`.
- [ ] **Step 5: Commit** `git commit -am "feat(home): resizable multi-widget left column with Calendar widget"`

---

### Task 11: New action tiles — Portal Dashboard + Portal Calling

**Files:** Create `PortalBridge.kt`; Modify `HomeActivity.kt` (add two `BuiltInTile`s to the grid or as pinnable tiles).

**Interfaces:**
- Produces: `fun launchPortalHome(context: Context): Boolean` — `Intent(ACTION_VIEW, Uri.parse("portal://launcher/home")).setPackage("com.facebook.alohaapps.launcher")` guarded by `resolveActivity(...) != null`, with the existing Portal-TV Back-burst fallback (extract from HomeActivity.kt:290-362). Both tiles call this.
- Consumes: existing `BuiltInTile` pattern (:1806) + `PortalHomeTile` (:1774).

- [ ] **Step 1: Extract `launchPortalHome`** into `PortalBridge.kt` (move the inline bridge logic; keep the `resolveActivity` guard + fallback). Update existing callers to use it.
- [ ] **Step 2: Add two built-in tiles** — "Dashboard" and "Call" — both `onClick = { launchPortalHome(context) }`, distinct labels/glyphs (Dashboard: a grid glyph; Call: `ICON_CALL` already exists per icon research). Render them in the grid (as leading built-in tiles) or make them addable; simplest v1 = always show both as the first two grid tiles.
- [ ] **Step 3: Compile + install + verify on device** (tap Dashboard → Portal home/dashboard opens; tap Call → Portal calling surface opens). Screenshot `/tmp/portal_shots/home_tiles.png`.
- [ ] **Step 4: Commit** `git commit -am "feat(home): Portal dashboard + calling action tiles via portal://launcher/home bridge"`

---

### Task 12: Full regression + on-device acceptance

**Files:** none (verification).

- [ ] **Step 1: Unit tests.** `./gradlew :app:testDebugUnitTest` → all green (`CalendarWidgetTest`, `WidgetGridTest`, `DockStoreTest`, `UserLayoutTest`, existing `SettingsDomainTest`).
- [ ] **Step 2: Full build + install.** `./gradlew :app:assembleDebug --offline && adb -s FAMILY_PORTAL_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk`
- [ ] **Step 3: Acceptance checklist (screenshot each):**
  - Tap launches; **press never sticks enlarged**.
  - Long-press → jiggle + gray minus top-left; system apps show no minus.
  - Drag reorders; persists across relaunch.
  - Minus on user app → uninstall dialog.
  - Dock: remove via minus; add in edit mode up to MAX; persists.
  - Widgets: add Calendar; resize S/M/L; two smalls side-by-side; remove; persists.
  - Calendar widget shows grouped events (with a URL set) or the hint.
  - WiFi bars in top-right; fading divider between widgets and icons.
  - Icons render as uniform squircles.
  - Dashboard + Call tiles open the Portal home/calling surface.
  - Back / tap-blank / Done exits edit mode.
- [ ] **Step 4: Final commit** if cleanup needed.

---

## Self-Review notes

- **Scope covered:** edit mode + exits (T8), minus-delete top-left + uninstall semantics (T8), reorder (T8), editable dock (T4,T9), Calendar widget (T1-3,T10), resizable/multi widgets (T2,T10), WiFi bars + divider (T7), squircle/custom icons (T6), Dashboard + Call tiles (T11), press-bug fix (T8 gesture pattern, applied to dock in T9).
- **Honest limits baked in:** true one-tap WhatsApp/Messenger call is NOT built (Meta signature wall) — Call tile routes to Portal's calling surface per user decision. Third-party `AppWidgetHost` binding NOT built (device has no providers). Full drag-into-dock is a tap-to-add v1 (noted in T9).
- **Type consistency:** `AppEntry` gains `uninstallable: Boolean` (T8) used only in T8; `nextWidgetSpan(Pair<Int,Int>)` defined T2 used T10; `launchPortalHome(Context): Boolean` defined T11 used T11 + existing callers; `DockStore.addToDock/removeFromDock/load/save` defined T4 used T9.
- **Risk:** grid drag hit-testing (T8.4) — mitigated by the delta÷cell-size helper with a unit test. Widget `FlowRow` side-by-side sizing (T10) is the other soft spot — verified on device in T10.4.
- **Glyph safety:** minus/resize marks stroke-drawn or basic; no fragile unicode.

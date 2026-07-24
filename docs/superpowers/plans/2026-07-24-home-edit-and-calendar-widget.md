# Home-Screen Edit Mode + Calendar Widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add long-press edit mode to the live home screen (jiggle, drag-to-reorder icons, ✕-to-uninstall) and a native Calendar widget that reads the app's existing calendar feed.

**Architecture:** The shipping home screen is `LauncherScreen` (HomeActivity.kt:365-633), a static iOS-26 "Liquid Glass" layout: a `LazyVerticalGrid(Fixed(5))` of `installedApps` on the right, fixed `LiquidGlassWidget` cards on the left. We (1) add an `editMode` state driven by long-press, (2) apply the existing `jiggle` modifier + a `RemoveBadge` to each icon and make the grid reorderable with persistence via the existing `UserLayout` order store, and (3) add a new `ImmortalCalendarWidget` composable (a new `KIND_CALENDAR` in `HomeWidgetStore`) reading `CalendarFeed`. No `AppWidgetHost` binding is built (out of scope; the device has no third-party widget providers anyway).

**Tech Stack:** Kotlin, Jetpack Compose, existing `UserLayout`/`HomeWidgetStore` SharedPreferences stores, `CalendarFeed` (iCal parser), JUnit + Mockito (JVM unit tests, no Robolectric).

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
- `minSdk 24` / `targetSdk 36`; device is Portal+ Android 10 (API 29), arm64, touch screen. No Google Play Services.
- Match existing style; prefer editing over rewrites; keep changes focused.
- Build: `./gradlew :app:assembleDebug`. Unit tests (need network for deps first run): `./gradlew :app:testDebugUnitTest`. Compile-only quick check: `./gradlew :app:compileDebugKotlin --offline`.
- Glyphs must have coverage on Portal Android 10 — prefer filled/basic glyphs (`▾`, `✓`, `✕` drawn as strokes via the existing `RemoveBadge`). (Lesson from the settings pass: `⌄` rendered as a stray "v".)
- Install/verify target device serial: `FAMILY_PORTAL_SERIAL`. Settings/home aren't `am start`-able (exported=false) — launch `HomeActivity` then tap the dock, or relaunch the app.
- On-device Compose behavior is verified manually (repo convention); pure logic is unit-tested.

---

## File Structure

- **Modify** `app/src/main/java/com/immortal/launcher/HomeActivity.kt` — add `editMode` state + long-press trigger to `LauncherScreen`; make the right-column grid reorderable; wire `RemoveBadge`/jiggle/uninstall onto `LiquidGlassAppIcon`; add the Calendar widget card to the left column and an "+ Add Calendar" affordance in edit mode.
- **Modify** `app/src/main/java/com/immortal/launcher/HomeWidgetStore.kt` — add `KIND_CALENDAR` constant + label.
- **Create** `app/src/main/java/com/immortal/launcher/CalendarWidget.kt` — the `ImmortalCalendarWidget` composable + a pure `formatEventTime`/`groupByDay` helper (unit-testable).
- **Create** `app/src/test/java/com/immortal/launcher/CalendarWidgetTest.kt` — unit tests for the pure formatting/grouping helpers.
- **Modify** `app/src/test/java/com/immortal/launcher/UserLayoutTest.kt` (or create if absent) — cover `moveOrder`/`applyOrder` edge cases relied on by reorder.

---

### Task 1: Calendar widget data helpers (pure, TDD)

**Files:**
- Create: `app/src/main/java/com/immortal/launcher/CalendarWidget.kt` (helpers only in this task)
- Test: `app/src/test/java/com/immortal/launcher/CalendarWidgetTest.kt`

**Interfaces:**
- Consumes: `CalendarFeed.Event(startMillis, endMillis, title, allDay, location)` (CalendarFeed.kt:42-48).
- Produces:
  - `internal fun calendarEventLine(e: CalendarFeed.Event, nowMillis: Long, is24h: Boolean): String` — a one-line label like `"9:30 AM  Standup"` or `"All day  Holiday"`.
  - `internal data class CalendarDayGroup(val label: String, val events: List<CalendarFeed.Event>)`
  - `internal fun groupEventsByDay(events: List<CalendarFeed.Event>, nowMillis: Long, maxDays: Int = 3): List<CalendarDayGroup>` — groups sorted events into day buckets labelled "Today"/"Tomorrow"/weekday, capped to `maxDays`.

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

  private fun at(year: Int, month: Int, day: Int, hour: Int, min: Int): Long {
    val c = Calendar.getInstance()
    c.set(year, month, day, hour, min, 0); c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
  }

  @Test fun timedEventLine_12h() {
    val now = at(2026, 6, 24, 8, 0)
    val e = CalendarFeed.Event(at(2026, 6, 24, 9, 30), at(2026, 6, 24, 10, 0), "Standup", false)
    val line = calendarEventLine(e, now, is24h = false)
    assertTrue(line.contains("Standup"))
    assertTrue(line.contains("9:30"))
  }

  @Test fun allDayEventLine() {
    val now = at(2026, 6, 24, 8, 0)
    val e = CalendarFeed.Event(at(2026, 6, 24, 0, 0), at(2026, 6, 25, 0, 0), "Holiday", true)
    assertEquals("All day  Holiday", calendarEventLine(e, now, is24h = false))
  }

  @Test fun groupByDay_labelsTodayTomorrow_andCaps() {
    val now = at(2026, 6, 24, 8, 0)
    val today = CalendarFeed.Event(at(2026, 6, 24, 9, 0), at(2026, 6, 24, 10, 0), "A", false)
    val tomorrow = CalendarFeed.Event(at(2026, 6, 25, 9, 0), at(2026, 6, 25, 10, 0), "B", false)
    val far = CalendarFeed.Event(at(2026, 6, 30, 9, 0), at(2026, 6, 30, 10, 0), "C", false)
    val groups = groupEventsByDay(listOf(today, tomorrow, far), now, maxDays = 2)
    assertEquals(2, groups.size)
    assertEquals("Today", groups[0].label)
    assertEquals("Tomorrow", groups[1].label)
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.immortal.launcher.CalendarWidgetTest"`
Expected: FAIL — `calendarEventLine` / `groupEventsByDay` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `CalendarWidget.kt` with the header and helpers (composable added in Task 5):

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
  val time = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(e.startMillis))
  return "$time  ${e.title}"
}

internal data class CalendarDayGroup(val label: String, val events: List<CalendarFeed.Event>)

internal fun groupEventsByDay(
    events: List<CalendarFeed.Event>,
    nowMillis: Long,
    maxDays: Int = 3,
): List<CalendarDayGroup> {
  fun dayIndex(millis: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    val n = Calendar.getInstance().apply { timeInMillis = nowMillis }
    n.set(Calendar.HOUR_OF_DAY, 0); n.set(Calendar.MINUTE, 0)
    n.set(Calendar.SECOND, 0); n.set(Calendar.MILLISECOND, 0)
    return ((c.timeInMillis - n.timeInMillis) / (24L * 60 * 60 * 1000)).toInt()
  }
  fun label(idx: Int, millis: Long): String = when (idx) {
    0 -> "Today"
    1 -> "Tomorrow"
    else -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(millis))
  }
  return events
      .sortedBy { it.startMillis }
      .groupBy { dayIndex(it.startMillis) }
      .toSortedMap()
      .entries
      .filter { it.key in 0 until maxDays }
      .map { (idx, evs) -> CalendarDayGroup(label(idx, evs.first().startMillis), evs) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.immortal.launcher.CalendarWidgetTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/immortal/launcher/CalendarWidget.kt app/src/test/java/com/immortal/launcher/CalendarWidgetTest.kt
git commit -m "feat(home): calendar widget data helpers (event line + day grouping)"
```

---

### Task 2: KIND_CALENDAR in the widget store

**Files:**
- Modify: `app/src/main/java/com/immortal/launcher/HomeWidgetStore.kt:41-44`

**Interfaces:**
- Produces: `HomeWidgetStore.KIND_CALENDAR = "calendar"` constant.

- [ ] **Step 1: Add the constant**

In `HomeWidgetStore.kt`, after line 44 (`const val KIND_TIMERS = "timers"`), add:

```kotlin
  const val KIND_CALENDAR = "calendar"
```

- [ ] **Step 2: Compile check**

Run: `./gradlew :app:compileDebugKotlin --offline`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/immortal/launcher/HomeWidgetStore.kt
git commit -m "feat(home): add KIND_CALENDAR widget kind"
```

---

### Task 3: The ImmortalCalendarWidget composable

**Files:**
- Modify: `app/src/main/java/com/immortal/launcher/CalendarWidget.kt` (append composable)

**Interfaces:**
- Consumes: `ScreensaverConfig.load(context).calendarUrl` (ScreensaverConfig.kt:142,283) for the feed URL; `CalendarFeed.fetch(url)` (CalendarFeed.kt:119); `groupEventsByDay` / `calendarEventLine` (Task 1). Follows the `ImmortalWeatherWidget` pattern (HomeActivity.kt, produceState + IO fetch, RoundedCornerShape(24.dp) glass card).
- Produces: `@Composable internal fun ImmortalCalendarWidget(modifier: Modifier = Modifier)`.

- [ ] **Step 1: Append the composable** to `CalendarWidget.kt`

```kotlin
@androidx.compose.runtime.Composable
internal fun ImmortalCalendarWidget(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val is24h = remember { android.text.format.DateFormat.is24HourFormat(context) }
  // Reuse the screensaver's calendar feed URL so there's a single source of truth.
  val url = remember { ScreensaverConfig.load(context).calendarUrl }
  val events by androidx.compose.runtime.produceState(
      initialValue = emptyList<CalendarFeed.Event>(), url) {
    value = if (url.isNullOrBlank()) emptyList()
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
          CalendarFeed.fetch(url)
        }
  }
  val now = System.currentTimeMillis()
  val groups = remember(events) { groupEventsByDay(events, now, maxDays = 3) }

  androidx.compose.foundation.layout.Box(
      modifier = modifier
          .androidx.compose.ui.draw.clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
          .background(androidx.compose.ui.graphics.Color(0x1EFFFFFF))
          .border(1.dp, androidx.compose.ui.graphics.Color(0x38FFFFFF),
              androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
          .padding(18.dp),
  ) {
    androidx.compose.foundation.layout.Column(
        modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
      androidx.compose.material3.Text(
          "Calendar",
          color = androidx.compose.ui.graphics.Color.White,
          fontSize = 20.sp,
          fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
      )
      androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(10.dp))
      when {
        url.isNullOrBlank() -> CalendarWidgetHint(
            "No calendar connected. Add a calendar link in Settings → Screensaver.")
        groups.isEmpty() -> CalendarWidgetHint("No upcoming events.")
        else -> androidx.compose.foundation.lazy.LazyColumn(
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
          groups.forEach { group ->
            item {
              androidx.compose.material3.Text(
                  group.label.uppercase(),
                  color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.55f),
                  fontSize = 12.sp,
                  fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                  modifier = androidx.compose.ui.Modifier.padding(top = 6.dp, bottom = 2.dp),
              )
            }
            items(group.events.size) { i ->
              androidx.compose.material3.Text(
                  calendarEventLine(group.events[i], now, is24h),
                  color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f),
                  fontSize = 15.sp,
                  maxLines = 1,
                  overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }
  }
}

@androidx.compose.runtime.Composable
private fun CalendarWidgetHint(text: String) {
  androidx.compose.material3.Text(
      text,
      color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
      fontSize = 14.sp,
      lineHeight = 20.sp,
  )
}
```

> Note: fully-qualified names keep the new file self-contained; the implementer may hoist them to imports to match house style (preferred). Match the exact imports already used by `ImmortalWeatherWidget` in HomeActivity.kt.

- [ ] **Step 2: Compile check**

Run: `./gradlew :app:compileDebugKotlin --offline`
Expected: BUILD SUCCESSFUL (fix any import/qualifier issues).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/immortal/launcher/CalendarWidget.kt
git commit -m "feat(home): ImmortalCalendarWidget reading the screensaver calendar feed"
```

---

### Task 4: Show the Calendar widget in the left column

**Files:**
- Modify: `app/src/main/java/com/immortal/launcher/HomeActivity.kt` — LEFT COLUMN of `LauncherScreen` (around lines 486-599, the `Column` weighted `0.38f`).

**Interfaces:**
- Consumes: `ImmortalCalendarWidget` (Task 3). Persisted visibility via `HomeWidgetStore.load(context)` containing a `KIND_CALENDAR` custom widget (Task 2 + Task 6 add path). For THIS task, gate on a simple read so the widget shows once present.

- [ ] **Step 1: Read current left column** to find the insertion point

Run: `sed -n '486,599p' app/src/main/java/com/immortal/launcher/HomeActivity.kt`
Confirm the left `Column(weight(0.38f))` holds fixed `LiquidGlassWidget` cards.

- [ ] **Step 2: Add calendar widget when present**

Inside `LauncherScreen`, before the left column, add:

```kotlin
    val homeWidgets by produceState(initialValue = HomeWidgetStore.load(context)) {
      value = HomeWidgetStore.load(context)
    }
    val hasCalendar = homeWidgets.any { it.kind == HomeWidgetStore.KIND_CALENDAR }
```

Then inside the left `Column`, after the existing weather widget block, add:

```kotlin
          if (hasCalendar) {
            ImmortalCalendarWidget(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).height(220.dp),
            )
          }
```

> The implementer must place this inside the existing `Column(verticalArrangement = spacedBy(12.dp))` so spacing is consistent, and confirm total left-column height still fits (drop or shrink a stub card if needed — the stub weather/now-playing cards at 493-598 may be reduced to make room; keep the live weather one).

- [ ] **Step 3: Compile + install + verify (manual, on device)**

```bash
./gradlew :app:assembleDebug --offline
adb -s FAMILY_PORTAL_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

Temporarily seed a calendar widget to see it (until Task 6 adds the UI):
`adb -s FAMILY_PORTAL_SERIAL shell` is not needed — instead verify after Task 6. For now confirm the app builds and the home screen still renders (no calendar widget yet because none is stored). Screenshot:
```bash
adb -s FAMILY_PORTAL_SERIAL shell am start -n com.immortal.launcher.debug/com.immortal.launcher.HomeActivity
adb -s FAMILY_PORTAL_SERIAL exec-out screencap -p > /tmp/portal_shots/home_task4.png
```
Expected: home renders unchanged (calendar hidden until added).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/immortal/launcher/HomeActivity.kt
git commit -m "feat(home): render Calendar widget in left column when present"
```

---

### Task 5: Long-press → edit mode (jiggle + exit)

**Files:**
- Modify: `app/src/main/java/com/immortal/launcher/HomeActivity.kt` — `LauncherScreen` body + `LiquidGlassAppIcon` (line 688).

**Interfaces:**
- Consumes: existing `Modifier.jiggle(enabled, seed)` (HomeActivity.kt:2871), `RemoveBadge(size, modifier, onClick)` (line 2910), `onUninstall: (String) -> Unit` already passed to `LauncherScreen` (line 372, currently unused).
- Produces: `editMode` state in `LauncherScreen`; `LiquidGlassAppIcon` gains `editMode: Boolean`, `onLongPress: () -> Unit`, `onRemove: () -> Unit` params.

- [ ] **Step 1: Add editMode state** near the top of `LauncherScreen` (after `installedApps`):

```kotlin
  var editMode by remember { mutableStateOf(false) }
```

- [ ] **Step 2: Exit affordances** — add Back handling and tap-blank.

At the top of `LauncherScreen`, add:
```kotlin
  BackHandler(enabled = editMode) { editMode = false }
```
(import `androidx.activity.compose.BackHandler` if not present.)

On the root `BoxWithConstraints`'s content `Column` (line 430), add a tap-to-exit when in edit mode:
```kotlin
    Column(
        modifier = Modifier.fillMaxSize().let {
          if (editMode) it.pointerInput(Unit) { detectTapGestures(onTap = { editMode = false }) } else it
        }
    ) {
```
(import `androidx.compose.foundation.gestures.detectTapGestures` and `androidx.compose.ui.input.pointer.pointerInput` if not present.)

- [ ] **Step 3: Change `LiquidGlassAppIcon` signature + wire long-press/jiggle/remove**

Replace the signature (line 688-691) and its click/gesture block:

```kotlin
private fun LiquidGlassAppIcon(
    app: AppEntry,
    editMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onRemove: () -> Unit,
) {
```

Wrap the icon `Box` in a small `Box` so the remove badge can overlap the corner, apply jiggle, and route gestures:

```kotlin
  Box(modifier = Modifier.jiggle(editMode, app.component.packageName.hashCode())) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .pointerInput(editMode) {
              detectTapGestures(
                  onLongPress = { onLongPress() },
                  onTap = { if (!editMode) onClick() },
              )
            },
    ) {
      // ... existing icon Box + label ...
    }
    if (editMode) {
      RemoveBadge(size = 28.dp, modifier = Modifier.align(Alignment.TopEnd)) { onRemove() }
    }
  }
```

> Remove the old `.clickable{}` and the `awaitEachGesture` press block (replaced by `detectTapGestures`). Keep the `pressed`/`scale` animation only if retained via a press interaction; otherwise drop `pressed` to avoid an unused warning.

- [ ] **Step 4: Update the grid call site** (line 614-619):

```kotlin
            items(orderedApps) { app ->
              LiquidGlassAppIcon(
                  app = app,
                  editMode = editMode,
                  onClick = { onLaunch(app.component) },
                  onLongPress = { editMode = true },
                  onRemove = { onUninstall(app.component.packageName) },
              )
            }
```

(`orderedApps` is introduced in Task 6; for this task use `installedApps` and switch in Task 6.)

- [ ] **Step 5: Compile + install + verify on device**

```bash
./gradlew :app:assembleDebug --offline
adb -s FAMILY_PORTAL_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s FAMILY_PORTAL_SERIAL shell am start -n com.immortal.launcher.debug/com.immortal.launcher.HomeActivity
```
Manual: long-press an icon → all icons jiggle and show a ✕ badge; tap blank space → jiggle stops; press Back → jiggle stops. Screenshot `/tmp/portal_shots/home_editmode.png`.
Expected: long-press enters edit mode; ✕ visible; tapping an app in edit mode does NOT launch it; tapping blank / Back exits.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/immortal/launcher/HomeActivity.kt
git commit -m "feat(home): long-press enters edit mode (jiggle + remove badge + exit)"
```

---

### Task 6: Drag-to-reorder + persist, and "+ Add Calendar"

**Files:**
- Modify: `app/src/main/java/com/immortal/launcher/HomeActivity.kt` — grid reorder + add-widget affordance.
- Test: `app/src/test/java/com/immortal/launcher/UserLayoutTest.kt` (create if absent) — `moveOrder`/`applyOrder`.

**Interfaces:**
- Consumes: `UserLayout.loadOrder` / `saveOrder` / `applyOrder(items, order, idOf)` / `moveOrder(order, source, target)` (UserLayout.kt:46-163); `HomeWidgetStore.custom(kind)` / `withAdded` / `save` (HomeWidgetStore.kt:65-74).
- Produces: `orderedApps` (installedApps with saved order applied); an "+ Add Calendar" control shown in edit mode.

- [ ] **Step 1: Write failing tests for the reorder logic** (guards the persistence contract)

Create/append `app/src/test/java/com/immortal/launcher/UserLayoutTest.kt`:

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
  @Test fun moveOrder_movesSourceBeforeTargetPosition() {
    val order = listOf("a", "b", "c", "d")
    assertEquals(listOf("b", "c", "a", "d"), UserLayout.moveOrder(order, "a", "c"))
  }

  @Test fun moveOrder_noopWhenSameOrMissing() {
    val order = listOf("a", "b")
    assertEquals(order, UserLayout.moveOrder(order, "a", "a"))
    assertEquals(order, UserLayout.moveOrder(order, "x", "b"))
  }

  @Test fun applyOrder_ordersKnownThenAppendsRest() {
    val items = listOf("a", "b", "c")
    assertEquals(listOf("c", "a", "b"), UserLayout.applyOrder(items, listOf("c", "a")) { it })
  }
}
```

- [ ] **Step 2: Run — verify pass or fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.immortal.launcher.UserLayoutTest"`
Expected: PASS (these functions already exist; the tests lock the contract). If any fail, the reorder wiring below must adapt to actual behavior — do NOT change `UserLayout`.

- [ ] **Step 3: Apply saved order to the grid**

In `LauncherScreen`, derive the ordered list:

```kotlin
  val order = remember(installedApps) { UserLayout.loadOrder(context) }
  val orderedApps = remember(installedApps, order) {
    UserLayout.applyOrder(installedApps, order) { it.component.packageName }
  }
```

Switch the grid `items(installedApps)` → `items(orderedApps)` (from Task 5 step 4).

- [ ] **Step 4: Add drag-to-reorder** on each icon in edit mode.

Use `detectDragGesturesAfterLongPress` (already imported, HomeActivity.kt:53) on the icon in edit mode to detect a drop target. Simplest robust approach for a Fixed(5) grid: track drag as index movement. Add to `LiquidGlassAppIcon`'s gesture modifier when `editMode`:

```kotlin
            .then(
                if (editMode) Modifier.pointerInput(app.component.packageName) {
                  detectDragGesturesAfterLongPress(
                      onDragEnd = { onReorderCommit() },
                  ) { change, _ -> change.consume(); onReorderDrag(change.position) }
                } else Modifier
            )
```

> The implementer wires `onReorderDrag`/`onReorderCommit` in `LauncherScreen` to compute the hovered item and call `UserLayout.moveOrder`, then `UserLayout.saveOrder(context, newOrder)` and update a local `mutableStateOf` order so the grid recomposes. Because pixel-accurate hit-testing across a `LazyVerticalGrid` is fiddly, an acceptable v1 is: on drag end, compute target index from accumulated drag delta / cell size and reorder by index. Keep the reorder logic in a small pure helper if it grows, and add a unit test for it.

- [ ] **Step 5: "+ Add Calendar" affordance** — shown in the left column only in edit mode.

In the left `Column`, when `editMode && !hasCalendar`, render a dashed "add" card:

```kotlin
          if (editMode && !hasCalendar) {
            Box(
                modifier = Modifier.fillMaxWidth().height(120.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0x14FFFFFF))
                    .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(24.dp))
                    .pointerInput(Unit) { detectTapGestures {
                      val next = HomeWidgetStore.withAdded(
                          HomeWidgetStore.load(context),
                          HomeWidgetStore.custom(HomeWidgetStore.KIND_CALENDAR))
                      HomeWidgetStore.save(context, next)
                    } },
                contentAlignment = Alignment.Center,
            ) { Text("+ Add Calendar", color = Color.White, fontSize = 18.sp) }
          }
```

> After save, the implementer must refresh `homeWidgets` state (re-read `HomeWidgetStore.load` — e.g. bump a `remember` key or move `homeWidgets` to a `mutableStateOf` updated here) so the calendar widget appears immediately. Add a matching "✕ remove" on the calendar widget in edit mode using `HomeWidgetStore.without(list, key)`.

- [ ] **Step 6: Compile + install + verify on device**

```bash
./gradlew :app:assembleDebug --offline
adb -s FAMILY_PORTAL_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s FAMILY_PORTAL_SERIAL shell am start -n com.immortal.launcher.debug/com.immortal.launcher.HomeActivity
```
Manual: long-press → edit mode → drag an icon to a new spot → exit → reopen Settings/home → order persisted. In edit mode tap "+ Add Calendar" → calendar widget appears with events (needs a calendar URL set in Settings → Screensaver). Screenshots `/tmp/portal_shots/home_reorder.png`, `/tmp/portal_shots/home_calendar.png`.
Expected: reorder persists across app relaunch; calendar widget shows grouped upcoming events (or the "No calendar connected" hint if no URL).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/immortal/launcher/HomeActivity.kt app/src/test/java/com/immortal/launcher/UserLayoutTest.kt
git commit -m "feat(home): drag-to-reorder icons (persisted) + add/remove Calendar widget in edit mode"
```

---

### Task 7: Full regression + final on-device pass

**Files:** none (verification only).

- [ ] **Step 1: Unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; `CalendarWidgetTest`, `UserLayoutTest`, and existing `SettingsDomainTest` all green.

- [ ] **Step 2: Full build + install**

Run: `./gradlew :app:assembleDebug --offline && adb -s FAMILY_PORTAL_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: BUILD SUCCESSFUL, Success.

- [ ] **Step 3: On-device acceptance checklist** (screenshot each)
  - Normal mode: tapping an app launches it (edit mode OFF by default).
  - Long-press → jiggle + ✕ badges appear.
  - Drag an icon → reorder; relaunch → order persisted.
  - ✕ on an app → uninstall dialog (`onUninstall` → ACTION_DELETE).
  - "+ Add Calendar" → widget appears; with a calendar URL set, shows grouped events; without, shows the hint.
  - Tap blank / Back → exits edit mode; tapping apps launches again.

- [ ] **Step 4: Final commit (if any cleanup)**

```bash
git add -A
git commit -m "chore(home): edit-mode + calendar widget verification pass"
```

---

## Self-Review notes

- **Spec coverage:** reorder (Task 6), delete/uninstall (Task 5, reuses `onUninstall`), long-press edit mode + exit (Task 5), add Calendar widget (Tasks 3-6), native calendar reading existing feed (Tasks 1,3). Resize and third-party `AppWidgetHost` binding are explicitly OUT of scope per the approved "Reorder + delete + add Calendar widget" option.
- **Out of scope (documented):** free-placement grid, widget resize, real system-widget hosting, Play Store/Play Services (firmware-blocked — see docs/limitations.md).
- **Risk:** drag hit-testing in a `LazyVerticalGrid` is the least-certain piece; Task 6 Step 4 gives a delta/cell-size fallback so v1 always ships a working reorder.
- **Glyph safety:** uses `RemoveBadge` (stroke-drawn ✕) and plain text; no fragile unicode.

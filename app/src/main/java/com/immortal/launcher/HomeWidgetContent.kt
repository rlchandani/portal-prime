/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Intent
import com.immortal.launcher.ui.theme.ProvideHearthpaneTouchTargets
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.components.OnResume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.drop

/**
 * Picker and draft previews: the real widget for local-data kinds (cheap, no network), a static
 * representative preview for kinds that would fetch or poll just to be seen.
 */
internal val HomeWidgetPreviewRenderer: HomeWidgetPreviewContent = { kind, span, modifier ->
  if (kind in HomeWidgetPicker.livePreviewKinds) {
    val widget = remember(kind, span) {
      HomeWidgetStore.HomeWidget(kind = kind, customId = "preview:$kind", spanX = span.first, spanY = span.second,
          explicitSize = true)
    }
    ImmortalWidgetContent(widget, modifier)
  } else HomeStaticWidgetPreview(kind, modifier)
}

@Composable
internal fun ImmortalWidgetContent(
    widget: HomeWidgetStore.HomeWidget,
    modifier: Modifier = Modifier,
    onEnterEdit: (() -> Unit)? = null,
) {
  // Guest mode: personal widgets (photos, calendar, Today, the household list) show the time instead.
  val guestContent = GuestPolicy.homeWidgetContent(widget.kind)
  if (guestContent != null && rememberGuestHides(guestContent)) {
    GuestNeutralCard(modifier)
    return
  }
  when (widget.kind) {
    HomeWidgetStore.KIND_WEATHER -> ApprovedHomeWeather(modifier)
    HomeWidgetStore.KIND_WORLD_CLOCK -> ImmortalWorldClockWidget(modifier)
    HomeWidgetStore.KIND_TIMERS -> SharedTimersWidget(modifier)
    HomeWidgetStore.KIND_CALENDAR -> ApprovedHomeCalendar(modifier)
    HomeWidgetStore.KIND_ALARM -> ImmortalAlarmWidget(modifier)
    HomeWidgetStore.KIND_CRYPTO -> ImmortalCryptoWidget(modifier, maxRows = listRows(widget.spanY))
    // The To-do kind now shows the shared household list (old To-do items merge in once).
    HomeWidgetStore.KIND_TODO -> HouseholdListWidget(modifier, small = widget.spanY < 2)
    HomeWidgetStore.KIND_ROUTINES -> RoutinesHomeWidget(modifier)
    HomeWidgetStore.KIND_CHORES -> ChoresHomeWidget(modifier)
    HomeWidgetStore.KIND_CALLS -> CallsHomeWidget(modifier)
    HomeWidgetStore.KIND_NEWS -> ImmortalNewsWidget(modifier, maxRows = listRows(widget.spanY))
    HomeWidgetStore.KIND_STOCKS -> ImmortalStocksWidget(modifier, maxRows = listRows(widget.spanY))
    HomeWidgetStore.KIND_SPORTS -> SportsHomeWidget(modifier)
    HomeWidgetStore.KIND_OPTIONS -> ImmortalOptionsWidget(modifier, maxRows = listRows(widget.spanY))
    HomeWidgetStore.KIND_HA -> ImmortalHaWidget(modifier, maxTileRows = widget.spanY.coerceIn(1, 4))
    // The former fixed dashboard cards, now regular widgets. They keep their self-sizing card
    // look; the grid cell just gives them room.
    HomeWidgetStore.KIND_TODAY -> TodayStripWidget(modifier)
    HomeWidgetStore.KIND_QUICK_CONTROLS -> QuickControlsWidget(modifier)
    HomeWidgetStore.KIND_PHOTO_MEMORY -> ApprovedHomePhoto(modifier, onEnterEdit)
    HomeWidgetStore.KIND_WIFI -> WifiHomeWidget(modifier, WifiWidgetLayout.of(widget.spanX, widget.spanY))
    else -> UnknownImmortalWidget(widget.kind, modifier)
  }
}

/**
 * How many list rows a scrolling widget should show at a given vertical span. One grid unit fits a
 * title + ~2 rows; each extra unit adds ~3. Clamped so a 1-tall widget still shows a couple of rows
 * and a 4-tall one doesn't run away.
 */
private fun listRows(spanY: Int): Int = (spanY * 3).coerceIn(2, 12)

@Composable
internal fun ImmortalWidgetShell(
    title: String,
    openPage: String,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    scrollContent: Boolean = true,
    /** True when the card body already shows a button doing exactly what [onOpen] does. */
    duplicatesBodyAction: Boolean = false,
    /** The ✦ line for learned rows (Jarvis personalization); null draws the card exactly as before. */
    provenance: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val action = HomeRefinements.headerAction(opensContent = onOpen != null, duplicatesBodyAction)
  val icon = when (openPage) {
    "calendar" -> "calendar"
    "alarm" -> "alarm"
    "ha" -> "ha_widget"
    "world" -> "clock"
    "crypto", "stocks", "options" -> "calculator"
    "timers" -> "timer"
    "todo" -> "notes"
    "news" -> "globe"
    "sports" -> "interest_ball"
    else -> "tools"
  }
  Column(modifier = modifier.padding(30.dp)) {
    ApprovedHomeWidgetHeading(title, icon, action) {
      if (onOpen != null) onOpen() else context.openApprovedHomeSettings(openPage)
    }
    if (provenance != null) LearnedProvenance(provenance, Modifier.padding(top = 10.dp))
    Spacer(Modifier.height(if (provenance != null) 14.dp else 22.dp))
    Column(Modifier.weight(1f).fillMaxWidth().then(
        if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier)) { content() }
  }

}

/**
 * Home quick-controls: a glass grid of tap targets for the smart-home stuff a Portal owner reaches
 * for — a Hey Portal voice tile (launches the on-device assistant), and Home Assistant shortcuts
 * that open the HA app (the one-way MQTT bridge can't toggle devices, so we hand off to HA itself).
 * Tiles whose target isn't installed are hidden, so the grid only shows what actually works here.
 */
@Composable
private fun QuickControlsWidget(modifier: Modifier = Modifier) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val pm = context.packageManager
  var refresh by remember { mutableStateOf(0) }
  OnResume { refresh++ }
  val installed by produceState(initialValue = emptySet<String>(), refresh) {
    value = withContext(Dispatchers.IO) {
      listOf("io.homeassistant.companion.android", "io.homeassistant.companion.android.minimal", "com.portal.assistant")
          .filter { runCatching { pm.getLaunchIntentForPackage(it) != null }.getOrDefault(false) }.toSet()
    }
  }
  val haPackage = installed.firstOrNull { it.startsWith("io.homeassistant.") }
  val haInstalled = haPackage != null
  val assistantInstalled = "com.portal.assistant" in installed
  val dashboardConfigured = remember(refresh) { HomeDashboardConfig.configured(context) }
  fun launch(pkg: String) {
    runCatching { pm.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) } }
  }

  data class QC(val icon: String, val label: String, val description: String, val onTap: () -> Unit)
  var showDinner by remember { mutableStateOf(false) }
  val tiles = buildList {
    // Call everyone (household feature): chime + speak a message in every room.
    add(QC(DinnerCallTile.ICON, DinnerCallTile.LABEL, DinnerCallTile.DESCRIPTION) { showDinner = true })
    // Guests: the Wi-Fi card and Guest mode (hides personal content while visitors are here).
    add(QC(GuestEntry.ICON, GuestEntry.LABEL, GuestEntry.DESCRIPTION) { GuestEntry.openCard(context) })
    if (assistantInstalled) add(QC("mic", "Hey Jarvis", "Open your assistant") { triggerJarvis(context) })
    if (dashboardConfigured) {
      add(QC("ha", "Dashboard", "Open your dashboard") {
        HomeDashboardActivity.openDashboard(context)
      })
    } else if (haInstalled) {
      add(QC("bulb", "Lights", "Open Home Assistant") { launch(haPackage.orEmpty()) })
      add(QC("film", "Scenes", "Open Home Assistant") { launch(haPackage.orEmpty()) })
      add(QC("weather", "Climate", "Open Home Assistant") { launch(haPackage.orEmpty()) })
      add(QC("ha", "Home", "Open Home Assistant") { launch(haPackage.orEmpty()) })
    }
  }
  // The sheet is full-screen: host it in its own window, not inside this widget's card bounds.
  if (showDinner) {
    androidx.compose.ui.window.Dialog(onDismissRequest = { showDinner = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false)) { ProvideHearthpaneTouchTargets {
      Box(Modifier.fillMaxSize()) { DinnerCallSheet(onDismiss = { showDinner = false }) }
    }}
  }
  ImmortalWidgetShell(title = "Quick controls", openPage = "display", modifier = modifier, scrollContent = false) {
    if (tiles.isEmpty()) {
      Text("Add an assistant or Home Assistant app to use quick controls.",
          color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp)
    } else {
      // Fit every tile in the card (no hidden scroll): split the real height across the rows and
      // drop the description line when a row is too short to hold it.
      val rows = tiles.chunked(2)
      val compactCards = currentLayoutClass().compact
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = 14.dp
        val rowHeight = (maxHeight - gap * (rows.size - 1)) / rows.size
        val showDescription = rowHeight >= QUICK_TILE_FULL_HEIGHT
        val padding = if (rowHeight >= QUICK_TILE_FULL_HEIGHT) 20.dp else 14.dp
        // Compact cards can leave a row too short for icon over label: lay those tiles out side by side.
        val sideBySide = compactCards && rowHeight < QUICK_TILE_STACKED_MIN
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
          rows.forEach { row ->
            Row(Modifier.fillMaxWidth().height(rowHeight), horizontalArrangement = Arrangement.spacedBy(gap)) {
              row.forEach { qc ->
                if (sideBySide) Row(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(17.dp))
                    .background(ApprovedHomePalette.Chrome).tvFocusable { qc.onTap() }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                  ApprovedHomeIcon(qc.icon, Modifier.size(28.dp), ApprovedHomePalette.Text)
                  Text(qc.label, color = ApprovedHomePalette.Text, fontSize = 21.sp, lineHeight = 26.sp,
                      fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(17.dp))
                    .background(ApprovedHomePalette.Chrome).tvFocusable { qc.onTap() }.padding(padding),
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                  ApprovedHomeIcon(qc.icon, Modifier.size(32.dp), ApprovedHomePalette.Text)
                  Text(qc.label, color = ApprovedHomePalette.Text, fontSize = 23.sp, lineHeight = 30.sp,
                      fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                  if (showDescription) Text(qc.description, color = ApprovedHomePalette.Secondary,
                      fontSize = 20.sp, lineHeight = 27.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
              }
              if (row.size == 1) Spacer(Modifier.weight(1f))
            }
          }
        }
      }
    }
  }
}

/** Row height at which a quick-control tile has room for its description line. */
private val QUICK_TILE_FULL_HEIGHT = 150.dp
/** Below this row height a compact tile can't stack its 32 dp icon over a 30 dp label. */
private val QUICK_TILE_STACKED_MIN = 100.dp

@Composable
private fun UnknownImmortalWidget(kind: String, modifier: Modifier = Modifier) {
  Box(modifier.padding(30.dp), contentAlignment = Alignment.Center) {
    Text(kind, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp)
  }
}

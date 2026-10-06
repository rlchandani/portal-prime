/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

/**
 * Tools list and in-tab detail workspaces, also embedded in the native Home launcher.
 * Only explicit camera viewing and lamp preview actions hand off to full-screen Activities.
 */
class ToolsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(R.style.Theme_SampleApp)
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { ApprovedToolsScreen(::finish) } }
  }
}

/** Which tool detail is open in the tab (or [ToolPage.LIST] for the retained list). */
private enum class ToolPage(val title: String) {
  LIST("Tools"),
  ISS("ISS passes"),
  AURORA("Aurora outlook"),
  SPEEDTEST("Speed test"),
  TIMERS("Timers"),
  NOTES("Leave a note"),
  CONVERTER("Converter"),
  ALARM("Alarm"),
  CAMERAS("Cameras"),
  COUNTDOWNS("Countdowns"),
  LAMP("Lamp"),
  INTERCOM("Intercom"),
  BEDTIME("Bedtime story"),
}

/** Native tool pages; an embedding Home host supplies its theme, header and dock. */
@Composable
internal fun ApprovedToolsScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
  var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(ToolPage.LIST) }
  // Keep the grid's position outside the conditional detail composition.
  val listState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
  val back = { page = ToolPage.LIST }
  BackHandler { if (page == ToolPage.LIST) onExit() else back() }
  androidx.compose.runtime.CompositionLocalProvider(LocalApprovedToolPage provides true) {
    val content: @Composable () -> Unit = {
      Box(modifier.fillMaxSize()) {
        if (page == ToolPage.LIST) {
          ToolsScreen(listState = listState, onOpen = { page = it })
        } else {
          ApprovedToolWorkspace(
              title = page.title, onDismiss = back,
              scrollContent = page != ToolPage.BEDTIME,
          ) {
            when (page) {
              ToolPage.ISS -> IssOverlay(back)
              ToolPage.AURORA -> AuroraOverlay(back)
              ToolPage.SPEEDTEST -> SpeedTestOverlay(back)
              ToolPage.TIMERS -> TimersOverlay(back)
              ToolPage.NOTES -> NotesOverlay(back)
              ToolPage.CONVERTER -> ConverterOverlay(back)
              ToolPage.ALARM -> AlarmContent()
              ToolPage.CAMERAS -> CameraControlsContent(onDismiss = back)
              ToolPage.COUNTDOWNS -> CountdownSettingsContent()
              ToolPage.LAMP -> LampControlsContent()
              ToolPage.INTERCOM -> IntercomContent()
              ToolPage.BEDTIME -> BedtimeOverlay(back)
              ToolPage.LIST -> Unit
            }
          }
        }
      }
    }
    if (embedded) content() else ApprovedAppsShell("tools", onExit) { content() }
  }
}

/** Bedtime keeps its dark aesthetic, so it gets a dark card instead of the light [ToolCard]. */
@Composable
private fun BedtimeOverlay(onDismiss: () -> Unit) {
  BackHandler { onDismiss() }
  if (LocalApprovedToolWorkspace.current) {
    Surface(color = Color(0xFF0E0E1A), shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxSize()) {
      BedtimeContent(onExit = onDismiss)
    }
    return
  }
  if (LocalApprovedToolPage.current) {
    Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
      ApprovedAppsButton("‹  Tools", onClick = onDismiss)
      Spacer(Modifier.height(26.dp))
      Surface(color = Color(0xFF0E0E1A), shape = RoundedCornerShape(28.dp),
          modifier = Modifier.weight(1f).fillMaxWidth()) {
        BedtimeContent(onExit = onDismiss)
      }
    }
    return
  }
  val cfg = androidx.compose.ui.platform.LocalConfiguration.current
  val cardWidth = (cfg.screenWidthDp * 0.72f).dp.coerceAtMost(860.dp)
  val cardMaxHeight = (cfg.screenHeightDp * 0.82f).dp
  Scrim(onDismiss) {
    Surface(
        color = Color(0xFF0E0E1A),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.width(cardWidth).heightIn(max = cardMaxHeight)
            .tvFocusable(RoundedCornerShape(28.dp), focusScale = 1f) { /* consume */ },
    ) {
      Box {
        BedtimeContent(onExit = onDismiss)
        // ✕ close pinned to the card's top-right, over the dark content.
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) { CloseCircle(onDismiss) }
      }
    }
  }
}

/** One tool tile: icon + tint + label + tap action. */
private data class ToolItem(val label: String, val description: String, val onOpen: () -> Unit)

@Composable
private fun ToolsScreen(
    listState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onOpen: (ToolPage) -> Unit,
) {
  val advanced = com.immortal.launcher.ui.theme.LocalAdvancedSettings.current
  val tools = listOf(
      ToolItem("Alarm", "Choose your next wake-up time.") { onOpen(ToolPage.ALARM) },
      ToolItem("Cameras", "Keep an eye on your home.") { onOpen(ToolPage.CAMERAS) },
      ToolItem("Countdowns", "Count down to a day that matters.") { onOpen(ToolPage.COUNTDOWNS) },
      ToolItem("Lamp", "A soft glow for your room.") { onOpen(ToolPage.LAMP) },
      ToolItem("Bedtime story", "Something calm for the evening.") { onOpen(ToolPage.BEDTIME) },
      ToolItem("Intercom", "Talk across the house.") { onOpen(ToolPage.INTERCOM) },
      ToolItem("Timers", "A little time, set aside.") { onOpen(ToolPage.TIMERS) },
      ToolItem("Leave a note", "A message for the next person here.") { onOpen(ToolPage.NOTES) },
      ToolItem("Converter", "Quick everyday measurements.") { onOpen(ToolPage.CONVERTER) },
      ToolItem("ISS passes", "Find the next pass above your home.") { onOpen(ToolPage.ISS) },
      ToolItem("Aurora outlook", "Check the chance of northern lights.") { onOpen(ToolPage.AURORA) },
  ) + if (advanced) listOf(ToolItem("Speed test", "Download test and latency.") { onOpen(ToolPage.SPEEDTEST) }) else emptyList()
  androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
      columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
      state = listState,
      horizontalArrangement = Arrangement.spacedBy(24.dp),
      modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 36.dp, bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    item(key = "heading", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { ApprovedAppsHeading("Tools", "Everyday tools for your Portal.") }
    items(tools.size, key = { tools[it].label }) { index -> ToolTile(tools[index], Modifier.fillMaxWidth()) }
  }
}

@Composable
private fun ToolTile(item: ToolItem, modifier: Modifier = Modifier) {
  val glyph = when (item.label) {
    "Alarm" -> "bell"; "Cameras" -> "film"; "Countdowns" -> "hourglass"; "Lamp" -> "bulb"
    "Bedtime story", "Leave a note" -> "notes"; "Intercom" -> "megaphone"; "Timers" -> "timer"
    "Converter" -> "calc"; "ISS passes" -> "globe"; "Aurora outlook" -> "weather"; else -> "wifi"
  }
  Surface(color = IosCard, shape = RoundedCornerShape(24.dp), border = androidx.compose.foundation.BorderStroke(1.dp, IosDivider), modifier = modifier) {
    Column(Modifier.height(210.dp).tvFocusable(RoundedCornerShape(24.dp), focusScale = 1f, onClick = item.onOpen).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
      ApprovedAppsGlyph(glyph, IosBlue, 40.dp)
      Text(item.label, color = IosLabel, fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium)
      Text(item.description, color = IosSecondary, fontSize = 22.sp, lineHeight = 30.sp)
    }
  }
}

/** A small circular ✕ close button (top-right of the folder / tool cards). */
@Composable
internal fun CloseCircle(onClick: () -> Unit) {
  Surface(
      color = IosDivider,
      shape = CircleShape,
      modifier = Modifier.size(68.dp).tvFocusable(CircleShape, focusScale = 1f) { onClick() },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text("✕", color = IosSecondary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
  }
}

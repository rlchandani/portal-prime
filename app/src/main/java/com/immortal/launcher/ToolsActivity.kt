/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

/**
 * The full-page "Tools" screen, opened from the built-in Tools tile on the home grid. A plain
 * launcher for the in-process tool features. See docs/design/feature-integration.md (step 3).
 *
 * Tools that already have their own Activity (cameras, countdowns, lamp, bedtime, intercom) launch
 * it directly. Tools whose UI formerly lived only in ForkHome open as an in-page overlay hosted here
 * (see [HomeToolOverlays]); this change adds the sky/space pair (ISS passes, aurora outlook).
 */
class ToolsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { ToolsRoot(::finish) } }
  }
}

/** Which in-page tool overlay is showing over the list (or [ToolPage.LIST] for the list itself). */
private enum class ToolPage {
  LIST,
  ISS,
  AURORA,
  SPEEDTEST,
  TIMERS,
  NOTES,
  CONVERTER,
  ALARM,
  COUNTDOWNS,
  INTERCOM,
  BEDTIME,
}

@Composable
private fun ToolsRoot(onExit: () -> Unit) {
  var page by remember { mutableStateOf(ToolPage.LIST) }
  val back = { page = ToolPage.LIST }
  when (page) {
    ToolPage.LIST -> ToolsScreen(onExit = onExit, onOpen = { page = it })
    ToolPage.ISS -> IssOverlay(back)
    ToolPage.AURORA -> AuroraOverlay(back)
    ToolPage.SPEEDTEST -> SpeedTestOverlay(back)
    ToolPage.TIMERS -> TimersOverlay(back)
    ToolPage.NOTES -> NotesOverlay(back)
    ToolPage.CONVERTER -> ConverterOverlay(back)
    // Formerly separate activities — now in-page pages so they share the one Tools overlay:
    // tapping a tile swaps the grid for the tool; closing returns to the grid (not home).
    ToolPage.ALARM -> ToolPageOverlay("Alarm", back) { AlarmContent() }
    ToolPage.COUNTDOWNS -> ToolPageOverlay("Countdowns", back) { CountdownSettingsContent() }
    ToolPage.INTERCOM -> ToolPageOverlay("Intercom", back) { IntercomContent() }
    ToolPage.BEDTIME -> BedtimeOverlay(back)
  }
}

/**
 * A light in-page tool page: a [ToolCard] with a [title] + ✕ close header, holding [content]. Used
 * for the tools converted from standalone activities (Alarm, Countdowns, Intercom). Closing is via
 * the ✕ or Back only — not tap-outside — so accidental taps don't lose your place.
 */
@Composable
private fun ToolPageOverlay(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
  BackHandler { onDismiss() }
  Scrim(onDismiss) {
    ToolCard {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(title, color = IosLabel, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        CloseCircle(onDismiss)
      }
      content()
    }
  }
}

/** Bedtime keeps its dark aesthetic, so it gets a dark card instead of the light [ToolCard]. */
@Composable
private fun BedtimeOverlay(onDismiss: () -> Unit) {
  BackHandler { onDismiss() }
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
private data class ToolItem(val label: String, val iconRes: Int, val tint: Color, val onOpen: () -> Unit)

@Composable
private fun ToolsScreen(onExit: () -> Unit, onOpen: (ToolPage) -> Unit) {
  val context = LocalContext.current
  val activity = context as? Activity
  fun launch(cls: Class<*>) = context.startActivity(Intent(context, cls))

  // App-drawer grid of tools, in a light iOS surface. Icon tints echo the Settings section colors.
  val tools = listOf(
      ToolItem("Alarm", R.drawable.ic_tool_alarm, Color(0xFFFF453A)) { onOpen(ToolPage.ALARM) },
      ToolItem("Cameras", R.drawable.ic_tool_camera, Color(0xFF5E5CE6)) { launch(CameraViewerActivity::class.java) },
      ToolItem("Countdowns", R.drawable.ic_settings_countdown, Color(0xFFFF9F0A)) { onOpen(ToolPage.COUNTDOWNS) },
      ToolItem("Lamp", R.drawable.ic_tool_lamp, Color(0xFFFFD60A)) { launch(LampActivity::class.java) },
      ToolItem("Bedtime story", R.drawable.ic_tool_story, Color(0xFFAF52DE)) { onOpen(ToolPage.BEDTIME) },
      ToolItem("Intercom", R.drawable.ic_tool_intercom, Color(0xFF30D158)) { onOpen(ToolPage.INTERCOM) },
      ToolItem("Timers", R.drawable.ic_tool_timer, Color(0xFFFF9F0A)) { onOpen(ToolPage.TIMERS) },
      ToolItem("Leave a note", R.drawable.ic_tool_note, Color(0xFFFFD60A)) { onOpen(ToolPage.NOTES) },
      ToolItem("Converter", R.drawable.ic_tool_converter, Color(0xFF64D2FF)) { onOpen(ToolPage.CONVERTER) },
      ToolItem("ISS passes", R.drawable.ic_tool_iss, Color(0xFF0A84FF)) { onOpen(ToolPage.ISS) },
      ToolItem("Aurora outlook", R.drawable.ic_tool_aurora, Color(0xFF30D158)) { onOpen(ToolPage.AURORA) },
      ToolItem("Speed test", R.drawable.ic_tool_speed, Color(0xFF5E5CE6)) { onOpen(ToolPage.SPEEDTEST) },
  )

  // iOS-style folder: a dark scrim over the (translucent-window) home, with a centered floating
  // card holding the tool grid. Closing is via the explicit ✕ button (top-right) or Back — NOT by
  // tapping outside, so an accidental outside-tap while using a tool can't dismiss it.
  val cfg = androidx.compose.ui.platform.LocalConfiguration.current
  val cardWidth = (cfg.screenWidthDp * 0.82f).dp.coerceAtMost(1100.dp)
  Box(
      modifier = Modifier.fillMaxSize()
          .onPreviewKeyEvent { e ->
            if (e.key == Key.Back) { if (e.type == KeyEventType.KeyUp) onExit(); true } else false
          }
          .background(Color(0x99000000)),
      contentAlignment = Alignment.Center,
  ) {
    Surface(
        color = IosBackground,
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier.width(cardWidth).padding(24.dp),
    ) {
      Column(modifier = Modifier.padding(horizontal = 32.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
          Text("Tools", color = IosLabel, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
          CloseCircle(onExit)
        }
        Spacer(Modifier.size(24.dp))
        val perRow = 4
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
          tools.chunked(perRow).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
              row.forEach { t -> ToolTile(t, modifier = Modifier.weight(1f)) }
              repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
          }
        }
      }
    }
  }
}

/**
 * One tool tile styled to match the home-grid app icons: a squircle-cornered tinted icon with a
 * soft drop shadow (3D depth) + a glossy top sheen, and a label in the same weight/size as the grid.
 */
@Composable
private fun ToolTile(item: ToolItem, modifier: Modifier = Modifier) {
  val iconShape = RoundedCornerShape(24.dp)
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = modifier.clip(RoundedCornerShape(22.dp)).tvFocusable(RoundedCornerShape(22.dp)) { item.onOpen() }
          .padding(vertical = 14.dp),
  ) {
    Box(
        modifier = Modifier
            .size(104.dp)
            .shadow(elevation = 8.dp, shape = iconShape, clip = false)
            .clip(iconShape)
            .background(item.tint),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painter = painterResource(item.iconRes),
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(56.dp),
      )
      // Glossy top-down sheen for the "candy"/3D finish — same as the home-grid icons.
      Box(
          modifier = Modifier.matchParentSize().background(
              Brush.verticalGradient(
                  colors = listOf(Color(0x33FFFFFF), Color(0x0FFFFFFF), Color.Transparent),
                  endY = 120f,
              )
          )
      )
    }
    Text(
        item.label,
        color = IosLabel,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier.padding(top = 12.dp),
    )
  }
}

/** A small circular ✕ close button (top-right of the folder / tool cards). */
@Composable
internal fun CloseCircle(onClick: () -> Unit) {
  Surface(
      color = IosDivider,
      shape = CircleShape,
      modifier = Modifier.size(40.dp).tvFocusable(CircleShape, focusScale = 1.1f) { onClick() },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text("✕", color = IosSecondary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
  }
}

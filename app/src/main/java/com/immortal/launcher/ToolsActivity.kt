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
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
}

@Composable
private fun ToolsRoot(onExit: () -> Unit) {
  var page by remember { mutableStateOf(ToolPage.LIST) }
  when (page) {
    ToolPage.LIST -> ToolsScreen(onExit = onExit, onOpen = { page = it })
    ToolPage.ISS -> IssOverlay { page = ToolPage.LIST }
    ToolPage.AURORA -> AuroraOverlay { page = ToolPage.LIST }
    ToolPage.SPEEDTEST -> SpeedTestOverlay { page = ToolPage.LIST }
    ToolPage.TIMERS -> TimersOverlay { page = ToolPage.LIST }
    ToolPage.NOTES -> NotesOverlay { page = ToolPage.LIST }
    ToolPage.CONVERTER -> ConverterOverlay { page = ToolPage.LIST }
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
      ToolItem("Alarm", R.drawable.ic_tool_alarm, Color(0xFFFF453A)) { launch(AlarmSettingsActivity::class.java) },
      ToolItem("Cameras", R.drawable.ic_tool_camera, Color(0xFF5E5CE6)) { launch(CameraViewerActivity::class.java) },
      ToolItem("Countdowns", R.drawable.ic_settings_countdown, Color(0xFFFF9F0A)) { launch(CountdownSettingsActivity::class.java) },
      ToolItem("Lamp", R.drawable.ic_tool_lamp, Color(0xFFFFD60A)) { launch(LampActivity::class.java) },
      ToolItem("Bedtime story", R.drawable.ic_tool_story, Color(0xFFAF52DE)) { launch(BedtimeStoryActivity::class.java) },
      ToolItem("Intercom", R.drawable.ic_tool_intercom, Color(0xFF30D158)) { launch(IntercomActivity::class.java) },
      ToolItem("Timers", R.drawable.ic_tool_timer, Color(0xFFFF9F0A)) { onOpen(ToolPage.TIMERS) },
      ToolItem("Leave a note", R.drawable.ic_tool_note, Color(0xFFFFD60A)) { onOpen(ToolPage.NOTES) },
      ToolItem("Converter", R.drawable.ic_tool_converter, Color(0xFF64D2FF)) { onOpen(ToolPage.CONVERTER) },
      ToolItem("ISS passes", R.drawable.ic_tool_iss, Color(0xFF0A84FF)) { onOpen(ToolPage.ISS) },
      ToolItem("Aurora outlook", R.drawable.ic_tool_aurora, Color(0xFF30D158)) { onOpen(ToolPage.AURORA) },
      ToolItem("Speed test", R.drawable.ic_tool_speed, Color(0xFF5E5CE6)) { onOpen(ToolPage.SPEEDTEST) },
  )

  SettingsScaffold(
      title = "Tools",
      subtitle = "Extra utilities for your Portal.",
      onBack = { onExit() },
  ) {
    // 4-per-row grid of icon tiles. Non-scrolling here (the scaffold body scrolls); use fixed rows.
    val perRow = 4
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
      tools.chunked(perRow).forEach { row ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          row.forEach { t -> ToolTile(t, modifier = Modifier.weight(1f)) }
          // Pad a short last row so tiles keep their column width instead of stretching.
          repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
        }
      }
    }
  }
}

@Composable
private fun ToolTile(item: ToolItem, modifier: Modifier = Modifier) {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = modifier.clip(RoundedCornerShape(18.dp)).tvFocusable(RoundedCornerShape(18.dp)) { item.onOpen() }
          .padding(vertical = 12.dp),
  ) {
    Box(
        modifier = Modifier.size(104.dp).clip(RoundedCornerShape(24.dp)).background(item.tint),
        contentAlignment = Alignment.Center,
    ) {
      Icon(
          painter = painterResource(item.iconRes),
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(56.dp),
      )
    }
    Text(
        item.label,
        color = IosLabel,
        fontSize = 18.sp,
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = Modifier.padding(top = 10.dp),
    )
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.immortal.launcher.ui.components.launchSafely

/** Guest-mode entry points shared by Home, Tools and Settings. */
internal object GuestEntry {
  const val ICON = "wifi"
  const val LABEL = "Guests"
  const val DESCRIPTION = "Wi-Fi and house basics for visitors"

  fun openCard(context: Context) = context.launchSafely(GuestCardActivity::class.java)

  fun openSettings(context: Context) {
    runCatching {
      context.startActivity(Intent(context, GuestSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }
}

/** Recomposes when Guest mode starts, ends, or reaches its scheduled end. */
@Composable
internal fun rememberGuestHides(content: GuestContent): Boolean {
  val state by GuestMode.state.collectAsState()
  return GuestMode.hides(content) && state.active
}

/** "Until Sat 11:00", "Until you end it". */
internal fun guestUntilLabel(state: GuestModeState, zone: ZoneId = ZoneId.systemDefault()): String {
  val until = state.untilMs ?: return "Until you end it"
  val pattern = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
  return "Until " + Instant.ofEpochMilli(until).atZone(zone).format(pattern)
}

/** What a personal widget shows while guests are here: the time, the date, and why. */
@Composable
internal fun GuestNeutralCard(modifier: Modifier = Modifier) {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(Unit) { while (true) { delay(60_000L - now % 60_000L); now = System.currentTimeMillis() } }
  val time = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
  Column(modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(ApprovedHomePalette.Card)
      .padding(24.dp).semantics { contentDescription = "Hidden while guests are here" },
      verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
    Text(time.format(DateTimeFormatter.ofPattern("HH:mm")), color = ApprovedHomePalette.Text,
        fontSize = 56.sp, lineHeight = 64.sp, fontWeight = FontWeight.Light)
    Text(time.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())),
        color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp)
  }
}

/** One calm line where personal content would be. */
@Composable
internal fun GuestHiddenNote(text: String, modifier: Modifier = Modifier) {
  Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(text, color = ApprovedHomePalette.Secondary, fontSize = 26.sp, lineHeight = 36.sp)
  }
}

private const val HOLD_MS = 1_500

/** Press and hold [HOLD_MS] with a filling ring to end Guest mode; a tap only hints. */
@Composable
internal fun GuestHoldToEnd(onEnd: () -> Unit, modifier: Modifier = Modifier) {
  val progress = remember { Animatable(0f) }
  val scope = rememberCoroutineScope()
  var hint by remember { mutableLongStateOf(0L) }
  val pressing = progress.value > 0f
  val ring = ApprovedHomePalette.Border
  val fill = ApprovedHomePalette.Text
  Row(modifier.heightIn(min = 88.dp).clip(RoundedCornerShape(44.dp))
      .background(if (pressing) ApprovedHomePalette.Selected else ApprovedHomePalette.Chrome)
      .semantics {
        contentDescription = "Press and hold to end Guest mode"
        onClick(label = "End Guest mode") { onEnd(); true }
      }
      .pointerInput(Unit) {
        detectTapGestures(onPress = {
          val job = scope.launch {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
            onEnd()
          }
          tryAwaitRelease()
          if (job.isActive) hint = System.currentTimeMillis()
          job.cancel()
          progress.snapTo(0f)
        })
      }.padding(start = 12.dp, end = 32.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Canvas(Modifier.size(64.dp)) {
      val stroke = 5.dp.toPx()
      drawCircle(ring, radius = size.minDimension / 2 - stroke, style = Stroke(stroke))
      if (progress.value > 0f) drawArc(fill, -90f, 360f * progress.value, false, topLeft = Offset(stroke, stroke),
          size = Size(size.width - 2 * stroke, size.height - 2 * stroke), style = Stroke(stroke, cap = StrokeCap.Round))
    }
    val recentTap = hint != 0L && System.currentTimeMillis() - hint < 3_000L
    Text(if (pressing) "Keep holding…" else if (recentTap) "Press and hold to end" else "Hold to end Guest mode",
        color = if (pressing) ApprovedHomePalette.Text else ApprovedHomePalette.Secondary, fontSize = 24.sp)
  }
  LaunchedEffect(hint) { if (hint != 0L) { delay(3_000L); hint = 0L } }
}

/** A full-screen stand-in for a personal screen opened while guests are here. */
@Composable
internal fun GuestHiddenScreen(text: String, onClose: () -> Unit) {
  Box(Modifier.fillMaxSize().background(ApprovedHomePalette.Background).padding(32.dp)) {
    GuestHiddenNote(text)
    Box(Modifier.align(Alignment.TopEnd)) { RoundIconButton("x", "Close", onClose) }
  }
}

/** Tools → Guests. */
@Composable
internal fun ToolsGuestsContent() {
  val context = LocalContext.current
  val state by GuestMode.state.collectAsState()
  val on = state.isOn(System.currentTimeMillis())
  ToolsPanel(GuestEntry.LABEL) {
    ToolsText("Show visitors your Wi-Fi as a QR code, with the house basics you choose.")
    ToolsText(if (on) "Guest mode is on. ${guestUntilLabel(state)}."
        else "Guest mode hides photos, calendar, notes and your music library. Playback controls stay.", secondary = true)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      ToolsButton("Show guest card", primary = true) { GuestEntry.openCard(context) }
      ToolsButton("Edit guest card") { GuestEntry.openSettings(context) }
    }
  }
}

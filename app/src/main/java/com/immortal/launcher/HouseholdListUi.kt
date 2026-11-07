/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import kotlinx.coroutines.delay

/** Everything a list surface renders, at one instant. */
internal data class HouseholdListView(
    val list: HouseholdListState,
    val sync: HouseholdListSyncState,
    val now: Long,
) {
  val status: HouseholdListSyncStatus.Status get() = sync.status(now, list)
  val notice: HouseholdListSyncState.Notice? get() = sync.notice?.takeIf { it.until > now }
}

/**
 * Collects the list + sync flows. The clock only ticks (1 s) while something on screen is
 * time-based — an arrival ring, a retry countdown or a notice — so an idle widget costs nothing.
 */
@Composable
internal fun rememberHouseholdListView(): HouseholdListView {
  val context = LocalContext.current
  LaunchedEffect(Unit) { HouseholdListStore.ensureLoaded(context) }
  val list by HouseholdListStore.state.collectAsState()
  val sync by HouseholdListSync.state.collectAsState()
  var now by remember { mutableStateOf(HouseholdListStore.clock()) }
  val ticking = remember(sync, now) {
    sync.retryAt?.let { it > now } == true || sync.notice?.let { it.until > now } == true ||
        sync.arrivals.values.any { now - it.at <= HouseholdListSyncState.FRESH_MS } || sync.busy > 0
  }
  LaunchedEffect(sync, ticking) {
    now = HouseholdListStore.clock()
    while (ticking) { delay(1_000); now = HouseholdListStore.clock() }
  }
  return HouseholdListView(list, sync, now)
}

/** A transient toast with optional Undo, shared by the widget and the sheet. */
internal class HouseholdListToast {
  var current by mutableStateOf<Pair<HouseholdListCommands.Feedback, Long>?>(null)
    private set

  fun show(feedback: HouseholdListCommands.Feedback) {
    if (feedback.text.isNotEmpty()) current = feedback to HouseholdListStore.clock()
  }

  fun clear() { current = null }
}

/** Applies a [HouseholdListCommands] call made on this Portal and shows its toast. */
internal fun Context.runListCommand(toast: HouseholdListToast?, f: (HouseholdListCommands, HouseholdListState, Long) -> HouseholdListCommands.Result): String? {
  val commands = HouseholdListCommands(HouseholdListSync.selfName(this)) { HouseholdListSync.state.value.online }
  val result = HouseholdListStore.update(this, HouseholdListStore.Source.LOCAL) { s ->
    f(commands, s, HouseholdListStore.clock()).let { it.state to it }
  }
  toast?.show(result.feedback)
  return result.focusId
}

internal object HouseholdListColors {
  val Green: Color @Composable get() = LocalApprovedPalette.current.green
  val Amber: Color @Composable get() = if (LocalApprovedPalette.current.dark) Color(0xFFF4DB93) else Color(0xFF9A6A00)
}

/** The rounded checkbox from the prototype (40dp on the widget, 48dp in the sheet). */
@Composable
internal fun HouseholdCheckbox(checked: Boolean, fresh: Boolean = false, size: Dp = 40.dp) {
  val shape = RoundedCornerShape(size * 0.28f)
  val green = HouseholdListColors.Green
  val border = when { checked -> green; fresh -> green; else -> Color(0xFF8A8A8A) }
  Box(Modifier.size(size).clip(shape).background(if (checked) green else Color.Transparent).border(3.dp, border, shape),
      contentAlignment = Alignment.Center) {
    if (checked) TickGlyph(Modifier.size(size * 0.62f), if (LocalApprovedPalette.current.dark) Color(0xFF0E2A1C) else Color.White)
  }
}

@Composable
internal fun TickGlyph(modifier: Modifier, color: Color) {
  Canvas(modifier) {
    val p = Path().apply {
      moveTo(size.width * 0.18f, size.height * 0.52f)
      lineTo(size.width * 0.42f, size.height * 0.76f)
      lineTo(size.width * 0.84f, size.height * 0.28f)
    }
    drawPath(p, color, style = Stroke(width = size.minDimension * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round))
  }
}

/** Small status glyphs drawn in code (the approved icon set has no tick / warning / offline). */
@Composable
internal fun SyncGlyph(kind: HouseholdListSyncStatus.Kind, color: Color, modifier: Modifier = Modifier.size(24.dp)) {
  when (kind) {
    HouseholdListSyncStatus.Kind.OK, HouseholdListSyncStatus.Kind.PARTIAL -> TickGlyph(modifier, color)
    HouseholdListSyncStatus.Kind.OFFLINE, HouseholdListSyncStatus.Kind.FAILING -> Canvas(modifier) {
      drawCircle(color, radius = size.minDimension * 0.42f, style = Stroke(size.minDimension * 0.1f))
      drawLine(color, Offset(size.width * 0.5f, size.height * 0.28f), Offset(size.width * 0.5f, size.height * 0.56f),
          strokeWidth = size.minDimension * 0.11f, cap = StrokeCap.Round)
      drawCircle(color, radius = size.minDimension * 0.06f, center = Offset(size.width * 0.5f, size.height * 0.72f))
    }
    else -> Canvas(modifier) {
      drawArc(color, 30f, 280f, false, style = Stroke(size.minDimension * 0.1f, cap = StrokeCap.Round))
    }
  }
}

/** The widget's last line: "Up to date" / "Syncing…" / "Not synced…" / "Couldn’t sync…". */
@Composable
internal fun HouseholdSyncLine(status: HouseholdListSyncStatus.Status) {
  val palette = LocalApprovedPalette.current
  val warn = status.warning
  Row(Modifier.fillMaxWidth().then(if (warn) Modifier.clip(RoundedCornerShape(16.dp)).background(palette.surface2)
      .padding(horizontal = 16.dp, vertical = 12.dp) else Modifier),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    SyncGlyph(status.kind, if (warn) HouseholdListColors.Amber else palette.secondary)
    Text(status.short, color = if (warn) palette.text else palette.secondary, fontSize = 20.sp, lineHeight = 26.sp)
  }
}

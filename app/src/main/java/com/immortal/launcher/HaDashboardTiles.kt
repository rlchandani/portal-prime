/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.immortal.launcher.HaEntityLook.Tone

/** The outlined tile surface: amber when lit, dimmed when stale, red-ringed when its call failed. */
@Composable
internal fun Modifier.haTile(tone: Tone, stale: Boolean, failed: Boolean): Modifier {
  val c = LocalHaColors.current
  val ring = if (failed) drawBehind {
    val r = FAILED_RING.dp.toPx()
    drawRoundRect(c.red.copy(alpha = FAILED_ALPHA), Offset(-r, -r), Size(size.width + 2 * r, size.height + 2 * r),
        CornerRadius((TILE_RADIUS + FAILED_RING).dp.toPx()))
  } else this
  val fill = if (tone == Tone.LIT) c.amberBg else c.surface
  val line = when { failed -> c.red; tone == Tone.LIT -> c.amberLine; else -> c.outline }
  return ring.graphicsLayer { alpha = if (stale) STALE_ALPHA else 1f }.haCard(c, HaShape.Tile, fill, line)
}

/** The icon well: amber with a white glyph when lit, red when it needs attention. */
@Composable
internal fun HaWell(icon: String, tone: Tone) {
  val c = LocalHaColors.current
  val (fill, ink) = when (tone) {
    Tone.LIT -> c.amber to Color.White
    Tone.ALERT -> c.redBg to c.red
    Tone.PLAIN -> c.surface2 to c.text
  }
  Box(Modifier.size(LocalHaDims.current.well.dp).clip(HaShape.Well).background(fill), contentAlignment = Alignment.Center) {
    HaIcon(icon, ink, 30.dp)
  }
}

@Composable
internal fun HaNameStatus(name: String, status: String, tone: Tone, modifier: Modifier = Modifier) {
  val d = LocalHaDims.current
  val c = LocalHaColors.current
  val (ink, weight) = when (tone) {
    Tone.LIT -> c.amber to FontWeight.SemiBold
    Tone.ALERT -> c.red to FontWeight.SemiBold
    Tone.PLAIN -> c.sec to FontWeight.Normal
  }
  Column(modifier) {
    HaText(name, d.name, c.text, weight = FontWeight.SemiBold)
    HaText(status, d.status, ink, Modifier.padding(top = 2.dp), weight)
  }
}

/** Light, switch, fan, lock, cover: the whole tile toggles; the CC 88×50 switch shows the state. */
@Composable
internal fun HaToggleTile(d: HaDashboardApi.Detail, stale: Boolean, failed: Boolean, onTap: () -> Unit, modifier: Modifier) {
  val dims = LocalHaDims.current
  val tone = HaEntityLook.tone(d)
  val on = HaEntityLook.isActive(d)
  Column(modifier.haTile(tone, stale, failed)
      .toggleable(on, role = Role.Switch) { onTap() }
      .semantics { contentDescription = d.friendlyName; stateDescription = HaEntityLook.status(d) }
      .padding(dims.tilePad.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
      HaWell(HaEntityLook.icon(d), tone)
      Spacer(Modifier.weight(1f))
      HaSwitch(on)
    }
    Spacer(Modifier.weight(1f))
    HaNameStatus(d.friendlyName, HaEntityLook.status(d), tone)
  }
}

@Composable
private fun HaSwitch(on: Boolean) {
  val c = LocalHaColors.current
  val x by animateDpAsState(if (on) SWITCH_ON_X.dp else SWITCH_INSET.dp,
      spring(dampingRatio = Spring.DampingRatioLowBouncy), label = "switch")
  Box(Modifier.size(width = 88.dp, height = 50.dp).clip(HaShape.Pill).background(if (on) c.amber else c.switchOff)) {
    Box(Modifier.offset(x = x, y = SWITCH_INSET.dp).size(40.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape))
  }
}

/** A dimmable light: the left 38% toggles, the full-height track on the right sets brightness. */
@Composable
internal fun HaDimmerTile(d: HaDashboardApi.Detail, stale: Boolean, failed: Boolean, onTap: () -> Unit,
    onDim: (Int) -> Unit, modifier: Modifier) {
  val dims = LocalHaDims.current
  val tone = HaEntityLook.tone(d)
  val on = HaEntityLook.isActive(d)
  Row(modifier.haTile(tone, stale, failed).padding(dims.tilePad.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
    Column(Modifier.fillMaxWidth(DIMMER_LEFT).fillMaxHeight().toggleable(on, role = Role.Switch) { onTap() }
        .semantics { contentDescription = d.friendlyName; stateDescription = HaEntityLook.status(d) }) {
      HaWell(HaEntityLook.icon(d), tone)
      Spacer(Modifier.weight(1f))
      HaNameStatus(d.friendlyName, HaEntityLook.status(d), tone)
    }
    HaBrightnessTrack(name = d.friendlyName, pct = if (on) d.brightnessPct ?: 0 else 0, lit = tone == Tone.LIT,
        onDim = onDim, modifier = Modifier.weight(1f).fillMaxHeight())
  }
}

@Composable
private fun HaBrightnessTrack(name: String, pct: Int, lit: Boolean, onDim: (Int) -> Unit, modifier: Modifier) {
  val c = LocalHaColors.current
  val dims = LocalHaDims.current
  var dragging by remember { mutableStateOf<Float?>(null) }
  val commit by rememberUpdatedState(onDim)
  val shown = dragging ?: (pct / PERCENT)
  val shape = RoundedCornerShape(20.dp)
  val track = if (lit) c.amber.mix(TRACK_LIT, c.surface) else c.surface2
  val fill = if (lit) c.amber else c.amber.mix(FILL_OFF, c.surface2)
  BoxWithConstraints(modifier.clip(shape).background(track)
      .semantics {
        contentDescription = "$name brightness"
        progressBarRangeInfo = ProgressBarRangeInfo(shown * PERCENT, 0f..PERCENT)
        setProgress { v -> commit(v.roundToInt().coerceIn(0, PERCENT.toInt())); true }
      }
      .pointerInput(Unit) { detectTapGestures { p -> commit(toPct(p.x / size.width)) } }
      .pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { p -> dragging = (p.x / size.width).coerceIn(0f, 1f) },
            onDragEnd = { dragging?.let { commit(toPct(it)) }; dragging = null },
            onDragCancel = { dragging = null },
            onHorizontalDrag = { ch, _ -> dragging = (ch.position.x / size.width).coerceIn(0f, 1f) })
      }) {
    Box(Modifier.fillMaxHeight().fillMaxWidth(shown).background(fill, RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)))
    if (shown > KNOB_MIN) Box(Modifier.offset(x = maxWidth * shown - 12.dp).padding(vertical = 12.dp).width(6.dp)
        .fillMaxHeight().background(Color.White, RoundedCornerShape(3.dp)))
    HaText("${(shown * PERCENT).roundToInt()}%", dims.status, c.text, Modifier.align(Alignment.CenterEnd).padding(end = 20.dp),
        FontWeight.Bold)
  }
}

private fun toPct(fraction: Float) = (fraction.coerceIn(0f, 1f) * PERCENT).roundToInt()

/** Temperature / humidity / air (value + 24 h sparkline) or a door/window contact. */
@Composable
internal fun HaSensorTile(d: HaDashboardApi.Detail, history: List<Float>, stale: Boolean, modifier: Modifier) {
  val dims = LocalHaDims.current
  val c = LocalHaColors.current
  val tone = HaEntityLook.tone(d)
  if (d.domain == "binary_sensor") {
    Column(modifier.haTile(tone, stale, false).semantics(mergeDescendants = true) {}.padding(dims.tilePad.dp)) {
      HaWell(HaEntityLook.icon(d), tone)
      Spacer(Modifier.weight(1f))
      HaNameStatus(d.friendlyName, HaEntityLook.status(d), tone)
    }
    return
  }
  Box(modifier.haTile(tone, stale, false).semantics(mergeDescendants = true) {}.padding(dims.tilePad.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      HaWell(HaEntityLook.icon(d), tone)
      HaNameStatus(d.friendlyName, "24 h", Tone.PLAIN, Modifier.padding(top = 4.dp))
    }
    Row(Modifier.align(Alignment.BottomStart), verticalAlignment = Alignment.Bottom) {
      HaText(d.state, dims.big, c.text, serif = true, lineHeight = 1f)
      if (d.unit.isNotBlank()) HaText(d.unit, dims.status, c.sec, Modifier.padding(start = 6.dp), FontWeight.Medium)
    }
    if (history.size >= 2) HaSparkline(history, Modifier.align(Alignment.BottomEnd).fillMaxWidth(SPARK_W).fillMaxHeight(SPARK_H))
  }
}

@Composable
private fun HaSparkline(values: List<Float>, modifier: Modifier) {
  val c = LocalHaColors.current
  Canvas(modifier) {
    val pts = HaSpark.points(values, size.width, size.height)
    val line = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.first, p.second) else lineTo(p.first, p.second) } }
    val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
    drawPath(area, c.accent.copy(alpha = SPARK_FILL))
    drawPath(line, c.accent, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
  }
}

private const val PERCENT = 100f
private const val DIMMER_LEFT = 0.38f
private const val TRACK_LIT = 0.16f
private const val FILL_OFF = 0.38f
private const val KNOB_MIN = 0.02f
private const val SPARK_W = 0.42f
private const val SPARK_H = 0.44f
private const val SPARK_FILL = 0.14f
private const val STALE_ALPHA = 0.55f
private const val FAILED_ALPHA = 0.22f
private const val FAILED_RING = 4
private const val TILE_RADIUS = 26
private const val SWITCH_INSET = 5
private const val SWITCH_ON_X = 43

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Blinds page (`ctl-blinds`): a window whose slats follow the position, a slider and presets.
 * The drag is hoisted state read only by the headline text and the slat drawing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CcCoverControl(ui: CcUi, e: CcEntity, gate: CcGate) {
  val drag = remember(e.id) { mutableStateOf<Int?>(null) }
  val settable = coverSettable(e)
  Row(Modifier.fillMaxWidth().height(540.dp), horizontalArrangement = Arrangement.spacedBy(34.dp)) {
    BlindWindow(drag, e.position ?: 0)
    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
      CoverHeadline(drag, CcSheetLogic.coverText(e))
      if (settable) CcCtlHSlider(e.position ?: 0, { "$it%" }, { p -> ui.act(e, CcCalls.coverPosition(e, p)) }, enabled = gate.enabled,
          description = "${e.name} position", drag = drag, idleLabel = "Drag to set")
      FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val p = e.position
        CcCtlChip("Open", { ui.act(e, CcCalls.coverOpen(e)) }, on = p == FULL, enabled = gate.enabled)
        if (settable) CcCtlChip("Half", { ui.act(e, CcCalls.coverPosition(e, HALF)) }, on = p == HALF, enabled = gate.enabled)
        CcCtlChip("Closed", { ui.act(e, CcCalls.coverClose(e)) }, on = p == 0, enabled = gate.enabled)
        if (e.state == "opening" || e.state == "closing") CcCtlChip("Stop", { ui.act(e, CcCalls.coverStop(e)) }, enabled = gate.enabled)
      }
      Spacer(Modifier.weight(1f))
      if (!settable) CcCtlNote("These blinds only open and close — Home Assistant doesn't report a position.")
    }
  }
}

@Composable
private fun CoverHeadline(drag: State<Int?>, idle: String) {
  val d = drag.value
  Text(if (d != null) "$d% open" else idle, color = LocalCc.current.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
}

/** HA cover feature SET_POSITION (4), or a reported position. */
internal fun coverSettable(e: CcEntity): Boolean = e.supportedFeatures and SET_POSITION != 0 || e.attrs.containsKey("current_position")

/** `.blindWin`: sky behind a window frame, slats drawn down to (100 − position)%, eased in draw only. */
@Composable
private fun BlindWindow(drag: State<Int?>, position: Int) {
  val c = LocalCc.current
  val pos = rememberUpdatedState(position)
  val closed = remember { Animatable(1f - position / FULL.toFloat()) }
  LaunchedEffect(Unit) {
    snapshotFlow { 1f - (drag.value ?: pos.value) / FULL.toFloat() }.collectLatest { closed.animateTo(it, tween(SLAT_MS, easing = CcEase)) }
  }
  Box(Modifier.width(320.dp).fillMaxHeight().clip(RoundedCornerShape(20.dp)).border(10.dp, c.surface2, RoundedCornerShape(20.dp))
      .background(SKY)) {
    Spacer(Modifier.fillMaxSize().drawBehind { drawSlats(closed.value) })
  }
}

private fun DrawScope.drawSlats(closed: Float) {
  val slatPx = SLAT.dp.toPx(); val gapPx = SLAT_GAP.dp.toPx()
  val h = size.height * closed
  var y = 0f
  while (y < h) {
    val slat = minOf(slatPx, h - y)
    drawRect(SLAT_LIGHT, Offset(0f, y), Size(size.width, slat))
    if (slat >= slatPx) drawRect(SLAT_DARK, Offset(0f, y + slatPx - gapPx), Size(size.width, gapPx))
    y += slatPx
  }
  if (h > 0f) drawRect(SHADE, Offset(0f, h), Size(size.width, SHADOW.dp.toPx()))
}

private const val FULL = 100
private const val HALF = 50
private const val SET_POSITION = 4
private const val SLAT_MS = 500
private const val SLAT = 26f
private const val SLAT_GAP = 4f
private const val SHADOW = 6f
private val SKY_A = Color(0xFF8EC5FF)
private val SKY_B = Color(0xFFCFE6FF)
private val SKY_C = Color(0xFFE9F3D8)
private val SLAT_LIGHT = Color(0xFFE7DFD2)
private val SLAT_DARK = Color(0xFFCFC5B5)
private val SHADE = Color(0x33000000)
private val SKY = Brush.verticalGradient(listOf(SKY_A, SKY_B, SKY_C))

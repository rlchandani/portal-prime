/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.roundToInt

/** Flow colours from the mock's SVG (solar, grid, battery, charger). */
internal object CcEnergyColors {
  val Solar = Color(0xFFE0A800)
  val Grid = Color(0xFF6F86FF)
  val Battery = Color(0xFF3FAA7C)
  val Charger = Color(0xFFE8774A)
}

private fun CcEnergyNode.color(text: Color): Color = when (this) {
  CcEnergyNode.SOLAR -> CcEnergyColors.Solar
  CcEnergyNode.GRID -> CcEnergyColors.Grid
  CcEnergyNode.BATTERY -> CcEnergyColors.Battery
  CcEnergyNode.CHARGER -> CcEnergyColors.Charger
  CcEnergyNode.HOME -> text
}

private fun CcEnergyNode.icon(): String = when (this) {
  CcEnergyNode.SOLAR -> "sun"; CcEnergyNode.GRID -> "grid"; CcEnergyNode.BATTERY -> "battery"
  CcEnergyNode.CHARGER -> "charger"; CcEnergyNode.HOME -> "home"
}

private fun watts(n: CcEnergyNow, node: CcEnergyNode): Double? = when (node) {
  CcEnergyNode.SOLAR -> n.solarW; CcEnergyNode.GRID -> n.gridW; CcEnergyNode.BATTERY -> n.batteryW
  CcEnergyNode.CHARGER -> n.chargerW; CcEnergyNode.HOME -> n.homeW
}

/** The live flow (`energyFlowSVG`): nodes the house has, dotted lines moving the way power goes. */
@Composable
internal fun CcEnergyFlowDiagram(now: CcEnergyNow, modifier: Modifier = Modifier) {
  val c = LocalCc.current
  val motion = LocalCcMotion.current
  val nodes = remember(now) { CcEnergyFlow.nodes(now) }
  val lines = remember(now) { CcEnergyFlow.lines(now).take(MAX_LINES) }
  var clock by remember { mutableLongStateOf(0L) }
  if (motion && lines.any { it.active }) LaunchedEffect(Unit) { while (true) withFrameMillis { clock = it } }
  BoxWithConstraints(modifier) {
    val unit = min(maxWidth.value / CcEnergyFlow.VIEW_W, maxHeight.value / CcEnergyFlow.VIEW_H)
    val ox = (maxWidth.value - CcEnergyFlow.VIEW_W * unit) / 2
    val oy = (maxHeight.value - CcEnergyFlow.VIEW_H * unit) / 2
    Canvas(Modifier.fillMaxSize()) {
      val k = min(size.width / CcEnergyFlow.VIEW_W, size.height / CcEnergyFlow.VIEW_H)
      val origin = Offset((size.width - CcEnergyFlow.VIEW_W * k) / 2, (size.height - CcEnergyFlow.VIEW_H * k) / 2)
      fun at(n: CcEnergyNode) = origin + Offset(n.x * k, n.y * k)
      lines.forEach { l -> drawFlowLine(at(l.from), at(l.to), l, k, l.node.color(c.text), c.div, if (motion) clock else 0L) }
      nodes.forEach { n ->
        drawCircle(c.surface, n.radius * k, at(n))
        drawCircle(n.color(c.text), n.radius * k, at(n), style = Stroke(NODE_STROKE * k))
      }
    }
    nodes.forEach { n -> NodeText(n, now, ox, oy, unit, n.color(c.text)) }
  }
}

private fun DrawScope.drawFlowLine(a: Offset, b: Offset, l: CcEnergyLine, k: Float, color: Color, idle: Color, clock: Long) {
  if (!l.active) {
    drawLine(idle, a, b, IDLE_WIDTH * k, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(IDLE_ON * k, IDLE_OFF * k)))
    return
  }
  val w = l.width * k
  drawLine(color.copy(alpha = UNDERLAY_ALPHA), a, b, w, StrokeCap.Round)
  val period = (CcEnergyFlow.DASH_ON + CcEnergyFlow.DASH_OFF) * k
  val progress = if (clock == 0L) 0f else (clock % l.periodMs).toFloat() / l.periodMs
  drawLine(color, a, b, w, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(CcEnergyFlow.DASH_ON * k, CcEnergyFlow.DASH_OFF * k), (1f - progress) * period))
}

/** Icon + value inside the circle, label under it (Solar's to its right, as in the mock). */
@Composable
private fun NodeText(n: CcEnergyNode, now: CcEnergyNow, ox: Float, oy: Float, unit: Float, color: Color) {
  val c = LocalCc.current
  val big = n == CcEnergyNode.HOME
  val cx = ox + n.x * unit
  val cy = oy + n.y * unit
  Box(Modifier.centeredAt(cx.dp, cy.dp)) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((4 * unit).dp)) {
      CcIcon(n.icon(), color, ((if (big) HOME_ICON else NODE_ICON) * unit).dp)
      Text(CcEnergyFlow.value(n, watts(now, n)), color = c.text, fontWeight = FontWeight.SemiBold,
          fontSize = ((if (big) HOME_VALUE else NODE_VALUE) * unit).sp, maxLines = 1)
    }
  }
  val label = CcEnergyFlow.label(n, now)
  val labelSize = (LABEL * unit).sp
  if (n == CcEnergyNode.SOLAR) Box(Modifier.startAt((cx + (n.radius + LABEL_GAP) * unit).dp, cy.dp)) {
    Text(label, color = c.sec, fontSize = labelSize, maxLines = 1)
  } else Box(Modifier.centeredAt(cx.dp, (cy + (n.radius + LABEL_BELOW) * unit).dp)) {
    Text(label, color = c.sec, fontSize = labelSize, maxLines = 1)
  }
}

/** Places the content centred on ([x], [y]) of the parent, without taking space. */
private fun Modifier.centeredAt(x: Dp, y: Dp): Modifier = layout { m, constraints ->
  val p = m.measure(constraints.copy(minWidth = 0, minHeight = 0))
  layout(0, 0) { p.place((x.toPx() - p.width / 2f).roundToInt(), (y.toPx() - p.height / 2f).roundToInt()) }
}

/** Places the content starting at [x], vertically centred on [y]. */
private fun Modifier.startAt(x: Dp, y: Dp): Modifier = layout { m, constraints ->
  val p = m.measure(constraints.copy(minWidth = 0, minHeight = 0))
  layout(0, 0) { p.place(x.toPx().roundToInt(), (y.toPx() - p.height / 2f).roundToInt()) }
}

/** ≤ 8 moving dash lines (Portal GPU budget); the flow has at most four. */
private const val MAX_LINES = 8
private const val NODE_STROKE = 5f
private const val IDLE_WIDTH = 4f
private const val IDLE_ON = 4f
private const val IDLE_OFF = 10f
private const val UNDERLAY_ALPHA = 0.2f
private const val NODE_ICON = 38f
private const val HOME_ICON = 44f
private const val NODE_VALUE = 26f
private const val HOME_VALUE = 34f
private const val LABEL = 22f
private const val LABEL_GAP = 18f
private const val LABEL_BELOW = 26f

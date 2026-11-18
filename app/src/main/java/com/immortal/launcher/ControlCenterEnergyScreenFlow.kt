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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
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

/**
 * The live flow (`energyFlowSVG`): nodes the house has, dotted lines moving the way power goes.
 * Laid out once at the mock's 800×700 design size and scaled to fit in its own layer, so there is
 * no measure-time subcomposition; the dash clock is read only in the Canvas draw lambda.
 */
@Composable
internal fun CcEnergyFlowDiagram(now: CcEnergyNow, modifier: Modifier = Modifier) {
  val c = LocalCc.current
  val motion = LocalCcMotion.current
  val shape = remember(now) { CcFlowShape(CcEnergyFlow.nodes(now), CcEnergyFlow.lines(now).take(MAX_LINES)) }
  val nodes = shape.nodes
  val lines = shape.lines
  val clock = remember { mutableLongStateOf(0L) }
  if (motion && lines.any { it.active }) LaunchedEffect(clock) { while (true) withFrameMillis { clock.longValue = it } }
  Box(modifier.fitDesign(CcEnergyFlow.VIEW_W.dp, CcEnergyFlow.VIEW_H.dp)) {
    FlowCanvas(shape, clock, motion, c)
    nodes.forEach { n -> NodeText(n, now, n.color(c.text)) }
  }
}

@Composable
private fun FlowCanvas(shape: CcFlowShape, clock: MutableLongState, motion: Boolean, c: CcColors) {
  val nodes = shape.nodes
  val lines = shape.lines
  Canvas(Modifier.fillMaxSize()) {
    val k = size.width / CcEnergyFlow.VIEW_W
    val t = if (motion) clock.longValue else 0L
    lines.forEach { l -> drawFlowLine(l, k, l.node.color(c.text), c.div, t) }
    nodes.forEach { n ->
      val center = Offset(n.x * k, n.y * k)
      drawCircle(c.surface, n.radius * k, center)
      drawCircle(n.color(c.text), n.radius * k, center, style = Stroke(NODE_STROKE * k))
    }
  }
}

/** What the canvas draws: the nodes and their lines. */
@Immutable
internal data class CcFlowShape(val nodes: List<CcEnergyNode>, val lines: List<CcEnergyLine>)

/** Dashes drawn as short round-capped segments: no PathEffect (or any allocation) per frame. */
private fun DrawScope.drawFlowLine(l: CcEnergyLine, k: Float, color: Color, idle: Color, clock: Long) {
  val a = Offset(l.from.x * k, l.from.y * k)
  val b = Offset(l.to.x * k, l.to.y * k)
  if (!l.active) { dashes(a, b, IDLE_ON * k, IDLE_OFF * k, 0f, IDLE_WIDTH * k, idle); return }
  val w = l.width * k
  drawLine(color.copy(alpha = UNDERLAY_ALPHA), a, b, w, StrokeCap.Round)
  val period = (CcEnergyFlow.DASH_ON + CcEnergyFlow.DASH_OFF) * k
  val progress = if (clock == 0L) 0f else (clock % l.periodMs).toFloat() / l.periodMs
  dashes(a, b, CcEnergyFlow.DASH_ON * k, CcEnergyFlow.DASH_OFF * k, progress * period, w, color)
}

/** Dashes [on]/[off] along a→b, shifted [shift] px toward b (the direction of flow). */
private fun DrawScope.dashes(a: Offset, b: Offset, on: Float, off: Float, shift: Float, width: Float, color: Color) {
  val dx = b.x - a.x
  val dy = b.y - a.y
  val len = kotlin.math.sqrt(dx * dx + dy * dy)
  if (len <= 0f) return
  val ux = dx / len
  val uy = dy / len
  val period = on + off
  var s = shift % period - period
  while (s < len) {
    val from = s.coerceAtLeast(0f)
    val to = (s + on).coerceAtMost(len)
    if (to > from) drawLine(color, Offset(a.x + ux * from, a.y + uy * from), Offset(a.x + ux * to, a.y + uy * to), width, StrokeCap.Round)
    s += period
  }
}

/** Icon + value inside the circle, label under it (Solar's to its right, as in the mock); design units = dp. */
@Composable
private fun NodeText(n: CcEnergyNode, now: CcEnergyNow, color: Color) {
  val c = LocalCc.current
  val big = n == CcEnergyNode.HOME
  Box(Modifier.centeredAt(n.x.dp, n.y.dp)) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
      CcIcon(n.icon(), color, (if (big) HOME_ICON else NODE_ICON).dp)
      Text(CcEnergyFlow.value(n, watts(now, n)), color = c.text, fontWeight = FontWeight.SemiBold,
          fontSize = (if (big) HOME_VALUE else NODE_VALUE).sp, maxLines = 1)
    }
  }
  val label = CcEnergyFlow.label(n, now)
  if (n == CcEnergyNode.SOLAR) Box(Modifier.startAt((n.x + n.radius + LABEL_GAP).dp, n.y.dp)) {
    Text(label, color = c.sec, fontSize = LABEL.sp, maxLines = 1)
  } else Box(Modifier.centeredAt(n.x.dp, (n.y + n.radius + LABEL_BELOW).dp)) {
    Text(label, color = c.sec, fontSize = LABEL.sp, maxLines = 1)
  }
}

/** Measures the content at [w]×[h] and scales it uniformly (in a layer) to fit, centred. */
private fun Modifier.fitDesign(w: Dp, h: Dp): Modifier = layout { m, constraints ->
  val p = m.measure(Constraints.fixed(w.roundToPx(), h.roundToPx()))
  val outW = if (constraints.hasBoundedWidth) constraints.maxWidth else p.width
  val outH = if (constraints.hasBoundedHeight) constraints.maxHeight else p.height
  val k = min(outW / p.width.toFloat(), outH / p.height.toFloat())
  layout(outW, outH) { p.placeWithLayer((outW - p.width) / 2, (outH - p.height) / 2) { scaleX = k; scaleY = k } }
}

/** Places the content centred on ([x], [y]) of the parent, without taking space. */
private fun Modifier.centeredAt(x: Dp, y: Dp): Modifier = layout { m, constraints ->
  val p = m.measure(constraints.copy(minWidth = 0, minHeight = 0))
  layout(0, 0) { p.place((x.toPx() - p.width / 2f).roundToInt(), (y.toPx() - p.height / 2f).roundToInt()) }
}

/** Places the content starting at [x], vertically centred on [y]. */
private fun Modifier.startAt(x: Dp, y: Dp): Modifier = layout { m, constraints ->
  val p = m.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = Constraints.Infinity))
  layout(0, 0) { p.place(x.toPx().roundToInt(), (y.toPx() - p.height / 2f).roundToInt()) }
}

/** ≤ 8 moving dash lines (Portal GPU budget); the flow has at most four. */
private const val MAX_LINES = 8
private const val NODE_STROKE = 5f
private const val IDLE_WIDTH = 4f
private const val IDLE_ON = 4f
private const val IDLE_OFF = 10f
private const val UNDERLAY_ALPHA = 0.2f
private const val NODE_ICON = 38
private const val HOME_ICON = 44
private const val NODE_VALUE = 26
private const val HOME_VALUE = 34
private const val LABEL = 22
private const val LABEL_GAP = 18f
private const val LABEL_BELOW = 26f

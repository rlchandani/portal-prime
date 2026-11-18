/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp

/** Chart colours from the mock (`#f4c542` fill, `#e0a800` line, ember "now"). */
private val SolarFill = Color(0xFFF4C542)
private val SolarLine = Color(0xFFE0A800)

/** Plot frame in dp, as the mock's 900×300 SVG lays it out (pad 34, 30 below and above). */
private const val PAD_LEFT = 34f
private const val PAD_BOTTOM = 30f
private const val PAD_TOP = 30f
private const val LABEL_DROP = 6f

/**
 * "Today · use vs solar" bars + solar area, or "This week" bars with the solar share. Paths and
 * text layouts are built once per model and size (drawWithCache); a redraw only replays them.
 */
@Composable
internal fun CcEnergyChart(model: CcChartModel, modifier: Modifier = Modifier) {
  val c = LocalCc.current
  val measurer = rememberTextMeasurer()
  val styles = remember(c) {
    ChartStyles(TextStyle(color = c.sec, fontSize = 17.sp), TextStyle(color = c.sec, fontSize = 16.sp), TextStyle(color = c.text, fontSize = 17.sp))
  }
  Spacer(modifier.drawWithCache {
    val f = Frame(PAD_LEFT * density, PAD_TOP * density, size.width, size.height - PAD_BOTTOM * density)
    val texts = if (model.week) weekTexts(model, f, measurer, styles) else todayTexts(model, f, measurer, styles)
    val solar = if (model.week) null else solarPaths(model, f)
    val dash = nowDash(density)
    onDrawBehind {
      if (model.week) drawWeekBars(model, f, c) else drawTodayBars(model, f, c, solar, dash)
      texts.forEach { (layout, at) -> drawText(layout, topLeft = at) }
    }
  })
}

private class ChartStyles(val axis: TextStyle, val tick: TextStyle, val value: TextStyle)

private data class Frame(val left: Float, val top: Float, val right: Float, val base: Float) {
  val height: Float get() = base - top
}

private fun bucket(m: CcChartModel, f: Frame): Float = (f.right - f.left) / m.use.size.coerceAtLeast(1)

private class SolarPaths(val area: Path, val line: Path)

private fun CacheDrawScope.solarPaths(m: CcChartModel, f: Frame): SolarPaths? {
  if (m.solar.isEmpty() || m.solar.none { it > 0 }) return null
  val bw = bucket(m, f)
  val n = m.use.size.coerceAtLeast(1)
  val pts = m.solar.mapIndexed { i, v -> Offset(f.left + i * bw + bw / 2, f.base - m.y(v) * f.height) }
  val area = Path().apply { moveTo(f.left, f.base); pts.forEach { lineTo(it.x, it.y) }; lineTo(f.left + (n - 0.5f) * bw, f.base); close() }
  val line = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
  return SolarPaths(area, line)
}

private fun DrawScope.drawTodayBars(m: CcChartModel, f: Frame, c: CcColors, solar: SolarPaths?, dash: PathEffect) {
  val bw = bucket(m, f)
  val inset = 3 * density
  m.use.forEachIndexed { i, u ->
    val h = m.y(u) * f.height
    val past = i <= m.lastPast
    drawRoundRect(if (past) c.surface2 else c.div.copy(alpha = FUTURE_ALPHA), Offset(f.left + i * bw + inset, f.base - h),
        Size((bw - 2 * inset).coerceAtLeast(1f), h), CornerRadius(5 * density))
  }
  solar?.let { drawPath(it.area, SolarFill.copy(alpha = AREA_ALPHA)); drawPath(it.line, SolarLine, style = Stroke(4 * density)) }
  m.now?.let { pos ->
    val x = f.left + pos * bw
    drawLine(CcStyle.Ember, Offset(x, NOW_TOP * density), Offset(x, f.base), 3 * density, pathEffect = dash)
  }
}

private fun CacheDrawScope.todayTexts(m: CcChartModel, f: Frame, tm: TextMeasurer, st: ChartStyles): List<Pair<TextLayoutResult, Offset>> {
  val bw = bucket(m, f)
  return m.labels.map { (i, label) -> place(tm, label, st.axis, f.left + i * bw + bw / 2, size.height - LABEL_DROP * density, Align.CENTER) } +
      m.ticks.map { v -> place(tm, fmtTick(v), st.tick, 0f, f.base - m.y(v) * f.height + LABEL_DROP * density, Align.START) } +
      listOfNotNull(m.now?.let { pos -> place(tm, "now", st.value, f.left + pos * bw - 8 * density, NOW_LABEL * density, Align.END) })
}

private fun DrawScope.drawWeekBars(m: CcChartModel, f: Frame, c: CcColors) {
  val bw = bucket(m, f)
  val inset = (WEEK_INSET * density).coerceAtMost(bw / 4)
  val r = CornerRadius(8 * density)
  m.use.forEachIndexed { i, u ->
    val x = f.left + i * bw + inset
    val w = (bw - 2 * inset).coerceAtLeast(1f)
    val h1 = m.y(u) * f.height
    val h2 = m.y(m.solar.getOrElse(i) { 0.0 }) * f.height
    drawRoundRect(c.surface2, Offset(x, f.base - h1), Size(w, h1), r)
    if (h2 > 0) drawRoundRect(SolarFill.copy(alpha = WEEK_SOLAR_ALPHA), Offset(x, f.base - h2), Size(w, h2), r)
  }
}

private fun CacheDrawScope.weekTexts(m: CcChartModel, f: Frame, tm: TextMeasurer, st: ChartStyles): List<Pair<TextLayoutResult, Offset>> {
  val bw = bucket(m, f)
  return m.use.mapIndexed { i, u -> place(tm, "%.0f".format(u), st.value, f.left + i * bw + bw / 2, f.base - m.y(u) * f.height - 8 * density, Align.CENTER) } +
      m.labels.map { (i, label) -> place(tm, label, st.axis, f.left + i * bw + bw / 2, size.height - LABEL_DROP * density, Align.CENTER) }
}

private enum class Align { START, CENTER, END }

/** Lays out [s] with its baseline at [baseline] (SVG `text` semantics). */
private fun place(tm: TextMeasurer, s: String, style: TextStyle, x: Float, baseline: Float, align: Align): Pair<TextLayoutResult, Offset> {
  val layout = tm.measure(s, style)
  val left = when (align) { Align.START -> x; Align.CENTER -> x - layout.size.width / 2f; Align.END -> x - layout.size.width }
  return layout to Offset(left, baseline - layout.firstBaseline)
}

private fun fmtTick(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)

private const val FUTURE_ALPHA = 0.5f
private const val AREA_ALPHA = 0.35f
private const val WEEK_SOLAR_ALPHA = 0.85f
private const val WEEK_INSET = 14f
private const val NOW_TOP = 20f
private const val NOW_LABEL = 34f
private const val NOW_DASH_DP = 6f

/** The dashed "now" line's pattern (built once per cache). */
private fun nowDash(density: Float): PathEffect = PathEffect.dashPathEffect(floatArrayOf(NOW_DASH_DP * density, NOW_DASH_DP * density))

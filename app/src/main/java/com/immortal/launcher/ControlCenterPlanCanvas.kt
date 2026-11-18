/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A scene walk as the canvas draws it: rooms in order and the live progress (read in draw only). */
@Immutable
internal class CcWalkView(val rooms: List<String>, val progress: State<Float>)

/** What a tap on the plan hit. */
internal sealed interface CcPlanHit {
  data class Room(val roomId: String) : CcPlanHit
  data class Device(val roomId: String, val entityId: String, val kind: CcMapBadgeKind?) : CcPlanHit
}

/**
 * The floor plan: ONE Canvas. Geometry, text layouts and paint are cached per floor/size/state;
 * a frame only reads the animation clock (pulses, dashes, highlight) and the walk progress.
 * Loops run only while [LocalCcMotion] is true; otherwise a still frame is drawn.
 */
@Composable
internal fun CcPlanCanvas(
    model: CcPlanModel,
    lens: CcLens,
    focus: String?,
    walk: CcWalkView?,
    sources: CcMapSources,
    stale: Boolean,
    fahrenheit: Boolean,
    onTap: (CcPlanHit) -> Unit,
    modifier: Modifier = Modifier,
) {
  val c = LocalCc.current
  val density = LocalDensity.current
  val paint = rememberCcPlanPaint()
  val measurer = rememberTextMeasurer(cacheSize = TEXT_CACHE)
  var size by remember { mutableStateOf(IntSize.Zero) }
  val maxScale = with(density) { MAX_CELL_DP.dp.toPx() }
  val px = remember(model.floor, size, maxScale) {
    CcPlanPx(CcPlanGeo.fit(model.bounds, size.width.toFloat(), size.height.toFloat(), maxScale = maxScale), model)
  }
  val flows = remember(px, model.flows, sources, lens) { if (lens == CcLens.ENERGY) CcFlowPx(px, model.flows, sources) else null }
  val dashes = remember(density) { with(density) { CcMapDashes(DASH_DP.dp.toPx(), GAP_DP.dp.toPx()) } }
  val labels = remember(model.views, lens, c, density) { measureRooms(model, lens, c, measurer) }
  val chipText = remember(model.badges, model.chips, sources, lens, c, density) { measureChips(model, sources, lens, c, measurer) }
  val walkCentres = remember(walk?.rooms, model.floor) { walk?.rooms?.map { id -> model.floor.rooms.firstOrNull { it.id == id }?.rect?.let(CcPlanGeo::center) } }
  val clock = planClock(LocalCcMotion.current && (model.pulses.isNotEmpty() || flows != null || focus != null))
  val ctx = remember(model, px, flows, paint, c, labels, chipText, lens, focus, stale, sources, fahrenheit) {
    CcPlanDraw(model, px, flows, paint, c, labels, chipText, lens, focus, stale, dashes, sources, fahrenheit, density.density)
  }
  val latestTap by rememberUpdatedState(onTap)
  val description = remember(model.views) { "Floor plan: " + model.views.joinToString(", ") { it.room.name } }
  Canvas(modifier.onSizeChanged { size = it }.semantics { contentDescription = description }
      .pointerInput(px, model) { detectTapGestures { pos -> hit(model, px, pos, TAP_DP.dp.toPx())?.let { latestTap(it) } } }) {
    if (px.fit.scale <= 0f || size == IntSize.Zero) return@Canvas
    val t = clock.value
    ctx.drawFloor(this)
    ctx.drawPulses(this, t)
    walk?.let { w -> walkCentres?.let { ctx.drawWalk(this, w.rooms, it, w.progress.value) } }
    ctx.drawFlows(this, t)
    ctx.drawFocus(this, t)
    ctx.drawMarks(this)
    ctx.drawLabels(this)
    ctx.drawEnergyNodes(this)
  }
}

/** One clock for every loop on the plan (a period every loop divides), or a still value. */
@Composable
private fun planClock(running: Boolean): State<Float> {
  if (!running) return remember { mutableFloatStateOf(STILL_T) }
  val tr = rememberInfiniteTransition(label = "plan")
  return tr.animateFloat(0f, 1f, infiniteRepeatable(tween(CLOCK_MS, easing = LinearEasing)), label = "planClock")
}

private fun hit(model: CcPlanModel, px: CcPlanPx, pos: Offset, radiusPx: Float): CcPlanHit? {
  val cx = px.fit.cellX(pos.x)
  val cy = px.fit.cellY(pos.y)
  val radius = radiusPx / px.fit.scale
  val tappable = model.badges.filter { it.kind != CcMapBadgeKind.APPLIANCE_DONE }.map { it.entityId to CcMapPt(it.x, it.y) } +
      model.chips.map { it.entityId to CcMapPt(it.x, it.y) } + model.dots.map { it.entityId to CcMapPt(it.x, it.y) }
  ccNearest(tappable, cx, cy, radius)?.let { id ->
    val room = model.floor.rooms.firstOrNull { r -> r.devices.any { it.entityId == id } }
        ?: model.floor.openings.firstOrNull { it.entityId == id }?.let { o -> model.floor.rooms.firstOrNull { it.id == o.roomId } }
    if (room != null) return CcPlanHit.Device(room.id, id, model.badges.firstOrNull { it.entityId == id }?.kind)
  }
  return CcPlanGeo.roomAt(model.floor, cx, cy)?.let { CcPlanHit.Room(it.id) }
}

/** A room's name + caption, measured once per state change. */
internal class CcRoomText(val name: TextLayoutResult, val caption: TextLayoutResult?)

private fun measureRooms(model: CcPlanModel, lens: CcLens, c: CcColors, m: androidx.compose.ui.text.TextMeasurer): Map<String, CcRoomText> =
    model.views.associate { v ->
      val nameSize = if (v.room.outdoor) OUTDOOR_NAME_SP else NAME_SP
      val name = m.measure(v.room.name, TextStyle(color = c.text, fontSize = nameSize.sp, fontWeight = FontWeight.SemiBold))
      val text = CcOverviewText.caption(v, lens)
      val big = lens == CcLens.CLIMATE
      val caption = text.takeIf { it.isNotBlank() }?.let {
        m.measure(it, TextStyle(color = if (big) c.text else c.sec, fontSize = (if (big) NAME_SP else CAPTION_SP).sp,
            fontWeight = if (big) FontWeight.SemiBold else FontWeight.Medium))
      }
      v.room.id to CcRoomText(name, caption)
    }

/** Chip and node texts, measured once per state change (looked up by id while drawing). */
internal class CcMapChipText(
    val badge: Map<String, TextLayoutResult>,
    val chip: Map<String, TextLayoutResult>,
    val hub: TextLayoutResult?,
    val hubUnit: TextLayoutResult?,
    val solar: TextLayoutResult?,
    val grid: TextLayoutResult?,
    val battery: TextLayoutResult?,
)

private fun measureChips(model: CcPlanModel, sources: CcMapSources, lens: CcLens, c: CcColors, m: androidx.compose.ui.text.TextMeasurer): CcMapChipText {
  val bold = TextStyle(fontSize = CHIP_SP.sp, fontWeight = FontWeight.Bold)
  val badge = model.badges.filter { it.kind == CcMapBadgeKind.CLIMATE }.mapNotNull { b -> b.label?.let { b.entityId to m.measure(it, bold.copy(color = c.text)) } }.toMap()
  val chip = model.chips.associate { ch -> ch.entityId to m.measure(ch.text, bold.copy(color = CcStyle.EmberInk, fontWeight = FontWeight.ExtraBold)) }
  if (lens != CcLens.ENERGY) return CcMapChipText(badge, chip, null, null, null, null, null)
  val node = TextStyle(color = c.text, fontSize = NODE_SP.sp, fontWeight = FontWeight.Bold)
  return CcMapChipText(badge, chip,
      hub = sources.homeW?.let { m.measure(CcEnergy.kw(it), TextStyle(color = c.bg, fontSize = HUB_SP.sp, fontWeight = FontWeight.Bold)) },
      hubUnit = m.measure("kW home", TextStyle(color = c.bg, fontSize = HUB_UNIT_SP.sp, fontWeight = FontWeight.SemiBold)),
      solar = sources.solarW?.let { m.measure("Solar ${CcEnergy.kw(it)} kW", node) },
      grid = sources.gridW?.let { m.measure("Grid ${CcEnergy.kw(kotlin.math.abs(it))} kW ${if (it >= 0) "in" else "out"}", node) },
      battery = sources.batteryW?.let { w -> m.measure("Battery${sources.batteryPct?.let { " $it%" } ?: ""} · ${CcEnergy.kw(kotlin.math.abs(w))} kW", node) })
}

/** Plan sizes from the mock (dp; geometry itself is in cells). */
internal const val MAX_CELL_DP = 34f
private const val TAP_DP = 36f
private const val DASH_DP = 3f
private const val GAP_DP = 22f
private const val NAME_SP = 22f
private const val OUTDOOR_NAME_SP = 18f
private const val CAPTION_SP = 17f
private const val CHIP_SP = 19f
private const val HUB_SP = 22f
private const val NODE_SP = 18f
private const val HUB_UNIT_SP = 15f
private const val TEXT_CACHE = 64
/** Loop period: pulses (÷3 = 2.2 s), dashes (÷6 = 1.1 s) and the highlight (÷4 = 1.65 s) all divide it. */
private const val CLOCK_MS = 6_600
/** Clock value of the still frame (pulses drawn mid-ring so they still read). */
private const val STILL_T = 0.1f

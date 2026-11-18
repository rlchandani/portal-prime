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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** A scene walk as the canvas draws it: rooms in order and the live progress (read in draw only). */
@Immutable
internal class CcWalkView(val rooms: List<String>, val progress: State<Float>)

/** What a tap on the plan hit. */
internal sealed interface CcPlanHit {
  data class Room(val roomId: String) : CcPlanHit
  data class Device(val roomId: String, val entityId: String, val kind: CcMapBadgeKind?) : CcPlanHit
}

/**
 * The floor plan, drawn as separately invalidated layers (each its own RenderNode):
 *  1. base floors + 3. walls — static: redraw only on floor / size / theme change;
 *  2. under (lens tints, light washes, glows), 4. over (openings, focus veil, badges, chips,
 *     markers) and 6. top (names + captions, energy nodes) — redraw only when the derived
 *     [CcPlanModel] / lens / focus change (equal models skip);
 *  5. animation (pulses, energy dashes, focus edge, scene walk) — the only layer read per frame,
 *     and it only exists while [LocalCcMotion] is on and something animates. With motion off the
 *     same marks are drawn once, still, in the over layer.
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
  val base = remember(px, c, density) { CcPlanBase(px, c, density.density) }
  val flows = remember(px, model.flows, sources, lens) { if (lens == CcLens.ENERGY) CcFlowPx(px, model.flows, sources) else null }
  val dashes = remember(density) { with(density) { CcMapDashes(DASH_DP.dp.toPx(), GAP_DP.dp.toPx()) } }
  val labels = remember(model.views, lens, c, density) { measureRooms(model, lens, c, measurer, density.density) }
  val chipText = remember(model.badges, model.chips, sources, lens, c, density) { measureChips(model, sources, lens, c, measurer) }
  val ctx = remember(model, px, flows, paint, c, labels, chipText, lens, focus, stale, sources, fahrenheit) {
    CcPlanDraw(model, px, flows, paint, c, labels, chipText, lens, focus, stale, dashes, sources, fahrenheit, density.density)
  }
  val motion = LocalCcMotion.current
  val loops = model.pulses.isNotEmpty() || flows != null || focus != null
  val latestTap by rememberUpdatedState(onTap)
  val description = remember(model.views) { "Floor plan: " + model.views.joinToString(", ") { it.room.name } }
  val ready = size != IntSize.Zero && px.fit.scale > 0f
  Box(modifier.onSizeChanged { size = it }.semantics { contentDescription = description }
      .pointerInput(px, model) { detectTapGestures { pos -> hit(model, px, pos, TAP_DP.dp.toPx())?.let { latestTap(it) } } }) {
    if (!ready) return@Box
    PlanLayer { base.drawFloors(this) }
    PlanLayer { ctx.drawUnder(this) }
    PlanLayer { base.drawWalls(this) }
    val still = !motion && loops
    PlanLayer {
      ctx.drawOver(this)
      if (still) ctx.drawAnimated(this, STILL_T)
    }
    if (motion && (loops || walk != null)) CcPlanAnimLayer(ctx, walk, model.floor, loops)
    PlanLayer { ctx.drawTop(this) }
  }
}

/** One cached RenderNode; its draw lambda is memoized by what it captures, so it re-records only when those change. */
@Composable
private fun PlanLayer(draw: DrawScope.() -> Unit) {
  Spacer(Modifier.fillMaxSize().graphicsLayer().drawBehind(draw))
}

/**
 * The per-frame layer: one clock (a period every loop divides) read only inside the draw lambda,
 * plus the scene walk's progress. Not composed at all while motion is off.
 */
@Composable
private fun CcPlanAnimLayer(ctx: CcPlanDraw, walk: CcWalkView?, floor: CcFloor, loops: Boolean) {
  val clock: State<Float>? = if (loops) {
    rememberInfiniteTransition(label = "plan").animateFloat(0f, 1f, infiniteRepeatable(tween(CLOCK_MS, easing = LinearEasing)), label = "planClock")
  } else null
  val centres = remember(walk?.rooms, floor) { walk?.rooms?.map { id -> floor.rooms.firstOrNull { it.id == id }?.rect?.let(CcPlanGeo::center) } }
  Spacer(Modifier.fillMaxSize().graphicsLayer().drawBehind {
    clock?.let { ctx.drawAnimated(this, it.value) }
    if (walk != null && centres != null) ctx.drawWalk(this, walk.rooms, centres, walk.progress.value)
  })
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

/** Plan sizes from the mock (dp; geometry itself is in cells). */
internal const val MAX_CELL_DP = 34f
private const val TAP_DP = 36f
private const val DASH_DP = 3f
private const val GAP_DP = 22f
private const val TEXT_CACHE = 64
/** Loop period: pulses (÷3 = 2.2 s), dashes (÷6 = 1.1 s) and the highlight (÷4 = 1.65 s) all divide it. */
private const val CLOCK_MS = 6_600
/** Clock value of the still frame (pulses drawn mid-ring so they still read). */
private const val STILL_T = 0.1f

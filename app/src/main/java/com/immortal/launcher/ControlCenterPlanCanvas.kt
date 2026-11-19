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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
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
 *     markers) and 6. top (names + captions) — redraw only when the derived
 *     [CcPlanModel] / lens / focus change (equal models skip);
 *  5. animation (pulses, focus edge, scene walk) — the only layer read per frame,
 *     and it only exists while [LocalCcMotion] is on and something visibly animates
 *     ([CcPlanDraw.animates]). With motion off the same marks are drawn once, still, in the over layer.
 * Layers 1-4 and 6 keep their pixels in offscreen textures (see the comments at each).
 */
@Composable
internal fun CcPlanCanvas(
    model: CcPlanModel,
    lens: CcLens,
    focus: String?,
    walk: CcWalkView?,
    stale: Boolean,
    fahrenheit: Boolean,
    onTap: (CcPlanHit) -> Unit,
    modifier: Modifier = Modifier,
    /** Shared by the parked floors' canvases (one glow bitmap and one set of icon caches). */
    paint: CcPlanPaint = rememberCcPlanPaint(),
    /** Kept composed but not shown ([ccFloorSlot]): holds no offscreen textures (GPU memory) while parked. */
    parked: Boolean = false,
) {
  val c = LocalCc.current
  val density = LocalDensity.current
  val textPaints = remember(c, density) { CcPlanTextPaints(c, density) }
  var size by remember { mutableStateOf(IntSize.Zero) }
  val maxScale = with(density) { MAX_CELL_DP.dp.toPx() }
  // Floors already shown keep their geometry, so switching back costs no path building.
  val pxCache = remember { CcLru<CcPxKey, CcPlanPx>(FLOOR_CACHE) }
  val px = remember(model.floor, size, maxScale) {
    pxCache.getOrPut(CcPxKey(model.floor, size.width, size.height, maxScale)) {
      CcPlanPx(CcPlanGeo.fit(model.bounds, size.width.toFloat(), size.height.toFloat(), maxScale = maxScale), model)
    }
  }
  // Floors already shown keep their draw objects too, so their recorded passes (below) are reused.
  val bases = remember { CcLru<CcBaseKey, CcPlanBase>(FLOOR_CACHE) }
  val base = remember(px, c, density) {
    bases.getOrPut(CcBaseKey(CcSame(px), c, density.density)) { CcPlanBase(px, c, density.density) }
  }
  // Texts per (model, lens), kept for the lenses just shown: switching back measures nothing.
  val texts = remember(textPaints) { CcLru<CcLensKey, Pair<Map<String, CcRoomText>, CcMapChipText>>(LENS_CACHE) }
  val (labels, chipText) = remember(model, lens, textPaints) {
    texts.getOrPut(CcLensKey(model, lens)) { ccTrace("cc:texts") { roomTexts(model, lens, textPaints) to chipTexts(model, textPaints) } }
  }
  val draws = remember { CcLru<CcDrawKey, CcPlanDraw>(DRAW_CACHE) }
  val ctx = remember(model, px, paint, c, labels, chipText, lens, focus, stale, fahrenheit) {
    draws.getOrPut(CcDrawKey(CcSame(model), CcSame(px), CcSame(paint), c, CcSame(labels), CcSame(chipText), lens, focus, stale,
        fahrenheit, density.density)) {
      ccTrace("cc:drawCtx") { CcPlanDraw(model, px, paint, c, labels, chipText, lens, focus, stale, fahrenheit, density.density) }
    }
  }
  // Each pass recorded once per floor/model/lens/focus into a retained GraphicsLayer (display list).
  val graphics = LocalGraphicsContext.current
  val layers = remember(graphics) { CcPlanLayers(graphics) }
  DisposableEffect(layers) { onDispose { layers.release() } }
  val motion = LocalCcMotion.current
  val latestTap by rememberUpdatedState(onTap)
  // The tap detector reads the current floor; it isn't restarted on every floor switch.
  val latestHitArea by rememberUpdatedState(model to px)
  val isParked by rememberUpdatedState(parked)
  val description = remember(model.views, model.floor.name, lens) {
    ccTrace("cc:speech") { CcSpeech.plan(CcOverviewText.shortFloor(model.floor.name), lens, model.views.map { it.room.name to CcOverviewText.caption(it, lens) }) }
  }
  val ready = size != IntSize.Zero && px.fit.scale > 0f
  Box(modifier.onSizeChanged { size = it }.ccSpeak(description)
      .pointerInput(Unit) {
        detectTapGestures { pos ->
          if (isParked) return@detectTapGestures
          val (m, p) = latestHitArea
          hit(m, p, pos, TAP_DP.dp.toPx())?.let { latestTap(it) }
        }
      }) {
    if (!ready) return@Box
    val loops = ctx.animates
    val still = !motion && loops
    // Layers 1-4 rasterize once into one GPU texture: an animation frame (or the sheet sliding
    // over the plan) then blits it instead of replaying every fill, glow and wall path.
    val strategy = if (parked) CompositingStrategy.Auto else CompositingStrategy.Offscreen
    Box(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = strategy)) {
      PlanLayer { layers.draw(this, CcPassKey(CcPlanPass.FLOORS, CcSame(base))) { base.drawFloors(this) } }
      PlanLayer { layers.draw(this, CcPassKey(CcPlanPass.UNDER, CcSame(ctx))) { ctx.drawUnder(this) } }
      PlanLayer { layers.draw(this, CcPassKey(CcPlanPass.WALLS, CcSame(base))) { base.drawWalls(this) } }
      PlanLayer {
        layers.draw(this, CcPassKey(if (still) CcPlanPass.OVER_STILL else CcPlanPass.OVER, CcSame(ctx))) {
          ctx.drawOver(this)
          if (still) ctx.drawAnimated(this, STILL_T)
        }
      }
    }
    if (motion && (loops || walk != null)) CcPlanAnimLayer(ctx, walk, model.floor, loops)
    // Labels sit above the motion; their soft text shadows are blurs, so they get a texture too.
    PlanLayer(cached = !parked) { layers.draw(this, CcPassKey(CcPlanPass.TOP, CcSame(ctx))) { ctx.drawTop(this) } }
  }
}

/**
 * One RenderNode; its draw lambda is memoized by what it captures, so it re-records only when
 * those change. [cached] also keeps its pixels in an offscreen texture between re-records.
 */
@Composable
private fun PlanLayer(cached: Boolean = false, draw: DrawScope.() -> Unit) {
  val layer = if (cached) Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen) else Modifier.graphicsLayer()
  Spacer(Modifier.fillMaxSize().then(layer).drawBehind(draw))
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

/** A plan model (by identity: the engine keeps unchanged models) shown in one lens. */
internal class CcLensKey(private val model: CcPlanModel, private val lens: CcLens) {
  override fun equals(other: Any?): Boolean = other is CcLensKey && other.model === model && other.lens == lens
  override fun hashCode(): Int = System.identityHashCode(model) * HASH_PRIME + lens.ordinal
}

private const val HASH_PRIME = 31
/** Model × lens text sets kept (two lenses on each of four floors). */
private const val LENS_CACHE = 8
/** Draw objects kept: one per floor and lens shown, plus the ones an HA update just replaced. */
private const val DRAW_CACHE = 8

/** Plan sizes from the mock (dp; geometry itself is in cells). */
internal const val MAX_CELL_DP = 34f
private const val TAP_DP = 36f
/** Loop period: pulses (÷3 = 2.2 s) and the highlight (÷4 = 1.65 s) both divide it. */
private const val CLOCK_MS = 6_600
/** Floors whose geometry stays cached (a Portal home rarely has more). */
private const val FLOOR_CACHE = 4
/** Clock value of the still frame (pulses drawn mid-ring so they still read). */
private const val STILL_T = 0.1f

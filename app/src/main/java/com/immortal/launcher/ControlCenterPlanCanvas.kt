/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** A scene walk as the canvas draws it: rooms in order and the live progress (read in draw only). */
@Immutable
internal class CcWalkView(val rooms: List<String>, val progress: State<Float>)

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
 *
 * 3D ([iso] = 1, the mock's isometric view): the flat passes (floors, under, openings and the
 * veil, the animation) are the very same recordings replayed through [CcIsoPx.view], so the
 * switch re-records nothing; the slab goes under them, the standing walls and the upright
 * markers into a second cached texture above the animation (walls hide what's behind them), and
 * the labels are recorded once more, upright. While [iso] moves between 0 and 1 the view tips
 * back, the walls rise and the markers ride along, drawn live; at rest everything is cached again.
 * A light change fades (see [CcLightFade]) while motion is on.
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
    /** 0 = the flat plan, 1 = isometric; read only while drawing (the switch animates it). */
    iso: State<Float> = FLAT,
    /** Jarvis is pointing at [focus] (the mock's pulsing `.hl.on`). */
    pulse: Boolean = false,
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
  // The 3D geometry is built the first time this floor is drawn in 3D (never on a 2D-only Portal).
  val iso3 = remember(px) { lazy { ccTrace("cc:iso") { CcIsoPx(px, model, size.width.toFloat(), size.height.toFloat(), maxScale) } } }
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
  val motion = LocalCcMotion.current
  val fade = rememberLightFade(model, motion && !parked)
  val skipLit = fade?.first?.rooms ?: emptySet()
  val draws = remember { CcLru<CcDrawKey, CcPlanDraw>(DRAW_CACHE) }
  val ctx = remember(model, px, paint, c, labels, chipText, lens, focus, stale, fahrenheit, skipLit, pulse) {
    draws.getOrPut(CcDrawKey(CcSame(model), CcSame(px), CcSame(paint), c, CcSame(labels), CcSame(chipText), lens, focus, stale,
        fahrenheit, density.density, skipLit, pulse)) {
      ccTrace("cc:drawCtx") { CcPlanDraw(model, px, paint, c, labels, chipText, lens, focus, stale, fahrenheit, density.density, skipLit, pulse) }
    }
  }
  // Each pass recorded once per floor/model/lens/focus into a retained GraphicsLayer (display list).
  val graphics = LocalGraphicsContext.current
  val layers = remember(graphics) { CcPlanLayers(graphics) }
  DisposableEffect(layers) { onDispose { layers.release() } }
  val latestTap by rememberUpdatedState(onTap)
  // The tap detector reads the current floor; it isn't restarted on every floor switch.
  val latestHitArea by rememberUpdatedState(Triple(model, px, iso3))
  val latestIso by rememberUpdatedState(iso)
  val isParked by rememberUpdatedState(parked)
  val description = remember(model.views, model.floor.name, lens) {
    ccTrace("cc:speech") { CcSpeech.plan(CcOverviewText.shortFloor(model.floor.name), lens, model.views.map { it.room.name to CcOverviewText.caption(it, lens) }) }
  }
  val outline = remember(density) { CcIsoPx.outline(density.density) }
  // Composition and the cached passes only learn whether the view left 2D / reached 3D, not every frame.
  val raised by remember(iso) { derivedStateOf { iso.value > 0f } }
  val rest3dState = remember(iso) { derivedStateOf { iso.value >= 1f } }
  val rest3d: State<Boolean> = rest3dState
  // Mid-switch, the upper passes redraw every frame anyway: drawing them straight to the screen
  // saves an offscreen pass per frame (their textures come back at rest).
  val moving = remember(iso) { derivedStateOf { iso.value > 0f && iso.value < 1f } }
  // Once this canvas has shown 3D (or is switching to it) its upper passes stay composed.
  val shown3d = remember { mutableStateOf(false) }
  SideEffect { if (raised) shown3d.value = true }
  val ready = size != IntSize.Zero && px.fit.scale > 0f
  Box(modifier.onSizeChanged { size = it }.ccSpeak(description)
      .pointerInput(Unit) {
        detectTapGestures { pos ->
          if (isParked) return@detectTapGestures
          val (m, p, i3) = latestHitArea
          val e = latestIso.value
          val view = if (e > 0f) i3.value.blend(e) else CcAffine.IDENTITY
          CcPlanHitTest.hit(m, p.fit, view, pos.x, pos.y, TAP_DP.dp.toPx())?.let { latestTap(it) }
        }
      }) {
    if (!ready) return@Box
    val loops = ctx.animates
    val still = !motion && loops
    // Layers 1-4 rasterize once into one GPU texture: an animation frame (or the sheet sliding
    // over the plan) then blits it instead of replaying every fill, glow and wall path.
    val strategy = if (parked) CompositingStrategy.Auto else CompositingStrategy.Offscreen
    // The slab hangs below the floors, in screen space.
    PlanLayer {
      val e = iso.value
      if (e >= 1f) layers.draw(this, CcPassKey(CcPlanPass.SLAB3D, CcSame(base))) { iso3.value.drawSlab(this, c) }
      else if (e > 0f) iso3.value.drawSlabLive(this, e, c)
    }
    // While the view switches, this texture is moved as it is (turned, squashed, scaled by the
    // two layers' properties: no re-raster); at rest in 3D its passes are replayed projected.
    Box(Modifier.fillMaxSize()
        .graphicsLayer { isoOuter(iso3, iso.value) }
        .graphicsLayer { isoInner(iso3, iso.value); compositingStrategy = strategy }) {
      PlanLayer { flatAtRest(iso3, rest3d) { layers.draw(this, CcPassKey(CcPlanPass.FLOORS, CcSame(base))) { base.drawFloors(this) } } }
      PlanLayer { flatAtRest(iso3, rest3d) { layers.draw(this, CcPassKey(CcPlanPass.UNDER, CcSame(ctx))) { ctx.drawUnder(this) } } }
      fade?.let { (f, t) -> PlanLayer { flatAtRest(iso3, rest3d) { ctx.drawFade(this, f, t.value) } } }
      PlanLayer { if (!raised) layers.draw(this, CcPassKey(CcPlanPass.WALLS, CcSame(base))) { base.drawWalls(this) } }
      PlanLayer {
        if (!raised) {
          layers.draw(this, CcPassKey(if (still) CcPlanPass.OVER_STILL else CcPlanPass.OVER, CcSame(ctx))) {
            ctx.drawOver(this)
            if (still) ctx.drawAnimated(this, STILL_T)
          }
        } else flatAtRest(iso3, rest3d) {
          layers.draw(this, CcPassKey(if (still) CcPlanPass.FLAT_STILL else CcPlanPass.FLAT, CcSame(ctx))) {
            ctx.drawOverFlat(this)
            if (still) ctx.drawAnimated(this, STILL_T)
          }
        }
      }
    }
    if (motion && (loops || walk != null)) CcPlanAnimLayer(ctx, walk, model.floor, loops, iso, iso3)
    if (raised || shown3d.value) {
      // 3D: the standing walls cover the animation behind them; the markers stay upright on top.
      // Composed from the first 3D frame on (no nodes inserted when a switch starts); a texture only at rest in 3D.
      Box(Modifier.fillMaxSize().graphicsLayer {
        compositingStrategy = if (rest3d.value && !parked) strategy else CompositingStrategy.Auto
      }) {
        PlanLayer {
          val e = iso.value
          if (e >= 1f) layers.draw(this, CcPassKey(CcPlanPass.WALLS3D, CcSame(base))) { iso3.value.drawWalls(this, c, outline) }
          else if (e > 0f) iso3.value.drawWallsLive(this, e, c, outline)
        }
        PlanLayer {
          val e = iso.value
          if (e >= 1f) layers.draw(this, CcPassKey(CcPlanPass.MARKS3D, CcSame(ctx))) { ctx.drawMarks(this, iso3.value.view) }
          else if (e > 0f) ctx.drawMarks(this, iso3.value.blend(e))
        }
      }
    }
    // Labels sit above the motion; their soft text shadows are blurs, so they get a texture too.
    PlanLayer(cached = { !parked && !moving.value }) {
      val e = iso.value
      when {
        e <= 0f -> layers.draw(this, CcPassKey(CcPlanPass.TOP, CcSame(ctx))) { ctx.drawTop(this) }
        e >= 1f -> layers.draw(this, CcPassKey(CcPlanPass.TOP3D, CcSame(ctx))) { ctx.drawTop(this, iso3.value.view) }
        else -> ctx.drawTop(this, iso3.value.blend(e))
      }
    }
  }
}

/** Draws [block] as recorded (2D, or moving as a texture mid-switch), or projected once the view rests in 3D. */
private inline fun DrawScope.flatAtRest(iso3: Lazy<CcIsoPx>, rest3d: State<Boolean>, crossinline block: DrawScope.() -> Unit) {
  if (!rest3d.value) block() else iso3.value.projected(this, 1f) { block() }
}

/** Mid-switch only: the outer layer scales about the floor's centre and slides it toward its 3D spot. */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.isoOuter(iso3: Lazy<CcIsoPx>, e: Float) {
  if (e <= 0f || e >= 1f) { scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f; return }
  val p = iso3.value.parts(e)
  scaleX = p.scaleX; scaleY = p.scaleY; translationX = p.tx; translationY = p.ty
  if (size.width > 0f && size.height > 0f) transformOrigin = androidx.compose.ui.graphics.TransformOrigin(p.pivotX / size.width, p.pivotY / size.height)
}

/** Mid-switch only: the inner (cached) layer turns about the floor's centre. */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.isoInner(iso3: Lazy<CcIsoPx>, e: Float) {
  if (e <= 0f || e >= 1f) { rotationZ = 0f; return }
  val p = iso3.value.parts(e)
  rotationZ = p.degrees
  if (size.width > 0f && size.height > 0f) transformOrigin = androidx.compose.ui.graphics.TransformOrigin(p.pivotX / size.width, p.pivotY / size.height)
}

/**
 * The light change running on this floor, if any, with its progress: computed in the same
 * composition as the new [model] (so the first frame already shows the old light), then played
 * once over [CcLightFade.durationMs]. Null with motion off: lights then simply switch.
 */
@Composable
private fun rememberLightFade(model: CcPlanModel, enabled: Boolean): Pair<CcLightFade, State<Float>>? {
  val last = remember { arrayOfNulls<CcPlanModel>(1) }
  val fade = remember(model) {
    val prev = last[0]
    if (enabled && prev != null && prev !== model) ccTrace("cc:fade") { CcLightFade.diff(prev, model) } else null
  }
  SideEffect { last[0] = model }
  var done by remember { mutableStateOf<CcLightFade?>(null) }
  val progress = remember(fade) { Animatable(0f) }
  LaunchedEffect(fade) {
    if (fade == null) return@LaunchedEffect
    progress.animateTo(1f, tween(fade.durationMs, easing = LinearEasing))
    done = fade
  }
  return fade?.takeIf { it !== done }?.let { it to progress.asState() }
}

/**
 * One RenderNode; its draw lambda is memoized by what it captures, so it re-records only when
 * those change. [cached] also keeps its pixels in an offscreen texture between re-records.
 */
@Composable
private fun PlanLayer(cached: (() -> Boolean)? = null, draw: DrawScope.() -> Unit) {
  val layer = if (cached != null) {
    Modifier.graphicsLayer { compositingStrategy = if (cached()) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
  } else Modifier.graphicsLayer()
  Spacer(Modifier.fillMaxSize().then(layer).drawBehind(draw))
}

/**
 * The per-frame layer: one clock (a period every loop divides) read only inside the draw lambda,
 * plus the scene walk's progress. Not composed at all while motion is off. Drawn on the floor
 * (through the projection in 3D).
 */
@Composable
private fun CcPlanAnimLayer(ctx: CcPlanDraw, walk: CcWalkView?, floor: CcFloor, loops: Boolean, iso: State<Float>, iso3: Lazy<CcIsoPx>) {
  val clock: State<Float>? = if (loops) {
    rememberInfiniteTransition(label = "plan").animateFloat(0f, 1f, infiniteRepeatable(tween(CLOCK_MS, easing = LinearEasing)), label = "planClock")
  } else null
  val centres = remember(walk?.rooms, floor) { walk?.rooms?.map { id -> floor.rooms.firstOrNull { it.id == id }?.rect?.let(CcPlanGeo::center) } }
  Spacer(Modifier.fillMaxSize().graphicsLayer().drawBehind {
    // Screen space: pulses and edges are placed through the view, never drawn under a moving matrix.
    val e = iso.value
    val view = if (e > 0f) iso3.value.blend(e) else CcAffine.IDENTITY
    clock?.let { ctx.drawAnimated(this, it.value, view) }
    if (walk != null && centres != null) ctx.drawWalk(this, walk.rooms, centres, walk.progress.value, view)
  })
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
/** The flat plan (no 3D): what every canvas without a style switch draws. */
private val FLAT: State<Float> = mutableStateOf(0f)

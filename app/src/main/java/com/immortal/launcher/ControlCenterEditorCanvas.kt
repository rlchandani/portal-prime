/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The finger's drag across the whole editor (canvas and device list share it, so a chip can be
 * carried onto a room). [active] — what was grabbed — changes only when a drag starts or ends and
 * may be read in composition; [current] and [ghost] change every pointer move and are read ONLY in
 * draw/layout lambdas, so a drag re-draws one layer and never recomposes the editor.
 */
@Stable
internal class CcEdDragHost {
  var active by mutableStateOf<CcEdDrag?>(null)
    private set
  private val currentState = mutableStateOf<CcEdDrag?>(null)
  private val ghostState = mutableStateOf<Offset?>(null)
  /** Read in draw/layout only. */
  val current: CcEdDrag? get() = currentState.value
  /** Root position of the carried chip; read in layout only. */
  val ghost: Offset? get() = ghostState.value
  var canvasOrigin: Offset = Offset.Zero
  /** The editor root's window position (ghost placement), written by layout. */
  var editorOrigin: Offset = Offset.Zero
  var canvasSize: IntSize = IntSize.Zero
  var geom by mutableStateOf<CcEdGeom?>(null)

  /** Grid point under a root position, or null when it's off the canvas. */
  fun gridAt(root: Offset): Pair<Float, Float>? {
    val g = geom ?: return null
    val local = root - canvasOrigin
    val inside = local.x in 0f..canvasSize.width.toFloat() && local.y in 0f..canvasSize.height.toFloat()
    return if (inside) g.gx(local.x) to g.gy(local.y) else null
  }

  fun begin(d: CcEdDrag, root: Offset) { currentState.value = d; active = d; move(root) }

  fun move(root: Offset) {
    val d = currentState.value ?: return
    val at = gridAt(root)
    currentState.value = CcEdGesture.update(d, at?.first, at?.second)
    if (d is CcEdDrag.Device) ghostState.value = root
  }

  fun end(): CcEdDrag? = currentState.value.also { currentState.value = null; ghostState.value = null; active = null }
}

/** Mock `.edCanvas`: grid layer, static plan layer, device dots, and the one layer a drag redraws. */
@Composable
internal fun CcEditorCanvas(st: CcEdState, floor: CcFloor, reg: CcRegistry, live: Map<String, CcEntity>, host: CcEdDragHost,
                            motion: Boolean, onTap: (Float, Float) -> Unit, onDrop: (CcEdDrag) -> Unit, modifier: Modifier = Modifier) {
  val c = LocalCc.current
  val density = LocalDensity.current
  val insets = with(density) { Triple(CANVAS_TOP.dp.toPx(), CANVAS_BOTTOM.dp.toPx(), CANVAS_SIDE.dp.toPx()) }
  val tolPx = with(density) { HIT_DP.dp.toPx() }
  val latest by rememberUpdatedState(st)
  val latestFloor by rememberUpdatedState(floor)
  val tap by rememberUpdatedState(onTap)
  val drop by rememberUpdatedState(onDrop)
  val active = host.active
  val still = remember(floor, active) { CcEdGesture.staticFloor(floor, active) }
  /** Dots keep a moved room's devices (they follow it in layout); only a carried device leaves. */
  val dots = remember(floor, active) { (active as? CcEdDrag.Device)?.let { CcPlanEdit.removeEntity(floor, it.entityId) } ?: floor }
  val text = rememberTextMeasurer()
  val labels = rememberEdLabels(floor, st.suggestions, st.step != CcEdStep.FOUND, c, text)
  Box(modifier.clip(CcStyle.Sheet).background(c.planBg)
      .onGloballyPositioned { lc ->
        host.canvasOrigin = lc.positionInRoot()
        if (host.canvasSize != lc.size) {
          host.canvasSize = lc.size
          host.geom = CcEdGeom(lc.size.width.toFloat(), lc.size.height.toFloat(), insets.first, insets.second, insets.third)
        }
      }
      .pointerInput(host) { editorGestures(host, { latest }, { latestFloor }, tolPx, { x, y -> tap(x, y) }, { d -> drop(d) }) }
      .semantics { contentDescription = "Floor plan canvas. Drag to draw or move rooms." }) {
    val geom = host.geom ?: return@Box
    val scene = remember(geom, st, still, reg, live, c, labels, density.density) { CcEdScene(geom, st, still, reg, live, c, labels, density.density) }
    Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawEditorGrid(geom, c) }
    Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawEditorStatic(scene) }
    CcEditorDots(st, dots, geom, reg, live, motion, host)
    Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawEditorDrag(scene, floor, host.current) }
  }
}

private suspend fun PointerInputScope.editorGestures(
    host: CcEdDragHost, st: () -> CcEdState, floor: () -> CcFloor, tolPx: Float,
    onTap: (Float, Float) -> Unit, onDrop: (CcEdDrag) -> Unit,
) = awaitEachGesture {
  val down = awaitFirstDown()
  val geom = host.geom ?: return@awaitEachGesture
  val gx = geom.gx(down.position.x)
  val gy = geom.gy(down.position.y)
  val start = CcEdGesture.begin(st(), floor(), gx, gy, geom.cells(tolPx))
  val slop = awaitTouchSlopOrCancellation(down.id) { ch, _ -> if (start != null) ch.consume() }
  if (slop == null) { onTap(gx, gy); return@awaitEachGesture }
  if (start == null) return@awaitEachGesture
  host.begin(start, host.canvasOrigin + slop.position)
  drag(slop.id) { ch -> host.move(host.canvasOrigin + ch.position); ch.consume() }
  host.end()?.let(onDrop)
}

/**
 * Device dots (mock `.edDev`), flying in from the right after Auto-place. Positions are computed
 * in the layout lambda from the drag State, so dots ride along with a moved/resized room without
 * recomposing.
 */
@Composable
private fun CcEditorDots(st: CcEdState, floor: CcFloor, geom: CcEdGeom, reg: CcRegistry, live: Map<String, CcEntity>, motion: Boolean,
                         host: CcEdDragHost) {
  if (st.step == CcEdStep.FOUND || (st.first && st.step == CcEdStep.ROOMS)) return
  val c = LocalCc.current
  val density = LocalDensity.current
  val flying = remember(st.flyIds) { st.flyIds.toList().sorted() }
  val fly = remember(st.flySeq) { Animatable(if (motion && st.flySeq > 0) 0f else 1f) }
  LaunchedEffect(st.flySeq, motion) {
    if (!motion) fly.snapTo(1f) else fly.animateTo(1f, tween(CcEdGeo.flyDurationMs(flying.size), easing = LinearEasing))
  }
  val radius = with(density) { DOT_DP.dp.toPx() / 2f }
  val flyPx = with(density) { FLY_DISTANCE_DP.dp.toPx() }
  floor.rooms.forEach { room ->
    room.devices.forEach { d ->
      key(d.entityId) {
        val index = flying.indexOf(d.entityId)
        CcEditorDot(CcEd.iconFor(d.entityId, reg, live[d.entityId]), c.text, c.surface, c.accent, Modifier
            .offset {
              val r = CcEdGesture.liveRect(room.id, room.rect, host.current)
              IntOffset((geom.px(r.x + d.fx * r.w) - radius).roundToInt(), (geom.py(r.y + d.fy * r.h) - radius).roundToInt())
            }
            .graphicsLayer { translationX = if (index < 0) 0f else CcEdGeo.flyOffset(fly.value, index, flying.size) * flyPx })
      }
    }
  }
}

@Composable
private fun CcEditorDot(icon: String, ink: Color, fill: Color, edge: Color, modifier: Modifier) {
  Box(modifier.size(DOT_DP.dp).clip(CircleShape).background(fill).border(2.5.dp, edge, CircleShape), contentAlignment = Alignment.Center) {
    CcIcon(icon, ink, 20.dp)
  }
}

internal val CcWindowBlue = Color(0xFF7FB2E5)
internal const val CANVAS_TOP = 96
private const val CANVAS_BOTTOM = 20
private const val CANVAS_SIDE = 20
/** Finger tolerance for handles, dots and openings. */
private const val HIT_DP = 34
internal const val DOT_DP = 36
private const val FLY_DISTANCE_DP = 900

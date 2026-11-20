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
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
  // Which room's "+N" cluster is open (one stable State: the gesture loop outlives recompositions); a new floor or step closes it.
  val expanded = remember { mutableStateOf<String?>(null) }
  LaunchedEffect(st.floorId, st.step) { expanded.value = null }
  val layout = rememberDotLayout(st, dots, host.geom, labels, expanded.value)
  val latestLayout by rememberUpdatedState(layout)
  val clusterTap = remember(host) {
    { gx: Float, gy: Float, tol: Float ->
      if (latest.step !in CLUSTER_STEPS) false
      else CcEdDots.tap(latestLayout, expanded.value, gx, gy, tol).let { (used, next) -> expanded.value = next; used }
    }
  }
  Box(modifier.clip(CcStyle.Sheet).background(c.planBg)
      .onGloballyPositioned { lc ->
        host.canvasOrigin = lc.positionInRoot()
        if (host.canvasSize != lc.size) {
          host.canvasSize = lc.size
          host.geom = CcEdGeom(lc.size.width.toFloat(), lc.size.height.toFloat(), insets.first, insets.second, insets.third)
        }
      }
      .pointerInput(host) {
        editorGestures(host, { latest }, { latestFloor }, { latestLayout }, tolPx, clusterTap, { x, y -> tap(x, y) }, { d -> drop(d) })
      }
      .semantics { contentDescription = "Floor plan canvas. Drag to draw or move rooms." }) {
    val geom = host.geom ?: return@Box
    val scene = remember(geom, st, still, reg, live, c, labels, density.density) { CcEdScene(geom, st, still, reg, live, c, labels, density.density) }
    Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawEditorGrid(geom, c) }
    Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawEditorStatic(scene) }
    CcEditorDots(st, dots, layout, geom, reg, live, motion, host)
    Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawEditorDrag(scene, floor, host.current) }
  }
}

private suspend fun PointerInputScope.editorGestures(
    host: CcEdDragHost, st: () -> CcEdState, floor: () -> CcFloor, dots: () -> CcEdDotLayout, tolPx: Float,
    onClusterTap: (Float, Float, Float) -> Boolean, onTap: (Float, Float) -> Unit, onDrop: (CcEdDrag) -> Unit,
) = awaitEachGesture {
  val down = awaitFirstDown()
  val geom = host.geom ?: return@awaitEachGesture
  val gx = geom.gx(down.position.x)
  val gy = geom.gy(down.position.y)
  val tol = geom.cells(tolPx)
  val start = CcEdGesture.begin(st(), floor(), gx, gy, tol, dots())
  val slop = awaitTouchSlopOrCancellation(down.id) { ch, _ -> if (start != null) ch.consume() }
  if (slop == null) { if (!onClusterTap(gx, gy, tol)) onTap(gx, gy); return@awaitEachGesture }
  if (start == null) return@awaitEachGesture
  host.begin(start, host.canvasOrigin + slop.position)
  drag(slop.id) { ch -> host.move(host.canvasOrigin + ch.position); ch.consume() }
  host.end()?.let(onDrop)
}

/**
 * Device dots (mock `.edDev`) where [CcEdDots] laid them out, flying in from the right after
 * Auto-place, then any "+N" clusters, then an open cluster's ring on top. Positions are computed in
 * the layout lambda from the drag State, so dots ride along with a moved/resized room without
 * recomposing.
 */
@Composable
private fun CcEditorDots(st: CcEdState, floor: CcFloor, layout: CcEdDotLayout, geom: CcEdGeom, reg: CcRegistry,
                         live: Map<String, CcEntity>, motion: Boolean, host: CcEdDragHost) {
  if (!dotsShown(st)) return
  val c = LocalCc.current
  val density = LocalDensity.current
  val flying = remember(st.flyIds) { st.flyIds.toList().sorted() }
  val fly = remember(st.flySeq) { Animatable(if (motion && st.flySeq > 0) 0f else 1f) }
  LaunchedEffect(st.flySeq, motion) {
    if (!motion) fly.snapTo(1f) else fly.animateTo(1f, tween(CcEdGeo.flyDurationMs(flying.size), easing = LinearEasing))
  }
  val radius = with(density) { DOT_DP.dp.toPx() / 2f }
  val flyPx = with(density) { FLY_DISTANCE_DP.dp.toPx() }
  val rooms = remember(floor) { floor.rooms.associateBy { it.id } }
  val at = { room: CcRoom, fx: Float, fy: Float ->
    Modifier.offset {
      val r = CcEdGesture.liveRect(room.id, room.rect, host.current)
      IntOffset((geom.px(r.x + fx * r.w) - radius).roundToInt(), (geom.py(r.y + fy * r.h) - radius).roundToInt())
    }
  }
  val dot = @Composable { d: CcEdDot ->
    rooms[d.roomId]?.let { room ->
      key(d.entityId) {
        val index = flying.indexOf(d.entityId)
        CcEditorDot(CcEd.iconFor(d.entityId, reg, live[d.entityId]), c.text, c.surface, c.accent, at(room, d.fx, d.fy)
            .graphicsLayer { translationX = if (index < 0) 0f else CcEdGeo.flyOffset(fly.value, index, flying.size) * flyPx })
      }
    }
  }
  layout.dots.forEach { if (!it.ring) dot(it) }
  layout.clusters.forEach { cl ->
    rooms[cl.roomId]?.let { room -> key("cluster:${cl.roomId}") { CcEditorCluster(cl, room.name, c.bg, c.accent, at(room, cl.fx, cl.fy)) } }
  }
  layout.dots.forEach { if (it.ring) dot(it) }
}

/** "+N" (mock-styled dot, accent filled); open, it shows a close mark over its ring. */
@Composable
private fun CcEditorCluster(cl: CcEdCluster, roomName: String, ink: Color, fill: Color, modifier: Modifier) {
  val what = if (cl.expanded) "Hide the ${cl.hidden.size} more devices in $roomName"
      else "${cl.hidden.size} more devices in $roomName, tap to show them"
  Box(modifier.size(DOT_DP.dp).clip(CircleShape).background(fill).semantics { contentDescription = what },
      contentAlignment = Alignment.Center) {
    if (cl.expanded) CcIcon("x", ink, 20.dp)
    else Text(cl.label, color = ink, fontSize = CLUSTER_SP.sp, fontWeight = FontWeight.Bold, maxLines = 1)
  }
}

/** The dot layout for the floor as drawn, recomputed only when the floor, canvas size, labels or open cluster change. */
@Composable
private fun rememberDotLayout(st: CcEdState, floor: CcFloor, geom: CcEdGeom?, labels: Map<String, CcEdLabel>, expanded: String?): CcEdDotLayout {
  val density = LocalDensity.current
  val shown = dotsShown(st)
  return remember(floor, geom, labels, expanded, shown, density.density) {
    if (geom == null || !shown) CcEdDotLayout.EMPTY
    else {
      val spec = with(density) { CcEdDotSpec.of(geom.cell, DOT_DP.dp.toPx(), DOT_GAP_DP.dp.toPx()) }
      val halves = labels.mapValues { (_, l) ->
        val w = maxOf(l.title.size.width, l.sub?.size?.width ?: 0) / 2f
        val h = (l.title.size.height + (l.sub?.size?.height ?: 0)) / 2f
        geom.cells(w) to geom.cells(h)
      }
      CcEdDots.layout(floor, spec, expanded, halves)
    }
  }
}

private fun dotsShown(st: CcEdState): Boolean = !(st.step == CcEdStep.FOUND || (st.first && st.step == CcEdStep.ROOMS))

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
/** Space between neighbouring dots (mock: 36 dp dots, 46 apart). */
internal const val DOT_GAP_DP = 10
private const val CLUSTER_SP = 15
/** Steps where a "+N" cluster opens on tap (in Rooms a tap selects the room). */
private val CLUSTER_STEPS = setOf(CcEdStep.DEVICES, CcEdStep.OPENINGS)
private const val FLY_DISTANCE_DP = 900

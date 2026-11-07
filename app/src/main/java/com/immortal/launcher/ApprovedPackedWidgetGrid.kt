/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * Short swipes scroll; held drags move a card to the slot under it, committing only on release.
 * [fixed] carries exact free-placement positions; null packs [spans] in order. [canDrop]/[onDrop]
 * take a slot column (0–3) and slot row (0–1) on this page.
 */
@Composable
internal fun ApprovedPackedWidgetGrid(
    widgets: List<HomeWidgetStore.HomeWidget>,
    spans: List<ApprovedWidgetPacking.Span>,
    fixed: List<ApprovedWidgetPacking.Placement>?,
    editMode: Boolean,
    modifier: Modifier,
    canDrop: (key: String, column: Int, row: Int) -> Boolean,
    onDrop: (key: String, column: Int, row: Int) -> Unit,
    content: @Composable (HomeWidgetStore.HomeWidget, Modifier, ApprovedWidgetPacking.Span, Modifier) -> Unit,
) {
  val placements = remember(spans, fixed) { fixed ?: ApprovedWidgetPacking.pack(spans) }
  val latestDrop = rememberUpdatedState(onDrop)
  val latestCanDrop = rememberUpdatedState(canDrop)
  val scroll = rememberScrollState()
  val dragState = remember { ApprovedWidgetDragState() }
  var preview by remember { mutableStateOf<ApprovedWidgetDragState.Snapshot?>(null) }
  val draggedKey by remember { derivedStateOf { preview?.key } }
  var dragOwner by remember { mutableStateOf<Any?>(null) }
  // Page activation only controls editing gestures. Normal paging reuses the packed grid.
  val pageActive = editMode && LocalApprovedLauncherPageActive.current && LocalApprovedWidgetPageInput.current
  var viewportCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
  fun resetDrag() { dragState.reset(); preview = null; dragOwner = null }
  fun cancelDrag(owner: Any) {
    if (dragState.isOwner(owner)) {
      dragState.cancel(owner)
      preview = null
      dragOwner = null
    }
  }
  // Capture the unscrolled container, not coordinates of a node with verticalScroll.
  BoxWithConstraints(modifier.onGloballyPositioned { viewportCoordinates = it }) {
    val density = LocalDensity.current
    // Halved from 24dp on request: tighter grid, larger cards (Home v2 space).
    val gap = 12.dp
    val column = ((maxWidth - gap * 3) / 4).coerceAtLeast(1.dp)
    val row = ((maxHeight - gap * 3) / 4).coerceAtLeast(1.dp)
    val columnStep = with(density) { (column + gap).toPx() }
    val rowStep = with(density) { (row + gap).toPx() }
    val gapPx = with(density) { gap.toPx() }
    val widthPx = with(density) { maxWidth.toPx() }
    val viewportHeightPx = with(density) { maxHeight.toPx() }
    val edgeBandPx = with(density) { 64.dp.toPx() }
    val maxScrollSpeedPx = with(density) { 480.dp.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val bounds = remember(widgets, placements, columnStep, rowStep, widthPx, gapPx, rtl) {
      placements.map {
        val width = (it.columns * columnStep - gapPx).roundToInt().coerceAtLeast(1)
        val logicalLeft = (it.column * columnStep).roundToInt()
        ApprovedWidgetDragState.Bounds(widgets[it.index].key,
            (if (rtl) widthPx.roundToInt() - logicalLeft - width else logicalLeft).toFloat(),
            (it.row * rowStep).roundToInt().toFloat(), width.toFloat(),
            (it.rows * rowStep - gapPx).roundToInt().coerceAtLeast(1).toFloat())
      }
    }
    fun publishPreview() { preview = dragState.snapshot(scroll.value.toFloat(), bounds) }
    /** The slot (column, slot row) the dragged card's top-left snaps to, clamped onto the page. */
    fun targetSlot(snapshot: ApprovedWidgetDragState.Snapshot): Pair<Int, Int>? {
      val index = widgets.indexOfFirst { it.key == snapshot.key }.takeIf { it >= 0 } ?: return null
      val placement = placements.firstOrNull { it.index == index } ?: return null
      val source = bounds.firstOrNull { it.key == snapshot.key } ?: return null
      val left = source.left + snapshot.translationX
      val logicalLeft = if (rtl) widthPx - left - source.width else left
      val column = (logicalLeft / columnStep).roundToInt().coerceIn(0, 4 - placement.columns)
      val halfRow = ((source.top + snapshot.translationY) / rowStep).roundToInt()
      val row = (halfRow / 2f).roundToInt().coerceIn(0, ((4 - placement.rows) / 2).coerceAtLeast(0))
      return column to row
    }
    // Invalidation is synchronous with composition disposal, including retained inactive pages.
    DisposableEffect(editMode, pageActive, widgets, bounds, viewportHeightPx) {
      resetDrag()
      onDispose { resetDrag() }
    }
    val activeOwner = dragOwner
    LaunchedEffect(activeOwner, editMode, pageActive, widgets, bounds, viewportHeightPx) {
      val owner = activeOwner ?: return@LaunchedEffect
      try {
        // One frame loop per active drag. UserInput also stops a pre-existing scroll fling.
        scroll.scroll(MutatePriority.UserInput) {
          var previousFrame = withFrameNanos { it }
          while (isActive && dragState.isOwner(owner)) {
            val frame = withFrameNanos { it }
            if (!dragState.isOwner(owner)) break
            val current = dragState.snapshot(scroll.value.toFloat(), bounds) ?: break
            val delta = ApprovedWidgetDragState.edgeScrollDelta(current.pointerY,
                viewportHeightPx, edgeBandPx, maxScrollSpeedPx,
                ((frame - previousFrame) / 1_000_000_000.0).toFloat(),
                scroll.value.toFloat(), scroll.maxValue.toFloat())
            previousFrame = frame
            if (delta != 0f) scrollBy(delta)
            publishPreview()
          }
        }
      } finally {
        cancelDrag(owner)
      }
    }
    val rowCount = maxOf(4, placements.maxOfOrNull { it.row + it.rows } ?: 0)
    val height = row * rowCount + gap * (rowCount - 1)
    val dropTarget = preview?.let { snap -> targetSlot(snap)?.let { Triple(snap.key, it.first, it.second) } }
    Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
      dropTarget?.let { (key, column, slotRow) ->
        val index = widgets.indexOfFirst { it.key == key }
        val placement = placements.firstOrNull { it.index == index }
        if (placement != null) {
          val fits = latestCanDrop.value(key, column, slotRow)
          val outline = if (fits) ApprovedHomePalette.Accent else androidx.compose.ui.graphics.Color(0xFFE5484D)
          val width = placement.columns * columnStep - gapPx
          val logicalLeft = column * columnStep
          val left = if (rtl) widthPx - logicalLeft - width else logicalLeft
          androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(height)) {
            drawRoundRect(outline, topLeft = androidx.compose.ui.geometry.Offset(left, slotRow * 2 * rowStep),
                size = androidx.compose.ui.geometry.Size(width, placement.rows * rowStep - gapPx),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
          }
        }
      }
      Layout(
          modifier = Modifier.fillMaxWidth().height(height),
          content = {
            widgets.forEachIndexed { index, widget ->
              key(widget.key) {
                val placement = placements[index]
                val cellSpan = ApprovedWidgetPacking.Span(placement.columns, placement.rows)
                val dragging = draggedKey == widget.key
                var handleCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                val drag = if (!editMode || !pageActive) Modifier else Modifier
                    .onGloballyPositioned { handleCoordinates = it }.pointerInput(
                    widget.key, editMode, pageActive, widgets, placement, bounds, viewportHeightPx) {
                  var owner = Any()
                  fun viewportPoint(local: Offset): ApprovedWidgetDragState.Point? {
                    val viewport = viewportCoordinates?.takeIf { it.isAttached } ?: return null
                    val handle = handleCoordinates?.takeIf { it.isAttached } ?: return null
                    // The handle scrolls AND translates with the ghost. Its local position is
                    // not a stable drag accumulator; convert the actual pointer to the viewport.
                    val point = viewport.localPositionOf(handle, local)
                    return ApprovedWidgetDragState.Point(point.x, point.y)
                  }
                  try { detectDragGesturesAfterLongPress(
                      onDragStart = { local ->
                        owner = Any() // A new token for each held gesture, not just each detector.
                        viewportPoint(local)?.let { point ->
                          if (dragState.start(owner, bounds[index], point, scroll.value.toFloat())) {
                            dragOwner = owner
                            publishPreview()
                          }
                        }
                      },
                      onDragCancel = { cancelDrag(owner) },
                      onDragEnd = {
                        if (dragState.isOwner(owner)) {
                          val target = dragState.snapshot(scroll.value.toFloat(), bounds)?.let(::targetSlot)
                          dragState.finish(owner, scroll.value.toFloat(), bounds)
                          resetDrag()
                          if (target != null) latestDrop.value(widget.key, target.first, target.second)
                        }
                      },
                  ) { change, _ ->
                    if (dragState.isOwner(owner)) {
                      change.consume()
                      val point = viewportPoint(change.position)
                      if (point == null) cancelDrag(owner)
                      else if (dragState.move(owner, point)) publishPreview()
                    }
                  } } finally { cancelDrag(owner) }
                }
                content(widget, Modifier.fillMaxSize().zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                      translationX = preview?.takeIf { it.key == widget.key }?.translationX ?: 0f
                      translationY = preview?.takeIf { it.key == widget.key }?.translationY ?: 0f
                      alpha = if (preview?.key == widget.key) .88f else 1f
                    },
                    cellSpan, drag)
              }
            }
          },
      ) { measurables, constraints ->
        val measured = measurables.mapIndexed { index, measurable ->
          val placement = placements[index]
          measurable.measure(Constraints.fixed(
              (placement.columns * columnStep - gapPx).roundToInt().coerceAtLeast(1),
              (placement.rows * rowStep - gapPx).roundToInt().coerceAtLeast(1)))
        }
        layout(constraints.maxWidth, with(density) { height.roundToPx() }) {
          measured.forEachIndexed { index, placeable ->
            val placement = placements[index]
            placeable.placeRelative((placement.column * columnStep).roundToInt(),
                (placement.row * rowStep).roundToInt())
          }
        }
      }
    }
  }
}

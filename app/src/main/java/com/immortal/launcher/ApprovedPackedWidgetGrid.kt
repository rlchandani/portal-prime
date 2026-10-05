/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/** Both viewing and editing measure the same placements; a drag commits only on release. */
@Composable
internal fun ApprovedPackedWidgetGrid(
    widgets: List<HomeWidgetStore.HomeWidget>,
    spans: List<ApprovedWidgetPacking.Span>,
    editMode: Boolean,
    modifier: Modifier,
    onMove: (String, String) -> Unit,
    content: @Composable (HomeWidgetStore.HomeWidget, Modifier, ApprovedWidgetPacking.Span, Modifier) -> Unit,
) {
  val placements = remember(spans) { ApprovedWidgetPacking.pack(spans) }
  val identities = remember(widgets) { widgets.map { it.key } }
  val latestMove = rememberUpdatedState(onMove)
  val scroll = rememberScrollState()
  var draggedKey by remember { mutableStateOf<String?>(null) }
  var targetKey by remember { mutableStateOf<String?>(null) }
  var dragOffset by remember { mutableStateOf(Offset.Zero) }
  var dragOwner by remember { mutableStateOf<Any?>(null) }
  fun resetDrag() { draggedKey = null; targetKey = null; dragOffset = Offset.Zero; dragOwner = null }
  LaunchedEffect(editMode, widgets) { resetDrag() }
  BoxWithConstraints(modifier) {
    val density = LocalDensity.current
    val gap = 24.dp
    val column = ((maxWidth - gap * 3) / 4).coerceAtLeast(1.dp)
    val row = ((maxHeight - gap * 3) / 4).coerceAtLeast(1.dp)
    val columnStep = with(density) { (column + gap).toPx() }
    val rowStep = with(density) { (row + gap).toPx() }
    val gapPx = with(density) { gap.toPx() }
    val widthPx = with(density) { maxWidth.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    fun left(placement: ApprovedWidgetPacking.Placement): Float =
        if (rtl) widthPx - (placement.column + placement.columns) * columnStep + gapPx
        else placement.column * columnStep
    val rowCount = maxOf(4, placements.maxOfOrNull { it.row + it.rows } ?: 0)
    val height = row * rowCount + gap * (rowCount - 1)
    Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
      Layout(
          modifier = Modifier.fillMaxWidth().height(height),
          content = {
            widgets.forEachIndexed { index, widget ->
              key(widget.key) {
                val placement = placements[index]
                val cellSpan = ApprovedWidgetPacking.Span(placement.columns, placement.rows)
                val dragging = draggedKey == widget.key
                val drag = if (!editMode) Modifier else Modifier.pointerInput(
                    widget.key, editMode, identities, placement, placements, columnStep, rowStep, rtl) {
                  val owner = Any()
                  try { detectDragGestures(
                      onDragStart = { dragOwner = owner; draggedKey = widget.key; targetKey = null; dragOffset = Offset.Zero },
                      onDragCancel = { if (dragOwner === owner) resetDrag() },
                      onDragEnd = {
                        val destination = targetKey
                        if (dragOwner === owner) {
                          resetDrag()
                          if (destination != null && destination != widget.key) latestMove.value(widget.key, destination)
                        }
                      },
                  ) { change, amount ->
                    change.consume()
                    dragOffset += amount
                    val center = Offset(
                        left(placement) + (placement.columns * columnStep - gapPx) / 2,
                        placement.row * rowStep + (placement.rows * rowStep - gapPx) / 2) + dragOffset
                    targetKey = placements.firstOrNull {
                      val x = left(it); val y = it.row * rowStep
                      center.x >= x && center.x <= x + it.columns * columnStep - gapPx &&
                          center.y >= y && center.y <= y + it.rows * rowStep - gapPx
                    }?.let { widgets[it.index].key }
                  } } finally { if (dragOwner === owner) resetDrag() }
                }
                content(widget, Modifier.fillMaxSize().zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                      translationX = if (draggedKey == widget.key) dragOffset.x else 0f
                      translationY = if (draggedKey == widget.key) dragOffset.y else 0f
                      alpha = if (draggedKey == widget.key) .88f else 1f
                    }.then(if (targetKey == widget.key && !dragging)
                        Modifier.border(2.dp, ApprovedHomePalette.Accent, RoundedCornerShape(20.dp)) else Modifier),
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

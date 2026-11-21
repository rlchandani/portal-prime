/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.key.key
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

internal typealias HomeWidgetChange =
    (List<HomeWidgetStore.HomeWidget>, List<HomeWidgetStore.HomeWidget>) -> Boolean

/** A stable cell boundary keeps cached widget content out of paging recompositions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ApprovedWidgetCell(
    widget: HomeWidgetStore.HomeWidget,
    cell: Modifier,
    widgets: List<HomeWidgetStore.HomeWidget>,
    editMode: Boolean,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    onChanged: HomeWidgetChange,
    onEnterEdit: () -> Unit,
    onRemove: (HomeWidgetStore.HomeWidget) -> Unit,
    displaySpan: Pair<Int, Int> = widget.spanX to widget.spanY,
    onMove: () -> Unit = {},
    dragHandle: Modifier = Modifier,
    gripHandle: Modifier = Modifier,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalWidgetEditing provides editMode) {
    key(widget.key) {
      val context = LocalContext.current
      var showTimers by remember { mutableStateOf(false) }
      var widthPx by remember { mutableStateOf(0) }
      var heightPx by remember { mutableStateOf(0) }
      var resizeAccum by remember { mutableStateOf(Offset.Zero) }
      var startSpan by remember { mutableStateOf(widget.spanX to widget.spanY) }
      var resizeSnapshot by remember { mutableStateOf<List<HomeWidgetStore.HomeWidget>?>(null) }
      var resizeDraft by remember { mutableStateOf<Pair<Int, Int>?>(null) }
      var columnUnit by remember { mutableStateOf(1f) }
      var rowUnit by remember { mutableStateOf(1f) }
      val density = androidx.compose.ui.platform.LocalDensity.current
      val gapPx = with(density) { 24.dp.toPx() }
      val resizeRtl = androidx.compose.ui.platform.LocalLayoutDirection.current ==
          androidx.compose.ui.unit.LayoutDirection.Rtl
      LaunchedEffect(editMode) { resizeDraft = null; resizeSnapshot = null; resizeAccum = Offset.Zero }
      val compactLegend = currentLayoutClass().compact
      Box(cell.zIndex(if (resizeDraft != null) 2f else 0f)
          .onSizeChanged { widthPx = it.width; heightPx = it.height }
          .semantics { contentDescription = "${if (widget.isAppWidget) "App" else customWidgetLabel(widget.kind)} widget" }) {
        ApprovedHomeSurface(Modifier.fillMaxSize().combinedClickable(
            // A container, not a button: its only action is the long press into Customize.
            enabled = !editMode, role = null, onClickLabel = null, onLongClickLabel = "Customize Home",
            onClick = {}, onLongClick = onEnterEdit)
            .then(if (editMode) Modifier.clearAndSetSemantics { } else Modifier)) {
          if (widget.isAppWidget) {
            ApprovedHostedWidget(widget, host, manager, editMode)
          } else {
            ImmortalWidgetContent(widget, Modifier.fillMaxSize(), onEnterEdit = onEnterEdit)
          }
        }
        if (editMode) {
          // Own the editing gesture above widget content, whose click/scroll handlers
          // otherwise compete with moving the card.
          Box(Modifier.matchParentSize().then(dragHandle))
          ApprovedWidgetDragGrip(gripHandle, Modifier.align(Alignment.TopEnd).padding(10.dp))
          val onSize: () -> Unit = {
              // Cycle to the next size that fits here; neighbours never get covered or pushed.
              var next = displaySpan
              var resized: List<HomeWidgetStore.HomeWidget>? = null
              repeat(4) {
                if (resized == null) {
                  next = HomeWidgetStore.nextWidgetSpan(next)
                  resized = HomeSlots.resizeForEditing(widgets, widget.key, next)
                }
              }
              resized?.let { onChanged(widgets, it) }
                  ?: context.showHomeLayoutRefusal("No room to resize here. Move it or free a spot first.")
            }
          val onSettings: () -> Unit = {
              when {
                widget.kind == HomeWidgetStore.KIND_TIMERS -> showTimers = true
                widget.isAppWidget -> {
                  val config = manager.getAppWidgetInfo(widget.appWidgetId)?.configure
                  val intent = if (config != null) Intent().setComponent(config)
                      .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget.appWidgetId)
                      else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                          Uri.parse("package:${widget.providerPackage}"))
                  runCatching { context.startApprovedInternalActivity(intent) }.onFailure {
                    Toast.makeText(context, "Widget settings couldn’t be opened.", Toast.LENGTH_SHORT).show()
                  }
                }
                else -> context.openApprovedHomeSettings(when (widget.kind) {
                  HomeWidgetStore.KIND_WORLD_CLOCK -> "world"
                  HomeWidgetStore.KIND_TODAY -> "calendar"
                  HomeWidgetStore.KIND_PHOTO_MEMORY -> "sources"
                  HomeWidgetStore.KIND_QUICK_CONTROLS -> "quickbar"
                  else -> widget.kind
                })
              }
            }
          val widgetName = remember(widget.key, widget.appWidgetId) { widgetSpokenName(widget, manager, context) }
          val legend = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
              .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
              .background(ApprovedHomePalette.Card).padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 58.dp)
          if (!compactLegend) {
            Row(legend, horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
              ApprovedWidgetAction("Move", widgetName, Modifier.weight(1f), onClick = onMove)
              ApprovedWidgetAction("Size", widgetName, Modifier.weight(1f), onClick = onSize)
              ApprovedWidgetAction("Settings", widgetName, Modifier.weight(1f), onClick = onSettings)
              ApprovedWidgetAction("Remove", widgetName, Modifier.weight(1f)) { onRemove(widget) }
            }
          } else {
            // Narrow cards (compact Customize): two rows of two, so no label is cut to a letter.
            Column(legend) {
              Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ApprovedWidgetAction("Move", widgetName, Modifier.weight(1f), onClick = onMove)
                ApprovedWidgetAction("Size", widgetName, Modifier.weight(1f), onClick = onSize)
              }
              Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ApprovedWidgetAction("Settings", widgetName, Modifier.weight(1f), onClick = onSettings)
                ApprovedWidgetAction("Remove", widgetName, Modifier.weight(1f)) { onRemove(widget) }
              }
            }
          }
          resizeDraft?.let { span ->
            Canvas(Modifier.matchParentSize()) {
              drawRoundRect(ApprovedHomeTabPalette.accent,
                  size = Size(span.first * columnUnit - gapPx, span.second * rowUnit - gapPx),
                  cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                  style = Stroke(3.dp.toPx()))
            }
          }
          ResizeBadge(Modifier.align(Alignment.BottomEnd), onResizeStart = {
            startSpan = displaySpan
            resizeSnapshot = widgets
            resizeAccum = Offset.Zero
            resizeDraft = displaySpan
            columnUnit = ((widthPx - gapPx * (displaySpan.first - 1)) / displaySpan.first).coerceAtLeast(1f) + gapPx
            rowUnit = ((heightPx - gapPx * (displaySpan.second - 1)) / displaySpan.second).coerceAtLeast(1f) + gapPx
          }, onResizeDrag = { delta ->
            resizeAccum += Offset(if (resizeRtl) -delta.x else delta.x, delta.y)
            fun steps(value: Float, unit: Float): Int = (kotlin.math.sign(value) *
                kotlin.math.floor(kotlin.math.abs(value / unit) + .35f)).toInt()
            val x = startSpan.first + steps(resizeAccum.x, columnUnit)
            val y = startSpan.second + steps(resizeAccum.y, rowUnit)
            // Corner drag snaps to whole slots of the 2-row × 4-column grid.
            val slot = HomePresentation.slotSpan(x, y)
            resizeDraft = slot.columns to slot.rows
          }, onResizeEnd = {
            resizeDraft?.takeIf { it != startSpan }?.let { span ->
              resizeSnapshot?.let { expected ->
                HomeSlots.resizeForEditing(expected, widget.key, span)?.let { onChanged(expected, it) }
                    ?: context.showHomeLayoutRefusal("No room to resize here. Move it or free a spot first.")
              }
            }
            resizeDraft = null; resizeSnapshot = null; resizeAccum = Offset.Zero
          }, onResizeCancel = { resizeDraft = null; resizeSnapshot = null; resizeAccum = Offset.Zero })
        }
      }
      if (showTimers) ApprovedHomeTimersPage { showTimers = false }
    }
    }
}

/**
 * Customize-only drag grip: touching it and moving starts the drag at once, with no long press.
 * A long press anywhere on the card still works; the grip is the reliable, discoverable handle.
 */
@Composable
private fun ApprovedWidgetDragGrip(drag: Modifier, modifier: Modifier = Modifier) {
  val dots = ApprovedHomePalette.Text
  Box(modifier.size(64.dp).clip(CircleShape).background(ApprovedHomePalette.Chrome).approvedOutline(CircleShape)
      .semantics { contentDescription = "Drag to move" }.then(drag),
      contentAlignment = Alignment.Center) {
    Canvas(Modifier.size(26.dp)) {
      val r = 2.6.dp.toPx()
      for (column in 0..1) for (row in 0..2) {
        drawCircle(dots, r, androidx.compose.ui.geometry.Offset(
            size.width * (.32f + column * .36f), size.height * (.18f + row * .32f)))
      }
    }
  }
}

/** The card's name for its Customize actions: the widget kind, or the hosted app widget's own label. */
private fun widgetSpokenName(widget: HomeWidgetStore.HomeWidget, manager: AppWidgetManager, context: Context): String =
    if (!widget.isAppWidget) customWidgetLabel(widget.kind)
    else runCatching { manager.getAppWidgetInfo(widget.appWidgetId)?.loadLabel(context.packageManager) }
        .onFailure { android.util.Log.w("HomeWidgetGrid", "app widget label unavailable", it) }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: "App widget"

@Composable
private fun ApprovedWidgetAction(label: String, widgetName: String, modifier: Modifier, onClick: () -> Unit) {
  // Every card in Customize has these four; the spoken name says which card ("Move Weather").
  val spoken = HomeRefinements.widgetEditSpoken(label, widgetName)
  Box(modifier.heightIn(min = 52.dp)
      .tvFocusable(RoundedCornerShape(12.dp), focusScale = 1f, onClick = onClick).ccSpeak(spoken)
      .padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
    Text(label, color = ApprovedHomePalette.Accent, fontSize = 18.sp, lineHeight = 26.sp, maxLines = 1,
        overflow = TextOverflow.Ellipsis)
  }
}

@Composable
private fun ApprovedWidgetGrid(
    visible: List<HomeWidgetStore.HomeWidget>,
    gridModifier: Modifier,
    widgets: List<HomeWidgetStore.HomeWidget>,
    editMode: Boolean,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    onChanged: HomeWidgetChange,
    onEnterEdit: () -> Unit,
    onRemove: (HomeWidgetStore.HomeWidget) -> Unit,
    pageIndex: Int,
    fixed: List<ApprovedWidgetPacking.Placement>?,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val spans = remember(visible) { HomePresentation.spans(visible) }
  var move by remember { mutableStateOf<HomeWidgetStore.HomeWidget?>(null) }
  val addFlow = LocalHomeAddFlow.current
  val emptySlots = remember(widgets, pageIndex, editMode) {
    if (editMode && addFlow != null) HomeSlots.emptySlots(widgets, pageIndex) else emptyList()
  }
  val addDecor: (@Composable BoxScope.(ApprovedSlotGeometry) -> Unit)? =
      if (addFlow == null || (!editMode && addFlow.ring == null)) null
      else { geometry -> HomeAddDecor(geometry, addFlow, pageIndex, emptySlots, HomeWidgetPreviewRenderer) }
  // A shared note (Share to Portal) takes the first empty slot on page 1 outside Customize.
  val note = LocalHomeNote.current
  val noteSlot = remember(widgets, pageIndex, editMode, note?.note?.id) {
    if (note == null || editMode || pageIndex != 0) null else HomeSlots.emptySlots(widgets, 0).firstOrNull()
  }
  val decor: (@Composable BoxScope.(ApprovedSlotGeometry) -> Unit)? =
      if (addDecor == null && noteSlot == null) null
      else { geometry ->
        addDecor?.invoke(this, geometry)
        if (note != null && noteSlot != null) {
          HomeNoteCard(note, geometry.cell(noteSlot.column, noteSlot.row * 2, 1, 2))
        }
      }
  ApprovedPackedWidgetGrid(visible, spans, fixed, editMode, gridModifier, decor = decor,
      reservedLeftHalfRows = noteSlot?.takeIf { it.column < CompactWidgetGrid.FOLDED_COLUMNS }?.let { it.row * 2 + 2 } ?: 0,
      canDrop = { key, column, row ->
        HomeSlots.moveForEditing(widgets, key, HomeWidgetStore.Slot(pageIndex, column, row)) != null
      },
      onDrop = { key, column, row ->
        val moved = HomeSlots.moveForEditing(widgets, key, HomeWidgetStore.Slot(pageIndex, column, row))
        if (moved == null) context.showHomeLayoutRefusal("That spot is taken. Move or resize the other widget first.")
        else if (moved != widgets) onChanged(widgets, moved)
      }) { widget, cell, span, drag, grip ->
    ApprovedWidgetCell(widget, cell, widgets, editMode, host, manager, onChanged, onEnterEdit,
        onRemove, span.columns to span.rows, onMove = { move = widget }, dragHandle = drag, gripHandle = grip)
  }
  move?.let { selected ->
    val spots = remember(selected, widgets) { HomeSlots.freeSpotsForEditing(widgets, selected.key) }
    val pages = remember(widgets) { HomePresentation.page(HomeSlots.forEditing(widgets), 0).count }
    ApprovedHomeDialog("Move ${customWidgetLabel(selected.kind)}", onDismiss = { move = null }) {
      Text(if (spots.isEmpty()) "No empty spot fits this size. Make it smaller or free a spot first."
          else "Choose an empty spot, including on another page.",
          color = ApprovedHomePalette.Secondary, fontSize = 23.sp)
      spots.forEach { spot ->
        val label = if (spot.page >= pages) "New page · row ${spot.row + 1} · column ${spot.column + 1}"
            else "Page ${spot.page + 1} · row ${spot.row + 1} · column ${spot.column + 1}"
        ApprovedHomeTextButton(label) {
          HomeSlots.moveForEditing(widgets, selected.key, spot)?.let { onChanged(widgets, it) }
          move = null
        }
      }
    }
  }
}

/** Short, non-blocking explanation when Customize refuses a move or resize that would overlap. */
private fun android.content.Context.showHomeLayoutRefusal(message: String) {
  android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
}

/** Home and Customize keep the same page, identities, measurements and placement rules. */
@Composable
internal fun ApprovedWidgetPage(
    displayed: HomePresentation.Page,
    widgets: List<HomeWidgetStore.HomeWidget>,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    onChanged: HomeWidgetChange,
    onEnterEdit: () -> Unit,
    onRemove: (HomeWidgetStore.HomeWidget) -> Unit,
    editMode: Boolean = false,
) {
  val pageWidgets = remember(displayed, widgets) { HomePresentation.pageWidgets(widgets, displayed) }
  ApprovedWidgetGrid(pageWidgets, Modifier.fillMaxSize(), widgets, editMode, host, manager,
      onChanged, onEnterEdit, onRemove, pageIndex = displayed.index, fixed = displayed.placements)
}

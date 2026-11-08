/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import kotlin.math.roundToInt

private val ROW = 100.dp
private val COL_GAP = 40.dp

/** Right pane: open items in two columns (long-press a name to drag), then the Done section. */
@Composable
internal fun HouseholdListColumns(view: HouseholdListView, toast: HouseholdListToast, singleColumn: Boolean,
                                  onOpenItem: (String) -> Unit, onClearDone: () -> Unit, modifier: Modifier = Modifier) {
  val palette = LocalApprovedPalette.current
  val open = view.list.open()
  val done = view.list.done()
  var doneOpen by remember { mutableStateOf(true) }
  val cols = if (singleColumn) 1 else 2
  Column(modifier.verticalScroll(rememberScrollState())) {
    if (open.isEmpty() && done.isEmpty()) {
      Text("The list is empty", color = palette.text, fontSize = 34.sp, lineHeight = 42.sp)
      Spacer(Modifier.height(10.dp))
      Text("Anything you add shows up on every Portal and in Portal Toolkit on everyone’s phone.", color = palette.secondary,
          fontSize = 22.sp, lineHeight = 30.sp)
      return@Column
    }
    if (open.isEmpty()) Text("Everything’s ticked off", color = palette.text, fontSize = 34.sp, lineHeight = 42.sp,
        modifier = Modifier.padding(vertical = 24.dp))
    else ItemGrid(open, cols, view, toast, onOpenItem, draggable = true)
    if (done.isEmpty()) return@Column
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
      Row(Modifier.heightIn(min = 64.dp).tvFocusableRow { doneOpen = !doneOpen }.padding(horizontal = 8.dp),
          verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (doneOpen) "⌄" else "›", color = palette.secondary, fontSize = 26.sp)
        Text("Done (${done.size})", color = palette.secondary, fontSize = 24.sp, lineHeight = 30.sp)
      }
      Spacer(Modifier.weight(1f))
      ApprovedHomePillButton("Clear done", onClick = onClearDone)
    }
    if (doneOpen) ItemGrid(done, cols, view, toast, onOpenItem, draggable = false)
  }
}

@Composable
private fun ItemGrid(items: List<HouseholdItem>, cols: Int, view: HouseholdListView, toast: HouseholdListToast,
                     onOpenItem: (String) -> Unit, draggable: Boolean) {
  val context = LocalContext.current
  val density = LocalDensity.current
  val rows = (items.size + cols - 1) / cols
  var dragging by remember { mutableStateOf<String?>(null) }
  var drag by remember { mutableStateOf(Offset.Zero) }
  BoxWithConstraints(Modifier.fillMaxWidth().height(ROW * rows)) {
    val cellW = (maxWidth - COL_GAP * (cols - 1)) / cols
    val pitchX = with(density) { (cellW + COL_GAP).toPx() }
    val rowPx = with(density) { ROW.toPx() }
    items.forEachIndexed { index, item ->
      key(item.id) {
        val col = index / rows
        val row = index % rows
        val isDragged = dragging == item.id
        val base = IntOffset((col * pitchX).roundToInt(), (row * rowPx).roundToInt())
        val offset = if (isDragged) base + IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) else base
        val dragModifier = if (!draggable) Modifier else Modifier.pointerInput(item.id, items) {
          detectDragGesturesAfterLongPress(
              onDragStart = { dragging = item.id; drag = Offset.Zero },
              onDrag = { change, amount -> change.consume(); drag += amount },
              onDragCancel = { dragging = null },
              onDragEnd = {
                val x = col * pitchX + pitchX / 2 + drag.x
                val y = row * rowPx + rowPx / 2 + drag.y
                val target = ((x / pitchX).toInt().coerceIn(0, cols - 1) * rows + (y / rowPx).toInt().coerceIn(0, rows - 1))
                    .coerceIn(0, items.size - 1)
                dragging = null
                if (target != index) {
                  val rest = items.filter { it.id != item.id }
                  context.runListCommand(toast) { c, s, now -> c.moveBefore(s, item.id, rest.getOrNull(target)?.id, now) }
                }
              })
        }
        Box(Modifier.offset { offset }.width(cellW).height(ROW).zIndex(if (isDragged) 1f else 0f)
            .then(if (isDragged) Modifier.shadow(12.dp, RoundedCornerShape(18.dp)) else Modifier)) {
          BigRow(item, view, toast, onOpenItem, dragModifier)
        }
      }
    }
  }
}

@Composable
private fun BigRow(item: HouseholdItem, view: HouseholdListView, toast: HouseholdListToast,
                   onOpenItem: (String) -> Unit, dragModifier: Modifier) {
  val context = LocalContext.current
  val palette = LocalApprovedPalette.current
  val fresh = view.sync.arrivalLine(item, view.now)
  val note = HouseholdListNotes.note(item)
  val showWho = remember(view.list) { HouseholdListConfig.load(context).showWho }
  val done = item.done.value
  Row(Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))
      .background(if (fresh != null) HouseholdListColors.Green.copy(alpha = .12f) else palette.background)
      .padding(start = if (fresh != null) 14.dp else 0.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    Box(Modifier.size(68.dp).clip(RoundedCornerShape(16.dp))
        .tvFocusableRow { context.runListCommand(toast) { c, s, now -> c.toggle(s, item.id, now) } }
        .semantics { contentDescription = "${if (done) "Untick" else "Tick"} ${item.text.value}" },
        contentAlignment = Alignment.Center) { HouseholdCheckbox(done, fresh != null, size = 48.dp) }
    Column(Modifier.weight(1f).fillMaxHeight().then(dragModifier).tvFocusableRow { onOpenItem(item.id) },
        verticalArrangement = Arrangement.Center) {
      Text(item.text.value, color = if (done) palette.secondary else palette.text, fontSize = 30.sp, lineHeight = 38.sp,
          maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = if (done) TextDecoration.LineThrough else null)
      val (meta, color) = when {
        fresh != null -> fresh to HouseholdListColors.Green
        note != null -> note.text to HouseholdListColors.Amber
        !showWho -> null to palette.secondary
        done -> "Ticked by ${item.done.stamp.by}, ${HouseholdListTime.short(item.done.stamp.at, view.now)}" to palette.secondary
        else -> "${item.added.by}, ${HouseholdListTime.short(item.added.at, view.now)}" to palette.secondary
      }
      meta?.let { Text(it, color = color, fontSize = 20.sp, lineHeight = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    Box(Modifier.size(68.dp).clip(RoundedCornerShape(16.dp))
        .tvFocusableRow { context.runListCommand(toast) { c, s, now -> c.remove(s, item.id, now) } }
        .semantics { contentDescription = "Remove ${item.text.value}" }, contentAlignment = Alignment.Center) {
      ApprovedHomeIcon("x", Modifier.size(28.dp), tint = palette.secondary)
    }
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/**
 * The Home "Household list" widget (Tall 1×2: count + top 4; Small 1×1: top 2). Tapping a box
 * ticks the item (toast with Undo); tapping anywhere else opens the full list; the header's
 * Settings opens the list settings. Items that arrived from another device in the last minute
 * get a green ring and "Just now, Rohit’s Pixel". The last line is the sync state.
 *
 * Integrator: render for [HouseholdListWidgetKind.KIND] (and the legacy `todo` kind) as
 * `HouseholdListWidget(modifier, small = widget.spanY < 2)`.
 */
@Composable
internal fun HouseholdListWidget(modifier: Modifier = Modifier, small: Boolean = false) {
  val context = LocalContext.current
  val view = rememberHouseholdListView()
  val toast = remember { HouseholdListToast() }
  val display = remember(view.list) { HouseholdListConfig.load(context) }
  val open = { HouseholdListActivity.open(context) }
  val palette = LocalApprovedPalette.current
  val rows = if (small) 2 else 4
  Column(modifier.tvFocusableRow { open() }.padding(30.dp).semantics { contentDescription = "Open the household list" }) {
    ApprovedHomeWidgetHeading("Household list", "todo") { HouseholdListActivity.open(context, settings = true) }
    Spacer(Modifier.height(22.dp))
    val status = view.status
    val openItems = view.list.open()
    val doneItems = view.list.done()
    when {
      status.kind == HouseholdListSyncStatus.Kind.FIRST -> {
        Text(status.long, color = palette.secondary, fontSize = 22.sp, lineHeight = 30.sp)
        Spacer(Modifier.height(18.dp))
        repeat(rows) { SkeletonRow() }
      }
      openItems.isEmpty() && !(display.widgetShowsDone && doneItems.isNotEmpty()) -> EmptyWidgetBody(doneItems.size, open)
      else -> {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("${openItems.size}", color = palette.text, fontSize = 64.sp, lineHeight = 70.sp)
          Text("to get", color = palette.secondary, fontSize = 22.sp, lineHeight = 36.sp)
        }
        Spacer(Modifier.height(8.dp))
        openItems.take(rows).forEach { item ->
          key(item.id) { WidgetRow(item, view, toast) }
        }
        if (display.widgetShowsDone && openItems.size < rows) {
          doneItems.take(rows - openItems.size).forEach { item -> key(item.id) { WidgetRow(item, view, toast) } }
        }
        if (openItems.size > rows) {
          Spacer(Modifier.height(12.dp))
          Text("and ${openItems.size - rows} more", color = palette.secondary, fontSize = 20.sp, lineHeight = 26.sp)
        }
      }
    }
    Spacer(Modifier.weight(1f))
    WidgetFooter(view, toast)
  }
}

@Composable
private fun WidgetRow(item: HouseholdItem, view: HouseholdListView, toast: HouseholdListToast) {
  val context = LocalContext.current
  val palette = LocalApprovedPalette.current
  val fresh = view.sync.arrivalLine(item, view.now)
  val kept = HouseholdListNotes.note(item) != null
  val shape = RoundedCornerShape(16.dp)
  Row(Modifier.fillMaxWidth().heightIn(min = 88.dp)
      .then(if (fresh != null) Modifier.border(3.dp, HouseholdListColors.Green, shape).padding(horizontal = 12.dp) else Modifier),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Box(Modifier.size(64.dp).clip(shape)
        .tvFocusableRow { context.runListCommand(toast) { c, s, now -> c.toggle(s, item.id, now) } }
        .semantics { contentDescription = "${if (item.done.value) "Untick" else "Tick"} ${item.text.value}" },
        contentAlignment = Alignment.Center) { HouseholdCheckbox(item.done.value, fresh != null) }
    Column(Modifier.weight(1f)) {
      Text(item.text.value, color = if (item.done.value) palette.secondary else palette.text, fontSize = 28.sp,
          lineHeight = 36.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
          textDecoration = if (item.done.value) TextDecoration.LineThrough else null)
      when {
        fresh != null -> Text(fresh, color = HouseholdListColors.Green, fontSize = 18.sp, lineHeight = 24.sp, maxLines = 1)
        kept -> Text("Kept after a conflict", color = HouseholdListColors.Amber, fontSize = 18.sp, lineHeight = 24.sp)
      }
    }
  }
}

@Composable
private fun EmptyWidgetBody(doneCount: Int, onAdd: () -> Unit) {
  val palette = LocalApprovedPalette.current
  Text(if (doneCount > 0) "All done" else "Nothing on the list", color = palette.text, fontSize = 34.sp, lineHeight = 42.sp)
  Spacer(Modifier.height(8.dp))
  Text(if (doneCount > 0) "$doneCount ticked off. Add the next thing here, on another Portal or in Calder Pocket."
       else "Add something here, on another Portal or in Calder Pocket on your phone.",
      color = palette.secondary, fontSize = 22.sp, lineHeight = 30.sp)
  Spacer(Modifier.height(18.dp))
  ApprovedHomePillButton("+  Add an item", onClick = onAdd)
}

/** Undo toast for a tick made on the widget, a sync notice, or the sync line. */
@Composable
private fun WidgetFooter(view: HouseholdListView, toast: HouseholdListToast) {
  val context = LocalContext.current
  val palette = LocalApprovedPalette.current
  val current = toast.current?.takeIf { view.now - it.second < HouseholdListSyncState.NOTICE_MS }
  LaunchedEffect(current) { if (current != null) { kotlinx.coroutines.delay(HouseholdListSyncState.NOTICE_MS); toast.clear() } }
  when {
    current != null -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.surface2)
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(current.first.text, color = palette.text, fontSize = 20.sp, lineHeight = 26.sp, maxLines = 2,
          overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      current.first.undo?.let { undo ->
        ApprovedHomeTextButton("Undo") { context.runListCommand(toast) { c, s, now -> c.undo(s, undo, now) } }
      }
    }
    view.notice != null -> Text(view.notice!!.text, color = palette.text, fontSize = 20.sp, lineHeight = 26.sp)
    else -> HouseholdSyncLine(view.status)
  }
}

@Composable
private fun SkeletonRow() {
  val palette = LocalApprovedPalette.current
  Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(palette.surface2))
    Box(Modifier.height(22.dp).fillMaxWidth(0.6f).clip(RoundedCornerShape(8.dp)).background(palette.surface2))
  }
}

/** Widget kind id the integrator adds to [HomeWidgetStore] (sizes: 1×2 Tall default, 1×1 Small). */
internal object HouseholdListWidgetKind {
  const val KIND = "householdlist"
  const val TITLE = "Household list"
}

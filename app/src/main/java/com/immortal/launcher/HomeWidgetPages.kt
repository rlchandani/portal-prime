/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.appwidget.AppWidgetHost
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import android.appwidget.AppWidgetManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Paging state is local: changing pages does not invalidate the launcher chrome or favorites. */
@Composable
internal fun ApprovedHomePagedWidgets(
    widgets: List<HomeWidgetStore.HomeWidget>,
    editMode: Boolean,
    compact: Boolean,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    onChanged: HomeWidgetChange,
    onEnterEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
  var pageIndex by remember { mutableStateOf(0) }
  var counterFocused by remember { mutableStateOf(false) }
  val navigation = remember { ApprovedHomePagerNavigation() }
  val addFlow = LocalHomeAddFlow.current
  val savedPages = remember(widgets) { HomePresentation.page(widgets, 0).count }
  val targetPage = addFlow?.targetPage
  // An empty page after the last while adding there ("+ new page" or a full layout).
  val extraPage = editMode && addFlow != null && (addFlow.newPage || (targetPage ?: -1) >= savedPages)
  val page = remember(widgets, pageIndex, extraPage) { HomePresentation.editingPage(widgets, pageIndex, extraPage) }
  LaunchedEffect(page.index) { pageIndex = page.index }
  val changePage: (Int) -> Unit = { index ->
    navigation.request(index)
    pageIndex = index
  }
  // The draft is placed on its target page: follow it there.
  LaunchedEffect(targetPage, extraPage) { if (targetPage != null && targetPage != pageIndex) changePage(targetPage) }
  val pageFull = remember(widgets, page.index, editMode) {
    editMode && addFlow != null && HomeSlots.emptySlots(widgets, page.index).isEmpty()
  }
  Column(modifier) {
    ApprovedWidgetArea(widgets, page, editMode, host, manager, onChanged,
        onEnterEdit = onEnterEdit, onPageChanged = changePage, navigation = navigation,
        preserveCounterFocus = counterFocused,
        modifier = Modifier.weight(1f).fillMaxWidth(), extraPage = extraPage)
    // A slim row under the widgets for page dots (and Add widget while customizing). No heading or
    // "All apps" — the dock's Apps tab already does that. The row is omitted when it would be empty.
    val showDots = HomeRefinements.showsPageIndicator(page.count)
    if (showDots || editMode) {
      Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(if (editMode) 52.dp else 40.dp)) {
        if (editMode) ApprovedHomeTextButton("Add widget", modifier = Modifier.align(Alignment.CenterStart)) {
          // Same picker as tapping a slot: the first empty slot here, or a new page when full.
          addFlow?.openPicker(HomeSlots.firstEmptySlot(widgets, page.index))
        }
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
          if (showDots) ApprovedWidgetPageIndicator(page.index, page.count, changePage,
              onFocusChanged = { counterFocused = it })
          // Fallback (approved): a full page offers "+ new page" after the last dot.
          if (pageFull && addFlow?.draft == null) ApprovedHomeTextButton("+ New page") {
            addFlow?.newPage = true
            changePage(savedPages)
          }
        }
      }
    } else {
      Spacer(Modifier.height(10.dp))
    }
  }
}

/** Page dots plus "n of m"; focusable with Left/Right and accessibility actions to change page. */
@Composable
private fun ApprovedWidgetPageIndicator(
    index: Int,
    count: Int,
    changePage: (Int) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
  Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
    Row(Modifier.clip(RoundedCornerShape(18.dp)).semantics {
          contentDescription = "Widget page ${index + 1} of $count"
          customActions = buildList {
            if (index > 0) add(CustomAccessibilityAction("Previous widget page") {
              changePage(index - 1); true
            })
            if (index < count - 1) add(CustomAccessibilityAction("Next widget page") {
              changePage(index + 1); true
            })
          }
        }.onPreviewKeyEvent {
          if (it.type != KeyEventType.KeyDown) false
          else {
            val target = when (it.key) {
              Key.DirectionRight -> index + 1
              Key.DirectionLeft -> index - 1
              else -> return@onPreviewKeyEvent false
            }.coerceIn(0, count - 1)
            if (target == index) false
            else {
              changePage(target)
              true
            }
          }
        }.onFocusChanged { onFocusChanged(it.isFocused) }.focusable()
        .padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      repeat(count) { dot ->
        val active = dot == index
        Box(Modifier.height(8.dp).width(if (active) 22.dp else 8.dp).clip(RoundedCornerShape(4.dp))
            .background(if (active) ApprovedHomePalette.Text else ApprovedHomePalette.Secondary.copy(alpha = .45f))
            .semantics { contentDescription = "Widget page ${dot + 1}"; selected = active }
            .clickable(role = Role.Tab, onClickLabel = "Show widget page ${dot + 1}") { changePage(dot) })
      }
      Spacer(Modifier.width(6.dp))
      Text("${index + 1} of $count", color = ApprovedHomePalette.Secondary, fontSize = 20.sp,
          lineHeight = 26.sp)
    }
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.input.key.key
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop

internal data class PendingWidgetAdd(
    val appWidgetId: Int,
    /** The provider's flattened component; the bound id's own info is read back when completing. */
    val provider: String,
    val spanX: Int,
    val spanY: Int,
    /** Add-flow target: saved exactly here when it still fits, else auto-placed. */
    val slot: HomeWidgetStore.Slot? = null,
    val span: Pair<Int, Int>? = null,
) {
  companion object {
    fun of(id: Int, entry: WidgetProviderEntry, info: AppWidgetProviderInfo,
           slot: HomeWidgetStore.Slot? = null, span: Pair<Int, Int>? = null) =
        PendingWidgetAdd(id, info.provider.flattenToString(), entry.spanX, entry.spanY, slot, span)

    /**
     * Saved across a recreate or process death while the system bind/configure screen is in
     * front, so its result still completes the add (and a cancel still frees the allocated id).
     */
    val Saver = androidx.compose.runtime.saveable.listSaver<PendingWidgetAdd?, Any>(
        save = { p -> if (p == null) emptyList() else listOf(p.appWidgetId, p.provider, p.spanX, p.spanY,
            p.slot?.page ?: -1, p.slot?.column ?: -1, p.slot?.row ?: -1, p.span?.first ?: -1, p.span?.second ?: -1) },
        restore = { v -> if (v.size < 9) null else PendingWidgetAdd(v[0] as Int, v[1] as String, v[2] as Int, v[3] as Int,
            (v[4] as Int).takeIf { it >= 0 }?.let { HomeWidgetStore.Slot(it, v[5] as Int, v[6] as Int) },
            (v[7] as Int).takeIf { it >= 0 }?.let { it to (v[8] as Int) }) })
  }
}

/** Bounded pages share the same grid in Home and Customize. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ApprovedWidgetArea(
    widgets: List<HomeWidgetStore.HomeWidget>,
    page: HomePresentation.Page,
    editMode: Boolean,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    onChanged: HomeWidgetChange,
    onEnterEdit: () -> Unit,
    onPageChanged: (Int) -> Unit,
    navigation: ApprovedHomePagerNavigation,
    preserveCounterFocus: Boolean,
    modifier: Modifier = Modifier,
    extraPage: Boolean = false,
) {
  var remove by remember { mutableStateOf<HomeWidgetStore.HomeWidget?>(null) }
  val onRemove: (HomeWidgetStore.HomeWidget) -> Unit = remember { { remove = it } }
  val configuration = androidx.compose.ui.platform.LocalConfiguration.current
  val isTv = configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK ==
      android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
  if (!isTv) {
    val prepared = remember(widgets, extraPage) {
      List(HomePresentation.editingPage(widgets, 0, extraPage).count) {
        HomePresentation.editingPage(widgets, it, extraPage)
      }
    }
    val placing = LocalHomeAddFlow.current?.draft != null
    val homeActive = LocalApprovedLauncherPageActive.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val currentPage = rememberUpdatedState(page)
    val pageChanged = rememberUpdatedState(onPageChanged)
    val slide = remember { Animatable(page.index.toFloat()) }
    // The page the last slide finished on. Page work (producers, lifecycle, resume reads) and the
    // next neighbor's composition follow it, so they start after the slide, not in its first frame.
    var settled by remember { mutableIntStateOf(page.index) }
    val swipeThreshold = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(page.index, page.count) {
      if (!preserveCounterFocus) focus.clearFocus(force = true)
      // Removed widgets or numbered Move can skip pages outside the retained neighbors.
      if (slide.value > prepared.lastIndex || abs(slide.value - page.index) > 1f)
        slide.snapTo(page.index.toFloat())
      else slide.animateTo(page.index.toFloat(), tween(180, easing = EaseOutCubic))
      settled = page.index
    }
    BoxWithConstraints(modifier.clipToBounds().semantics { contentDescription = "Home widgets" }
        .then(if (!homeActive || page.count < 2 || placing) Modifier else Modifier.pointerInput(page.count) {
          var distance = 0f
          detectHorizontalDragGestures(
              onDragStart = { distance = 0f },
              onDragCancel = { distance = 0f },
              onDragEnd = {
                if (abs(distance) >= swipeThreshold) {
                  val selected = currentPage.value
                  val next = HomePresentation.pageAfterSwipe(selected, swipeRight = distance > 0f)
                  if (next != selected.index) pageChanged.value(next)
                }
                distance = 0f
              },
          ) { change, delta ->
            change.consume()
            distance += delta
          }
        })) {
      val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
      for (index in HomePagerWindow.composed(page.index, settled, prepared.lastIndex)) key(index) {
        val displayed = prepared[index]
        val selected = index == page.index
        // Input, focus and semantics follow the target at once; page work waits for the settle.
        val active = homeActive && selected
        val working = homeActive && HomePagerWindow.working(index, settled)
        // Deferred placement caches the display list; the viewport clips off-screen pages.
        Box(Modifier.fillMaxSize().absoluteOffset {
          IntOffset((HomePresentation.pageOffset(index, slide.value) * widthPx).roundToInt(), 0)
        }.then(if (active) Modifier else Modifier.clearAndSetSemantics { })
            .pointerInput(active) {
              if (!active) awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
              }
            }
            .focusProperties { canFocus = active }) {
          ApprovedWidgetPageScope(working) {
            ApprovedWidgetPage(displayed, widgets, host, manager, onChanged,
                onEnterEdit, onRemove, editMode && selected)
          }
        }
      }
    }
  } else {
    val pager = rememberPagerState(initialPage = page.index) { page.count }
    SideEffect { navigation.reflectParent(page.index) }
    LaunchedEffect(page.index) {
      if (pager.currentPage != page.index) pager.scrollToPage(page.index)
      navigation.acknowledge(pager.currentPage)
    }
    val pageChanged = rememberUpdatedState(onPageChanged)
    LaunchedEffect(pager) {
      snapshotFlow { pager.currentPage }.drop(1).collect { index ->
        if (index == pager.currentPage)
          navigation.observe(index)?.let { pageChanged.value(it) }
      }
    }
    HorizontalPager(pager, modifier.clipToBounds().semantics { contentDescription = "Home widgets" }, userScrollEnabled = false,
        beyondViewportPageCount = 1) { index ->
      val active = LocalApprovedLauncherPageActive.current && index == pager.currentPage
      ApprovedWidgetPageScope(active) {
        ApprovedWidgetPage(HomePresentation.editingPage(widgets, index, extraPage), widgets, host, manager,
            onChanged, onEnterEdit, onRemove, editMode && active)
      }
    }
  }
  remove?.let { widget ->
    ApprovedHomeDialog("Remove ${customWidgetLabel(widget.kind)}?", onDismiss = { remove = null }) {
      Text("You can add it again from the widget library.", color = ApprovedHomePalette.Secondary, fontSize = 23.sp)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        ApprovedHomeTextButton("Cancel") { remove = null }
        ApprovedHomeTextButton("Remove") {
          synchronized(RemoteCompanionStore.mutationLock) {
            if (onChanged(widgets, HomeWidgetStore.without(widgets, widget.key)) && widget.isAppWidget)
              runCatching { host.deleteAppWidgetId(widget.appWidgetId) }
          }
          remove = null
        }
      }
    }
  }
}

@Composable
internal fun ApprovedHostedWidget(
    widget: HomeWidgetStore.HomeWidget,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    editing: Boolean,
) {
  val active = LocalApprovedLauncherPageActive.current && LocalApprovedWidgetPageInput.current
  val info = remember(widget.appWidgetId) { manager.getAppWidgetInfo(widget.appWidgetId) }
  if (info == null) {
    Column(Modifier.padding(30.dp)) {
      Text("Widget unavailable", color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 35.sp)
      Text(widget.providerPackage, color = ApprovedHomePalette.Secondary, fontSize = 22.sp,
          lineHeight = 30.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(top = 12.dp))
    }
  } else {
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val width = maxWidth.value.roundToInt()
      val height = maxHeight.value.roundToInt()
      LaunchedEffect(widget.appWidgetId, width, height) {
        manager.updateAppWidgetOptions(widget.appWidgetId, Bundle().apply {
          putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
        })
      }
      AndroidView(factory = { host.createView(it, widget.appWidgetId, info).apply {
        setAppWidget(widget.appWidgetId, info)
        setPadding(0, 0, 0, 0)
      } }, update = {
        it.isEnabled = active && !editing
        it.descendantFocusability = if (active && !editing) android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            else android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (!active) it.findFocus()?.clearFocus()
      }, modifier = Modifier.fillMaxSize())
      if (editing) Box(Modifier.fillMaxSize().background(Color(0x22000000)))
    }
  }
}

internal fun updateWidgetSizeOptions(
    manager: AppWidgetManager,
    widget: HomeWidgetStore.HomeWidget,
    tileDp: Dp,
) {
  val width =
      (tileDp.value * widget.spanX + 16f * (widget.spanX - 1).coerceAtLeast(0)).roundToInt()
  val height =
      (tileDp.value * widget.spanY + 20f * (widget.spanY - 1).coerceAtLeast(0)).roundToInt()
  runCatching {
    manager.updateAppWidgetOptions(
        widget.appWidgetId,
        Bundle().apply {
          putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
          putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
        })
  }
}

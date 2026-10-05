/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Presentation only: never rewrites widget IDs, sizes, order, or enabled services. */
internal object HomePresentation {
  val defaultFavoritePackages = listOf(
      listOf("org.chromium.chrome", "com.android.chrome", "com.android.browser"),
      listOf("org.videolan.vlc"),
      listOf("org.schabi.newpipe"),
      listOf("io.homeassistant.companion.android.minimal", "io.homeassistant.companion.android"),
      listOf("me.zhanghai.android.files"),
      listOf("org.fdroid.fdroid"),
  )
  data class Page(
      val hero: HomeWidgetStore.HomeWidget?,
      val widgets: List<HomeWidgetStore.HomeWidget>,
      val index: Int,
      val count: Int,
  )

  fun page(widgets: List<HomeWidgetStore.HomeWidget>, requested: Int): Page {
    val hero = widgets.firstOrNull { it.kind == HomeWidgetStore.KIND_PHOTO_MEMORY }
    // Additional photo widgets are ordinary paged items, so no configured identity disappears.
    val remaining = widgets.filterNot { it.key == hero?.key }
    val count = ((remaining.size + 3) / 4).coerceAtLeast(1)
    val index = requested.coerceIn(0, count - 1)
    return Page(hero, remaining.drop(index * 4).take(4), index, count)
  }

  fun usesCustomSizing(widgets: List<HomeWidgetStore.HomeWidget>): Boolean =
      widgets.any {
        val default = when (it.kind) {
          HomeWidgetStore.KIND_TODAY,
          HomeWidgetStore.KIND_QUICK_CONTROLS,
          HomeWidgetStore.KIND_PHOTO_MEMORY -> 2 to 1
          else -> 2 to 2
        }
        it.isAppWidget || it.explicitSize || (it.spanX to it.spanY) != default
      }

  /** Resolve legacy dashboard dimensions once, identically for Home and Customize. */
  fun spans(widgets: List<HomeWidgetStore.HomeWidget>): List<ApprovedWidgetPacking.Span> {
    return widgets.map { widget ->
      val default = when (widget.kind) {
        HomeWidgetStore.KIND_TODAY, HomeWidgetStore.KIND_QUICK_CONTROLS,
        HomeWidgetStore.KIND_PHOTO_MEMORY -> 2 to 1
        else -> 2 to 2
      }
      when {
        widget.explicitSize || widget.isAppWidget || (widget.spanX to widget.spanY) != default ->
          ApprovedWidgetPacking.Span(widget.spanX, widget.spanY)
        widget.kind == HomeWidgetStore.KIND_PHOTO_MEMORY ->
          ApprovedWidgetPacking.Span(2, 4)
        else -> ApprovedWidgetPacking.Span(1, 2)
      }
    }
  }

  /** Stable identity moves also work across pages; headers never enter the saved order. */
  fun move(
      widgets: List<HomeWidgetStore.HomeWidget>,
      source: String,
      target: String,
  ): List<HomeWidgetStore.HomeWidget> {
    val from = widgets.indexOfFirst { it.key == source }
    val to = widgets.indexOfFirst { it.key == target }
    if (from < 0 || to < 0 || from == to) return widgets
    return widgets.toMutableList().apply { add(to, removeAt(from)) }
  }
}

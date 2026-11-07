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
      /** Slot mode only: exact placements aligned with [widgets]; null = order-based packing. */
      val placements: List<ApprovedWidgetPacking.Placement>? = null,
  )

  /** Saved slots switch Home to free placement; otherwise the original order-based paging applies. */
  fun page(widgets: List<HomeWidgetStore.HomeWidget>, requested: Int): Page {
    if (!HomeSlots.hasSlots(widgets)) return legacyPage(widgets, requested)
    val pages = HomeSlots.resolve(widgets, spans(widgets))
    val index = requested.coerceIn(0, pages.size - 1)
    val placed = pages[index]
    return Page(null, placed.map { it.widget }, index, pages.size, placed.map { it.placement })
  }

  /**
   * Customize's page: [page], plus (when [extraPage]) one empty page after the last so the add flow
   * can place a widget there before anything is saved on it.
   */
  fun editingPage(widgets: List<HomeWidgetStore.HomeWidget>, requested: Int, extraPage: Boolean): Page {
    val base = page(widgets, requested)
    if (!extraPage) return base
    return if (requested >= base.count) Page(null, emptyList(), base.count, base.count + 1, emptyList())
    else base.copy(count = base.count + 1)
  }

  fun legacyPage(widgets: List<HomeWidgetStore.HomeWidget>, requested: Int): Page {
    val hero = widgets.firstOrNull { it.kind == HomeWidgetStore.KIND_PHOTO_MEMORY }
    // Additional photo widgets are ordinary paged items, so no configured identity disappears.
    val remaining = widgets.filterNot { it.key == hero?.key }
    if (hero == null) {
      val count = ((remaining.size + PAGE_BESIDE_HERO - 1) / PAGE_BESIDE_HERO).coerceAtLeast(1)
      val index = requested.coerceIn(0, count - 1)
      return Page(null, remaining.drop(index * PAGE_BESIDE_HERO).take(PAGE_BESIDE_HERO), index, count)
    }
    // The photo is the first page's hero only; later pages use the whole grid instead of repeating
    // the same photo beside four widgets (2026-11-06).
    val later = (remaining.size - PAGE_BESIDE_HERO).coerceAtLeast(0)
    val count = 1 + (later + PAGE_FULL_GRID - 1) / PAGE_FULL_GRID
    val index = requested.coerceIn(0, count - 1)
    return if (index == 0) Page(hero, remaining.take(PAGE_BESIDE_HERO), 0, count)
    else Page(null, remaining.drop(PAGE_BESIDE_HERO + (index - 1) * PAGE_FULL_GRID).take(PAGE_FULL_GRID),
        index, count)
  }

  /** Widgets that fit beside the photo hero, and on a page without one. */
  private const val PAGE_BESIDE_HERO = 4
  /** Widgets on a later page that no longer shares space with the photo. */
  private const val PAGE_FULL_GRID = 8

  /** Pages increase to the right: a left swipe advances, a right swipe returns. */
  fun pageAfterSwipe(page: Page, swipeRight: Boolean): Int =
      (page.index + if (swipeRight) -1 else 1).coerceIn(0, page.count - 1)

  /** Offset in viewport widths; both neighboring pages move with the finger's direction. */
  fun pageOffset(pageIndex: Int, scrollPosition: Float): Float = pageIndex - scrollPosition

  /** Sharing the photo across pages must not override its saved relative position. */
  fun pageWidgets(widgets: List<HomeWidgetStore.HomeWidget>, page: Page): List<HomeWidgetStore.HomeWidget> {
    if (page.placements != null) return page.widgets
    val positions = widgets.mapIndexed { index, widget -> widget.key to index }.toMap()
    return (listOfNotNull(page.hero) + page.widgets).sortedBy { positions[it.key] ?: Int.MAX_VALUE }
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
          slotSpan(widget.spanX, widget.spanY)
        widget.kind == HomeWidgetStore.KIND_PHOTO_MEMORY ->
          ApprovedWidgetPacking.Span(2, 4)
        else -> ApprovedWidgetPacking.Span(1, 2)
      }
    }
  }

  /**
   * Home is a 2-row × 4-column grid of slots. Spans are stored in 4×4 half-row units, so a slot is
   * 1 column × 2 rows: width clamps to 1–4 columns and height rounds up to 1 or 2 whole slots.
   * Display only; a legacy half-height save is never rewritten until the user resizes it.
   */
  fun slotSpan(columns: Int, rows: Int): ApprovedWidgetPacking.Span =
      ApprovedWidgetPacking.Span(columns.coerceIn(1, 4), if (rows <= 2) 2 else 4)

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

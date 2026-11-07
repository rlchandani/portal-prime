/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class ApprovedWidgetPageGeometryTest {
  private fun widget(id: String, kind: String = HomeWidgetStore.KIND_WEATHER, x: Int = 2, y: Int = 2) =
      HomeWidgetStore.HomeWidget(kind = kind, customId = id, spanX = x, spanY = y)

  @Test fun defaultDashboard_preservesHeroAndTwoColumnsWithoutRewritingSavedSizes() {
    val photo = widget("photo", HomeWidgetStore.KIND_PHOTO_MEMORY, y = 1)
    val widgets = listOf(photo, widget("today", HomeWidgetStore.KIND_TODAY, y = 1),
        widget("ha", HomeWidgetStore.KIND_HA), widget("calendar", HomeWidgetStore.KIND_CALENDAR), widget("weather"))
    val spans = HomePresentation.spans(widgets)
    val packed = ApprovedWidgetPacking.pack(spans)
    assertEquals(listOf(2 to 4, 1 to 2, 1 to 2, 1 to 2, 1 to 2), spans.map { it.columns to it.rows })
    assertEquals(listOf(0 to 0, 2 to 0, 3 to 0, 2 to 2, 3 to 2), packed.map { it.column to it.row })
    assertEquals(2 to 1, photo.spanX to photo.spanY)
  }

  @Test fun customShortNeighbors_stackBelowEachOtherBesideTallPhoto() {
    val widgets = listOf(widget("photo", HomeWidgetStore.KIND_PHOTO_MEMORY, y = 1),
        widget("short1", HomeWidgetStore.KIND_TODAY, y = 1).copy(explicitSize = true), widget("short2", y = 1))
    val packed = ApprovedWidgetPacking.pack(HomePresentation.spans(widgets))
    assertEquals(2 to 0, packed[1].column to packed[1].row)
    assertEquals(2 to 1, packed[2].column to packed[2].row)
    assertEquals(widgets.indices.toList(), packed.map { it.index })
  }

  @Test fun resizingOtherPage_doesNotChangeCurrentPageGeometry() {
    val widgets = listOf(widget("photo", HomeWidgetStore.KIND_PHOTO_MEMORY, y = 1)) +
        (0..7).map { widget("w$it") }
    val first = HomePresentation.page(widgets, 0)
    val before = HomePresentation.spans(listOfNotNull(first.hero) + first.widgets)
    val changed = widgets.map { if (it.customId == "w7") it.copy(spanX = 4, spanY = 4) else it }
    val after = HomePresentation.page(changed, 0)
    assertEquals(before, HomePresentation.spans(listOfNotNull(after.hero) + after.widgets))
  }

  @Test fun resizedPhoto_usesItsRequestedDimensionsOnTheFirstPage_andIsAbsentLater() {
    val widgets = listOf(widget("photo", HomeWidgetStore.KIND_PHOTO_MEMORY, x = 1, y = 3)) +
        (0..7).map { widget("w$it") }
    val first = HomePresentation.page(widgets, 0)
    assertEquals(ApprovedWidgetPacking.Span(1, 3),
        HomePresentation.spans(listOfNotNull(first.hero) + first.widgets).first())
    assertNull(HomePresentation.page(widgets, 1).hero)
  }

  @Test fun explicitSizeMatchingLegacyDefault_isHonoredWithoutResizingNeighbors() {
    val widgets = listOf(widget("photo", HomeWidgetStore.KIND_PHOTO_MEMORY, y = 1),
        widget("today", HomeWidgetStore.KIND_TODAY, y = 1), widget("weather"), widget("ha", HomeWidgetStore.KIND_HA))
    val before = HomePresentation.spans(widgets)
    val changed = widgets.map {
      if (it.customId == "today") it.copy(explicitSize = true) else it
    }
    val after = HomePresentation.spans(changed)
    assertEquals(ApprovedWidgetPacking.Span(2, 1), after[1])
    assertEquals(before.filterIndexed { index, _ -> index != 1 },
        after.filterIndexed { index, _ -> index != 1 })
    assertEquals(changed, HomeWidgetStore.deserialize(HomeWidgetStore.serialize(changed)))
  }

  @Test fun removingPhoto_doesNotPromoteOrResizeItsNeighbors() {
    val photo = widget("photo", HomeWidgetStore.KIND_PHOTO_MEMORY, y = 1)
    val neighbors = listOf(widget("weather"), widget("calendar", HomeWidgetStore.KIND_CALENDAR))
    assertEquals(HomePresentation.spans(listOf(photo) + neighbors).drop(1),
        HomePresentation.spans(neighbors))
    assertEquals(listOf(ApprovedWidgetPacking.Span(1, 2), ApprovedWidgetPacking.Span(1, 2)),
        HomePresentation.spans(neighbors))
  }

  @Test fun movingSharedPhoto_changesItsRenderedSlotWithoutLosingAnyIdentity() {
    val widgets = listOf(widget("photo", HomeWidgetStore.KIND_PHOTO_MEMORY, y = 1),
        widget("today", HomeWidgetStore.KIND_TODAY, y = 1), widget("ha", HomeWidgetStore.KIND_HA),
        widget("calendar", HomeWidgetStore.KIND_CALENDAR), widget("weather"))
    val moved = HomePresentation.move(widgets, widgets.first().key, widgets.last().key)
    val visible = HomePresentation.pageWidgets(moved, HomePresentation.page(moved, 0))
    assertEquals(moved.map { it.key }, visible.map { it.key })
    val placements = ApprovedWidgetPacking.pack(HomePresentation.spans(visible))
    val photo = placements[visible.indexOfFirst { it.customId == "photo" }]
    assertEquals(2, photo.row)
    assertEquals(widgets.map { it.key }.toSet(), visible.map { it.key }.toSet())
  }

  @Test fun photoPage_preservesSavedRelativeOrder_andPhotoShowsOnlyOnFirstPage() {
    val photo = widget("photo", HomeWidgetStore.KIND_PHOTO_MEMORY, y = 1)
    val widgets = (0..7).map { widget("w$it") }.toMutableList().apply { add(3, photo) }
    repeat(2) { index ->
      val visible = HomePresentation.pageWidgets(widgets, HomePresentation.page(widgets, index))
      val selectedKeys = visible.map { it.key }.toSet()
      assertEquals(widgets.filter { it.key in selectedKeys }.map { it.key }, visible.map { it.key })
      assertEquals(if (index == 0) 1 else 0, visible.count { it.key == photo.key })
    }
  }
}

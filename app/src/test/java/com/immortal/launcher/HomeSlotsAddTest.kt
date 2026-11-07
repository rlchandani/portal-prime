/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.HomeWidgetStore.HomeWidget
import com.immortal.launcher.HomeWidgetStore.Slot
import org.junit.Assert.*
import org.junit.Test

/** Add-into-slot for the simplified "Add widget" flow (approved 2026-11-07). */
class HomeSlotsAddTest {
  private fun w(id: String, slot: Slot? = null, kind: String = HomeWidgetStore.KIND_WEATHER,
      span: Pair<Int, Int>? = null) =
      if (span == null) HomeWidget(kind = kind, customId = id, slot = slot)
      else HomeWidget(kind = kind, customId = id, slot = slot, spanX = span.first, spanY = span.second,
          explicitSize = true)
  private fun fresh(id: String = "new") = HomeWidget(kind = HomeWidgetStore.KIND_TIMERS, customId = id)

  private val ONE = 1 to 2
  private val WIDE = 2 to 2
  private val TALL = 1 to 4
  private val BIG = 2 to 4

  /** Photo 2×2 at the left, weather and calendar stacked in column 2: the mock's page 1. */
  private val mockPage = listOf(
      w("photo", Slot(0, 0, 0), HomeWidgetStore.KIND_PHOTO_MEMORY, BIG),
      w("weather", Slot(0, 2, 0)),
      w("calendar", Slot(0, 2, 1), HomeWidgetStore.KIND_CALENDAR))

  // --- empty slots ----------------------------------------------------------------------------

  @Test fun emptySlots_listsEachUncoveredSlot_inReadingOrder() {
    assertEquals(listOf(Slot(0, 3, 0), Slot(0, 3, 1)), HomeSlots.emptySlots(mockPage, 0))
  }

  @Test fun emptySlots_ofAnEmptyOrNewPage_isTheWholeGrid() {
    assertEquals(8, HomeSlots.emptySlots(emptyList(), 0).size)
    assertEquals(8, HomeSlots.emptySlots(mockPage, 1).size)
  }

  @Test fun emptySlots_ofAFullPage_isEmpty() {
    val full = (0..7).map { w("w$it", Slot(0, it % 4, it / 4)) }
    assertTrue(HomeSlots.emptySlots(full, 0).isEmpty())
  }

  @Test fun emptySlots_onALegacyLayout_matchWhatIsShown() {
    // Legacy: photo hero 2×2 then two 1×1 widgets packed beside it in row 0; nothing saved.
    val legacy = listOf(w("photo", kind = HomeWidgetStore.KIND_PHOTO_MEMORY).copy(spanX = 2, spanY = 1),
        w("a"), w("b"))
    assertFalse(HomeSlots.hasSlots(legacy))
    assertEquals(listOf(Slot(0, 2, 1), Slot(0, 3, 1)), HomeSlots.emptySlots(legacy, 0))
  }

  @Test fun pageCount_countsSavedPages() {
    assertEquals(1, HomeSlots.pageCount(emptyList()))
    assertEquals(1, HomeSlots.pageCount(mockPage))
    assertEquals(3, HomeSlots.pageCount(mockPage + w("far", Slot(2, 0, 0))))
  }

  @Test fun firstEmptySlot_isOnThePage_orANewPageWhenFull() {
    assertEquals(Slot(0, 3, 0), HomeSlots.firstEmptySlot(mockPage, 0))
    val full = (0..7).map { w("w$it", Slot(0, it % 4, it / 4)) }
    assertEquals(Slot(1, 0, 0), HomeSlots.firstEmptySlot(full, 0))
  }

  // --- which shapes fit -----------------------------------------------------------------------

  @Test fun canPlace_refusesOverlapsAndOffGrid() {
    val spans = HomePresentation.spans(mockPage)
    assertTrue(HomeSlots.canPlace(mockPage, spans, ONE, Slot(0, 3, 0)))
    assertTrue(HomeSlots.canPlace(mockPage, spans, TALL, Slot(0, 3, 0)))
    assertFalse(HomeSlots.canPlace(mockPage, spans, TALL, Slot(0, 3, 1))) // off the bottom
    assertFalse(HomeSlots.canPlace(mockPage, spans, WIDE, Slot(0, 3, 0))) // off the right
    assertFalse(HomeSlots.canPlace(mockPage, spans, ONE, Slot(0, 2, 0))) // weather is there
    assertFalse(HomeSlots.canPlace(mockPage, spans, ONE, Slot(0, 4, 0))) // invalid slot
  }

  @Test fun fittingShapes_inTheMockSlot_areOneAndTwoTall() {
    assertEquals(listOf(ONE, TALL), HomeSlots.fittingShapes(mockPage, Slot(0, 3, 0)))
    // Tapping the lower empty slot: two tall still fits by growing upward around it.
    assertEquals(listOf(ONE, TALL), HomeSlots.fittingShapes(mockPage, Slot(0, 3, 1)))
  }

  @Test fun fittingShapes_onAnEmptyPage_includeEveryShape() {
    assertEquals(HomeSlots.SHAPES, HomeSlots.fittingShapes(emptyList(), Slot(0, 3, 1)))
  }

  @Test fun fittingShapes_ofAnOccupiedSlot_isEmpty() {
    assertTrue(HomeSlots.fittingShapes(mockPage, Slot(0, 0, 0)).isEmpty())
  }

  @Test fun placementFor_prefersTheTappedSlot_thenGrowsAroundIt() {
    assertEquals(Slot(0, 3, 0), HomeSlots.placementFor(mockPage, Slot(0, 3, 0), TALL))
    assertEquals(Slot(0, 3, 0), HomeSlots.placementFor(mockPage, Slot(0, 3, 1), TALL))
    // Bottom-right of an empty page: 2×2 grows up and left.
    assertEquals(Slot(0, 2, 0), HomeSlots.placementFor(emptyList(), Slot(0, 3, 1), BIG))
    assertNull(HomeSlots.placementFor(mockPage, Slot(0, 3, 0), WIDE))
  }

  // --- add into slot --------------------------------------------------------------------------

  @Test fun addAt_appendsWithExactSlotAndSpan() {
    val added = HomeSlots.addAt(mockPage, fresh(), Slot(0, 3, 0), TALL)!!
    assertEquals(mockPage, added.dropLast(1))
    val widget = added.last()
    assertEquals(Slot(0, 3, 0), widget.slot)
    assertEquals(TALL, widget.spanX to widget.spanY)
    assertTrue(widget.explicitSize)
    val pages = HomeSlots.resolve(added, HomePresentation.spans(added))
    val placement = pages[0].first { it.widget.customId == "new" }.placement
    assertEquals(listOf(3, 0, 1, 4), listOf(placement.column, placement.row, placement.columns, placement.rows))
  }

  @Test fun addAt_materializesALegacyLayout_withoutMovingAnything() {
    val legacy = listOf(w("photo", kind = HomeWidgetStore.KIND_PHOTO_MEMORY).copy(spanX = 2, spanY = 1),
        w("a"), w("b"), w("c"))
    val before = HomeSlots.legacyPages(legacy).flatMapIndexed { page, list ->
      list.map { it.widget.key to listOf(page, it.placement.column, it.placement.row) }
    }.toMap()
    val added = HomeSlots.addAt(legacy, fresh(), Slot(0, 3, 1), ONE)!!
    assertTrue(added.all { it.slot != null })
    val after = HomeSlots.resolve(added, HomePresentation.spans(added)).flatMapIndexed { page, list ->
      list.map { it.widget.key to listOf(page, it.placement.column, it.placement.row) }
    }.toMap()
    before.forEach { (key, position) -> assertEquals(key, position, after[key]) }
    assertEquals(listOf(0, 3, 2), after["custom:new"])
  }

  @Test fun addAt_refusesOverlapsOffGridAndDuplicates() {
    assertNull(HomeSlots.addAt(mockPage, fresh(), Slot(0, 2, 0), ONE))
    assertNull(HomeSlots.addAt(mockPage, fresh(), Slot(0, 3, 1), TALL))
    assertNull(HomeSlots.addAt(mockPage, fresh(), Slot(0, 3, 0), WIDE))
    assertNull(HomeSlots.addAt(mockPage, fresh(), Slot(0, 5, 0), ONE))
    assertNull(HomeSlots.addAt(mockPage, mockPage[1].copy(slot = null), Slot(0, 3, 0), ONE))
  }

  @Test fun addAt_ontoANewPage_isAllowed_butNotPastIt() {
    val added = HomeSlots.addAt(mockPage, fresh(), Slot(1, 0, 0), BIG)!!
    assertEquals(2, HomeSlots.pageCount(added))
    assertEquals(Slot(1, 0, 0), added.last().slot)
    assertNull(HomeSlots.addAt(mockPage, fresh(), Slot(2, 0, 0), ONE))
  }

  @Test fun addAt_neverMutatesItsInput() {
    val input = mockPage.toList()
    HomeSlots.addAt(input, fresh(), Slot(0, 3, 0), ONE)
    assertEquals(mockPage, input)
  }

  // --- editing page with an extra empty page --------------------------------------------------

  @Test fun editingPage_addsOneEmptyPageAfterTheLast() {
    val shown = HomePresentation.editingPage(mockPage, 1, extraPage = true)
    assertEquals(1, shown.index)
    assertEquals(2, shown.count)
    assertTrue(shown.widgets.isEmpty())
    assertEquals(emptyList<ApprovedWidgetPacking.Placement>(), shown.placements)
    assertEquals(2, HomePresentation.editingPage(mockPage, 0, extraPage = true).count)
    assertEquals(HomePresentation.page(mockPage, 1), HomePresentation.editingPage(mockPage, 1, extraPage = false))
  }
}

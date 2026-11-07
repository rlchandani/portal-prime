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

/** Free placement on the 2-row × 4-column Home slot grid (2026-11-06). */
class HomeSlotsTest {
  private fun w(id: String, slot: Slot? = null, kind: String = HomeWidgetStore.KIND_WEATHER) =
      HomeWidget(kind = kind, customId = id, slot = slot)
  private fun spans(widgets: List<HomeWidget>) = HomePresentation.spans(widgets)
  private fun at(pages: List<List<HomeSlots.Placed>>, id: String): Triple<Int, Int, Int>? {
    pages.forEachIndexed { page, list ->
      list.firstOrNull { it.widget.customId == id }?.let {
        return Triple(page, it.placement.column, it.placement.row)
      }
    }
    return null
  }

  // --- resolve -------------------------------------------------------------------------------

  @Test fun noSavedSlots_isLegacyMode() {
    assertFalse(HomeSlots.hasSlots(listOf(w("a"), w("b"))))
    assertTrue(HomeSlots.hasSlots(listOf(w("a"), w("b", Slot(0, 1, 0)))))
  }

  @Test fun savedSlot_rendersExactly_andLeavesTheGapAbove() {
    val widgets = listOf(w("a", Slot(0, 2, 1)))
    val pages = HomeSlots.resolve(widgets, spans(widgets))
    assertEquals(Triple(0, 2, 2), at(pages, "a")) // slot row 1 = half-row 2
  }

  @Test fun unplacedWidgets_flowIntoFreeSlots_aroundPlacedOnes() {
    val widgets = listOf(w("placed", Slot(0, 0, 0)), w("x"), w("y"))
    val pages = HomeSlots.resolve(widgets, spans(widgets))
    assertEquals(Triple(0, 0, 0), at(pages, "placed"))
    assertEquals(Triple(0, 1, 0), at(pages, "x"))
    assertEquals(Triple(0, 2, 0), at(pages, "y"))
  }

  @Test fun outOfBoundsOrCollidingSlots_fallBackToAutoFlow_withoutLosingWidgets() {
    val widgets = listOf(w("a", Slot(0, 0, 0)), w("clash", Slot(0, 0, 0)), w("off", Slot(0, 7, 5)),
        w("neg", Slot(-1, 0, 0)))
    val pages = HomeSlots.resolve(widgets, spans(widgets))
    assertEquals(setOf("a", "clash", "off", "neg"), pages.flatten().map { it.widget.customId }.toSet())
    assertEquals(Triple(0, 0, 0), at(pages, "a"))
    assertNotEquals(Triple(0, 0, 0), at(pages, "clash"))
  }

  @Test fun savedLaterPage_createsThePages() {
    val widgets = listOf(w("a"), w("far", Slot(2, 3, 1)))
    val pages = HomeSlots.resolve(widgets, spans(widgets))
    assertEquals(3, pages.size)
    assertEquals(Triple(2, 3, 2), at(pages, "far"))
    assertTrue(pages[1].isEmpty())
  }

  @Test fun fullPage_overflowsUnplacedOntoTheNextPage() {
    val placed = (0..7).map { w("p$it", Slot(0, it % 4, it / 4)) }
    val widgets = placed + w("extra")
    val pages = HomeSlots.resolve(widgets, spans(widgets))
    assertEquals(Triple(1, 0, 0), at(pages, "extra"))
  }

  @Test fun placementIndices_matchEachPagesListOrder() {
    val widgets = listOf(w("b", Slot(0, 1, 1)), w("a", Slot(0, 0, 0)))
    val page = HomeSlots.resolve(widgets, spans(widgets)).single()
    page.forEachIndexed { index, placed -> assertEquals(index, placed.placement.index) }
    assertEquals(listOf("a", "b"), page.map { it.widget.customId }) // reading order: row, then column
  }

  // --- materialize ---------------------------------------------------------------------------

  @Test fun materialize_savesCurrentOnScreenPositions_soNothingMoves() {
    val photo = w("photo", kind = HomeWidgetStore.KIND_PHOTO_MEMORY)
    val legacy = listOf(photo) + (0..5).map { w("w$it") }
    val shown = HomeSlots.legacyPages(legacy)
    val saved = HomeSlots.materialize(legacy, shown)
    assertTrue(saved.all { it.slot != null })
    val after = HomeSlots.resolve(saved, spans(saved))
    legacy.forEach { assertEquals(at(shown, it.customId), at(after, it.customId)) }
    assertEquals(legacy.map { it.key }, saved.map { it.key }) // order and identity preserved
  }

  // --- move ----------------------------------------------------------------------------------

  @Test fun moveIntoEmptyRowTwo_withoutFillingRowOne() {
    val widgets = listOf(w("a", Slot(1, 0, 0)), w("b", Slot(1, 1, 0)))
    val moved = HomeSlots.moveTo(widgets, spans(widgets), "custom:b", Slot(1, 3, 1))!!
    assertEquals(Slot(1, 3, 1), moved.first { it.customId == "b" }.slot)
    assertEquals(Slot(1, 0, 0), moved.first { it.customId == "a" }.slot)
  }

  @Test fun moveOntoSameSizedWidget_swaps() {
    val widgets = listOf(w("a", Slot(0, 0, 0)), w("b", Slot(0, 3, 1)))
    val moved = HomeSlots.moveTo(widgets, spans(widgets), "custom:a", Slot(0, 3, 1))!!
    assertEquals(Slot(0, 3, 1), moved.first { it.customId == "a" }.slot)
    assertEquals(Slot(0, 0, 0), moved.first { it.customId == "b" }.slot)
  }

  @Test fun moveOntoDifferentSizedOrPartlyCoveredSpace_isRefused() {
    val big = w("big", Slot(0, 2, 0)).copy(spanX = 2, spanY = 4, explicitSize = true)
    val widgets = listOf(w("a", Slot(0, 0, 0)), big)
    assertNull(HomeSlots.moveTo(widgets, spans(widgets), "custom:a", Slot(0, 2, 0)))
    assertNull(HomeSlots.moveTo(widgets, spans(widgets), "custom:a", Slot(0, 3, 1)))
  }

  @Test fun moveOffTheGrid_isRefused() {
    val wide = w("wide", Slot(0, 0, 0)).copy(spanX = 2, spanY = 2, explicitSize = true)
    assertNull(HomeSlots.moveTo(listOf(wide), spans(listOf(wide)), "custom:wide", Slot(0, 3, 0)))
    assertNull(HomeSlots.moveTo(listOf(wide), spans(listOf(wide)), "custom:wide", Slot(0, 0, 2)))
  }

  @Test fun moveToANewPage_isAllowed() {
    val widgets = listOf(w("a", Slot(0, 0, 0)))
    val moved = HomeSlots.moveTo(widgets, spans(widgets), "custom:a", Slot(1, 0, 0))!!
    assertEquals(Slot(1, 0, 0), moved.single().slot)
  }

  // --- resize --------------------------------------------------------------------------------

  @Test fun resizeIntoFreeSpace_isAllowed_andSavedExactly() {
    val widgets = listOf(w("a", Slot(0, 0, 0)))
    val resized = HomeSlots.resize(widgets, spans(widgets), "custom:a", 2 to 4)!!
    assertEquals(2, resized.single().spanX)
    assertEquals(4, resized.single().spanY)
    assertTrue(resized.single().explicitSize)
  }

  @Test fun resizeOverANeighbour_isRefused_butShrinkingIsAllowed() {
    val big = w("big", Slot(0, 0, 0)).copy(spanX = 2, spanY = 4, explicitSize = true)
    val widgets = listOf(big, w("n", Slot(0, 2, 0)))
    assertNull(HomeSlots.resize(widgets, spans(widgets), "custom:big", 3 to 4))
    assertNotNull(HomeSlots.resize(widgets, spans(widgets), "custom:big", 1 to 2))
  }

  @Test fun resizeOffTheGrid_isRefused() {
    val widgets = listOf(w("a", Slot(0, 3, 0)))
    assertNull(HomeSlots.resize(widgets, spans(widgets), "custom:a", 2 to 2))
  }

  // --- spots for the Move dialog -------------------------------------------------------------

  @Test fun freeSpots_listsEveryFittingEmptySlot_plusANewPage() {
    val widgets = (0..6).map { w("p$it", Slot(0, it % 4, it / 4)) }
    val spots = HomeSlots.freeSpots(widgets, spans(widgets), "custom:p0")
    assertTrue(Slot(0, 3, 1) in spots)
    assertTrue(Slot(1, 0, 0) in spots)
    assertFalse(Slot(0, 1, 0) in spots)
  }

  // --- editing entry points (used by Customize) --------------------------------------------

  @Test fun firstEditOfALegacyLayout_savesPositionsThenApplies_andNothingElseMoves() {
    val photo = w("photo", kind = HomeWidgetStore.KIND_PHOTO_MEMORY)
    val legacy = listOf(photo) + (0..7).map { w("w$it") } // page 2 holds w4..w7 on its top row
    val before = HomeSlots.legacyPages(legacy)
    val moved = HomeSlots.moveForEditing(legacy, "custom:w7", Slot(1, 0, 1))!!
    val after = HomeSlots.resolve(moved, spans(moved))
    assertEquals(Triple(1, 0, 2), at(after, "w7"))
    legacy.filter { it.customId != "w7" }.forEach { assertEquals(at(before, it.customId), at(after, it.customId)) }
  }

  @Test fun forEditing_keepsAnExistingSlotLayoutAsIs() {
    val widgets = listOf(w("a", Slot(0, 3, 1)))
    assertSame(widgets, HomeSlots.forEditing(widgets))
  }

  @Test fun resizeForEditing_onLegacyLayout_refusesToGrowOverANeighbour() {
    val legacy = (0..3).map { w("w$it") }
    assertNull(HomeSlots.resizeForEditing(legacy, "custom:w0", 2 to 2))
    assertNotNull(HomeSlots.resizeForEditing(legacy, "custom:w3", 1 to 4))
  }

  // --- persistence ---------------------------------------------------------------------------

  @Test fun slot_roundTripsThroughSavedJson() {
    val widgets = listOf(w("a", Slot(1, 3, 1)), w("b"))
    assertEquals(widgets, HomeWidgetStore.deserialize(HomeWidgetStore.serialize(widgets)))
  }

  @Test fun legacyOrGarbageSavedSlot_decodesAsUnplaced() {
    val legacy = """[{"kind":"weather","customId":"a","spanX":2,"spanY":2}]"""
    assertNull(HomeWidgetStore.deserialize(legacy).single().slot)
    val garbage = """[{"kind":"weather","customId":"a","page":-3,"column":"x","row":9}]"""
    assertNull(HomeWidgetStore.deserialize(garbage).single().slot)
  }
}

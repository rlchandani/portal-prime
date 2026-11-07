/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Home is a 2-row × 4-column grid of slots (2026-11-06). Spans are stored in the underlying
 * 4×4 half-row units, so one slot = 1 column × 2 rows.
 */
class WidgetGridTest {
  private val one = 1 to 2      // 1×1 slot
  private val wide = 2 to 2     // 2 slots wide
  private val tall = 1 to 4     // 2 slots tall (full page height)
  private val big = 2 to 4      // 2×2 slots

  @Test fun sizeCycle_oneWideTallBig_thenWraps() {
    assertEquals(wide, HomeWidgetStore.nextWidgetSpan(one))
    assertEquals(tall, HomeWidgetStore.nextWidgetSpan(wide))
    assertEquals(big, HomeWidgetStore.nextWidgetSpan(tall))
    assertEquals(one, HomeWidgetStore.nextWidgetSpan(big))
  }

  @Test fun sizeCycle_legacyHalfHeightSizes_continueFromTheirSnappedSlot() {
    assertEquals(wide, HomeWidgetStore.nextWidgetSpan(1 to 1)) // old Small snaps to one slot
    assertEquals(tall, HomeWidgetStore.nextWidgetSpan(2 to 1)) // old Medium snaps to wide
    assertEquals(one, HomeWidgetStore.nextWidgetSpan(4 to 4))  // anything larger wraps to one slot
  }

  @Test fun slotSpan_roundsHeightUpToWholeSlots_andClampsWidth() {
    assertEquals(ApprovedWidgetPacking.Span(1, 2), HomePresentation.slotSpan(1, 1))
    assertEquals(ApprovedWidgetPacking.Span(2, 2), HomePresentation.slotSpan(2, 2))
    assertEquals(ApprovedWidgetPacking.Span(3, 4), HomePresentation.slotSpan(3, 3))
    assertEquals(ApprovedWidgetPacking.Span(4, 4), HomePresentation.slotSpan(4, 4))
    assertEquals(ApprovedWidgetPacking.Span(1, 2), HomePresentation.slotSpan(0, 0))
    assertEquals(ApprovedWidgetPacking.Span(4, 4), HomePresentation.slotSpan(9, 9))
  }

  @Test fun savedHalfHeightSize_displaysAsWholeSlot_withoutRewritingTheSave() {
    val saved = HomeWidgetStore.HomeWidget(kind = HomeWidgetStore.KIND_WEATHER, customId = "w",
        spanX = 2, spanY = 1, explicitSize = true)
    assertEquals(listOf(ApprovedWidgetPacking.Span(2, 2)), HomePresentation.spans(listOf(saved)))
    assertEquals(1, saved.spanY)
  }

  @Test fun defaultSizedWidget_staysOneSlot_onEveryPage() {
    val widgets = (0..3).map { HomeWidgetStore.HomeWidget(kind = HomeWidgetStore.KIND_WEATHER, customId = "w$it") }
    assertEquals(List(4) { ApprovedWidgetPacking.Span(1, 2) }, HomePresentation.spans(widgets))
  }
}

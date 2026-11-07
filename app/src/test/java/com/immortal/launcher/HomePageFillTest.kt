/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

/** A sparse page without the photo fills the screen instead of leaving its lower half empty. */
class HomePageFillTest {
  private fun widget(id: String, kind: String = HomeWidgetStore.KIND_WEATHER) =
      HomeWidgetStore.HomeWidget(kind = kind, customId = id)
  private val big = ApprovedWidgetPacking.Span(2, 2)
  private val standard = ApprovedWidgetPacking.Span(1, 2)

  @Test fun fourDefaultWidgetsWithoutPhoto_growToTwoByTwo() {
    val visible = listOf(widget("a", HomeWidgetStore.KIND_ALARM), widget("b"), widget("c"),
        widget("d", HomeWidgetStore.KIND_QUICK_CONTROLS))
    assertEquals(List(4) { big }, HomePresentation.pageSpans(visible, editMode = false))
  }

  @Test fun fewerThanFour_alsoGrow() {
    assertEquals(List(2) { big }, HomePresentation.pageSpans(listOf(widget("a"), widget("b")), false))
  }

  @Test fun moreThanFour_keepTheirSizes() {
    val visible = (0..4).map { widget("w$it") }
    assertEquals(HomePresentation.spans(visible), HomePresentation.pageSpans(visible, false))
  }

  @Test fun pageWithPhoto_isUnchanged() {
    val visible = listOf(widget("photo", HomeWidgetStore.KIND_PHOTO_MEMORY)) + (0..2).map { widget("w$it") }
    assertEquals(HomePresentation.spans(visible), HomePresentation.pageSpans(visible, false))
  }

  @Test fun customize_showsSavedSizes() {
    val visible = (0..3).map { widget("w$it") }
    assertEquals(List(4) { standard }, HomePresentation.pageSpans(visible, editMode = true))
  }

  @Test fun userResizedAndAppWidgets_keepTheirSize_othersGrow() {
    val resized = widget("r").copy(spanX = 1, spanY = 1, explicitSize = true)
    val hosted = HomeWidgetStore.HomeWidget(appWidgetId = 7, providerPackage = "p", providerClass = "c")
    val spans = HomePresentation.pageSpans(listOf(resized, hosted, widget("a")), false)
    assertEquals(ApprovedWidgetPacking.Span(1, 1), spans[0])
    assertEquals(HomePresentation.spans(listOf(hosted)).single(), spans[1])
    assertEquals(big, spans[2])
  }

  @Test fun growingThatWouldOverflowTheGrid_keepsOriginalSizes() {
    val huge = widget("h").copy(spanX = 4, spanY = 3, explicitSize = true)
    val visible = listOf(huge, widget("a"), widget("b"))
    assertEquals(HomePresentation.spans(visible), HomePresentation.pageSpans(visible, false))
  }
}

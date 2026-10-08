/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class ApprovedHomePresentationPagingTest {
  private fun widget(id: String, kind: String = HomeWidgetStore.KIND_WEATHER) =
      HomeWidgetStore.HomeWidget(kind = kind, customId = id)

  @Test fun nineConfiguredWidgets_haveTwoPagesAndKeepPhotoIdentity() {
    val photo = widget("photo", HomeWidgetStore.KIND_PHOTO_MEMORY)
    val widgets = (0..7).map { widget("id$it") }
    val configured = widgets.take(2) + photo + widgets.drop(2)
    val first = HomePresentation.page(configured, 0)
    val second = HomePresentation.page(configured, 1)
    assertEquals(2, first.count)
    assertSame(photo, first.hero)
    assertSame(photo, second.hero)
    assertEquals(widgets, first.widgets + second.widgets)
    assertEquals(configured, widgets.take(2) + photo + widgets.drop(2))
  }

  @Test fun removalFromLastPage_clampsWithoutLosingWidgets() {
    val widgets = (0..3).map { widget("id$it") }
    val page = HomePresentation.page(widgets, 8)
    assertEquals(0, page.index)
    assertEquals(widgets, page.widgets)
    assertEquals(0, HomePresentation.page(emptyList(), -1).index)
    assertEquals(1, HomePresentation.page(emptyList(), 12).count)
  }

  @Test fun swipingLeftAdvancesAndSwipingRightReturns_withoutWrapping() {
    val widgets = (0..7).map { widget("id$it") }
    val first = HomePresentation.page(widgets, 0)
    val second = HomePresentation.page(widgets, 1)
    assertEquals(1, HomePresentation.pageAfterSwipe(first, swipeRight = false))
    assertEquals(0, HomePresentation.pageAfterSwipe(second, swipeRight = true))
    assertEquals(0, HomePresentation.pageAfterSwipe(first, swipeRight = true))
    assertEquals(1, HomePresentation.pageAfterSwipe(second, swipeRight = false))
    assertEquals(0, HomePresentation.pageAfterSwipe(
        HomePresentation.page(emptyList(), 0), swipeRight = false))
  }

  @Test fun increasingPagesSitToTheRight_andMoveLeftTogetherWhenAdvancing() {
    assertEquals(0f, HomePresentation.pageOffset(0, 0f), 0f)
    assertEquals(1f, HomePresentation.pageOffset(1, 0f), 0f)
    assertEquals(-.5f, HomePresentation.pageOffset(0, .5f), 0f)
    assertEquals(.5f, HomePresentation.pageOffset(1, .5f), 0f)
    assertEquals(-1f, HomePresentation.pageOffset(0, 1f), 0f)
    assertEquals(0f, HomePresentation.pageOffset(1, 1f), 0f)
  }

  @Test fun multiplePhotoWidgetsAndAppWidgets_allRemainReachable() {
    val first = widget("photo1", HomeWidgetStore.KIND_PHOTO_MEMORY)
    val second = widget("photo2", HomeWidgetStore.KIND_PHOTO_MEMORY)
    val hosted = HomeWidgetStore.HomeWidget(appWidgetId = 71, providerPackage = "p", providerClass = "c")
    val configured = listOf(first, second, hosted) + (0..6).map { widget("id$it") }
    val pages = (0 until HomePresentation.page(configured, 0).count)
        .map { HomePresentation.page(configured, it) }
    assertEquals(configured.map { it.key }.toSet(),
        (listOf(first) + pages.flatMap { it.widgets }).map { it.key }.toSet())
    assertEquals(1, pages.flatMap { it.widgets }.count { it.key == second.key })
  }

  @Test fun reorderingAcrossPages_retainsEveryIdAndSavedSize() {
    val configured = (0..8).map { widget("id$it").copy(spanX = 1 + it % 4, spanY = 1 + it % 3) }
    val moved = HomePresentation.move(configured, configured.last().key, configured.first().key)
    assertEquals(configured.last(), moved.first())
    assertEquals(configured.toSet(), moved.toSet())
    assertEquals(configured, HomePresentation.move(configured, "header", configured.first().key))
  }

  @Test fun migratedDashboardSizes_useNormalLayoutUntilUserResizes() {
    val defaults = listOf(widget("weather"),
        widget("today", HomeWidgetStore.KIND_TODAY).copy(spanY = 1),
        widget("photo", HomeWidgetStore.KIND_PHOTO_MEMORY).copy(spanY = 1))
    assertFalse(HomePresentation.usesCustomSizing(defaults))
    assertTrue(HomePresentation.usesCustomSizing(defaults.map {
      if (it.customId == "photo") it.copy(spanX = 1) else it
    }))
  }

  @Test fun deviceNineWidgetLayout_pageTwoCustomSizesDoNotChangePageOneGeometry() {
    // Actual pre-update inventory: narrow Alarm/World Clock/Quick Controls live on page two.
    val configured = listOf(
        widget("today", HomeWidgetStore.KIND_TODAY).copy(spanY = 1),
        widget("ha", HomeWidgetStore.KIND_HA),
        widget("calendar", HomeWidgetStore.KIND_CALENDAR),
        widget("weather"),
        widget("alarm", HomeWidgetStore.KIND_ALARM).copy(spanX = 1),
        widget("world", HomeWidgetStore.KIND_WORLD_CLOCK).copy(spanX = 1),
        widget("crypto", HomeWidgetStore.KIND_CRYPTO),
        widget("quick", HomeWidgetStore.KIND_QUICK_CONTROLS).copy(spanX = 1),
        widget("photo", HomeWidgetStore.KIND_PHOTO_MEMORY).copy(spanY = 1),
    )
    val first = HomePresentation.page(configured, 0)
    val second = HomePresentation.page(configured, 1)
    assertEquals(2, first.count)
    assertFalse(HomePresentation.usesCustomSizing(listOfNotNull(first.hero) + first.widgets))
    assertTrue(HomePresentation.usesCustomSizing(listOfNotNull(second.hero) + second.widgets))
    assertEquals(listOf("today", "ha", "calendar", "weather"), first.widgets.map { it.customId })
    assertEquals(listOf(1 to 2, 1 to 2, 2 to 2, 1 to 2),
        second.widgets.map { it.spanX to it.spanY })
    assertEquals(configured.map { it.key }.toSet(),
        (listOfNotNull(first.hero) + first.widgets + second.widgets).map { it.key }.toSet())
  }

}

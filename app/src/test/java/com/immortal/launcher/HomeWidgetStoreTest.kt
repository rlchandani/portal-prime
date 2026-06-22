/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWidgetStoreTest {

  @Test
  fun serialize_deserialize_roundTripsWidgets() {
    val widgets =
        listOf(
            HomeWidgetStore.HomeWidget(
                appWidgetId = 42,
                providerPackage = "io.homeassistant.companion.android",
                providerClass = "io.homeassistant.companion.android.widgets.button.ButtonWidget",
                spanX = 2,
                spanY = 3,
            ),
            HomeWidgetStore.HomeWidget(
                appWidgetId = 43,
                providerPackage = "com.android.chrome",
                providerClass = "org.chromium.chrome.browser.searchwidget.SearchWidgetProvider",
                spanX = 4,
                spanY = 1,
            ),
            HomeWidgetStore.HomeWidget(
                kind = HomeWidgetStore.KIND_WEATHER,
                customId = "weather:1",
                spanX = 2,
                spanY = 2,
            ),
        )

    assertEquals(widgets, HomeWidgetStore.deserialize(HomeWidgetStore.serialize(widgets)))
  }

  @Test
  fun deserialize_ignoresInvalidEntriesAndDuplicates() {
    val raw =
        """
        [
          {"id": 7, "package": "a", "class": "A", "spanX": 99, "spanY": 0},
          {"id": 7, "package": "b", "class": "B"},
          {"id": -1, "package": "c", "class": "C"},
          {"id": 8, "package": "", "class": "D"}
        ]
        """
            .trimIndent()

    val widgets = HomeWidgetStore.deserialize(raw)

    assertEquals(1, widgets.size)
    assertEquals(7, widgets[0].appWidgetId)
    assertEquals(HomeWidgetStore.MAX_SPAN, widgets[0].spanX)
    assertEquals(1, widgets[0].spanY)
  }

  @Test
  fun deserialize_isEmptyOnGarbage() {
    assertTrue(HomeWidgetStore.deserialize("not json").isEmpty())
    assertTrue(HomeWidgetStore.deserialize("").isEmpty())
  }

  @Test
  fun withAdded_replacesExistingId() {
    val original =
        listOf(HomeWidgetStore.HomeWidget(1, "old", "Old"), HomeWidgetStore.HomeWidget(2, "b", "B"))
    val added = HomeWidgetStore.HomeWidget(1, "new", "New")

    assertEquals(listOf(original[1], added), HomeWidgetStore.withAdded(original, added))
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeControlHeaderTest {
  private val now = 1_795_000_000_000L
  private val min = 60_000L
  private val live = CcConnection(CcPhase.LIVE)
  private fun state(vararg e: CcEntity, settings: ControlCenterConfig.Settings = ControlCenterConfig.Settings()) =
      CcUiState(connection = live, entities = e.associateBy { it.id }, now = now, settings = settings)

  @Test fun allFine_noDot() {
    val st = state(CcEntity("lock.front", "locked"), CcEntity("cover.garage", "closed", mapOf("device_class" to "garage")),
        CcEntity("binary_sensor.door", "off", mapOf("device_class" to "door")))
    assertEquals(HomeControlDot.NONE, HomeControlStatus.dot(st))
  }

  @Test fun openGarageDoorOrUnlockedLock_amber() {
    assertEquals(HomeControlDot.AMBER, HomeControlStatus.dot(state(CcEntity("cover.garage", "open", mapOf("device_class" to "garage"), now))))
    assertEquals(HomeControlDot.AMBER, HomeControlStatus.dot(state(CcEntity("lock.front", "unlocked"))))
    assertEquals(HomeControlDot.AMBER, HomeControlStatus.dot(state(CcEntity("binary_sensor.d", "on", mapOf("device_class" to "door"), now))))
    assertEquals("a window alone isn't a header warning until it has been open long enough to alert",
        HomeControlDot.NONE, HomeControlStatus.dot(state(CcEntity("binary_sensor.w", "on", mapOf("device_class" to "window"), now))))
  }

  @Test fun warningAlert_amber_criticalAlert_red() {
    val window = CcEntity("binary_sensor.w", "on", mapOf("device_class" to "window"), now - 30 * min)
    assertEquals(HomeControlDot.AMBER, HomeControlStatus.dot(state(window)))
    val leak = CcEntity("binary_sensor.leak", "on", mapOf("device_class" to "moisture"), now)
    assertEquals(HomeControlDot.RED, HomeControlStatus.dot(state(window, leak, CcEntity("lock.front", "unlocked"))))
  }

  @Test fun kidsModeDoesNotLightTheDotForHiddenThings_butCriticalsStillShow() {
    val kids = ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_KIDS)
    assertEquals(HomeControlDot.NONE, HomeControlStatus.dot(state(CcEntity("lock.front", "unlocked"), settings = kids)))
    val smoke = CcEntity("binary_sensor.s", "on", mapOf("device_class" to "smoke"), now)
    assertEquals(HomeControlDot.RED, HomeControlStatus.dot(state(smoke, settings = kids)))
  }

  @Test fun unavailableOrNotConfigured_noDot() {
    assertEquals(HomeControlDot.NONE, HomeControlStatus.dot(state(CcEntity("lock.front", "unavailable"))))
    val off = CcUiState(connection = CcConnection(CcPhase.NOT_CONFIGURED), entities = mapOf("lock.x" to CcEntity("lock.x", "unlocked")))
    assertEquals(HomeControlDot.NONE, HomeControlStatus.dot(off))
  }

  @Test fun offScreenKeepsTheLastFullViewsWarning() {
    assertEquals(HomeControlDot.AMBER, HomeControlStatus.combine(HomeControlDot.NONE, HomeControlDot.AMBER))
    assertEquals(HomeControlDot.RED, HomeControlStatus.combine(HomeControlDot.RED, HomeControlDot.AMBER))
    assertEquals(HomeControlDot.NONE, HomeControlStatus.combine(HomeControlDot.NONE, HomeControlDot.NONE))
  }

  @Test fun shownOnlyWithHaAndNotBannersOnly() {
    val full = ControlCenterConfig.Settings()
    assertTrue(HomeControlStatus.shown(true, full))
    assertFalse(HomeControlStatus.shown(false, full))
    assertFalse(HomeControlStatus.shown(true, ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_BANNERS)))
    assertTrue(HomeControlStatus.shown(true, ControlCenterConfig.Settings(portalMode = ControlCenterConfig.MODE_KIDS)))
  }

  @Test fun theHomeAssistantWidgetIsDroppedAndNothingElseMoves() {
    val ha = HomeWidgetStore.custom(HomeWidgetStore.KIND_HA)
    val today = HomeWidgetStore.custom(HomeWidgetStore.KIND_TODAY, spanX = 2, spanY = 1)
    val weather = HomeWidgetStore.custom(HomeWidgetStore.KIND_WEATHER)
    assertEquals(listOf(today, weather), HomeWidgetStore.withoutHomeAssistant(listOf(today, ha, weather)))
    assertEquals(listOf(today), HomeWidgetStore.withoutHomeAssistant(listOf(today)))
  }
}

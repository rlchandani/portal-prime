/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import com.immortal.launcher.ScreensaverConfig
import com.immortal.launcher.ScreensaverTestPrefs
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NightClockSettingsTest {
  private val keys = setOf("darkRoomClock", "darkRoomLux", "nightClockBrightness", "nightClockTone")
  private fun domain() = SettingsDomain(
      id = "screensaver", title = "Photos & screensaver",
      load = { c -> ScreensaverConfig.load(c) },
      specs = SettingsDomains.screensaver.specs.filter { it.key in keys },
      defaults = { ScreensaverConfig.Settings() },
  )

  private fun visibleKeys(prefs: ScreensaverTestPrefs): Set<String> {
    val controls = domain().schemaJson(prefs.context).getJSONArray("controls")
    return (0 until controls.length()).map { controls.getJSONObject(it).getString("key") }.toSet()
  }

  @Test fun defaultsLeaveExistingDaytimeBehaviorUnchanged() {
    val prefs = ScreensaverTestPrefs()
    val cfg = ScreensaverConfig.load(prefs.context)
    assertFalse(cfg.darkRoomClock)
    assertEquals(3, cfg.darkRoomLux)
    assertEquals(3, cfg.nightClockBrightness)
    assertEquals("warm", cfg.nightClockTone)
    assertEquals(setOf("darkRoomClock"), visibleKeys(prefs))
  }

  @Test fun enablingClockRevealsControlsAndPartialWritesPreservePhotoAndSleepChoices() {
    val prefs = ScreensaverTestPrefs(mapOf("mode" to "portraitPair", "overnight_enabled" to true,
        "overnight_night_clock" to false, "shuffle" to false))
    assertEquals(keys, domain().applyValues(prefs.context,
        JSONObject().put("darkRoomClock", true).put("darkRoomLux", 5)
            .put("nightClockBrightness", 2).put("nightClockTone", "red")))
    val cfg = ScreensaverConfig.load(prefs.context)
    assertTrue(cfg.darkRoomClock)
    assertEquals(5, cfg.darkRoomLux)
    assertEquals(2, cfg.nightClockBrightness)
    assertEquals("red", cfg.nightClockTone)
    assertEquals("portraitPair", cfg.mode)
    assertTrue(cfg.overnightEnabled)
    assertFalse(cfg.overnightNightClock)
    assertFalse(cfg.shuffle)
    assertEquals(keys, visibleKeys(prefs))
  }

  @Test fun scheduleCanUseClockAppearanceWithoutEnablingSensor() {
    val prefs = ScreensaverTestPrefs(mapOf("overnight_enabled" to true, "overnight_night_clock" to true))
    assertEquals(setOf("darkRoomClock", "nightClockBrightness", "nightClockTone"), visibleKeys(prefs))
  }

  @Test fun invalidValuesAreRejectedBeforePersistence() {
    val prefs = ScreensaverTestPrefs()
    for (bad in listOf(
        JSONObject().put("darkRoomLux", 0).put("nightClockBrightness", 0).put("nightClockTone", "neon"),
        JSONObject().put("darkRoomLux", 21).put("nightClockBrightness", 16).put("darkRoomClock", "yes"),
        JSONObject().put("darkRoomLux", 1.5).put("nightClockBrightness", true).put("nightClockTone", 7),
    )) assertTrue(domain().applyValues(prefs.context, bad).isEmpty())
    assertEquals(ScreensaverConfig.Settings(), ScreensaverConfig.load(prefs.context))
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import com.immortal.launcher.ImmortalSettings
import org.junit.Assert.*
import org.junit.Test

class SettingsPresentationTest {
  @Test fun defaultsAreSimpleAndNewPreferencesAreRegistryBound() {
    val defaults = ImmortalSettings.Settings()
    assertFalse(defaults.advancedSettings)
    assertEquals("light", defaults.settingsTheme)
    assertTrue(SettingsDomains.immortal.specs.any { it.key == "advancedSettings" && it is BoolSpec })
    assertTrue(SettingsDomains.immortal.specs.any { it.key == "settingsTheme" && it is EnumSpec })
  }

  @Test fun technicalControlsAreClassifiedWithoutHidingEverydayPermissions() {
    assertTrue(SettingsPresentation.isAdvanced("screensaver", "cacheEnabled"))
    assertTrue(SettingsPresentation.isAdvanced("immortal", "maPassword"))
    assertTrue(SettingsPresentation.isAdvancedPage("smb"))
    assertFalse(SettingsPresentation.isAdvancedPage("system"))
    assertFalse(SettingsPresentation.isAdvanced("screensaver", "pauseAudioOnSleep"))
    assertFalse(SettingsPresentation.isAdvanced("immortal", "advancedSettings"))
  }

  @Test fun hidingAdvancedDoesNotChangeStoredConfiguration() {
    val configured = ImmortalSettings.Settings(
        advancedSettings = true, multiRoomEnabled = true, snapcastHost = "test.local",
        maUsername = "test", maPassword = "test-password")
    val simple = configured.copy(advancedSettings = false)
    assertTrue(simple.multiRoomEnabled)
    assertEquals(configured.snapcastHost, simple.snapcastHost)
    assertEquals(configured.maUsername, simple.maUsername)
    assertEquals(configured.maPassword, simple.maPassword)
    assertEquals(SettingsDomains.immortal.flatJson(configured).get("maPassword"),
        SettingsDomains.immortal.flatJson(simple).get("maPassword"))
  }

  @Test fun everydayHelpUsesTheApprovedShortCopy() {
    assertEquals("Show photos when your Portal is idle.",
        SettingsPresentation.conciseHelp("screensaver", "enabled", "Technical explanation"))
    assertEquals("", SettingsPresentation.conciseHelp("immortal", "weatherUnit", "Technical explanation"))
  }
}

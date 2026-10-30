/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import com.immortal.launcher.ScreensaverConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowSettingsPresentationTest {
  private val options = ScreensaverConfig.MODES.map { it to ScreensaverConfig.modeLabel(it) }

  @Test fun basicPicker_containsOnlyPreferredStyles() {
    assertEquals(listOf("smartFrame", "portraitPair", "wholePhoto", "galleryPrint"),
        visible("smartFrame", false).map { it.first })
  }

  @Test fun savedLegacySelection_remainsVisibleWithoutChangingIt() {
    ScreensaverConfig.MODES.filterNot(ScreensaverConfig::isNativeStage).forEach { selected ->
      assertEquals(listOf("smartFrame", "portraitPair", "wholePhoto", "galleryPrint", selected),
          visible(selected, false).map { it.first })
    }
  }

  @Test fun advancedPicker_keepsEveryOptionAndItsOrder() {
    assertEquals(options, visible("smartFrame", true))
  }

  @Test fun optionTier_isSharedWithPhoneMetadataAndDoesNotAffectOtherEnums() {
    options.forEach { (value, _) ->
      assertEquals(!ScreensaverConfig.isNativeStage(value),
          SettingsPresentation.isAdvancedOption("screensaver", "mode", value))
    }
    assertFalse(SettingsPresentation.isAdvancedOption("screensaver", "fit", "fill"))
    assertFalse(SettingsPresentation.isAdvancedOption("immortal", "settingsTheme", "dark"))
    val unrelated = listOf("first" to "First", "second" to "Second")
    assertEquals(unrelated, SettingsPresentation.visibleEnumOptions(
        "other", "mode", unrelated, "first", false))
    assertTrue(SettingsPresentation.isAdvancedOption("screensaver", "mode", "kenBurns"))
  }

  private fun visible(selected: String, advanced: Boolean) =
      SettingsPresentation.visibleEnumOptions("screensaver", "mode", options, selected, advanced)
}

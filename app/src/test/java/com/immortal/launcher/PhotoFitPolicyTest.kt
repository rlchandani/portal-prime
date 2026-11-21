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

class PhotoFitPolicyTest {
  @Test fun croppingNativeStylesAndClassicHonourFit() {
    listOf(ScreensaverConfig.MODE_SMART, ScreensaverConfig.MODE_PORTRAITS, ScreensaverConfig.MODE_CLASSIC)
        .forEach { assertTrue(it, PhotoFitPolicy.honoursFit(it)) }
  }

  @Test fun stylesThatNeverCropIgnoreFit() {
    listOf(ScreensaverConfig.MODE_WHOLE, ScreensaverConfig.MODE_GALLERY_PRINT, ScreensaverConfig.MODE_GALLERY_SPREAD)
        .forEach { assertFalse(it, PhotoFitPolicy.honoursFit(it)) }
  }

  @Test fun splitGlanceOwnsItsCardFraming() {
    assertFalse(PhotoFitPolicy.honoursFit(ScreensaverConfig.MODE_SPLIT_GLANCE))
  }

  @Test fun engineStylesAndShuffleIgnoreFit() {
    (ScreensaverConfig.ENGINE_MODES + ScreensaverConfig.MODE_SHUFFLE)
        .forEach { assertFalse(it, PhotoFitPolicy.honoursFit(it)) }
  }

  @Test fun unknownModeIgnoresFit() {
    assertFalse(PhotoFitPolicy.honoursFit(""))
    assertFalse(PhotoFitPolicy.honoursFit("notAStyle"))
  }

  @Test fun everyRegisteredStyleHasAnExplicitAnswer() {
    val honouring = ScreensaverConfig.MODES.filter(PhotoFitPolicy::honoursFit).toSet()
    assertEquals(setOf(ScreensaverConfig.MODE_SMART, ScreensaverConfig.MODE_PORTRAITS,
        ScreensaverConfig.MODE_CLASSIC), honouring)
  }
}

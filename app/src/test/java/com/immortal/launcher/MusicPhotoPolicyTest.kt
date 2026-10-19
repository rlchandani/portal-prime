/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class MusicPhotoPolicyTest {
  @Test fun companionNeverSelectsVideoOrALegacyWebRenderer() {
    for (mode in ScreensaverConfig.MODES) {
      val saved = ScreensaverConfig.Settings(mode = mode, includeVideo = true)
      val projected = musicPhotoSettings(saved)
      assertEquals(ScreensaverConfig.MODE_WHOLE, projected.mode)
      assertFalse(projected.includeVideo)
      assertEquals(mode, saved.mode)
      assertTrue(saved.includeVideo)
    }
  }

  @Test fun sourceAndPhotoChoicesArePreservedWithoutWritingTheSavedSettings() {
    val saved = ScreensaverConfig.Settings(source = "folder", folderPath = "/photos",
        shuffle = true, intervalSec = 42, keepPeopleInFrame = true)
    val result = musicPhotoSettings(saved)
    assertEquals(saved.copy(mode = ScreensaverConfig.MODE_WHOLE, includeVideo = false), result)
    assertEquals("/photos", result.folderPath)
    assertEquals("folder", result.source)
  }
}

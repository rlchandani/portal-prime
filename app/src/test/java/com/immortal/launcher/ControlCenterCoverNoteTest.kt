/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** The open/close-only note names blinds only when the cover is one (a charge port or window isn't). */
class ControlCenterCoverNoteTest {
  private fun cover(deviceClass: String?) =
      CcEntity("cover.x", "closed", if (deviceClass == null) emptyMap() else mapOf("device_class" to deviceClass))

  @Test
  fun blindLikeCovers_sayBlinds() {
    for (c in listOf("blind", "shade", "curtain", "shutter", "awning")) {
      assertEquals("These blinds only open and close — Home Assistant doesn't report a position.", CcSheetLogic.coverNote(cover(c)))
    }
  }

  @Test
  fun otherCovers_useNeutralWording() {
    for (c in listOf(null, "door", "window", "damper", "gate")) {
      assertEquals("This only opens and closes — Home Assistant doesn't report a position.", CcSheetLogic.coverNote(cover(c)))
    }
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.SettingsBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jarvis's ring on Settings blocks: the Display editor rings the card with the control, other blocks ring themselves. */
class SettingsJarvisPulseTest {
  @Test fun nightShiftRowsRingTheNightShiftCard() {
    listOf("display.nightShift", "display.from", "display.until", "display.warmth").forEach {
      assertEquals(it, SettingsJarvisPulse.DisplayCard.NIGHT_SHIFT, SettingsJarvisPulse.displayCard(it))
    }
    assertEquals(SettingsJarvisPulse.DisplayCard.BRIGHTNESS, SettingsJarvisPulse.displayCard("display.brightness"))
    assertEquals(SettingsJarvisPulse.DisplayCard.VOLUME, SettingsJarvisPulse.displayCard("display.mediaVolume"))
  }

  @Test fun theDisplayBlockNeverRingsAsAWhole() {
    assertFalse(SettingsJarvisPulse.blockPulses(SettingsBlock.DISPLAY, "display.nightShift"))
    assertFalse(SettingsJarvisPulse.blockPulses(SettingsBlock.MEDIA_VOLUME, "display.mediaVolume"))
  }

  @Test fun otherBlocksRingForTheirOwnRowsAndClaims() {
    assertTrue(SettingsJarvisPulse.blockPulses(SettingsBlock.TOUCH_SOUNDS, "display.touchSounds"))
    assertTrue(SettingsJarvisPulse.blockPulses(SettingsBlock.CHIMES, "chime.anything")) // claims chime.*
    assertFalse(SettingsJarvisPulse.blockPulses(SettingsBlock.TOUCH_SOUNDS, "display.nightShift"))
  }

  @Test fun noPulseRingsNothing() {
    assertFalse(SettingsJarvisPulse.blockPulses(SettingsBlock.TOUCH_SOUNDS, null))
    assertNull(SettingsJarvisPulse.displayCard(null))
    assertNull(SettingsJarvisPulse.displayCard("immortal.settingsTheme"))
  }
}

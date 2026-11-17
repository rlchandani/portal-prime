/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION")
class MemoryTrimPolicyTest {
  private fun kept(level: Int) = MemoryTrimPolicy.retainedFraction(level)

  @Test fun visibleProcessTrimsWithPressure() {
    assertEquals(1f, kept(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE), 0f)
    assertEquals(.5f, kept(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW), 0f)
    assertEquals(0f, kept(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL), 0f)
  }

  @Test fun hiddenUiKeepsAQuarterAndACachedProcessKeepsNothing() {
    assertEquals(.25f, kept(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN), 0f)
    assertEquals(0f, kept(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND), 0f)
    assertEquals(0f, kept(ComponentCallbacks2.TRIM_MEMORY_MODERATE), 0f)
    assertEquals(0f, kept(ComponentCallbacks2.TRIM_MEMORY_COMPLETE), 0f)
  }

  @Test fun unknownLowLevelsKeepEverything() {
    assertEquals(1f, kept(0), 0f)
  }

  @Test fun budgetScalesAndClamps() {
    assertEquals(8, MemoryTrimPolicy.budget(16, .5f))
    assertEquals(0, MemoryTrimPolicy.budget(16, -1f))
    assertEquals(16, MemoryTrimPolicy.budget(16, 2f))
  }

  @Test fun playerOnScreenIsReleasedOnlyWhenTheProcessIsAboutToDie() {
    assertFalse(MemoryTrimPolicy.releasePlayer(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, attached = true))
    assertFalse(MemoryTrimPolicy.releasePlayer(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, attached = true))
    assertTrue(MemoryTrimPolicy.releasePlayer(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, attached = true))
  }

  @Test fun detachedPlayerInItsGraceIsReleasedUnderPressureOrWhenHidden() {
    assertFalse(MemoryTrimPolicy.releasePlayer(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, attached = false))
    assertTrue(MemoryTrimPolicy.releasePlayer(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, attached = false))
    assertTrue(MemoryTrimPolicy.releasePlayer(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, attached = false))
    assertTrue(MemoryTrimPolicy.releasePlayer(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, attached = false))
  }

  @Test fun musicIdleArtBudgetIsBelowTheFullArtCache() {
    assertTrue(MemoryTrimPolicy.MUSIC_IDLE_ART_BYTES < 16 * 1024 * 1024)
  }
}

class MusicPlayerPrewarmMemoryTest {
  private val enough = MusicPlayerPrewarm.MIN_AVAILABLE_BYTES

  @Test fun warmsOnlyWithEnoughFreeMemory() {
    assertTrue(MusicPlayerPrewarm.memoryAllows(lowRamDevice = false, lowMemory = false, availableBytes = enough))
    assertFalse(MusicPlayerPrewarm.memoryAllows(lowRamDevice = false, lowMemory = false, availableBytes = enough - 1))
  }

  @Test fun neverWarmsOnALowRamDeviceOrUnderSystemPressure() {
    assertFalse(MusicPlayerPrewarm.memoryAllows(lowRamDevice = true, lowMemory = false, availableBytes = enough * 4))
    assertFalse(MusicPlayerPrewarm.memoryAllows(lowRamDevice = false, lowMemory = true, availableBytes = enough * 4))
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class SlideshowRecoveryIntentTest {
  @Test fun manualNavigationDuringRecoveryDisplaysTheRecoveredPhotoWhilePaused() {
    val shown = mutableListOf<String>()
    val photos = SlideshowPhotoPause<String>({}, {})
    val recovery = SlideshowRecoveryIntent()
    photos.setPaused(true)
    assertTrue(recovery.request(automatic = true))
    assertFalse(recovery.request(automatic = false))
    photos.requested(recovery.finish())
    photos.deliver("recovered") { shown += it }
    assertEquals(listOf("recovered"), shown)
    assertFalse(photos.pending)
  }

  @Test fun laterAutoplayCannotDowngradeAnOutstandingManualRequest() {
    val recovery = SlideshowRecoveryIntent()
    recovery.request(true)
    recovery.request(false)
    recovery.request(true)
    assertFalse(recovery.finish())
  }

  @Test fun nextIndependentRecoveryCanUseAutoplayAgain() {
    val recovery = SlideshowRecoveryIntent()
    recovery.request(false)
    assertFalse(recovery.finish())
    assertTrue(recovery.request(true))
    assertTrue(recovery.finish())
  }

  @Test fun automaticRecoveryStillHonorsUserPause() {
    val shown = mutableListOf<String>()
    val photos = SlideshowPhotoPause<String>({}, {})
    val recovery = SlideshowRecoveryIntent()
    photos.setPaused(true)
    recovery.request(true)
    photos.requested(recovery.finish())
    photos.deliver("recovered") { shown += it }
    assertTrue(shown.isEmpty())
    assertTrue(photos.pending)
    assertTrue(photos.setPaused(false))
    assertEquals(listOf("recovered"), shown)
  }
}

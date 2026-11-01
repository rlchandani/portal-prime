/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackRetentionTest {
  private val paused = VideoPlaybackRetention(7, 3, 812)

  @Test fun retainedPlayerResumesWithoutSeekingItsCachedPosition() {
    assertFalse(paused.needsSeek(7, 3))
  }

  @Test fun recreatedSurfaceRestoresTheSameClipsPosition() {
    assertTrue(paused.needsSeek(7, 5))
  }

  @Test fun replacementClipNeverReceivesThePreviousClipsPosition() {
    assertFalse(paused.needsSeek(8, 3))
  }

  @Test fun replacementClipWithANewSurfaceStillDoesNotUseAnOldAnchor() {
    assertFalse(paused.needsSeek(8, 5))
  }

  @Test fun emptyOrInvalidPositionNeverRequestsRecovery() {
    assertFalse(VideoPlaybackRetention(7, 3, 0).needsSeek(7, 5))
    assertFalse(VideoPlaybackRetention(7, 3, -1).needsSeek(7, 5))
  }
}

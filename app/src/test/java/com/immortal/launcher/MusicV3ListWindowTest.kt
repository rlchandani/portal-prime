/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertTrue
import org.junit.Test

/** The song lists' prefetch window is sized for a Portal fling, not the one-row default. */
class MusicV3ListWindowTest {
  @Test fun songListsKeepAFlingsWorthOfRowsComposedAroundTheViewport() {
    val large = MusicStageDims.LARGE.row
    assertTrue(MusicV3ListWindow.AHEAD_DP >= 3 * large)
    assertTrue(MusicV3ListWindow.BEHIND_DP >= 2 * large)
  }
}

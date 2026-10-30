/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPositionCacheTest {
  @Test fun nativePositionBelongsOnlyToItsSource() {
    val cache = VideoPositionCache()
    cache.publish(7, 20_000)
    assertEquals(20_000, cache.read(7))
    assertEquals(0, cache.read(8))
  }

  @Test fun delayedOldReadCannotSeekTheReplacementToTheOldPosition() {
    val cache = VideoPositionCache()
    cache.publish(8, 500)
    cache.publish(7, 20_000)
    assertEquals(0, cache.read(8))
    cache.publish(8, 750)
    assertEquals(750, cache.read(8))
  }
}

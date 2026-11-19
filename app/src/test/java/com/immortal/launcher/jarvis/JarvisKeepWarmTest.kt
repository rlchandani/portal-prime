/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keep-warm runs on the first Hearthpane screen and then at most every two minutes. */
class JarvisKeepWarmTest {
  @Test fun theFirstScreenAlwaysWarms() = assertTrue(JarvisKeepWarm.due(nowMs = 5_000L, lastMs = 0L))

  @Test fun aScreenSoonAfterDoesNot() =
      assertFalse(JarvisKeepWarm.due(nowMs = 60_000L, lastMs = 10_000L))

  @Test fun twoMinutesLaterItWarmsAgain() =
      assertTrue(JarvisKeepWarm.due(nowMs = 10_000L + JarvisKeepWarm.MIN_INTERVAL_MS, lastMs = 10_000L))
}

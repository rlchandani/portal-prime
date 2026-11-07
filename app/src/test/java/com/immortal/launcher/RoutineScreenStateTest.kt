/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screensaver must not relaunch its frame over a routine, before or after the takeover starts. */
class RoutineScreenStateTest {
  @After fun reset() { RoutineScreenState.running = false; RoutineScreenState.visible = false }

  @Test fun nothingRunningLeavesTheScreenToTheScreensaver() = assertFalse(RoutineScreenState.ownsScreen())

  @Test fun aRunStartedButNotYetInFrontStillOwnsTheScreen() {
    RoutineScreenState.running = true
    assertTrue(RoutineScreenState.ownsScreen())
  }

  @Test fun aVisibleTakeoverOwnsTheScreen() {
    RoutineScreenState.visible = true
    assertTrue(RoutineScreenState.ownsScreen())
  }
}

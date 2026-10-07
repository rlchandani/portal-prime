/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class JarvisForegroundWakeTest {
  @Test fun openingActivityDoesNotActivateBackgroundMicrophone() {
    var wakes = 0
    val handoff = JarvisForegroundWake { wakes++ }
    handoff.onLaunched()
    assertEquals(0, wakes)
    handoff.onHostStopped()
    assertEquals(1, wakes)
  }

  @Test fun earlyStopStillWaitsForAcceptedLaunch() {
    var wakes = 0
    val handoff = JarvisForegroundWake { wakes++ }
    handoff.onHostStopped()
    assertEquals(0, wakes)
    handoff.onLaunched()
    assertEquals(1, wakes)
  }

  @Test fun lifecycleRepeatsCannotStartDuplicateSessions() {
    var wakes = 0
    val handoff = JarvisForegroundWake { wakes++ }
    repeat(3) { handoff.onLaunched(); handoff.onHostStopped() }
    assertEquals(1, wakes)
  }

  @Test fun failedOrDestroyedHandoffNeverActivatesMicrophone() {
    var wakes = 0
    val handoff = JarvisForegroundWake { wakes++ }
    handoff.cancel()
    handoff.onLaunched()
    handoff.onHostStopped()
    assertEquals(0, wakes)
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ember dot shows only while the policy listens and the mic session runs. */
class WakeIndicatorTest {
  @Test fun shownWhileListeningWithALiveMic() {
    assertTrue(WakeIndicator.shown(WakePolicy.Decision.Listen, micActive = true))
  }

  @Test fun hiddenWhenTheMicIsNotRunningYetOrFailed() {
    assertFalse(WakeIndicator.shown(WakePolicy.Decision.Listen, micActive = false))
    assertFalse(WakeIndicator.shown(null, micActive = false))
  }

  @Test fun hiddenInEveryPauseQuietHoursAndOffIncluded() {
    for (reason in WakePolicy.Reason.values()) {
      assertFalse(reason.name, WakeIndicator.shown(WakePolicy.Decision.Pause(reason), micActive = true))
    }
  }
}

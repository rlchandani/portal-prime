/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.ApprovedLauncherTab.APPS
import com.immortal.launcher.ApprovedLauncherTab.HOME
import com.immortal.launcher.ApprovedLauncherTab.TOOLS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [launcherPageAwake]: which Home tab pages run their work around a switch. */
class LauncherPageWakeTest {
  @Test fun onTheSwitchFrameOnlyThePreviousPageIsAwake() {
    // Home → Apps tapped; Apps has not woken yet.
    val wake = LauncherPageWake(HOME, null)
    assertTrue(launcherPageAwake(HOME, wake, APPS, visible = true))
    assertFalse(launcherPageAwake(APPS, wake, APPS, visible = true))
  }

  @Test fun theReplacedPageSleepsOnlyOnceItsSlideSettles() {
    val wake = LauncherPageWake(APPS, HOME)
    assertTrue("Apps woke", launcherPageAwake(APPS, wake, APPS, visible = true))
    assertTrue("Home still sliding out", launcherPageAwake(HOME, wake, APPS, visible = true))
    assertFalse("Home settled out of view", launcherPageAwake(HOME, wake, APPS, visible = false))
  }

  @Test fun aReplacedPageSelectedAgainWaitsForItsOwnWake() {
    // Apps woke after Home; the user goes back to Home before Home's wake: Home slides in, asleep.
    val wake = LauncherPageWake(APPS, HOME)
    assertFalse(launcherPageAwake(HOME, wake, HOME, visible = true))
    assertTrue("Apps keeps working while it slides out", launcherPageAwake(APPS, wake, HOME, visible = true))
  }

  @Test fun anUnvisitedThirdPageNeverWakes() {
    val wake = LauncherPageWake(APPS, HOME)
    assertFalse(launcherPageAwake(TOOLS, wake, APPS, visible = false))
  }

  @Test fun withoutAWakeDelayOnlyTheSelectedPageIsAwake() {
    val wake = LauncherPageWake(TOOLS, null)
    assertTrue(launcherPageAwake(TOOLS, wake, TOOLS, visible = true))
    assertFalse(launcherPageAwake(APPS, wake, TOOLS, visible = true))
  }
}

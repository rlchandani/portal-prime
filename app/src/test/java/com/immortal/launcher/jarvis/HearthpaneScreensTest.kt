/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The newest started screen is the one in front; a stopped one drops out. */
class HearthpaneScreensTest {
  private fun entry(id: String) = HearthpaneScreens.Entry({ id }) { JSONObject().put("screen", id) }

  @After fun reset() = HearthpaneScreens.clear()

  @Test fun nothingRegisteredIsNoScreen() { assertNull(HearthpaneScreens.current()) }

  @Test fun theNewestStartedScreenWins() {
    HearthpaneScreens.show(entry("home"))
    val settings = HearthpaneScreens.show(entry("settings"))
    assertEquals("settings", HearthpaneScreens.current()?.id?.invoke())
    HearthpaneScreens.hide(settings)
    assertEquals("home", HearthpaneScreens.current()?.id?.invoke())
  }

  @Test fun anOlderScreenStoppingLateKeepsTheNewOne() {
    val home = HearthpaneScreens.show(entry("home"))
    HearthpaneScreens.show(entry("control_center")) // started before Home's onStop
    HearthpaneScreens.hide(home)
    assertEquals("control_center", HearthpaneScreens.current()?.id?.invoke())
  }

  @Test fun idsAreShortLowercaseIds() {
    assertTrue(HearthpaneScreens.isValidId("control_center"))
    assertFalse(HearthpaneScreens.isValidId("Control Center"))
    assertFalse(HearthpaneScreens.isValidId("a".repeat(41)))
    assertFalse(HearthpaneScreens.isValidId(null))
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The one intent every Jarvis entry point sends, and which way a tap goes. */
class JarvisFloatLaunchTest {
  private val base = JarvisFloatLaunch.Request(screen = "control_center", quiet = false, callActive = false,
      light = false, tapUptimeMs = 4_242L)

  @Test fun aPlainTapSendsTheTapTimeAndTheScreen() {
    assertEquals(mapOf(JarvisFloatLaunch.EXTRA_TAP_UPTIME to 4_242L, JarvisFloatLaunch.EXTRA_SCREEN to "control_center"),
        JarvisFloatLaunch.extras(base))
  }

  @Test fun quietHoursACallAndTheLightThemeAreSent() {
    val extras = JarvisFloatLaunch.extras(base.copy(quiet = true, callActive = true, light = true, typing = true))
    assertEquals(true, extras[JarvisFloatLaunch.EXTRA_QUIET])
    assertEquals(true, extras[JarvisFloatLaunch.EXTRA_CALL])
    assertEquals("light", extras[JarvisFloatLaunch.EXTRA_THEME])
    assertEquals("text", extras[JarvisFloatLaunch.EXTRA_MODE])
  }

  @Test fun anInvalidOrMissingScreenIsLeftOut() {
    assertFalse(JarvisFloatLaunch.EXTRA_SCREEN in JarvisFloatLaunch.extras(base.copy(screen = null)))
    assertFalse(JarvisFloatLaunch.EXTRA_SCREEN in JarvisFloatLaunch.extras(base.copy(screen = "Not An Id")))
  }

  @Test fun theKeysMatchJarvisFloatRequest() {
    // Literal mirror of com.portal.assistant.floating.FloatRequest (the apps share no code).
    assertEquals("com.portal.assistant.float.extra.SCREEN", JarvisFloatLaunch.EXTRA_SCREEN)
    assertEquals("com.portal.assistant.float.extra.TAP_UPTIME", JarvisFloatLaunch.EXTRA_TAP_UPTIME)
    assertEquals("com.portal.assistant.floating.FloatActivity", JarvisFloatLaunch.FLOAT_ACTIVITY)
  }

  @Test fun aJarvisWithTheCardGetsTheCardAndAnOlderOneTheFullApp() {
    assertEquals(JarvisFloatLaunch.Route.FLOAT, JarvisFloatLaunch.route(floatInstalled = true))
    assertEquals(JarvisFloatLaunch.Route.FULL_APP, JarvisFloatLaunch.route(floatInstalled = false))
  }

  @Test fun aWakeOpensTheCardInItsWakeStateTimedFromWhenItWasHeard() {
    val extras = JarvisFloatLaunch.extras(base.copy(wake = true))
    assertEquals(JarvisFloatLaunch.MODE_WAKE, extras[JarvisFloatLaunch.EXTRA_MODE])
    assertEquals(4_242L, extras[JarvisFloatLaunch.EXTRA_TAP_UPTIME])
  }

  @Test fun typingWinsOverAWake() {
    assertEquals(JarvisFloatLaunch.MODE_TEXT, JarvisFloatLaunch.extras(base.copy(wake = true, typing = true))[JarvisFloatLaunch.EXTRA_MODE])
  }
}

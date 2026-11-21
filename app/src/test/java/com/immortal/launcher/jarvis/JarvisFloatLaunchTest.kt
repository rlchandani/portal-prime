/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.ActiveCall
import com.immortal.launcher.CallContact
import com.immortal.launcher.CallDirection
import com.immortal.launcher.CallKind
import com.immortal.launcher.CallLeg
import com.immortal.launcher.CallPhase
import com.immortal.launcher.LegPhase
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

  @Test fun aCallSendsWhoItIsWith_soTheMicBusyCardCanNameTheRoom() {
    val extras = JarvisFloatLaunch.extras(base.copy(callActive = true, callWith = "Living Area"))
    assertEquals(true, extras[JarvisFloatLaunch.EXTRA_CALL])
    assertEquals("Living Area", extras[JarvisFloatLaunch.EXTRA_CALL_WITH])
    // Literal mirror of Jarvis 2.7.4's optional FloatRequest extra.
    assertEquals("com.portal.assistant.float.extra.CALL_WITH", JarvisFloatLaunch.EXTRA_CALL_WITH)
  }

  @Test fun noCallMeansNoCallWith_evenIfANameIsLeftOver() {
    assertFalse(JarvisFloatLaunch.EXTRA_CALL_WITH in JarvisFloatLaunch.extras(base.copy(callWith = "Living Area")))
  }

  @Test fun aCallWithNoNameStillSaysCall_soAnOlderCardKeepsCallInProgress() {
    listOf(null, "", "   ").forEach { blank ->
      val extras = JarvisFloatLaunch.extras(base.copy(callActive = true, callWith = blank))
      assertEquals(true, extras[JarvisFloatLaunch.EXTRA_CALL])
      assertFalse(JarvisFloatLaunch.EXTRA_CALL_WITH in extras)
    }
  }

  @Test fun theNameIsOneTrimmedLineCutToTheCardWidth() {
    assertEquals("Kitchen and Den", JarvisFloatLaunch.callWithLine("  Kitchen\n and   Den "))
    val long = "A".repeat(JarvisFloatLaunch.CALL_WITH_MAX_CHARS + 20)
    val cut = JarvisFloatLaunch.callWithLine(long)!!
    assertEquals(JarvisFloatLaunch.CALL_WITH_MAX_CHARS, cut.length)
    assertEquals('…', cut.last())
    assertEquals("B".repeat(JarvisFloatLaunch.CALL_WITH_MAX_CHARS),
        JarvisFloatLaunch.callWithLine("B".repeat(JarvisFloatLaunch.CALL_WITH_MAX_CHARS)))
  }

  @Test fun theActiveCallNamesItsRoom_orEveryoneInAGroupCall() {
    val living = CallContact("living0123456789", "Living Area")
    val call = ActiveCall("0123456789abcdef", living, CallDirection.OUTGOING, CallKind.CALL, true, CallPhase.LIVE, 0, 0,
        cameraOn = true)
    assertEquals("Living Area", JarvisFloatLaunch.callWithOf(call))
    val group = call.copy(legs = listOf(
        CallLeg(CallContact("kitchen012345678", "Kitchen"), LegPhase.LIVE, 0, offerer = true),
        CallLeg(CallContact("priya-phone-0001", "Priya", kind = CallContact.Kind.PHONE), LegPhase.RINGING, 0, offerer = true)))
    assertEquals("Living Area, Kitchen and Priya", JarvisFloatLaunch.callWithOf(group))
    assertEquals(null, JarvisFloatLaunch.callWithOf(null))
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

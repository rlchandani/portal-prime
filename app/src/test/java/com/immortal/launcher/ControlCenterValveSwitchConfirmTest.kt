/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: a water main wired as a switch (`switch.water_main_valve`) is the valve for kids mode,
 * Security, leak alerts and voice, but "Ask before shutting off the water" only checked the valve
 * domain, so the switch shut off the water with no confirm from the screen, the phone or voice.
 */
class ControlCenterValveSwitchConfirmTest {
  private val sw = CcEntity("switch.water_main_valve", "on", mapOf("friendly_name" to "Main valve"))
  private val lamp = CcEntity("switch.lamp", "on", mapOf("friendly_name" to "Lamp"))
  private val asks = ControlCenterConfig.Settings(askValve = true)

  @Test fun shuttingAValveSwitchOffAsksLikeAValve() {
    assertEquals(CcPolicy.Ask.CONFIRM, CcPolicy.ask(sw, CcCalls.power(sw, false), asks))
  }

  @Test fun valveSwitchFollowsTheAskSetting() {
    assertEquals(CcPolicy.Ask.NONE, CcPolicy.ask(sw, CcCalls.power(sw, false), ControlCenterConfig.Settings(askValve = false)))
  }

  @Test fun ordinarySwitchesStillSendDirectly() {
    assertEquals(CcPolicy.Ask.NONE, CcPolicy.ask(lamp, CcCalls.power(lamp, false), asks))
  }

  @Test fun theSendGateRefusesAnUnconfirmedValveSwitchShutOff() {
    assertEquals("needs confirm first", CcPolicy.refusal(sw, CcCalls.power(sw, false), asks, confirmed = false) { emptySet() })
    assertEquals(null, CcPolicy.refusal(sw, CcCalls.power(sw, false), asks, confirmed = true) { emptySet() })
  }

  @Test fun voiceDoesNotRunAValveSwitchShutOffUnconfirmed() {
    val item = CcHomeItem(sw, CcCalls.power(sw, false), null)
    val r = CcHomeRoute.split(listOf(item), asks) { emptySet() }
    assertTrue("ran without asking: ${r.run}", r.run.isEmpty())
  }
}

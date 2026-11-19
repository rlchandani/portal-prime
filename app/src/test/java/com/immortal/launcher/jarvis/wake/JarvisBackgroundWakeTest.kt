/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Hearthpane's wake turns Jarvis's competing background wake off, and touches nothing else. */
class JarvisBackgroundWakeTest {
  private fun status(supported: Boolean, wake: Boolean) =
      JSONObject().put("supported", supported).put("wakeEnabled", wake).put("model", "gemini")

  @Test fun anEnabledBackgroundWakeIsTurnedOff() {
    val body = JarvisBackgroundWake.configurationFor(true, status(supported = true, wake = true))
    assertEquals("""{"wakeEnabled":false}""", body.toString())
  }

  @Test fun nothingIsSentWhenItIsAlreadyOffOrHearthpaneWakeIsOff() {
    assertNull(JarvisBackgroundWake.configurationFor(true, status(supported = true, wake = false)))
    assertNull(JarvisBackgroundWake.configurationFor(false, status(supported = true, wake = true)))
  }

  @Test fun anOlderJarvisWithoutTheControlIsLeftAlone() {
    assertNull(JarvisBackgroundWake.configurationFor(true, status(supported = false, wake = true)))
    assertNull(JarvisBackgroundWake.configurationFor(true, JSONObject()))
  }
}

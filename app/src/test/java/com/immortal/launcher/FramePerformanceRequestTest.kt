/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FramePerformanceRequestTest {
  @Test fun startDefaultsAndBoundsAreExplicit() {
    val d=FramePerformanceRequest.parse(JSONObject().put("action","start"))!!
    assertEquals(60,d.durationSec); assertEquals("all",d.phase); assertFalse(d.sampleMain)
    for (n in listOf(10,900)) assertEquals(n,FramePerformanceRequest.parse(
        JSONObject().put("action","start").put("durationSec",n))!!.durationSec)
  }
  @Test fun invalidDurationsCannotBecomeUnboundedBackgroundCapture() {
    for (v in listOf<Any>(9,901,-1,0,"60",true,60.0,60.5,Long.MAX_VALUE,JSONObject.NULL))
      assertNull(FramePerformanceRequest.parse(JSONObject().put("action","start").put("durationSec",v)))
  }
  @Test fun onlyLiteralActionsAndKnownPhasesAreAccepted() {
    assertNull(FramePerformanceRequest.parse(JSONObject()))
    assertNull(FramePerformanceRequest.parse(JSONObject().put("action","restart")))
    assertNull(FramePerformanceRequest.parse(JSONObject().put("action","start").put("phase","private UI contents")))
    for (phase in listOf("all","home","apps","tools","settings","slideshow","launch","idle"))
      assertEquals(phase,FramePerformanceRequest.parse(JSONObject().put("action","start").put("phase",phase))!!.phase)
  }
  @Test fun stopRejectsStartOnlyFieldsAndUnknownFields() {
    assertEquals("stop",FramePerformanceRequest.parse(JSONObject().put("action","stop"))!!.action)
    assertNull(FramePerformanceRequest.parse(JSONObject().put("action","stop").put("durationSec",60)))
    assertNull(FramePerformanceRequest.parse(JSONObject().put("action","start").put("anything",1)))
  }
  @Test fun samplingAcceptsOnlyShortExplicitCaptures() {
    for (duration in listOf(10, 60)) {
      val request = FramePerformanceRequest.parse(JSONObject().put("action", "start")
          .put("durationSec", duration).put("sampleMain", true))
      assertNotNull(request)
      assertEquals(duration, request!!.durationSec)
      assertTrue(request.sampleMain)
    }
    assertEquals(60, FramePerformanceRequest.parse(JSONObject().put("action", "start")
        .put("sampleMain", true))!!.durationSec)
    for (duration in listOf(61, 900)) assertNull(FramePerformanceRequest.parse(
        JSONObject().put("action", "start").put("durationSec", duration).put("sampleMain", true)))
  }
  @Test fun explicitSamplingFalsePreservesLongFrameCaptures() {
    val legacy = FramePerformanceRequest.parse(JSONObject().put("action", "start").put("durationSec", 900))
    assertNotNull(legacy)
    assertEquals(legacy, FramePerformanceRequest.parse(JSONObject().put("action", "start")
        .put("durationSec", 900).put("sampleMain", false)))
  }
  @Test fun samplingRejectsCoercedOrNullBooleanValues() {
    for (value in listOf<Any>("true", "false", 1, 0, 1.0, JSONObject.NULL, JSONObject())) {
      assertNull(FramePerformanceRequest.parse(JSONObject().put("action", "start").put("sampleMain", value)))
    }
  }
  @Test fun stopCannotEnableOrConfigureSampling() {
    for (value in listOf(false, true)) assertNull(FramePerformanceRequest.parse(
        JSONObject().put("action", "stop").put("sampleMain", value)))
  }
  @Test fun directlyConstructedRequestsCannotBypassSamplingLimits() {
    for ((action, duration) in listOf("start" to 61, "start" to 900, "stop" to 0)) {
      assertThrows(IllegalArgumentException::class.java) {
        FramePerformanceRequest(action, duration, "all", sampleMain = true)
      }
    }
  }
  @Test fun settingsWorkSamplingAcceptsOnlyExplicitBooleansAndKeepsLegacyDefault() {
    val legacy = FramePerformanceRequest.parse(JSONObject().put("action", "start"))!!
    val disabled = FramePerformanceRequest.parse(JSONObject().put("action", "start")
        .put("sampleSettingsWork", false))
    assertNotNull(disabled)
    assertNotEquals(legacy, disabled)
    assertEquals(legacy, FramePerformanceRequest.parse(JSONObject().put("action", "start")
        .put("sampleSettingsWork", true)))
  }

  @Test fun settingsWorkSamplingRejectsCoercionAndStopConfiguration() {
    for (value in listOf<Any>("true", "false", 1, 0, JSONObject.NULL, JSONObject())) {
      assertNull(FramePerformanceRequest.parse(JSONObject().put("action", "start")
          .put("sampleSettingsWork", value)))
    }
    for (value in listOf(false, true)) {
      assertNull(FramePerformanceRequest.parse(JSONObject().put("action", "stop")
          .put("sampleSettingsWork", value)))
    }
  }

}

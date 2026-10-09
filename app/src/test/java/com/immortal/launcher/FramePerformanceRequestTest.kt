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
    assertEquals(60,d.durationSec); assertEquals("all",d.phase)
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
}

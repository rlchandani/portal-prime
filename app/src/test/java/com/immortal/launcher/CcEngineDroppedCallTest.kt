/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: a socket that drops mid-call fails the call (its caller hears false), but the
 * tile kept showing the optimistic state for up to [CcLive.ECHO_MS], then said "didn't respond".
 */
class CcEngineDroppedCallTest {
  @Test fun aCallAbandonedWithItsSocketShowsHomeAssistantsStateAgain() {
    val r = CcEngineRig().apply { connectLive() }
    r.engine.send(CcCalls.power(r.state.entity("light.kitchen")!!, false), true, null, false); r.sched.runDue()
    assertEquals("off", r.state.entity("light.kitchen")?.state)
    r.fail()
    assertTrue(r.state.pending.isEmpty())
    assertEquals("on", r.state.entity("light.kitchen")?.state)
    r.advance(CcLive.ECHO_MS + 100)
    assertNotEquals("Kitchen light didn't respond", r.state.toast?.text)
  }

  @Test fun aRestartAbandonsCallsTheSameWay() {
    val r = CcEngineRig().apply { connectLive() }
    r.engine.send(CcCalls.power(r.state.entity("light.kitchen")!!, false), true, null, false); r.sched.runDue()
    r.engine.retryNow(); r.sched.runDue()
    assertTrue(r.engine.live.pending.isEmpty())
  }
}

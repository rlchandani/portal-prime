/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fleet `cc_debug` `haUnreachable`: the socket is pointed at a dead port so stale/unreachable can be checked. */
class ControlCenterUnreachableTestSwitchTest {

  @Test
  fun unreachableUrl_keepsHostAndPathButUsesTheDiscardPort() {
    assertEquals("ws://ha.local:9/api/websocket", CcTestPlan.unreachableUrl("ws://ha.local:8123/api/websocket"))
    assertEquals("wss://ha.example:9/api/websocket", CcTestPlan.unreachableUrl("wss://ha.example/api/websocket"))
  }

  @Test
  fun unreachableUrl_rejectsUnparseableInput() {
    assertNull(CcTestPlan.unreachableUrl("not a url"))
  }

  @Test
  fun pill_namesTheUnreachableSwitch() {
    assertEquals("Dry run · HA unreachable", CcTestPlan.label(true, false, unreachable = true))
    assertEquals("Dry run · Test plan", CcTestPlan.label(true, true))
  }
}

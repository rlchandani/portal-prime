/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

/** Tests resource/admission policy, not detector accuracy or a simulated detector. */
class SlideshowFaceOwnershipTest {
  @Test fun `timeout keeps owned pixels alive until task actually completes and releases once`() {
    val released = ArrayList<String>()
    val lease = SlideshowFaceLease("derivative") { released.add(it) }
    assertFalse(lease.finish(taskComplete = false))
    assertTrue(released.isEmpty())
    assertTrue(lease.finish(taskComplete = true))
    assertTrue(lease.finish(taskComplete = true))
    assertEquals(listOf("derivative"), released)
  }

  @Test fun `only one SDK call can start and ordinary completion allows reuse`() {
    val gate = SlideshowFaceRunGate(10_000)
    assertTrue(gate.start(100))
    assertFalse(gate.start(101))
    assertTrue(gate.canAdmit(10_099))
    gate.complete()
    assertTrue(gate.start(10_099))
  }

  @Test fun `exact timeout boundary stops admission permanently even after eventual completion`() {
    val gate = SlideshowFaceRunGate(10_000)
    assertTrue(gate.start(100))
    assertTrue(gate.canAdmit(10_099))
    assertFalse(gate.canAdmit(10_100))
    gate.complete()
    assertFalse(gate.start(10_101))
    assertFalse(gate.canAdmit(20_000))
  }

  @Test fun `initialization failure cannot create another detector through a new session`() {
    val gate = SlideshowFaceRunGate(10_000)
    gate.disable()
    gate.complete()
    assertFalse(gate.start(1))
  }
}

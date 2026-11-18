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

/** Auto-placed device dots keep clear of the centred room label and of each other (a 19-device garage). */
class ControlCenterDeviceSlotTest {

  @Test
  fun slots_avoidTheLabelBand() {
    for (n in 0 until 24) {
      val (_, fy) = CcPlanEdit.deviceSlot(n)
      assertTrue("device $n at fy=$fy sits on the label", fy < 0.4f || fy > 0.6f)
      assertTrue(fy in 0.05f..0.95f)
    }
  }

  @Test
  fun slots_areDistinctForACrowdedRoom() {
    val slots = (0 until 24).map { CcPlanEdit.deviceSlot(it) }
    assertEquals(slots.size, slots.toSet().size)
  }

  @Test
  fun firstRow_runsLeftToRightAlongTheTop() {
    assertEquals(0.2f to 0.2f, CcPlanEdit.deviceSlot(0))
    assertEquals(0.8f to 0.2f, CcPlanEdit.deviceSlot(3))
  }
}

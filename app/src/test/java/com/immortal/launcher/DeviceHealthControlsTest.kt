/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class DeviceHealthControlsTest {
  @Test fun connectedControlsAreHealthyRegardlessOfRepairPermission() {
    for (canRepair in listOf(false, true)) {
      val check = DevicePermissions.controlCheck(connected = true, canRepair = canRepair)
      assertEquals("Calls & remote controls", check.title)
      assertTrue(check.granted)
      assertNull(check.repair)
    }
  }

  @Test fun disconnectedControlsWithPermissionOfferReconnectWithoutClaimingHealthy() {
    val check = DevicePermissions.controlCheck(connected = false, canRepair = true)
    assertFalse(check.granted)
    assertEquals(DevicePermissions.Repair.RECONNECT_CONTROLS, check.repair)
    assertTrue(check.fix.contains("Reconnect controls"))
    assertTrue(check.fix.contains("Check again"))
  }

  @Test fun disconnectedControlsWithoutPermissionNeedComputerSetup() {
    val check = DevicePermissions.controlCheck(connected = false, canRepair = false)
    assertFalse(check.granted)
    assertNull(check.repair)
    assertTrue(check.fix.contains("Immortal setup"))
    assertTrue(check.fix.contains("computer"))
  }

  @Test fun otherHealthyCapabilitiesCannotHideDisconnectedControls() {
    val existing = listOf("Screen off", "Now playing", "Install apps", "Frame relaunch", "System settings")
        .map { DevicePermissions.Check(it, "", true, "", "") }
    val checks = existing + DevicePermissions.controlCheck(connected = false, canRepair = true)
    assertEquals(1, checks.count { !it.granted })
    assertFalse(checks.all { it.granted })
  }

  @Test fun existingChecksDoNotAcquireARepairAction() {
    assertNull(DevicePermissions.Check("Screen off", "", false, "", "").repair)
  }
}

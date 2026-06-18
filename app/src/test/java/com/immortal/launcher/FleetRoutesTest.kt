/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decision helpers behind the fleet agent's routes (no Context needed). */
class FleetRoutesTest {

  @Test
  fun modeFor_silentWhenDaemonUp_elsePausedOrDialog() {
    // The daemon (silent path) wins regardless of the paused flag.
    assertEquals("silent", FleetRoutes.modeFor(daemonAvailable = true, paused = true))
    assertEquals("silent", FleetRoutes.modeFor(daemonAvailable = true, paused = false))
    // No daemon: paused on a broken-installer Gen-1, else the system dialog.
    assertEquals("paused", FleetRoutes.modeFor(daemonAvailable = false, paused = true))
    assertEquals("dialog", FleetRoutes.modeFor(daemonAvailable = false, paused = false))
  }

  @Test
  fun terminalResult_mapsStoreStatusStrings() {
    assertEquals(true, FleetRoutes.terminalResult("Installed ✓"))
    assertEquals(false, FleetRoutes.terminalResult("Install failed"))
    assertEquals(false, FleetRoutes.terminalResult("Error: boom"))
    assertEquals(false, FleetRoutes.terminalResult("Paused — connect to your computer to add apps"))
    // Progress messages are not terminal.
    assertNull(FleetRoutes.terminalResult("Downloading…"))
    assertNull(FleetRoutes.terminalResult("Installing…"))
    assertNull(FleetRoutes.terminalResult("Resolving…"))
  }

  @Test
  fun constantTimeEquals_matchesOnlyIdentical() {
    assertTrue(FleetRoutes.constantTimeEquals("abc123", "abc123"))
    assertTrue(FleetRoutes.constantTimeEquals("", ""))
    assertFalse(FleetRoutes.constantTimeEquals("abc123", "abc124"))
    assertFalse(FleetRoutes.constantTimeEquals("abc", "abcd")) // length mismatch
    assertFalse(FleetRoutes.constantTimeEquals("", "x"))
  }
}

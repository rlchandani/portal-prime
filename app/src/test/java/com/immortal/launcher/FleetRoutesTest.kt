/*
 * Copyright (c) 2026 Starbright Lab.
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
  fun progressDoesNotInvalidateButEveryTerminalOutcomeDoes() {
    var invalidations = 0
    val invalidate = { invalidations++; Unit }
    assertNull(FleetRoutes.terminalResultAndInvalidate("Downloading…", invalidate))
    assertEquals(0, invalidations)
    assertEquals(true, FleetRoutes.terminalResultAndInvalidate("Installed ✓", invalidate))
    assertEquals(1, invalidations)
    assertEquals(false, FleetRoutes.terminalResultAndInvalidate("Error: generated failure", invalidate))
    assertEquals(2, invalidations)
  }

  @Test
  fun lateCompletionRefreshesInventoryRebuiltAfterWaiterTimedOut() {
    val context = org.mockito.Mockito.mock(android.content.Context::class.java)
    org.mockito.Mockito.`when`(context.applicationContext).thenReturn(context)
    var version = 1L
    val cache = InstalledVersionInventory({ 100L }, { _, _ -> true }, { _, _ -> mapOf("app" to version) })
    assertEquals(1L, requireNotNull(cache.snapshot(context, setOf("app"))).versionCode("app"))
    // The HTTP waiter expired, but the installer is still working. A poll in this gap
    // legitimately sees the old package and can populate a new five-second snapshot.
    cache.invalidate()
    assertEquals(1L, requireNotNull(cache.snapshot(context, setOf("app"))).versionCode("app"))
    version = 2L
    assertEquals(true, FleetRoutes.terminalResultAndInvalidate("Installed ✓", cache::invalidate))
    assertEquals(2L, requireNotNull(cache.snapshot(context, setOf("app"))).versionCode("app"))
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

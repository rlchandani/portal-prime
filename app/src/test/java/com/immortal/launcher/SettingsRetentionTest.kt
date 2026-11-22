/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ComponentCallbacks2
import com.immortal.launcher.SettingsRetention.Exit
import com.immortal.launcher.SettingsRetention.Reopen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings kept alive between opens: when it parks, how a reopen resets, and when it is released. */
@Suppress("DEPRECATION")
class SettingsRetentionTest {
  @Test fun backAtTheTopParksUnlessAlreadyFinishingOrDisabled() {
    assertEquals(Exit.PARK, SettingsRetention.exitOnBack(finishing = false, retentionEnabled = true))
    assertEquals(Exit.FINISH, SettingsRetention.exitOnBack(finishing = true, retentionEnabled = true))
    assertEquals(Exit.FINISH, SettingsRetention.exitOnBack(finishing = false, retentionEnabled = false))
  }

  @Test fun aReopenAfterBackIsLightAndAfterAnyOtherExitIsFull() {
    assertEquals(Reopen.LIGHT, SettingsRetention.reopen(leftTask = true, parked = true))
    assertEquals("Home press, screensaver, a sub-screen left behind", Reopen.FULL,
        SettingsRetention.reopen(leftTask = true, parked = false))
  }

  @Test fun aDeepLinkWhileSettingsOrItsSubScreenIsOnScreenOnlyNavigates() {
    assertEquals(Reopen.NONE, SettingsRetention.reopen(leftTask = false, parked = false))
    assertEquals(Reopen.NONE, SettingsRetention.reopen(leftTask = false, parked = true))
  }

  @Test fun memoryPressureReleasesAHiddenSettingsOnly() {
    val pressure = listOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, ComponentCallbacks2.TRIM_MEMORY_MODERATE,
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    pressure.forEach { level ->
      assertTrue("level $level", SettingsRetention.releaseOnTrim(level, visible = false))
      assertFalse("never while on screen ($level)", SettingsRetention.releaseOnTrim(level, visible = true))
    }
  }

  @Test fun uiHiddenAndModerateRunningAreNotPressure() {
    assertFalse(SettingsRetention.releaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, visible = false))
    assertFalse(SettingsRetention.releaseOnTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, visible = false))
  }

  @Test fun idleReleaseWaitsTheFullWindowFromTheStop() {
    val idle = SettingsRetention.IDLE_RELEASE_MS
    assertFalse(SettingsRetention.releaseWhenIdle(stoppedAtMs = null, nowMs = 10 * idle))
    assertFalse(SettingsRetention.releaseWhenIdle(stoppedAtMs = 1_000L, nowMs = 1_000L + idle - 1))
    assertTrue(SettingsRetention.releaseWhenIdle(stoppedAtMs = 1_000L, nowMs = 1_000L + idle))
    assertEquals(10 * 60 * 1000L, idle)
  }

  @Test fun aSubScreenOnScreenKeepsTheTaskAlive() {
    assertTrue(SettingsRetention.releasable(settingsVisible = false, startedSubScreens = 0))
    assertFalse(SettingsRetention.releasable(settingsVisible = false, startedSubScreens = 1))
    assertFalse(SettingsRetention.releasable(settingsVisible = true, startedSubScreens = 0))
  }

  @Test fun reopenResetsRunEveryRegisteredResetAndSurviveAFailingOne() {
    val resets = SettingsReopenResets()
    val ran = mutableListOf<String>()
    resets.register("sidebar") { ran += "sidebar" }
    resets.register("broken") { error("boom") }
    resets.register("page") { ran += "page" }
    assertEquals(1, resets.runAll())
    assertEquals(listOf("sidebar", "page"), ran)
  }

  @Test fun anUnregisteredOwnerIsNoLongerResetAndReRegisteringReplaces() {
    val resets = SettingsReopenResets()
    var count = 0
    resets.register("page") { count += 1 }
    resets.register("page") { count += 10 }
    assertEquals(1, resets.size)
    resets.runAll()
    assertEquals(10, count)
    resets.unregister("page")
    assertEquals(0, resets.size)
    assertEquals(0, resets.runAll())
    assertEquals(10, count)
  }

  @Test fun theViewportMemoryForgetsEveryPositionOnClear() {
    val memory = ApprovedSettingsViewportMemory()
    memory.save("list:display", ApprovedSettingsViewportMemory.Position(3, 40))
    memory.clear()
    assertEquals(null, memory.read("list:display"))
  }
}

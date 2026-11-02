/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallAutoConfirmWindowTest {
  @Test fun newWindowRemainsDormantUntilArmed() {
    val clock = FakeClock()
    val window = InstallAutoConfirmWindow(clock = { clock.nowMs })

    assertFalse(window.isArmed())
    clock.advanceBy(60_000L)
    assertFalse(window.isArmed())
  }

  @Test fun releasingOlderLeasePreservesNewerOwner() {
    val clock = FakeClock()
    val window = InstallAutoConfirmWindow(clock = { clock.nowMs })
    val first = window.arm()
    val second = window.arm()

    window.disarm(first)
    assertTrue(window.isArmed())

    window.disarm(second)
    assertFalse(window.isArmed())
  }

  @Test fun releasingNewerLeasePreservesOlderOwner() {
    val clock = FakeClock()
    val window = InstallAutoConfirmWindow(clock = { clock.nowMs })
    val first = window.arm()
    val second = window.arm()

    window.disarm(second)
    assertTrue(window.isArmed())

    window.disarm(first)
    assertFalse(window.isArmed())
  }

  @Test fun releasingLastLeaseMakesWindowDormantImmediately() {
    val clock = FakeClock()
    val window = InstallAutoConfirmWindow(clock = { clock.nowMs })
    val lease = window.arm()
    assertTrue(window.isArmed())

    window.disarm(lease)
    assertFalse(window.isArmed())
    clock.advanceBy(1L)
    assertFalse(window.isArmed())
  }

  @Test fun releasingSameLeaseTwiceCannotClearAnotherOwner() {
    val clock = FakeClock()
    val window = InstallAutoConfirmWindow(clock = { clock.nowMs })
    val first = window.arm()
    val second = window.arm()

    window.disarm(first)
    window.disarm(first)
    assertTrue(window.isArmed())

    window.disarm(second)
    assertFalse(window.isArmed())
  }

  @Test fun releasingExpiredLeaseCannotClearNewerOwner() {
    val clock = FakeClock()
    val window = InstallAutoConfirmWindow(clock = { clock.nowMs })
    val expired = window.arm()
    clock.advanceBy(30_000L)
    val newer = window.arm()

    window.disarm(expired)
    assertTrue(window.isArmed())

    window.disarm(newer)
    assertFalse(window.isArmed())
  }

  @Test fun secondArmDoesNotRefreshFirstLeasesThirtySecondExpiry() {
    val clock = FakeClock()
    val window = InstallAutoConfirmWindow(clock = { clock.nowMs })
    window.arm()
    clock.advanceBy(10_000L)
    val second = window.arm()
    clock.advanceBy(19_999L)

    window.disarm(second)
    assertTrue(window.isArmed())

    clock.advanceBy(1L)
    assertFalse(window.isArmed())
  }

  @Test fun laterLeaseExpiresThirtySecondsAfterItsOwnArm() {
    val clock = FakeClock()
    val window = InstallAutoConfirmWindow(clock = { clock.nowMs })
    val first = window.arm()
    clock.advanceBy(10_000L)
    window.arm()
    clock.advanceBy(20_000L)

    assertTrue(window.isArmed())
    window.disarm(first)
    assertTrue(window.isArmed())

    clock.advanceBy(9_999L)
    assertTrue(window.isArmed())
    clock.advanceBy(1L)
    assertFalse(window.isArmed())
  }

  @Test fun foreignWindowLeaseCannotClearActiveOwner() {
    val clock = FakeClock()
    val owner = InstallAutoConfirmWindow(clock = { clock.nowMs })
    val foreign = InstallAutoConfirmWindow(clock = { clock.nowMs })
    val ownedLease = owner.arm()
    val foreignLease = foreign.arm()

    owner.disarm(foreignLease)
    assertTrue(owner.isArmed())
    assertTrue(foreign.isArmed())

    owner.disarm(ownedLease)
    assertFalse(owner.isArmed())
    assertTrue(foreign.isArmed())

    foreign.disarm(foreignLease)
    assertFalse(foreign.isArmed())
  }

  @Test fun canReacquireAfterEveryLeaseExpires() {
    val clock = FakeClock()
    val window = InstallAutoConfirmWindow(clock = { clock.nowMs })
    val first = window.arm()
    clock.advanceBy(10_000L)
    val second = window.arm()
    clock.advanceBy(30_000L)
    assertFalse(window.isArmed())

    window.arm()
    window.disarm(first)
    window.disarm(second)
    assertTrue(window.isArmed())

    clock.advanceBy(29_999L)
    assertTrue(window.isArmed())
    clock.advanceBy(1L)
    assertFalse(window.isArmed())
  }

  private class FakeClock(var nowMs: Long = 1_000L) {
    fun advanceBy(elapsedMs: Long) {
      nowMs += elapsedMs
    }
  }
}

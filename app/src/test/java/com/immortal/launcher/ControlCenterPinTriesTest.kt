/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ControlCenterPinTriesTest {
  private class MemoryStore : CcPinTriesStore {
    var r: CcPinTriesRecord? = null
    var writes = 0
    override fun load() = r
    override fun save(r: CcPinTriesRecord?) { this.r = r; writes++ }
  }

  private val wall0 = 1_800_000_000_000L
  private fun at(elapsed: Long, wall: Long = wall0 + elapsed, boot: Int = 7) = CcPinClock(elapsed, wall, boot)

  @Before fun fresh() = CcKidsPinAttempts.reset()
  @After fun clean() = CcKidsPinAttempts.reset()

  private fun wrong(e: CcPinEntry, now: Long): CcPinEntry = e.copy(digits = "9999", busy = true).failed(now)

  @Test fun triesSurviveAnAppRestart() {
    val store = MemoryStore()
    var e = CcKidsPinAttempts.open(at(10_000), store)
    repeat(3) { e = wrong(e, 10_000); CcKidsPinAttempts.keep(e, at(10_000), store) }
    CcKidsPinAttempts.forgetProcess()
    val after = CcKidsPinAttempts.open(at(20_000), store)
    assertEquals(3, after.failures)
    assertFalse(after.locked(20_000))
    assertNull(after.error)
  }

  @Test fun lockoutSurvivesAnAppRestartOnTheMonotonicClock() {
    val store = MemoryStore()
    var e = CcKidsPinAttempts.open(at(50_000), store)
    repeat(CcPinEntry.MAX_TRIES) { e = wrong(e, 50_000); CcKidsPinAttempts.keep(e, at(50_000), store) }
    assertTrue(e.locked(50_000))
    CcKidsPinAttempts.forgetProcess()
    // 10 s later in the same boot; the wall clock was set back an hour: the rest still ends 20 s from now.
    val c = at(60_000, wall = wall0 + 60_000 - 3_600_000)
    val after = CcKidsPinAttempts.open(c, store)
    assertTrue(after.locked(79_999))
    assertFalse(after.locked(80_000))
    assertEquals(CcPinEntry.LOCKED, after.error)
    assertEquals(after to null, after.press('1', 70_000))
  }

  @Test fun wallClockSetForwardCannotEndTheRestInTheSameBoot() {
    val r = CcPinTriesClock.record(0, 80_000, at(50_000))
    val restored = CcPinTriesClock.restore(r, at(55_000, wall = wall0 + 55_000 + 86_400_000))
    assertEquals(80_000, restored.lockedUntil)
  }

  @Test fun afterARebootTheWallClockCarriesTheRest() {
    val r = CcPinTriesClock.record(2, 80_000, at(50_000)) // 30 s rest, ends at wall0 + 80 s
    // Rebooted (boot count moved, elapsed restarted); 12 s of wall time passed.
    val restored = CcPinTriesClock.restore(r, CcPinClock(4_000, wall0 + 62_000, 8))
    assertEquals(2, restored.failures)
    assertEquals(4_000 + 18_000, restored.lockedUntil)
  }

  @Test fun afterARebootAClockSetBackExtendsTheRestByAtMostOneRest() {
    val r = CcPinTriesClock.record(0, 80_000, at(50_000))
    val restored = CcPinTriesClock.restore(r, CcPinClock(4_000, wall0 - 86_400_000, 8))
    assertEquals(4_000 + CcPinEntry.LOCKOUT_MS, restored.lockedUntil)
  }

  @Test fun afterARebootAnExpiredRestIsOverButTheCountStays() {
    val r = CcPinTriesClock.record(4, 0, at(50_000))
    val restored = CcPinTriesClock.restore(r, CcPinClock(4_000, wall0 + 600_000, 8))
    assertEquals(4, restored.failures)
    assertEquals(0, restored.lockedUntil)
  }

  @Test fun rebootIsSeenWithoutABootCount() {
    val unknown = CcPinClock.UNKNOWN_BOOT
    val r = CcPinTriesClock.record(0, 80_000, CcPinClock(50_000, wall0 + 50_000, unknown))
    // Elapsed went backwards: certainly a reboot.
    assertFalse(CcPinTriesClock.sameBoot(r, CcPinClock(4_000, wall0 + 62_000, unknown)))
    // Elapsed moved on but the boot instant jumped by an hour: a reboot after a long time.
    assertFalse(CcPinTriesClock.sameBoot(r, CcPinClock(3_700_000, wall0 + 7_300_000, unknown)))
    // Same boot, small NTP correction.
    assertTrue(CcPinTriesClock.sameBoot(r, CcPinClock(60_000, wall0 + 60_000 + 30_000, unknown)))
  }

  @Test fun bootCountWinsOverTheEstimate() {
    val r = CcPinTriesClock.record(0, 80_000, at(50_000, boot = 7))
    // Elapsed is larger and the estimate matches, but the boot count moved: rebooted.
    assertFalse(CcPinTriesClock.sameBoot(r, at(60_000, boot = 8)))
    assertTrue(CcPinTriesClock.sameBoot(r, at(60_000, boot = 7)))
  }

  @Test fun corruptRecordsAreBounded() {
    val r = CcPinTriesRecord(failures = 99, lockedUntilElapsed = Long.MAX_VALUE / 2, lockedUntilWall = Long.MAX_VALUE / 2,
        savedElapsed = 50_000, savedWall = wall0 + 50_000, boot = 7)
    val restored = CcPinTriesClock.restore(r, at(60_000))
    assertEquals(CcPinEntry.MAX_TRIES - 1, restored.failures)
    assertEquals(60_000 + CcPinEntry.LOCKOUT_MS, restored.lockedUntil)
  }

  @Test fun theRightPinClearsTheStoreAndDigitsDoNotWrite() {
    val store = MemoryStore()
    var e = CcKidsPinAttempts.open(at(1_000), store)
    e = wrong(e, 1_000); CcKidsPinAttempts.keep(e, at(1_000), store)
    val writes = store.writes
    repeat(4) { e = e.press('1', 1_100).first; CcKidsPinAttempts.keep(e, at(1_100), store) }
    assertEquals(writes, store.writes)
    CcKidsPinAttempts.reset(store)
    assertNull(store.r)
    CcKidsPinAttempts.forgetProcess()
    assertEquals(0, CcKidsPinAttempts.open(at(2_000), store).failures)
  }

  @Test fun inProcessEntryWinsOverTheStore() {
    val store = MemoryStore()
    store.r = CcPinTriesClock.record(4, 0, at(1_000))
    val opened = CcKidsPinAttempts.open(at(2_000), store)
    assertEquals(4, opened.failures)
    store.r = null
    assertEquals(4, CcKidsPinAttempts.open(at(3_000), store).failures)
  }
}

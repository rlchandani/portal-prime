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

class ControlCenterPinPadStateTest {
  private fun type(e: CcPinEntry, keys: String, now: Long = 0L): Pair<CcPinEntry, String?> {
    var cur = e; var code: String? = null
    keys.forEach { k -> val (n, c) = cur.press(k, now); cur = n; if (c != null) code = c }
    return cur to code
  }

  @Test fun submitsOnceWhenTheLastDigitArrives() {
    val (e, code) = type(CcPinEntry(4), "258")
    assertNull(code); assertEquals("258", e.digits); assertFalse(e.busy)
    val (done, full) = e.press('0', 0)
    assertEquals("2580", full); assertTrue(done.busy)
    assertEquals(done to null, done.press('1', 0))
  }

  @Test fun deleteRemovesTheLastDigitAndIgnoresJunk() {
    val (e, _) = type(CcPinEntry(6), "12<3x")
    assertEquals("13", e.digits)
    assertEquals("", type(CcPinEntry(4), "<<").first.digits)
  }

  @Test fun failureClearsShakesAndShowsWrongCode() {
    val (busy, _) = type(CcPinEntry(4), "1111")
    val f = busy.failed(1_000)
    assertEquals("", f.digits); assertFalse(f.busy); assertEquals(1, f.shakes); assertEquals(CcPinEntry.WRONG, f.error)
    val retyped = f.press('5', 1_001).first
    assertNull(retyped.error)
  }

  @Test fun fiveFailuresRestThePad() {
    var e = CcPinEntry(4)
    repeat(CcPinEntry.MAX_TRIES) { e = e.failed(10_000) }
    assertTrue(e.locked(10_001))
    assertEquals(0, e.failures)
    assertEquals(e to null, e.press('1', 20_000))
    assertFalse(e.locked(10_000 + CcPinEntry.LOCKOUT_MS))
    assertEquals("1", e.press('1', 10_000 + CcPinEntry.LOCKOUT_MS).first.digits)
  }

  @Test fun uncheckedDoesNotCountAsFailure() {
    val (busy, _) = type(CcPinEntry(4), "1234")
    val u = busy.unchecked("Home Assistant isn't answering")
    assertEquals(0, u.failures); assertEquals(0, u.shakes); assertFalse(u.busy); assertEquals("", u.digits)
  }

  // --- leaving kids mode (bug: no lasting attempt limit, and the dots showed the PIN's length) ---

  @Test fun withoutAutoSubmitTheLengthIsSecretAndEnterSubmits() {
    val (typed, code) = type(CcPinEntry(8, autoSubmit = false), "1234")
    assertNull(code); assertFalse(typed.busy)
    val (busy, full) = typed.press(CcPinEntry.ENTER, 0)
    assertEquals("1234", full); assertTrue(busy.busy)
    val empty = CcPinEntry(8, autoSubmit = false)
    assertEquals(empty to null, empty.press(CcPinEntry.ENTER, 0))
    assertEquals("12345678", type(empty, "123456789").first.digits)
    assertNull(type(empty, "12345678").second)
  }

  @Test fun enterDoesNothingOnAnAutoSubmitPad() {
    val (e, _) = type(CcPinEntry(4), "12")
    assertEquals(e to null, e.press(CcPinEntry.ENTER, 0))
  }

  @Test fun kidsPadKeepsTriesAndRestAcrossReopening() {
    CcKidsPinAttempts.reset()
    val fresh = CcKidsPinAttempts.open(0)
    assertFalse(fresh.autoSubmit); assertEquals(ControlCenterConfig.MAX_PIN, fresh.length)
    var e = fresh
    repeat(CcPinEntry.MAX_TRIES - 1) { e = type(e, "9999").first.press(CcPinEntry.ENTER, 0).first.failed(1_000) }
    CcKidsPinAttempts.keep(e)
    val reopened = CcKidsPinAttempts.open(2_000)
    assertEquals(CcPinEntry.MAX_TRIES - 1, reopened.failures)
    assertEquals("", reopened.digits); assertNull(reopened.error)
    val rested = reopened.failed(3_000)
    CcKidsPinAttempts.keep(rested)
    val whileResting = CcKidsPinAttempts.open(4_000)
    assertTrue(whileResting.locked(4_000))
    assertEquals(whileResting to null, whileResting.press('1', 4_000))
    assertEquals(rested.error, whileResting.error)
    CcKidsPinAttempts.reset()
    assertEquals(0, CcKidsPinAttempts.open(4_000).failures)
    assertFalse(CcKidsPinAttempts.open(4_000).locked(4_000))
  }
}

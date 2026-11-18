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
}

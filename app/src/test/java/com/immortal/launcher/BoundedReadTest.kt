/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A peer's reply is capped while streaming and within a total time budget. */
class BoundedReadTest {

  /** An endless stream that counts what was pulled from it. */
  private class Endless : InputStream() {
    var pulled = 0L
    override fun read(): Int { pulled++; return 'x'.code }
    override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill('x'.code.toByte(), off, off + len); pulled += len; return len }
  }

  @Test fun aReplyAtOrUnderTheCapIsReturnedWhole() {
    assertEquals("", BoundedRead.utf8(ByteArray(0).inputStream(), 10))
    assertEquals("abc", BoundedRead.utf8("abc".byteInputStream(), 3))
    val big = ByteArray(100_000) { (it % 251).toByte() }
    assertArrayEquals(big, BoundedRead.bytes(big.inputStream(), 100_000))
    assertEquals("é", BoundedRead.utf8("é".byteInputStream(), 2))
  }

  @Test fun oneByteOverTheCapIsRefused() {
    assertNull(BoundedRead.utf8("abcd".byteInputStream(), 3))
    assertNull(BoundedRead.bytes(ByteArray(40_000).inputStream(), 32_000))
    assertNull(BoundedRead.bytes("x".byteInputStream(), 0))
  }

  @Test fun anEndlessReplyIsCutOffJustPastTheCapNotBufferedFirst() {
    val endless = Endless()
    assertNull(BoundedRead.bytes(endless, 1024 * 1024))
    assertTrue("pulled ${endless.pulled}", endless.pulled <= 1024 * 1024 + 1)
  }

  @Test fun aTricklingPeerIsCutOffAtTheBudget() {
    var now = 0L
    val trickle = object : InputStream() {
      override fun read(): Int = 'x'.code
      override fun read(b: ByteArray, off: Int, len: Int): Int { now += 1_000; b[off] = 'x'.code.toByte(); return 1 }
    }
    assertNull(BoundedRead.bytes(trickle, 1024 * 1024, budgetMs = 5_000, clock = { now }))
    assertTrue("stopped near the budget, at $now ms", now in 5_000..7_000)
  }

  @Test fun aDeclaredLengthOverTheCapIsRefusedBeforeReading() {
    assertTrue(BoundedRead.declaredFits(-1, 10)) // chunked: capped while streaming instead
    assertTrue(BoundedRead.declaredFits(10, 10))
    assertFalse(BoundedRead.declaredFits(11, 10))
    assertFalse(BoundedRead.declaredFits(Long.MAX_VALUE, Int.MAX_VALUE))
  }
}

/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/** Sealed chunks: round trips, sizes, and every way a message could be forged or cut short. */
class CallMessageCryptoTest {
  private val id = "0123456789abcdef"
  private val household = ByteArray(32) { it.toByte() }
  private val key = CallMessageCrypto.transitKey(household, id)
  private val chunk = CallMessageLimits.CHUNK

  private fun plain(n: Int) = Random(n).nextBytes(n)

  private fun seal(p: ByteArray, k: ByteArray = key, msg: String = id): ByteArray =
      ByteArrayOutputStream().also { CallMessageCrypto.seal(k, msg, ByteArrayInputStream(p), p.size.toLong(), it) }.toByteArray()

  private fun open(sealed: ByteArray, k: ByteArray = key, msg: String = id, writes: Int = 7_001): ByteArray {
    val out = ByteArrayOutputStream()
    val opener = CallMessageCrypto.Opener(k, msg, sealed.size.toLong()) { _, _, b, len -> out.write(b, 0, len) }
    var i = 0
    while (i < sealed.size) { val n = minOf(writes, sealed.size - i); opener.write(sealed, i, n); i += n }
    opener.finish()
    return out.toByteArray()
  }

  @Test fun roundTripsAcrossChunkBoundaries() {
    for (n in listOf(1, 100, chunk - 1, chunk, chunk + 1, 3 * chunk, 3 * chunk + 17)) {
      val p = plain(n)
      val s = seal(p)
      assertEquals(CallMessageCrypto.sealedLength(n.toLong()), s.size.toLong())
      assertEquals(n.toLong(), CallMessageCrypto.plainLength(s.size.toLong()))
      assertArrayEquals(p, open(s))
      assertArrayEquals(p, open(s, writes = 1 + n % 5))
    }
  }

  @Test fun lengthsThatNoMessageCanHaveAreRefused() {
    assertEquals(-1, CallMessageCrypto.plainLength(0))
    assertEquals(-1, CallMessageCrypto.plainLength(16))
    assertEquals(-1, CallMessageCrypto.plainLength(CallMessageCrypto.SEALED_CHUNK + 10L))
    assertEquals(1, CallMessageCrypto.chunkCount(1))
    assertEquals(2, CallMessageCrypto.chunkCount(chunk + 1L))
  }

  @Test fun aFlippedBitFailsTheTag() {
    val s = seal(plain(2 * chunk + 5))
    s[chunk + 40] = (s[chunk + 40].toInt() xor 1).toByte()
    assertThrows(CallMessageCrypto.Tampered::class.java) { open(s) }
  }

  @Test fun wrongKeyOrMessageIdFails() {
    val s = seal(plain(500))
    assertThrows(CallMessageCrypto.Tampered::class.java) { open(s, k = CallMessageCrypto.transitKey(ByteArray(32), id)) }
    assertThrows(CallMessageCrypto.Tampered::class.java) { open(s, msg = "fedcba9876543210") }
  }

  @Test fun truncationAtAChunkBoundaryFails() {
    val s = seal(plain(3 * chunk))
    val cut = s.copyOf(2 * CallMessageCrypto.SEALED_CHUNK)
    // The second chunk was sealed as not-final; read as the last one its AAD no longer matches.
    assertThrows(CallMessageCrypto.Tampered::class.java) { open(cut) }
  }

  @Test fun reorderedChunksFail() {
    val s = seal(plain(2 * chunk + 9))
    val c = CallMessageCrypto.SEALED_CHUNK
    val swapped = s.copyOfRange(c, 2 * c) + s.copyOfRange(0, c) + s.copyOfRange(2 * c, s.size)
    assertThrows(CallMessageCrypto.Tampered::class.java) { open(swapped) }
  }

  @Test fun shortOrLongBodiesFail() {
    val s = seal(plain(chunk + 3))
    val opener = CallMessageCrypto.Opener(key, id, s.size.toLong()) { _, _, _, _ -> }
    opener.write(s, 0, s.size - 1)
    assertThrows(CallMessageCrypto.Tampered::class.java) { opener.finish() }
    val o2 = CallMessageCrypto.Opener(key, id, s.size.toLong()) { _, _, _, _ -> }
    assertThrows(CallMessageCrypto.Tampered::class.java) { o2.write(s + byteArrayOf(1), 0, s.size + 1) }
  }

  @Test fun transitAndRestKeysDifferPerMessage() {
    assertFalse(CallMessageCrypto.transitKey(household, id).contentEquals(CallMessageCrypto.transitKey(household, "fedcba9876543210")))
    assertFalse(CallMessageCrypto.restKey(household, id).contentEquals(CallMessageCrypto.transitKey(household, id)))
  }

  @Test fun readerSeeksAnywhere() {
    val p = plain(3 * chunk + 77)
    val f = File.createTempFile("vmsg", ".hpv").apply { deleteOnExit(); writeBytes(seal(p)) }
    CallMessageCrypto.Reader(f, key, id).use { r ->
      assertEquals(p.size.toLong(), r.plainBytes)
      for (pos in listOf(0L, 5L, chunk - 2L, chunk.toLong(), 2L * chunk + 10, p.size - 1L)) {
        val buf = ByteArray(10)
        val n = r.read(pos, buf, 0, 10)
        assertTrue(n > 0)
        assertArrayEquals(p.copyOfRange(pos.toInt(), pos.toInt() + n), buf.copyOf(n))
      }
      assertEquals(-1, r.read(p.size.toLong(), ByteArray(4), 0, 4))
    }
  }

  @Test fun readerRefusesATamperedFile() {
    val s = seal(plain(chunk + 1))
    s[3] = (s[3].toInt() xor 0x40).toByte()
    val f = File.createTempFile("vmsg", ".hpv").apply { deleteOnExit(); writeBytes(s) }
    CallMessageCrypto.Reader(f, key, id).use { r -> assertThrows(CallMessageCrypto.Tampered::class.java) { r.read(0, ByteArray(4), 0, 4) } }
    val bad = File.createTempFile("vmsg", ".hpv").apply { deleteOnExit(); writeBytes(ByteArray(10)) }
    assertThrows(CallMessageCrypto.Tampered::class.java) { CallMessageCrypto.Reader(bad, key, id) }
  }
}

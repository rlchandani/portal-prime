/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class IntercomCipherStreamsTest {
  private val sessionKey = ByteArray(IntercomAuth.SESSION_KEY_SIZE) { (it * 3).toByte() }
  private val pcm = ByteArray(2048) { (it % 251).toByte() }

  @Test fun sealedAudioRoundTripsThroughThePcmReceiveLoop() {
    val wire = seal(pcm, pcm.copyOf(10))
    val played = ByteArrayOutputStream()
    LanPcmStream.copy16BitMono(IntercomOpeningInputStream(ByteArrayInputStream(wire), sessionKey), { true }) { b, o, n ->
      played.write(b, o, n); n
    }
    assertArrayEquals(pcm + pcm.copyOf(10), played.toByteArray())
  }

  @Test fun ciphertextDoesNotContainThePlainPcm() {
    val wire = seal(pcm)
    assertEquals(2 + pcm.size + IntercomFrames.TAG_BYTES, wire.size)
    assertFalse(wire.copyOfRange(2, 2 + 64).contentEquals(pcm.copyOf(64)))
  }

  @Test fun largeWritesAreSplitIntoBoundedFrames() {
    val big = ByteArray(IntercomFrames.MAX_PLAINTEXT * 2 + 5) { it.toByte() }
    assertArrayEquals(big, open(seal(big), sessionKey))
  }

  @Test fun tamperedFrameEndsPlayback() {
    val wire = seal(pcm).also { it[10] = (it[10].toInt() xor 1).toByte() }
    assertThrows(IOException::class.java) { open(wire, sessionKey) }
  }

  @Test fun wrongSessionKeyEndsPlayback() {
    assertThrows(IOException::class.java) { open(seal(pcm), ByteArray(IntercomAuth.SESSION_KEY_SIZE)) }
  }

  @Test fun replayedOrReorderedFrameEndsPlayback() {
    val one = seal(pcm)
    assertThrows(IOException::class.java) { open(one + one, sessionKey) }
  }

  @Test fun oversizedFrameIsRejectedBeforeReadingItsBody() {
    val size = IntercomFrames.MAX_FRAME + 1
    val wire = byteArrayOf((size ushr 8).toByte(), size.toByte())
    assertThrows(IOException::class.java) { open(wire, sessionKey) }
  }

  @Test fun tagOnlyOrTruncatedFramesAreRejected() {
    val tagOnly = IntercomFrames.TAG_BYTES
    assertThrows(IOException::class.java) {
      open(byteArrayOf(0, tagOnly.toByte()) + ByteArray(tagOnly), sessionKey)
    }
    assertThrows(IOException::class.java) { open(seal(pcm).copyOf(100), sessionKey) }
  }

  @Test fun cleanEndOfStreamBetweenFramesIsEof() {
    assertEquals(0, open(ByteArray(0), sessionKey).size)
  }

  @Test fun listenerSlotsAndHandshakeSlotsAreCappedIndependently() {
    val slots = IntercomSlots(maxPending = 2, maxListeners = 1)
    assertTrue(slots.tryBeginHandshake())
    assertTrue(slots.tryBeginHandshake())
    assertFalse(slots.tryBeginHandshake())
    assertTrue(slots.tryBeginListener())
    assertFalse(slots.tryBeginListener())
    slots.endHandshake()
    slots.endListener()
    assertTrue(slots.tryBeginHandshake())
    assertTrue(slots.tryBeginListener())
  }

  private fun seal(vararg chunks: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    val sealing = IntercomSealingOutputStream(out, sessionKey)
    chunks.forEach { sealing.write(it, 0, it.size) }
    return out.toByteArray()
  }

  private fun open(wire: ByteArray, key: ByteArray): ByteArray =
      IntercomOpeningInputStream(ByteArrayInputStream(wire), key).readAllBytes()
}

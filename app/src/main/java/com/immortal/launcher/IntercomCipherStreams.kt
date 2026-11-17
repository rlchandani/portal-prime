/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated encryption for intercom audio after [IntercomHandshake]: every `write` becomes one
 * AES-256-GCM frame `u16 length || ciphertext+tag`. The IV is a 64-bit frame counter (the key is
 * unique per session, so it never repeats), which also rejects dropped, reordered or replayed
 * frames: the reader expects the exact next counter, and any bad tag ends the stream.
 */
internal object IntercomFrames {
  const val TAG_BITS = 128
  const val TAG_BYTES = TAG_BITS / 8
  const val MAX_PLAINTEXT = 4_096
  const val MAX_FRAME = MAX_PLAINTEXT + TAG_BYTES
  private const val IV_BYTES = 12
  private const val TRANSFORM = "AES/GCM/NoPadding"

  fun cipher(mode: Int, sessionKey: ByteArray, counter: Long): Cipher =
      Cipher.getInstance(TRANSFORM).apply {
        val iv = ByteBuffer.allocate(IV_BYTES).putInt(0).putLong(counter).array()
        init(mode, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
      }
}

/** Seals each write as one frame; larger writes are split at [IntercomFrames.MAX_PLAINTEXT]. */
internal class IntercomSealingOutputStream(
    private val out: OutputStream,
    private val sessionKey: ByteArray,
) : OutputStream() {
  private var counter = 0L

  override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

  override fun write(buffer: ByteArray, offset: Int, length: Int) {
    var at = offset
    val end = offset + length
    while (at < end) {
      val chunk = minOf(end - at, IntercomFrames.MAX_PLAINTEXT)
      val sealed = IntercomFrames.cipher(Cipher.ENCRYPT_MODE, sessionKey, counter++).doFinal(buffer, at, chunk)
      out.write(byteArrayOf((sealed.size ushr 8).toByte(), sealed.size.toByte()) + sealed)
      at += chunk
    }
    out.flush()
  }

  override fun flush() = out.flush()
  override fun close() = out.close()
}

/** Opens frames in order; oversized, truncated or forged frames throw and end playback. */
internal class IntercomOpeningInputStream(
    input: InputStream,
    private val sessionKey: ByteArray,
) : InputStream() {
  private val input = DataInputStream(input)
  private var counter = 0L
  private var plain = ByteArray(0)
  private var position = 0

  override fun read(): Int {
    val one = ByteArray(1)
    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) return 0
    if (position == plain.size && !nextFrame()) return -1
    val count = minOf(length, plain.size - position)
    plain.copyInto(buffer, offset, position, position + count)
    position += count
    return count
  }

  private fun nextFrame(): Boolean {
    val size = try { input.readUnsignedShort() } catch (_: EOFException) { return false }
    if (size <= IntercomFrames.TAG_BYTES || size > IntercomFrames.MAX_FRAME)
      throw IOException("intercom frame size out of range")
    val sealed = ByteArray(size).also(input::readFully)
    plain = try {
      IntercomFrames.cipher(Cipher.DECRYPT_MODE, sessionKey, counter++).doFinal(sealed)
    } catch (error: java.security.GeneralSecurityException) {
      throw IOException("intercom frame failed authentication", error)
    }
    position = 0
    return true
  }

  override fun close() = input.close()
}

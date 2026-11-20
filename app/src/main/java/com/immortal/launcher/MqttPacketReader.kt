/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.EOFException
import java.io.InputStream

/** The MQTT 3.1.1 "remaining length" varint (§2.2.3): 1 to 4 bytes, 7 bits each, low group first. */
internal object MqttRemainingLength {
  /** The largest value four bytes can carry. */
  const val MAX = 268_435_455
  private const val MAX_BYTES = 4
  private const val CONTINUATION = 0x80
  private const val DIGIT = 0x7F
  private const val BITS = 7

  /**
   * The decoded length, or null at an end of stream or when the 4th byte still has its
   * continuation bit set (malformed; the 5th byte is left unread and the caller drops the link).
   */
  fun decode(input: InputStream): Int? {
    var value = 0
    for (i in 0 until MAX_BYTES) {
      val digit = input.read()
      if (digit < 0) return null
      value = value or ((digit and DIGIT) shl (BITS * i))
      if (digit and CONTINUATION == 0) return value
    }
    return null
  }

  fun encode(length: Int): ByteArray {
    require(length in 0..MAX) { "MQTT remaining length out of range: $length" }
    val out = ArrayList<Byte>(MAX_BYTES)
    var x = length
    do {
      var enc = x and DIGIT
      x = x ushr BITS
      if (x > 0) enc = enc or CONTINUATION
      out.add(enc.toByte())
    } while (x > 0)
    return out.toByteArray()
  }
}

/**
 * Reads MQTT control packets off a stream with an explicit size cap. A packet whose remaining
 * length is over [maxPacketBytes] is read past in [SKIP_CHUNK_BYTES] chunks (never allocated) and
 * skipped, and the next packet is returned, so a broker replaying a large retained message on a
 * subscribed topic can't force a reconnect loop or a huge allocation. The first skip on a
 * connection is logged; later ones are only counted ([oversizedSkipped]).
 */
internal class MqttPacketReader(
    private val maxPacketBytes: Int = MAX_PACKET_BYTES,
    private val log: (String) -> Unit,
) {
  /** Oversized packets skipped so far on this connection. */
  @Volatile var oversizedSkipped = 0
    private set

  /** The next packet within the cap; null on a clean end of stream or a malformed length. */
  fun read(input: InputStream): MqttClient.Packet? {
    while (true) {
      val first = input.read()
      if (first < 0) return null
      val length = MqttRemainingLength.decode(input) ?: return null
      if (length > maxPacketBytes) {
        skip(input, length.toLong())
        if (oversizedSkipped++ == 0) {
          log("skipped an oversized MQTT packet (type 0x%02x, %d bytes, cap %d); further ones on this connection are skipped silently"
              .format(first and 0xF0, length, maxPacketBytes))
        }
        continue
      }
      val body = ByteArray(length)
      readFully(input, body)
      return MqttClient.Packet(first and 0xF0, first and 0x0F, body)
    }
  }

  private fun skip(input: InputStream, length: Long) {
    val buf = ByteArray(SKIP_CHUNK_BYTES)
    var remaining = length
    while (remaining > 0) {
      val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
      if (n < 0) throw EOFException("socket closed mid-packet")
      remaining -= n
    }
  }

  private fun readFully(input: InputStream, buf: ByteArray) {
    var off = 0
    while (off < buf.size) {
      val n = input.read(buf, off, buf.size - off)
      if (n < 0) throw EOFException("socket closed mid-packet")
      off += n
    }
  }

  companion object {
    /** The largest packet (remaining length) delivered; anything bigger is skipped. */
    const val MAX_PACKET_BYTES = 2 * 1024 * 1024
    const val SKIP_CHUNK_BYTES = 64 * 1024
  }
}

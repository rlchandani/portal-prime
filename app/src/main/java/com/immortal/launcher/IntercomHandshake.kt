/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * Mutual household authentication on a fresh intercom TCP connection, before any audio:
 *
 * 1. broadcaster → `MAGIC || broadcasterNonce`
 * 2. listener → `MAGIC || listenerNonce || idLength || listenerId || listenerProof`
 * 3. broadcaster verifies (constant time, nonce not seen before) → `broadcasterProof`
 * 4. listener verifies; both derive the per-session audio key.
 *
 * The whole exchange must finish within one [timeoutMs] deadline (not per read, so a peer
 * dribbling bytes can't hold a slot). Any malformed, oversized, late or unproven message ends it
 * with a [Result.Rejected]; the caller closes the socket. Pure JVM: callers do the logging.
 */
internal class IntercomHandshake(
    private val credentials: IntercomCredentials,
    private val timeoutMs: Int = TIMEOUT_MS,
    private val random: SecureRandom = SecureRandom(),
) {
  sealed interface Result {
    class Accepted(val sessionKey: ByteArray, val peerId: String?) : Result
    class Rejected(val reason: Reason) : Result
  }

  enum class Reason { TIMEOUT, CLOSED, BAD_MAGIC, BAD_DEVICE_ID, BAD_PROOF, REPLAYED_NONCE, IO }

  /** Broadcaster side. [replay] remembers listener nonces across this broadcast's connections. */
  fun asBroadcaster(socket: Socket, replay: HouseholdMessage.ReplayGuard): Result = guarded(socket) { io ->
    val broadcasterNonce = nonce()
    io.write(MAGIC + broadcasterNonce)
    if (!io.read(MAGIC.size).contentEquals(MAGIC)) return@guarded Result.Rejected(Reason.BAD_MAGIC)
    val listenerNonce = io.read(IntercomAuth.NONCE_SIZE)
    val idLength = io.read(1)[0].toInt() and 0xff
    if (idLength !in 1..IntercomAuth.MAX_DEVICE_ID) return@guarded Result.Rejected(Reason.BAD_DEVICE_ID)
    val listenerId = String(io.read(idLength), Charsets.US_ASCII)
    val proof = io.read(IntercomAuth.MAC_SIZE)
    if (!IntercomAuth.isValidDeviceId(listenerId)) return@guarded Result.Rejected(Reason.BAD_DEVICE_ID)
    val expected = IntercomAuth.listenerProof(credentials.key, broadcasterNonce, listenerNonce, listenerId)
    if (!IntercomAuth.matches(expected, proof)) return@guarded Result.Rejected(Reason.BAD_PROOF)
    if (!replay.firstSighting(listenerId, HouseholdKey.toHex(listenerNonce)))
      return@guarded Result.Rejected(Reason.REPLAYED_NONCE)
    io.write(IntercomAuth.broadcasterProof(credentials.key, broadcasterNonce, listenerNonce))
    Result.Accepted(
        IntercomAuth.sessionKey(credentials.key, broadcasterNonce, listenerNonce, listenerId), listenerId)
  }

  /** Listener side: proves itself, then requires the broadcaster's proof over our fresh nonce. */
  fun asListener(socket: Socket): Result = guarded(socket) { io ->
    if (!io.read(MAGIC.size).contentEquals(MAGIC)) return@guarded Result.Rejected(Reason.BAD_MAGIC)
    val broadcasterNonce = io.read(IntercomAuth.NONCE_SIZE)
    val listenerNonce = nonce()
    val id = credentials.deviceId.toByteArray(Charsets.US_ASCII)
    val proof = IntercomAuth.listenerProof(credentials.key, broadcasterNonce, listenerNonce, credentials.deviceId)
    io.write(MAGIC + listenerNonce + byteArrayOf(id.size.toByte()) + id + proof)
    val reply = io.read(IntercomAuth.MAC_SIZE)
    val expected = IntercomAuth.broadcasterProof(credentials.key, broadcasterNonce, listenerNonce)
    if (!IntercomAuth.matches(expected, reply)) return@guarded Result.Rejected(Reason.BAD_PROOF)
    Result.Accepted(
        IntercomAuth.sessionKey(credentials.key, broadcasterNonce, listenerNonce, credentials.deviceId), null)
  }

  private fun nonce() = ByteArray(IntercomAuth.NONCE_SIZE).also(random::nextBytes)

  private inline fun guarded(socket: Socket, block: (DeadlineIo) -> Result): Result {
    val previousTimeout = socket.soTimeout
    return try {
      block(DeadlineIo(socket, System.nanoTime() + timeoutMs * NANOS_PER_MS)).also {
        if (it is Result.Accepted) socket.soTimeout = previousTimeout
      }
    } catch (_: SocketTimeoutException) {
      Result.Rejected(Reason.TIMEOUT)
    } catch (_: EOFException) {
      Result.Rejected(Reason.CLOSED)
    } catch (_: IOException) {
      Result.Rejected(Reason.IO)
    }
  }

  /** Exact-length reads bounded by one shared deadline. */
  private class DeadlineIo(private val socket: Socket, private val deadlineNanos: Long) {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    fun write(bytes: ByteArray) {
      output.write(bytes)
      output.flush()
    }

    fun read(count: Int): ByteArray {
      val out = ByteArray(count)
      var filled = 0
      while (filled < count) {
        val remainingMs = (deadlineNanos - System.nanoTime()) / NANOS_PER_MS
        if (remainingMs <= 0) throw SocketTimeoutException("intercom handshake deadline")
        socket.soTimeout = remainingMs.toInt()
        val n = input.read(out, filled, count - filled)
        if (n < 0) throw EOFException("intercom peer closed during handshake")
        filled += n
      }
      return out
    }
  }

  companion object {
    const val TIMEOUT_MS = 2_000
    private const val NANOS_PER_MS = 1_000_000L
    /** Protocol tag: an old raw-PCM peer never sends it, so it fails closed. */
    val MAGIC: ByteArray = "IMMIC/1\n".toByteArray(Charsets.US_ASCII)
  }
}

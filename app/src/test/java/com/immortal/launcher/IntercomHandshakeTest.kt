/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.IntercomHandshake.Reason
import com.immortal.launcher.IntercomHandshake.Result
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** The real handshake over loopback TCP, so framing, deadlines and closes are exercised end to end. */
class IntercomHandshakeTest {
  private val key = ByteArray(HouseholdKey.SIZE) { it.toByte() }
  private val otherKey = ByteArray(HouseholdKey.SIZE) { (it + 1).toByte() }
  private val listenerCreds = IntercomCredentials(key, "kitchen-portal")
  private val broadcasterCreds = IntercomCredentials(key, "nursery-portal")
  private val pool = Executors.newCachedThreadPool()
  private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())

  @After fun tearDown() {
    server.close()
    pool.shutdownNow()
  }

  @Test fun householdPortalsAgreeOnASessionKey() {
    val broadcaster = broadcast(broadcasterCreds)
    val listener = connect().use { IntercomHandshake(listenerCreds).asListener(it) }
    val accepted = broadcaster.get(5, TimeUnit.SECONDS) as Result.Accepted
    assertTrue(listener is Result.Accepted)
    assertEquals("kitchen-portal", accepted.peerId)
    assertArrayEquals(accepted.sessionKey, (listener as Result.Accepted).sessionKey)
    assertEquals(IntercomAuth.SESSION_KEY_SIZE, accepted.sessionKey.size)
  }

  @Test fun listenerWithAnotherKeyIsRejectedAndGetsNoReply() {
    val broadcaster = broadcast(broadcasterCreds)
    val listener = connect().use { IntercomHandshake(IntercomCredentials(otherKey, "intruder")).asListener(it) }
    assertEquals(Reason.BAD_PROOF, reason(broadcaster.get(5, TimeUnit.SECONDS)))
    assertEquals(Reason.CLOSED, reason(listener))
  }

  @Test fun broadcasterWithAnotherKeyNeverAuthenticatesTheListener() {
    broadcast(IntercomCredentials(otherKey, "impostor"))
    val listener = connect().use { IntercomHandshake(listenerCreds).asListener(it) }
    assertTrue(listener is Result.Rejected)
  }

  @Test fun impostorThatSkipsVerificationIsRejectedByTheListener() {
    val broadcasterNonce = ByteArray(IntercomAuth.NONCE_SIZE) { 9 }
    pool.submit {
      server.accept().use { s ->
        s.getOutputStream().write(IntercomHandshake.MAGIC + broadcasterNonce)
        val input = java.io.DataInputStream(s.getInputStream())
        val listenerNonce = ByteArray(IntercomAuth.NONCE_SIZE)
        input.readFully(ByteArray(IntercomHandshake.MAGIC.size))
        input.readFully(listenerNonce)
        input.readFully(ByteArray(input.readUnsignedByte() + IntercomAuth.MAC_SIZE))
        // Accepts anyone and "proves" itself with the wrong key, then would stream audio.
        s.getOutputStream().write(IntercomAuth.broadcasterProof(otherKey, broadcasterNonce, listenerNonce))
        s.getOutputStream().write(ByteArray(1024))
      }
    }
    val listener = connect().use { IntercomHandshake(listenerCreds).asListener(it) }
    assertEquals(Reason.BAD_PROOF, reason(listener))
  }

  @Test fun recordedListenerReplyFailsOnANewConnection() {
    val recorded = ByteArrayOutputStream()
    // Session 1: a genuine listener; capture what it sent after the broadcaster's hello.
    val first = pool.submit(Callable {
      server.accept().use { s ->
        s.getOutputStream().write(IntercomHandshake.MAGIC + ByteArray(IntercomAuth.NONCE_SIZE) { 7 })
        val reply = ByteArray(IntercomHandshake.MAGIC.size + IntercomAuth.NONCE_SIZE + 1 +
            listenerCreds.deviceId.length + IntercomAuth.MAC_SIZE)
        java.io.DataInputStream(s.getInputStream()).readFully(reply)
        recorded.write(reply)
      }
    })
    connect().use { IntercomHandshake(listenerCreds, timeoutMs = SHORT_MS).asListener(it) }
    first.get(5, TimeUnit.SECONDS)
    // Session 2: replay it to a real broadcaster, which chose a fresh nonce.
    val broadcaster = broadcast(broadcasterCreds)
    connect().use { s ->
      s.getInputStream().readNBytes(IntercomHandshake.MAGIC.size + IntercomAuth.NONCE_SIZE)
      s.getOutputStream().write(recorded.toByteArray())
      assertEquals(Reason.BAD_PROOF, reason(broadcaster.get(5, TimeUnit.SECONDS)))
    }
  }

  @Test fun repeatedListenerNonceIsRejectedEvenWithAValidProof() {
    val replay = HouseholdMessage.ReplayGuard()
    val fixed = FixedRandom() // both sides repeat nonces, so the second proof is identical and valid
    repeat(2) { attempt ->
      val broadcaster = pool.submit(Callable {
        server.accept().use { IntercomHandshake(broadcasterCreds, random = fixed).asBroadcaster(it, replay) }
      })
      connect().use { IntercomHandshake(listenerCreds, timeoutMs = SHORT_MS, random = fixed).asListener(it) }
      val result = broadcaster.get(5, TimeUnit.SECONDS)
      if (attempt == 0) assertTrue(result is Result.Accepted)
      else assertEquals(Reason.REPLAYED_NONCE, reason(result))
    }
  }

  @Test fun silentClientTimesOutAndOnlyEverSeesTheHello() {
    val broadcaster = broadcast(broadcasterCreds, timeoutMs = SHORT_MS)
    connect().use { s ->
      // Like `nc <ip> 8724`: a non-household client gets the hello (magic + nonce) and nothing else.
      val received = s.getInputStream().readAllBytes()
      assertEquals(IntercomHandshake.MAGIC.size + IntercomAuth.NONCE_SIZE, received.size)
      assertArrayEquals(IntercomHandshake.MAGIC, received.copyOf(IntercomHandshake.MAGIC.size))
    }
    assertEquals(Reason.TIMEOUT, reason(broadcaster.get(5, TimeUnit.SECONDS)))
  }

  @Test fun dribblingClientCannotStretchTheDeadline() {
    val broadcaster = broadcast(broadcasterCreds, timeoutMs = SHORT_MS)
    val started = System.nanoTime()
    connect().use { s ->
      val out = s.getOutputStream()
      runCatching { IntercomHandshake.MAGIC.forEach { out.write(it.toInt()); Thread.sleep(SHORT_MS / 4L) } }
      assertEquals(Reason.TIMEOUT, reason(broadcaster.get(5, TimeUnit.SECONDS)))
    }
    assertTrue(System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(SHORT_MS * 4L))
  }

  @Test fun garbageInsteadOfTheProtocolIsRejected() {
    val broadcaster = broadcast(broadcasterCreds)
    connect().use { s ->
      s.getOutputStream().write("GET / HTTP/1.1\r\nHost: portal\r\n\r\n".toByteArray())
      assertEquals(Reason.BAD_MAGIC, reason(broadcaster.get(5, TimeUnit.SECONDS)))
    }
  }

  @Test fun oldRawPcmBroadcasterIsNeverPlayed() {
    pool.submit { server.accept().use { it.getOutputStream().write(ByteArray(4096) { 0x11 }) } }
    val listener = connect().use { IntercomHandshake(listenerCreds).asListener(it) }
    assertEquals(Reason.BAD_MAGIC, reason(listener))
  }

  @Test fun oversizedOrEmptyDeviceIdFramesAreRejected() {
    for (length in listOf(IntercomAuth.MAX_DEVICE_ID + 1, 255, 0)) {
      val broadcaster = broadcast(broadcasterCreds)
      connect().use { s ->
        s.getInputStream().readNBytes(IntercomHandshake.MAGIC.size + IntercomAuth.NONCE_SIZE)
        s.getOutputStream().write(IntercomHandshake.MAGIC + ByteArray(IntercomAuth.NONCE_SIZE) +
            byteArrayOf(length.toByte()))
        assertEquals("length $length", Reason.BAD_DEVICE_ID, reason(broadcaster.get(5, TimeUnit.SECONDS)))
      }
    }
  }

  @Test fun deviceIdWithIllegalCharactersIsRejected() {
    val broadcaster = broadcast(broadcasterCreds)
    connect().use { s ->
      s.getInputStream().readNBytes(IntercomHandshake.MAGIC.size + IntercomAuth.NONCE_SIZE)
      val id = "bad id\n".toByteArray()
      s.getOutputStream().write(IntercomHandshake.MAGIC + ByteArray(IntercomAuth.NONCE_SIZE) +
          byteArrayOf(id.size.toByte()) + id + ByteArray(IntercomAuth.MAC_SIZE))
      assertEquals(Reason.BAD_DEVICE_ID, reason(broadcaster.get(5, TimeUnit.SECONDS)))
    }
  }

  @Test fun credentialsRejectMissingOrMalformedKeyMaterial() {
    assertThrows(IllegalArgumentException::class.java) { IntercomCredentials(ByteArray(16), "portal") }
    assertThrows(IllegalArgumentException::class.java) { IntercomCredentials(key, "") }
    assertThrows(IllegalArgumentException::class.java) { IntercomCredentials(key, "x".repeat(65)) }
  }

  @Test fun listenerAndBroadcasterProofsAreDomainSeparated() {
    val a = ByteArray(IntercomAuth.NONCE_SIZE) { 1 }
    val b = ByteArray(IntercomAuth.NONCE_SIZE) { 2 }
    assertFalse(IntercomAuth.listenerProof(key, a, b, "p").contentEquals(IntercomAuth.broadcasterProof(key, b, a)))
    assertFalse(IntercomAuth.sessionKey(key, a, b, "p").contentEquals(IntercomAuth.sessionKey(key, b, a, "p")))
  }

  private fun broadcast(creds: IntercomCredentials, timeoutMs: Int = SHORT_MS) = pool.submit(Callable {
    server.accept().use { IntercomHandshake(creds, timeoutMs).asBroadcaster(it, HouseholdMessage.ReplayGuard()) }
  })

  private fun connect(): Socket = Socket().apply {
    connect(InetSocketAddress(server.inetAddress, server.localPort), 2_000)
  }

  private fun reason(result: Result): Reason? = (result as? Result.Rejected)?.reason

  /** Repeats the same "random" bytes, to exercise the replay guard with an otherwise valid proof. */
  private class FixedRandom : SecureRandom() {
    override fun nextBytes(bytes: ByteArray) = bytes.fill(0x5a)
  }

  private companion object {
    const val SHORT_MS = 400
  }
}

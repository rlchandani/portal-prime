/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Actual connected exchange; generated loopback peers only, with bounded owned cleanup. */
class SnapcastConnectionOwnershipTest {
  @Test fun peerOutputEofClosesTheConnectedClientSocket() = withPeer { h ->
    val invocation = h.connect()
    val peer = h.acceptRequested()
    peer.shutdownOutput()
    invocation.get(2, TimeUnit.SECONDS)
    assertTrue("Completed EOF exchange must close its peer input", peerSawEof(peer))
    assertEquals(listOf(true), h.connected.toList())
  }

  @Test fun connectedCallbackFailureClosesTheRealConnectedSocket() =
      withPeer(connectedFailure = true) { h ->
        val invocation = h.connect()
        val peer = h.acceptRequested()
        val error = assertThrows(ExecutionException::class.java) {
          invocation.get(2, TimeUnit.SECONDS)
        }
        assertEquals("owned connected callback failure", error.cause!!.message)
        assertTrue("Callback failure must retire its connected socket", peerSawEof(peer))
      }

  @Test fun successiveEofExchangesRetireEverySocketBeforeItCanBeReplaced() = withPeer { h ->
    val old = h.connect()
    val oldPeer = h.acceptRequested()
    oldPeer.shutdownOutput()
    old.get(2, TimeUnit.SECONDS)
    val next = h.connect()
    val nextPeer = h.acceptRequested()
    nextPeer.shutdownOutput()
    next.get(2, TimeUnit.SECONDS)
    assertTrue("Earlier connection must not survive publication of the next", peerSawEof(oldPeer))
    assertTrue("Latest completed connection must also retire", peerSawEof(nextPeer))
    assertEquals(listOf(true, true), h.connected.toList())
  }

  @Test fun malformedMessageDoesNotPreventOurGroupsValidMetadata() = withPeer { h ->
    val invocation = h.connect()
    val peer = h.acceptRequested()
    send(peer, "\r\nnot-json\r\n$OWN_STATUS\r\n")
    peer.shutdownOutput()
    invocation.get(2, TimeUnit.SECONDS)
    assertEquals(listOf(true), h.connected.toList())
    assertEquals(listOf(NowPlayingState(
        state = PlaybackState.PLAYING, title = "Owned tune", artist = "Artist A, Artist B",
        album = "Owned album", artUrl = "https://example.invalid/art",
        durationMs = 12_500L, source = "snapcast")), h.states.toList())
    assertEquals("owned-client", h.client.ourClientId)
    assertEquals("Owned room", h.client.ourClientName)
  }

  @Test fun anotherGroupsPlayingMetadataKeepsThisClientsCardIdle() = withPeer { h ->
    val invocation = h.connect()
    val peer = h.acceptRequested()
    send(peer, OWN_STATUS.replace("192.0.2.10", "192.0.2.99") + "\r\n")
    peer.shutdownOutput()
    invocation.get(2, TimeUnit.SECONDS)
    assertEquals(listOf(NowPlayingState(PlaybackState.IDLE)), h.states.toList())
    assertNull(h.client.ourClientId)
    assertNull(h.client.ourClientName)
  }

  @Test fun publicStopClosesTheCurrentSocketAndUnblocksAnActiveRead() = withPeer { h ->
    val invocation = h.connect()
    val peer = h.acceptRequested()
    assertTrue(h.connectedSignal.await(2, TimeUnit.SECONDS))
    h.client.stop()
    try { invocation.get(2, TimeUnit.SECONDS) }
    catch (closed: ExecutionException) {
      assertTrue("Closing an active socket may interrupt the read", closed.cause is IOException)
    }
    assertTrue("Stop must close the peer input", peerSawEof(peer))
    assertEquals(listOf(true), h.connected.toList())
  }

  @Test fun setupAndInitialRequestFailuresRetireEachCreatedSocket() {
    val missed = mutableListOf<String>()
    for (stage in listOf("output", "input", "write", "flush")) {
      withInertSocket(stage) { closes ->
        val client = client()
        setRunning(client)
        val error = assertThrows(IOException::class.java) { invoke(client) }
        assertEquals("owned $stage failure", error.message)
        if (closes() != 1) missed += stage
      }
    }
    assertEquals("Every setup/request failure must close its owned connection",
        emptyList<String>(), missed)
  }

  @Test fun readFailureRetiresTheConnectedSocketAfterItsStatusRequest() {
    withInertSocket("read") { closes ->
      val connected = mutableListOf<Boolean>()
      val client = client(onConnected = { connected += it })
      setRunning(client)
      val error = assertThrows(IOException::class.java) { invoke(client) }
      assertEquals("owned read failure", error.message)
      assertEquals(listOf(true), connected)
      assertEquals(1, closes())
    }
  }

  private class PeerHarness(val connectedFailure: Boolean) {
    val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply {
      soTimeout = 2_000
    }
    val peers = mutableListOf<Socket>()
    val selected = CopyOnWriteArrayList<Socket>()
    val connected = CopyOnWriteArrayList<Boolean>()
    val states = CopyOnWriteArrayList<NowPlayingState>()
    val connectedSignal = CountDownLatch(1)
    val client = SnapcastControlClient("127.0.0.1", server.localPort, { "192.0.2.10" }, {
      connected += it
      selectedSocket(clientRef!!)?.let { socket -> selected += socket }
      connectedSignal.countDown()
      if (connectedFailure) throw IllegalStateException("owned connected callback failure")
    }, { states += it })
    private var clientRef: SnapcastControlClient? = null
    private val worker = Executors.newSingleThreadExecutor { job ->
      Thread(job, "owned-snapcast-loopback").apply { isDaemon = true }
    }

    init {
      clientRef = client
      setRunning(client)
    }

    fun connect(): Future<*> = worker.submit {
      // Mockito's static mock is thread-local: only the owned invocation worker
      // supplies the unavailable Android Log boundary. Socket and JSON remain real.
      Mockito.mockStatic(Log::class.java).use { invoke(client) }
    }

    fun acceptRequested(): Socket {
      val peer = server.accept().apply { soTimeout = 2_000 }
      peers += peer
      val request = peer.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
      assertNotNull("Actual helper must send its initial request", request)
      val json = JSONObject(request!!)
      assertEquals("Server.GetStatus", json.getString("method"))
      assertEquals("2.0", json.getString("jsonrpc"))
      assertEquals(1, json.getInt("id"))
      assertEquals(setOf("id", "jsonrpc", "method"), json.keys().asSequence().toSet())
      return peer
    }

    fun close() {
      client.stop()
      selected.forEach { it.close() }
      peers.forEach { it.close() }
      server.close()
      worker.shutdownNow()
      assertTrue("Owned client worker must retire", worker.awaitTermination(2, TimeUnit.SECONDS))
    }
  }

  private fun withPeer(connectedFailure: Boolean = false, block: (PeerHarness) -> Unit) {
    val h = PeerHarness(connectedFailure)
    try { block(h) } finally { h.close() }
  }

  private fun withInertSocket(
      stage: String,
      block: (() -> Int) -> Unit,
  ) {
    var closes = 0
    val unsupported = mutableListOf<String>()
    val output = object : OutputStream() {
      override fun write(value: Int) = throw AssertionError("Unexpected single-byte write")
      override fun write(bytes: ByteArray, offset: Int, count: Int) {
        if (stage == "write") throw IOException("owned write failure")
        val request = JSONObject(String(bytes, offset, count, Charsets.UTF_8).trim())
        assertEquals("Server.GetStatus", request.getString("method"))
        assertEquals("2.0", request.getString("jsonrpc"))
        assertEquals(1, request.getInt("id"))
      }
      override fun flush() {
        if (stage == "flush") throw IOException("owned flush failure")
      }
    }
    val input = object : InputStream() {
      override fun read(): Int = throw AssertionError("Unexpected single-byte read")
      override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
        if (stage == "read") throw IOException("owned read failure")
        return -1
      }
    }
    Mockito.mockStatic(Log::class.java).use {
      Mockito.mockConstruction(Socket::class.java,
          Mockito.withSettings().defaultAnswer { call ->
            when (call.method.name) {
              "getOutputStream" -> {
                if (stage == "output") throw IOException("owned output failure")
                output
              }
              "getInputStream" -> {
                if (stage == "input") throw IOException("owned input failure")
                input
              }
              "close" -> { closes++; null }
              "toString" -> "Owned inert Snapcast socket"
              else -> {
                unsupported += call.method.name
                throw AssertionError("Unsupported socket operation: ${call.method.name}")
              }
            }
          }) { _, constructor ->
        assertEquals(listOf("127.0.0.1", 1705), constructor.arguments())
      }.use {
        try {
          block { closes }
        } finally {
          assertEquals("A fixture API gap cannot count as a product failure",
              emptyList<String>(), unsupported)
        }
      }
    }
  }

  companion object {
    private fun client(onConnected: (Boolean) -> Unit = {}) =
        SnapcastControlClient("127.0.0.1", 1705, { "192.0.2.10" }, onConnected, {})

    private fun invoke(client: SnapcastControlClient) {
      val method = SnapcastControlClient::class.java.getDeclaredMethod("connectAndRead")
          .apply { isAccessible = true }
      try { method.invoke(client) }
      catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    private fun setRunning(client: SnapcastControlClient) {
      SnapcastControlClient::class.java.getDeclaredField("running").apply {
        isAccessible = true
        setBoolean(client, true)
      }
    }

    private fun selectedSocket(client: SnapcastControlClient): Socket? =
        SnapcastControlClient::class.java.getDeclaredField("socket").apply {
          isAccessible = true
        }.get(client) as Socket?

    private fun send(peer: Socket, text: String) {
      peer.getOutputStream().write(text.toByteArray(Charsets.UTF_8))
      peer.getOutputStream().flush()
    }

    private fun peerSawEof(peer: Socket): Boolean {
      peer.soTimeout = 400
      return try { peer.getInputStream().read() == -1 }
      catch (_: SocketTimeoutException) { false }
    }

    private const val OWN_STATUS = """{"jsonrpc":"2.0","id":1,"result":{"server":{"streams":[{"id":"own","properties":{"playbackStatus":"playing","metadata":{"title":"Owned tune","artist":["Artist A","Artist B"],"album":"Owned album","artUrl":"https://example.invalid/art","duration":12.5}}},{"id":"foreign","properties":{"playbackStatus":"playing","metadata":{"title":"Must not appear","artist":["Other"]}}}],"groups":[{"stream_id":"own","clients":[{"id":"owned-client","host":{"ip":"::ffff:192.0.2.10","name":"Owned room"}}]},{"stream_id":"foreign","clients":[{"id":"other-client","host":{"ip":"192.0.2.20","name":"Other room"}}]}]}}}"""
  }
}

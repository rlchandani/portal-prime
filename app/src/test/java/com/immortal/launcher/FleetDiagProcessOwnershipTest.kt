/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Actual diagnostic boundary with inert process doubles; no process, device or log access. */
class FleetDiagProcessOwnershipTest {
  @Test fun cappedProducerReturnsItsPrefixInsteadOfWaitingOnAnOpenFullPipe() {
    Harness("PREFIX-UNREAD-TAIL".toByteArray(), blockedUntilClosed = true).use { h ->
      assertEquals("PREFIX", h.exec(6))
      assertTrue(h.stream.closed)
      assertEquals(listOf("input", "close", "wait", "destroy"), h.events)
      assertEquals(1, h.destroyCount)
      assertEquals(6, h.stream.offset)
    }
  }

  @Test fun normalEofRetiresItsStreamAndOwnedProcess() {
    Harness("short output".toByteArray(), chunkSize = 2).use { h ->
      assertEquals("short output", h.exec(64))
      assertTrue(h.stream.closed)
      assertEquals(1, h.stream.closeCount)
      assertEquals(1, h.destroyCount)
      assertEquals(listOf("input", "close", "wait", "destroy"), h.events)
    }
  }

  @Test fun readFailureClosesTheStreamAndRetiresTheProcessWithoutWaiting() {
    Harness("generated".toByteArray(), failure = "read").use { h ->
      assertEquals("error: owned read failure", h.exec(64))
      assertTrue(h.stream.closed)
      assertEquals(1, h.destroyCount)
      assertEquals(0, h.waitCount)
    }
  }

  @Test fun inputAcquisitionFailureStillRetiresTheStartedProcess() {
    Harness("generated".toByteArray(), failure = "input").use { h ->
      assertEquals("error: owned input failure", h.exec(64))
      assertEquals(1, h.destroyCount)
      assertEquals(0, h.waitCount)
      assertEquals(0, h.stream.closeCount)
    }
  }

  @Test fun streamCloseFailureStillRetiresTheProcessAndKeepsTheErrorSurface() {
    Harness("generated".toByteArray(), failure = "close").use { h ->
      assertEquals("error: owned close failure", h.exec(64))
      assertEquals(1, h.stream.closeCount)
      assertEquals(1, h.destroyCount)
      assertEquals(0, h.waitCount)
    }
  }

  @Test fun waitFailureOccursAfterStreamClosureAndStillRetiresTheProcess() {
    Harness("generated".toByteArray(), failure = "wait").use { h ->
      assertEquals("error: owned wait failure", h.exec(64))
      assertTrue(h.stream.closed)
      assertEquals(1, h.destroyCount)
      assertEquals(listOf("input", "close", "wait", "destroy"), h.events)
    }
  }

  @Test fun emptyOutputKeepsItsEmptySuccessfulResult() {
    Harness(byteArrayOf()).use { h ->
      assertEquals("", h.exec(64))
      assertEquals(1, h.waitCount)
    }
  }

  @Test fun utf8PrefixAndPartialMultibyteBoundaryKeepTheExistingDecoder() {
    val bytes = "Aé終TAIL".toByteArray(Charsets.UTF_8)
    for (cap in listOf(1, 2, 3, 5, 6, bytes.size)) {
      Harness(bytes, chunkSize = 1).use { h ->
        assertEquals(String(bytes.copyOf(cap), Charsets.UTF_8), h.exec(cap))
        assertEquals(cap, h.stream.offset)
      }
    }
  }

  @Test fun publicLogcatKeepsItsFixedCommandAndClampsRequestedLineCount() {
    for ((requested, expected) in listOf(-10 to "1", 0 to "1", 17 to "17", 30_000 to "20000")) {
      Harness("generated log".toByteArray()).use { h ->
        assertEquals("generated log", FleetDiag.logcat(requested))
        assertEquals(listOf("logcat", "-d", "-t", expected), h.command)
        assertEquals(listOf(true), h.redirectSettings)
      }
    }
  }

  @Test fun publicLogcatKeepsTheFourMiBByteCap() {
    val limit = 4 * 1024 * 1024
    Harness(ByteArray(limit + 37) { 'L'.code.toByte() }, blockedUntilClosed = true).use { h ->
      val result = FleetDiag.logcat(20_000)
      assertEquals(limit, result.length)
      assertTrue(result.all { it == 'L' })
      assertEquals(limit, h.stream.offset)
      assertTrue(h.stream.closed)
      assertEquals(1, h.destroyCount)
    }
  }

  @Test fun startFailureDoesNotPretendToOwnAProcess() {
    Harness("generated".toByteArray(), failure = "start").use { h ->
      assertEquals("error: owned start failure", h.exec(64))
      assertEquals(0, h.destroyCount)
      assertEquals(0, h.waitCount)
      assertEquals(0, h.stream.closeCount)
    }
  }

  private class Harness(
      bytes: ByteArray,
      chunkSize: Int = Int.MAX_VALUE,
      val failure: String = "",
      blockedUntilClosed: Boolean = false,
  ) {
    val events = mutableListOf<String>()
    val unsupportedCalls = mutableListOf<String>()
    var command: List<String>? = null
    val redirectSettings = mutableListOf<Boolean>()
    var destroyCount = 0
    var waitCount = 0
    val stream = OwnedStream(bytes, chunkSize, failure, events)
    private val process = Mockito.mock(Process::class.java) { call ->
      when (call.method.name) {
        "getInputStream" -> {
          events += "input"
          if (failure == "input") throw IOException("owned input failure")
          stream
        }
        "waitFor" -> {
          events += "wait"
          waitCount++
          if (failure == "wait") throw IOException("owned wait failure")
          // An inert substitute for a writer that cannot exit while its full stdout
          // pipe remains unread and open. Throw rather than hang the test worker.
          if (blockedUntilClosed && stream.offset < bytes.size && !stream.closed)
            throw IOException("producer still blocked on its output pipe")
          0
        }
        "destroy" -> { events += "destroy"; destroyCount++; null }
        "toString" -> "Owned inert diagnostic process"
        else -> {
          unsupportedCalls += call.method.name
          throw AssertionError("Unsupported process operation: ${call.method.name}")
        }
      }
    }

    fun exec(cap: Int): String {
      val method = FleetDiag::class.java.getDeclaredMethod("exec",
          Array<String>::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
      return method.invoke(FleetDiag, arrayOf("owned-fixture-command"), cap) as String
    }

    fun use(block: (Harness) -> Unit) {
      Mockito.mockConstruction(ProcessBuilder::class.java,
          Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { builder, constructor ->
        @Suppress("UNCHECKED_CAST")
        command = (constructor.arguments().single() as Array<String>).toList()
        Mockito.`when`(builder.redirectErrorStream(Mockito.anyBoolean())).thenAnswer { call ->
          redirectSettings += call.getArgument<Boolean>(0)
          builder
        }
        Mockito.`when`(builder.start()).thenAnswer {
          if (failure == "start") throw IOException("owned start failure")
          process
        }
      }.use {
        try { block(this) }
        finally {
          assertEquals("A fixture service gap cannot count as a product failure",
              emptyList<String>(), unsupportedCalls)
        }
      }
    }
  }

  private class OwnedStream(
      val bytes: ByteArray,
      val chunkSize: Int,
      val failure: String,
      val events: MutableList<String>,
  ) : InputStream() {
    var offset = 0
    var closed = false
    var closeCount = 0
    override fun read(): Int = throw AssertionError("Unexpected single-byte read")
    override fun read(target: ByteArray, off: Int, len: Int): Int {
      check(!closed) { "Read after close" }
      if (failure == "read") throw IOException("owned read failure")
      if (offset == bytes.size) return -1
      val count = minOf(len, chunkSize, bytes.size - offset)
      check(count > 0) { "Unexpected zero-length read" }
      bytes.copyInto(target, off, offset, offset + count)
      offset += count
      return count
    }
    override fun close() {
      events += "close"
      closeCount++
      closed = true
      if (failure == "close") throw IOException("owned close failure")
    }
  }
}

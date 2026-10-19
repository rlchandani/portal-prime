/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM contracts; no Android decoder, socket, device, or private payload is involved. */
class MusicArtworkPolicyTest {
  private val bodyLimit = 8 * 1024 * 1024
  private val headerLimit = 32 * 1024
  private val chunkLineLimit = 1024
  @Test fun emptyUnknownLengthBodyIsAccepted() {
    assertArrayEquals(byteArrayOf(), readBounded(ByteArrayInputStream(byteArrayOf()), bodyLimit))
  }

  @Test fun fragmentedUnknownLengthBodyPreservesBinaryBytes() {
    val bytes = byteArrayOf(0, 1, 127, -128, -1, 13, 10)
    val input = ProbeInput(prefix = bytes, fragment = 1)
    assertArrayEquals(bytes, readBounded(input, bodyLimit))
    assertEquals(bytes.size.toLong(), input.bytesRead)
  }

  @Test fun exactEightMiBUnknownLengthBodyPassesWithoutTruncation() {
    val input = ProbeInput(tailBytes = bodyLimit.toLong())
    val actual = readBounded(input, bodyLimit)
    assertEquals(bodyLimit, actual.size)
    assertEquals(0x5a.toByte(), actual.first())
    assertEquals(0x5a.toByte(), actual.last())
    assertEquals(bodyLimit.toLong(), input.bytesRead)
  }

  @Test fun oversizedUnknownLengthBodyRejectsAfterAtMostOneExcessByte() {
    val input = ProbeInput(tailBytes = Long.MAX_VALUE, readCeiling = bodyLimit + 1L)
    assertThrows(IOException::class.java) { readBounded(input, bodyLimit) }
    assertTrue("must stop at the cap plus the EOF probe", input.bytesRead <= bodyLimit + 1L)
    assertFalse("must not silently truncate or drain the tail", input.eofSeen)
  }

  @Test fun smallerCallerBudgetAlsoStopsDuringTheRead() {
    val input = ProbeInput(tailBytes = Long.MAX_VALUE, readCeiling = 18)
    assertThrows(IOException::class.java) { readBounded(input, 17) }
    assertTrue(input.bytesRead <= 18)
    assertFalse(input.eofSeen)
  }

  @Test fun callerCannotRaiseTheEightMiBHardCeiling() {
    val input = ProbeInput(tailBytes = Long.MAX_VALUE, readCeiling = bodyLimit + 1L)
    assertThrows(IOException::class.java) { readBounded(input, Int.MAX_VALUE) }
    assertTrue(input.bytesRead <= bodyLimit + 1L)
  }

  @Test fun ioFailureDoesNotReturnPartiallyReadArtwork() {
    val failure = IOException("synthetic interrupted artwork")
    val input = object : InputStream() {
      private var reads = 0
      override fun read(): Int = if (reads++ < 3) 42 else throw failure
      override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (reads++ > 0) throw failure
        bytes[offset] = 42
        return 1
      }
    }
    assertSame(failure, assertThrows(IOException::class.java) { readBounded(input, bodyLimit) })
  }

  @Test fun negativeReadBudgetIsRejectedBeforeReading() {
    val input = ProbeInput(tailBytes = Long.MAX_VALUE, readCeiling = 0)
    assertThrows(IllegalArgumentException::class.java) { readBounded(input, -1) }
    assertEquals(0L, input.bytesRead)
  }

  @Test fun alreadySmallArtworkIsNeverUpsampled() {
    assertEquals(1, sampleSizeFor(384, 240, 384))
    assertEquals(1, sampleSizeFor(1, 1, 1024))
  }

  @Test fun thumbnailSampleBoundsBothDimensionsBeforeDecode() {
    // 4000/8 = 500 exceeds 384; sample 16 yields at most 250 x 188.
    assertEquals(16, sampleSizeFor(4000, 3000, 384))
    assertEquals(16, sampleSizeFor(3000, 4000, 384))
  }

  @Test fun currentCoverUsesIts1024PixelBudget() {
    assertEquals(4, sampleSizeFor(4000, 3000, 1024))
    assertEquals(4, sampleSizeFor(3000, 4000, 1024))
  }

  @Test fun oddDimensionCannotSlipOverTheLimitThroughFloorDivision() {
    // sample 2 can decode 385 pixels from 769, so it is insufficient.
    assertEquals(4, sampleSizeFor(769, 1, 384))
    assertEquals(4, sampleSizeFor(1, 769, 384))
    assertEquals(2, sampleSizeFor(768, 1, 384))
  }

  @Test fun extremeDimensionsDoNotOverflowOrProduceUnboundedSamples() {
    assertEquals(8_388_608, sampleSizeFor(Int.MAX_VALUE, Int.MAX_VALUE, 384))
    assertEquals(8_388_608, sampleSizeFor(Int.MAX_VALUE, 1, 384))
    assertEquals(8_388_608, sampleSizeFor(1, Int.MAX_VALUE, 384))
    assertEquals(2_097_152, sampleSizeFor(Int.MAX_VALUE, Int.MAX_VALUE, 1024))
  }

  @Test fun invalidOrUnrepresentableDecodeBoundsAreRejected() {
    assertNull(sampleSizeFor(0, 100, 384))
    assertNull(sampleSizeFor(100, -1, 384))
    assertNull(sampleSizeFor(100, 100, 0))
    assertNull(sampleSizeFor(Int.MAX_VALUE, Int.MAX_VALUE, 1))
  }

  @Test fun fixedLengthResponsePreservesAdjacentBinaryBody() {
    val body = byteArrayOf(0, -1, 13, 10, 127)
    val prefix = ascii("HTTP/1.1 200 OK\r\ncOnTeNt-LeNgTh: 5\r\n\r\n") + body
    val input = ProbeInput(prefix = prefix, tailBytes = Long.MAX_VALUE, fragment = 1,
        readCeiling = prefix.size.toLong())
    val response = readResponse(input)
    assertEquals(200, response.statusCode)
    assertNull(response.location)
    assertArrayEquals(body, response.body)
    assertEquals(prefix.size.toLong(), input.bytesRead)
  }

  @Test fun exactEightMiBFixedLengthBodyDoesNotCountHeadersAgainstItsBudget() {
    val header = ascii("HTTP/1.1 200 OK\r\nContent-Length: 8388608\r\n\r\n")
    val input = ProbeInput(prefix = header, tailBytes = bodyLimit.toLong(),
        readCeiling = header.size + bodyLimit.toLong())
    assertEquals(bodyLimit, readResponse(input).body.size)
    assertEquals(header.size + bodyLimit.toLong(), input.bytesRead)
  }

  @Test fun oversizedDeclaredBodyIsRejectedWithoutDrainingIt() {
    val header = ascii("HTTP/1.1 200 OK\r\nContent-Length: 8388609\r\n\r\n")
    val input = ProbeInput(prefix = header, tailBytes = Long.MAX_VALUE,
        readCeiling = header.size + 4096L)
    assertThrows(IOException::class.java) { readResponse(input) }
    assertTrue("a rejected declaration must not consume its body", input.bytesRead <= header.size + 4096L)
    assertFalse(input.eofSeen)
  }

  @Test fun truncatedDeclaredBodyIsRejectedRatherThanReturnedAsArtwork() {
    assertBadResponse("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nabc")
  }

  @Test fun invalidOrOverflowingContentLengthsAreRejected() {
    for (length in listOf("-1", "+3", "xyz", "9223372036854775808", "99999999999999999999999999")) {
      assertBadResponse("HTTP/1.1 200 OK\r\nContent-Length: $length\r\n\r\nabc")
    }
  }

  @Test fun conflictingContentLengthsCannotChooseTheSmallerDeclaration() {
    assertBadResponse("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nContent-Length: 5\r\n\r\nabcde")
  }

  @Test fun closeDelimitedHttpBodyUsesTheSameBoundedReader() {
    val header = ascii("HTTP/1.0 200 OK\r\nContent-Type: image/png\r\n\r\n")
    val input = ProbeInput(prefix = header, tailBytes = Long.MAX_VALUE,
        readCeiling = header.size + bodyLimit + 1L)
    assertThrows(IOException::class.java) { readResponse(input) }
    assertTrue(input.bytesRead <= header.size + bodyLimit + 1L)
    assertFalse(input.eofSeen)
  }

  @Test fun smallCloseDelimitedHttpBodyIsPreserved() {
    val response = readResponse(ByteArrayInputStream(ascii("HTTP/1.0 200 OK\r\n\r\ncover")))
    assertArrayEquals(ascii("cover"), response.body)
  }

  @Test fun fragmentedChunkedBodySupportsHexSizesExtensionsAndTrailers() {
    val wire = ascii("HTTP/1.1 200 OK\r\ntransfer-encoding: Chunked\r\n\r\n" +
        "3;fixture=yes\r\nabc\r\n2\r\nde\r\n0\r\nX-Fixture: done\r\n\r\n")
    val response = readResponse(ProbeInput(prefix = wire, fragment = 1))
    assertArrayEquals(ascii("abcde"), response.body)
  }

  @Test fun exactEightMiBChunkedBodyPassesAfterRemovingFramingBytes() {
    val header = ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n800000\r\n")
    val wire = header + ByteArray(bodyLimit) { 0x5a } + ascii("\r\n0\r\n\r\n")
    val response = readResponse(ByteArrayInputStream(wire))
    assertEquals(bodyLimit, response.body.size)
    assertEquals(0x5a.toByte(), response.body.last())
  }

  @Test fun chunkedResponseEndsAtTheTerminalTrailerWithoutReadingAnotherBody() {
    val wire = ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n0\r\n\r\n")
    val input = ProbeInput(prefix = wire, tailBytes = Long.MAX_VALUE, fragment = 1,
        readCeiling = wire.size.toLong())
    assertArrayEquals(ascii("a"), readResponse(input).body)
    assertEquals(wire.size.toLong(), input.bytesRead)
  }

  @Test fun oneDeclaredChunkAboveTheBodyBudgetIsRejectedBeforeReadingItsTail() {
    val header = ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n800001\r\n")
    val input = ProbeInput(prefix = header, tailBytes = Long.MAX_VALUE,
        readCeiling = header.size + 4096L)
    assertThrows(IOException::class.java) { readResponse(input) }
    assertTrue(input.bytesRead <= header.size + 4096L)
    assertFalse(input.eofSeen)
  }

  @Test fun cumulativeChunksCannotEachReuseTheFullBodyBudget() {
    val first = ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2\r\n")
    val input = ProbeInput(prefix = first, tailBytes = Long.MAX_VALUE, fragment = 1,
        readCeiling = first.size.toLong())
    assertThrows(IOException::class.java) { readResponse(input, maxBytes = 4) }
    assertEquals(first.size.toLong(), input.bytesRead)
  }

  @Test fun malformedOrOverflowingChunkSizesRejectInsteadOfReturningAPartialBody() {
    for (size in listOf("-1", "+1", "xyz", "80000000", "FFFFFFFFFFFFFFFFFFFFFFFF")) {
      assertBadChunked("1\r\na\r\n$size\r\nb\r\n0\r\n\r\n")
    }
  }

  @Test fun truncatedChunkDataRejectsInsteadOfReturningTheEarlierCompleteChunks() {
    assertBadChunked("1\r\na\r\n5\r\nbc")
  }

  @Test fun missingChunkCrlfCannotSkipTwoBytesOfArbitraryData() {
    assertBadChunked("1\r\naXX0\r\n\r\n")
  }

  @Test fun missingZeroChunkOrUnterminatedTrailersAreRejected() {
    assertBadChunked("1\r\na\r\n")
    assertBadChunked("1\r\na\r\n0\r\n")
    assertBadChunked("1\r\na\r\n0\r\nX-Fixture: truncated")
  }

  @Test fun oversizedChunkSizeLineIsRejectedBeforeItsUnboundedExtensionIsDrained() {
    val prefix = ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1;")
    val input = ProbeInput(prefix = prefix, tailBytes = Long.MAX_VALUE,
        readCeiling = prefix.size + chunkLineLimit + 1L)
    assertThrows(IOException::class.java) { readResponse(input) }
    assertTrue(input.bytesRead <= prefix.size + chunkLineLimit + 1L)
  }

  @Test fun excessiveTrailersAreBoundedIndependentlyOfTheDecodedBody() {
    val prefix = ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n0\r\nX-Fixture: ")
    val input = ProbeInput(prefix = prefix, tailBytes = Long.MAX_VALUE,
        readCeiling = prefix.size + headerLimit + 1L)
    assertThrows(IOException::class.java) { readResponse(input) }
    assertTrue(input.bytesRead <= prefix.size + headerLimit + 1L)
  }

  @Test fun repeatedValidChunkExtensionsCannotConsumeUnboundedFramingBytes() {
    val header = ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n")
    val chunk = "1;${"x".repeat(1000)}\r\na\r\n"
    val wire = header + ascii(chunk.repeat(300) + "0\r\n\r\n")
    val input = ProbeInput(prefix = wire, fragment = 1)
    assertThrows(IOException::class.java) { readResponse(input) }
    // At most 262 one-byte chunks can accompany 256 KiB of framing.
    assertTrue(input.bytesRead <= header.size + 256 * 1024 + 262L)
    assertFalse(input.eofSeen)
  }

  @Test fun unknownTransferCodingIsRejectedRatherThanDecodedAsAnImage() {
    assertBadResponse("HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n1\r\na\r\n0\r\n\r\n")
    assertBadResponse("HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\nabc")
  }

  @Test fun conflictingChunkedAndContentLengthFramingIsRejected() {
    assertBadResponse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 3\r\n\r\n" +
        "1\r\na\r\n0\r\n\r\n")
  }

  @Test fun oversizedHttpHeadersAreRejectedWhileReading() {
    val prefix = ascii("HTTP/1.1 200 OK\r\nX-Fixture: ")
    val input = ProbeInput(prefix = prefix, tailBytes = Long.MAX_VALUE,
        readCeiling = headerLimit + 1L)
    assertThrows(IOException::class.java) { readResponse(input) }
    assertTrue(input.bytesRead <= headerLimit + 1L)
    assertFalse(input.eofSeen)
  }

  @Test fun malformedStatusHeadersAndTruncatedHeaderTerminatorAreRejected() {
    for (wire in listOf(
        "not HTTP\r\n\r\nabc",
        "HTTP/1.1 bananas OK\r\n\r\nabc",
        "HTTP/1.1 20 OK\r\n\r\nabc",
        "HTTP/1.1 200 OK\r\nHeaderWithoutColon\r\n\r\nabc",
        "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n",
        "HTTP/1.1 200 OK\nContent-Length: 3\n\nabc")) {
      assertBadResponse(wire)
    }
  }

  @Test fun redirectPreservesRelativeLocationWithoutWaitingForAnUnboundedBody() {
    val header = ascii("HTTP/1.1 302 Found\r\nlOcAtIoN: /fixture/cover.png\r\n\r\n")
    val input = ProbeInput(prefix = header, tailBytes = Long.MAX_VALUE, fragment = 1,
        readCeiling = header.size.toLong())
    val response = readResponse(input)
    assertEquals(302, response.statusCode)
    assertEquals("/fixture/cover.png", response.location)
    assertArrayEquals(byteArrayOf(), response.body)
    assertEquals(header.size.toLong(), input.bytesRead)
  }

  private fun readBounded(input: InputStream, maxBytes: Int): ByteArray =
      MusicArtworkPolicy.readBounded(input, maxBytes)

  private fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int? =
      MusicArtworkPolicy.sampleSizeFor(width, height, maxEdge)

  private fun readResponse(input: InputStream, maxBytes: Int = bodyLimit): MusicArtworkPolicy.HttpResponse =
      MusicArtworkPolicy.readHttpResponse(input, maxBytes)

  private fun assertBadResponse(wire: String) {
    assertThrows(IOException::class.java) { readResponse(ByteArrayInputStream(ascii(wire))) }
  }

  private fun assertBadChunked(body: String) =
      assertBadResponse("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n$body")

  private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.ISO_8859_1)

  /** Generates a large tail without allocating it; attempting to drain it fails promptly. */
  private class ProbeInput(
      private val prefix: ByteArray = byteArrayOf(),
      private val tailBytes: Long = 0,
      private val fragment: Int = Int.MAX_VALUE,
      private val readCeiling: Long = Long.MAX_VALUE,
  ) : InputStream() {
    var bytesRead = 0L
      private set
    var eofSeen = false
      private set

    override fun read(): Int {
      val one = ByteArray(1)
      return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
      if (length == 0) return 0
      val remaining = if (bytesRead < prefix.size) {
        prefix.size - bytesRead
      } else {
        tailBytes - (bytesRead - prefix.size)
      }
      if (remaining == 0L) {
        eofSeen = true
        return -1
      }
      val count = minOf(length.toLong(), fragment.toLong(), remaining).toInt()
      if (bytesRead > readCeiling - count) {
        throw AssertionError("reader consumed beyond the permitted prefix/cap; unbounded tail read")
      }
      if (bytesRead < prefix.size) {
        prefix.copyInto(bytes, offset, bytesRead.toInt(), bytesRead.toInt() + count)
      } else {
        bytes.fill(0x5a.toByte(), offset, offset + count)
      }
      bytesRead += count
      return count
    }
  }
}

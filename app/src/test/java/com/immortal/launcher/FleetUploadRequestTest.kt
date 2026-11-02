/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class FleetUploadRequestTest {
  private fun request(input: InputStream, length: Int) =
      FleetHttpServer.Request("POST", "/fs/write", "", emptyMap(), input, length)

  @Test fun incompleteBodyThrowsInsteadOfAcknowledgingPrefix() {
    val out = ByteArrayOutputStream()
    assertThrows(EOFException::class.java) {
      request(ByteArrayInputStream("PART".toByteArray()), 100).streamBodyTo(out)
    }
    assertArrayEquals("PART".toByteArray(), out.toByteArray())
  }

  @Test fun emptyInterruptedBodyCannotBecomeSuccessfulEmptyUpload() {
    assertThrows(EOFException::class.java) {
      request(ByteArrayInputStream(byteArrayOf()), 10).streamBodyTo(ByteArrayOutputStream())
    }
  }

  @Test fun completeBinaryBodyReturnsExactBytes() {
    val bytes = ByteArray(150_003) { (it * 31).toByte() }
    val out = ByteArrayOutputStream()
    assertEquals(bytes.size.toLong(), request(ByteArrayInputStream(bytes), bytes.size).streamBodyTo(out))
    assertArrayEquals(bytes, out.toByteArray())
  }

  @Test fun deliberateZeroLengthUploadDoesNotReadInput() {
    val input = object : InputStream() {
      override fun read(): Int = error("empty upload must not read the next request")
    }
    assertEquals(0L, request(input, 0).streamBodyTo(ByteArrayOutputStream()))
  }

  @Test fun partiallyConsumedBodyStillRequiresEveryRemainingByte() {
    val req = request(ByteArrayInputStream("abc".toByteArray()), 5)
    assertEquals("ab", req.bodyText(2))
    assertThrows(EOFException::class.java) { req.streamBodyTo(ByteArrayOutputStream()) }
  }

  @Test fun outputFailureDoesNotDrainBeyondBytesAlreadyRead() {
    val input = ByteArrayInputStream("ABCDETRAILER".toByteArray())
    val req = request(input, 5)
    val out = object : OutputStream() {
      override fun write(value: Int) { throw IOException("public output failure") }
      override fun write(bytes: ByteArray, offset: Int, length: Int) {
        throw IOException("public output failure")
      }
    }
    assertThrows(IOException::class.java) { req.streamBodyTo(out) }
    req.drainRemaining()
    assertEquals("TRAILER", String(input.readBytes()))
  }

  @Test fun interruptedRequestPreservesExistingDestination() {
    val directory = kotlin.io.path.createTempDirectory("fleet-request-").toFile()
    try {
      val file = File(directory, "public.bin").apply { writeText("ORIGINAL") }
      val req = request(ByteArrayInputStream("PART".toByteArray()), 100)
      assertThrows(EOFException::class.java) { FleetUploadWriter.write(file, req::streamBodyTo) }
      assertEquals("ORIGINAL", file.readText())
      assertEquals(listOf("public.bin"), directory.list()!!.toList())
    } finally {
      directory.deleteRecursively()
    }
  }

  @Test fun interruptedNewDestinationLeavesNoFileOrStagingArtifact() {
    val directory = kotlin.io.path.createTempDirectory("fleet-request-").toFile()
    try {
      val file = File(directory, "absent.bin")
      val req = request(ByteArrayInputStream("PART".toByteArray()), 100)
      assertThrows(EOFException::class.java) { FleetUploadWriter.write(file, req::streamBodyTo) }
      assertFalse(file.exists())
      assertTrue(directory.list()!!.isEmpty())
    } finally {
      directory.deleteRecursively()
    }
  }
}

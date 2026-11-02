/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FleetUploadWriterTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun originalRemainsVisibleUntilBodyCompletes() {
    val destination = temporary.newFile("scratch.bin")
    val original = "existing public scratch".toByteArray(Charsets.UTF_8)
    destination.writeBytes(original)

    val written = FleetUploadWriter.write(destination) { output ->
      assertTrue(destination.isFile)
      assertArrayEquals(original, destination.readBytes())
      output.write(byteArrayOf(1, 2, 3))
      output.flush()
      assertArrayEquals(original, destination.readBytes())
      output.write(byteArrayOf(4, 5, 6))
      6L
    }

    assertEquals(6L, written)
    assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), destination.readBytes())
    assertEntries(temporary.root, "scratch.bin")
  }

  @Test
  fun completedBinaryBodyReplacesExactlyWithoutTrailingOldBytes() {
    val destination = temporary.newFile("binary.bin")
    destination.writeBytes(ByteArray(100) { 42 })
    val body = byteArrayOf(0, 1, 127, -128, -1, 0)

    val written = FleetUploadWriter.write(destination) { output ->
      output.write(body, 0, 2)
      output.write(body, 2, body.size - 2)
      body.size.toLong()
    }

    assertEquals(6L, written)
    assertArrayEquals(body, destination.readBytes())
    assertEntries(temporary.root, "binary.bin")
  }

  @Test
  fun unicodeFilenameAndUtf8BodyReplaceExactly() {
    val destination = temporary.newFile("家族-🎵.txt")
    destination.writeText("older contents")
    val body = "café / 家族 / 🎵\n".toByteArray(Charsets.UTF_8)

    val written = FleetUploadWriter.write(destination) { output ->
      output.write(body)
      body.size.toLong()
    }

    assertEquals(body.size.toLong(), written)
    assertArrayEquals(body, destination.readBytes())
    assertEntries(temporary.root, "家族-🎵.txt")
  }

  @Test
  fun completedEmptyBodyReplacesExistingFile() {
    val destination = temporary.newFile("empty.bin")
    destination.writeText("old contents")

    assertEquals(0L, FleetUploadWriter.write(destination) { 0L })

    assertTrue(destination.isFile)
    assertEquals(0L, destination.length())
    assertEntries(temporary.root, "empty.bin")
  }

  @Test
  fun newDestinationAppearsOnlyAfterCompletionAndParentsAreCreated() {
    val destination = File(temporary.root, "new/nested/upload.bin")

    val written = FleetUploadWriter.write(destination) { output ->
      assertFalse(destination.exists())
      output.write(byteArrayOf(9, 8))
      output.flush()
      assertFalse(destination.exists())
      2L
    }

    assertEquals(2L, written)
    assertArrayEquals(byteArrayOf(9, 8), destination.readBytes())
    assertEntries(requireNotNull(destination.parentFile), "upload.bin")
  }

  @Test
  fun streamFailureAfterPrefixPreservesOriginalAndRemovesOnlyOwnStaging() {
    val destination = temporary.newFile("scratch.bin")
    destination.writeText("existing 21-byte file!")
    val unrelated = temporary.newFile(".immortal-upload-unrelated.part")
    unrelated.writeText("another upload")
    val failure = IOException("body ended after six bytes")

    val thrown = assertThrows(IOException::class.java) {
      FleetUploadWriter.write(destination) { output ->
        output.write("prefix".toByteArray(Charsets.UTF_8))
        throw failure
      }
    }

    assertSame(failure, thrown)
    assertEquals("existing 21-byte file!", destination.readText())
    assertEquals("another upload", unrelated.readText())
    assertEntries(temporary.root, "scratch.bin", ".immortal-upload-unrelated.part")
  }

  @Test
  fun streamFailureAfterPrefixDoesNotLeaveNewDestinationOrStaging() {
    val destination = File(temporary.root, "new.bin")
    val failure = IOException("incomplete body")

    val thrown = assertThrows(IOException::class.java) {
      FleetUploadWriter.write(destination) { output ->
        output.write(byteArrayOf(1, 2, 3))
        throw failure
      }
    }

    assertSame(failure, thrown)
    assertFalse(destination.exists())
    assertEntries(temporary.root)
  }

  @Test
  fun cancellationAfterPrefixPreservesOriginalAndRemovesStaging() {
    val destination = temporary.newFile("scratch.bin")
    destination.writeText("keep this file")
    val cancellation = CancellationException("upload cancelled")

    val thrown = assertThrows(CancellationException::class.java) {
      FleetUploadWriter.write(destination) { output ->
        output.write(byteArrayOf(1, 2, 3))
        throw cancellation
      }
    }

    assertSame(cancellation, thrown)
    assertEquals("keep this file", destination.readText())
    assertEntries(temporary.root, "scratch.bin")
  }

  @Test
  fun realOutputFailurePreservesOriginalAndRemovesStaging() {
    val destination = temporary.newFile("scratch.bin")
    destination.writeText("keep this file")

    assertThrows(IOException::class.java) {
      FleetUploadWriter.write(destination) { output ->
        output.write(byteArrayOf(1, 2, 3))
        output.close()
        output.write(4)
        4L
      }
    }

    assertEquals("keep this file", destination.readText())
    assertEntries(temporary.root, "scratch.bin")
  }

  @Test
  fun outputIsClosedWhenWriteReturns() {
    val destination = File(temporary.root, "closed.bin")
    var completedOutput: OutputStream? = null

    assertEquals(1L, FleetUploadWriter.write(destination) { output ->
      completedOutput = output
      output.write(7)
      1L
    })

    assertThrows(IOException::class.java) { requireNotNull(completedOutput).write(8) }
    assertArrayEquals(byteArrayOf(7), destination.readBytes())
    assertEntries(temporary.root, "closed.bin")
  }

  @Test
  fun renameOntoDirectoryFailsAfterBodyAndPreservesDirectoryContents() {
    val destination = temporary.newFolder("occupied")
    val child = File(destination, "keep.txt").apply { writeText("keep directory contents") }
    var bodyCompleted = false

    assertThrows(IOException::class.java) {
      FleetUploadWriter.write(destination) { output ->
        output.write(byteArrayOf(1, 2, 3))
        bodyCompleted = true
        3L
      }
    }

    assertTrue("The completed body must reach the commit attempt", bodyCompleted)
    assertTrue(destination.isDirectory)
    assertEquals("keep directory contents", child.readText())
    assertEntries(destination, "keep.txt")
    assertEntries(temporary.root, "occupied")
  }

  @Test
  fun symlinkDestinationStillWritesTargetAndRetainsLink() {
    val targetDirectory = temporary.newFolder("targets")
    val target = File(targetDirectory, "target.txt").apply { writeText("old target") }
    val link = File(temporary.root, "upload-link")
    Files.createSymbolicLink(link.toPath(), target.toPath())
    val body = "replacement target".toByteArray(Charsets.UTF_8)

    assertEquals(body.size.toLong(), FleetUploadWriter.write(link) { output ->
      output.write(body)
      body.size.toLong()
    })

    assertTrue(Files.isSymbolicLink(link.toPath()))
    assertArrayEquals(body, target.readBytes())
    assertArrayEquals(body, link.readBytes())
    assertEntries(targetDirectory, "target.txt")
    assertEntries(temporary.root, "targets", "upload-link")
  }

  @Test
  fun concurrentWritersUseDistinctStagingAndPublishOnlyCompleteBodies() {
    val destination = temporary.newFile("shared.bin")
    destination.writeText("original")
    val writers = Executors.newFixedThreadPool(2)
    val ready = CountDownLatch(2)
    val releaseFirst = CountDownLatch(1)
    val releaseSecond = CountDownLatch(1)
    try {
      val first = writers.submit<Long> {
        FleetUploadWriter.write(destination) { output ->
          output.write("first-".toByteArray(Charsets.UTF_8))
          output.flush()
          ready.countDown()
          assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
          output.write("complete".toByteArray(Charsets.UTF_8))
          14L
        }
      }
      val second = writers.submit<Long> {
        FleetUploadWriter.write(destination) { output ->
          output.write("second-".toByteArray(Charsets.UTF_8))
          output.flush()
          ready.countDown()
          assertTrue(releaseSecond.await(5, TimeUnit.SECONDS))
          output.write("complete".toByteArray(Charsets.UTF_8))
          15L
        }
      }

      assertTrue("Both bodies must reach their streaming pause", ready.await(5, TimeUnit.SECONDS))
      assertEquals("original", destination.readText())
      val staging = requireNotNull(temporary.root.listFiles()).filter { it != destination }
      assertEquals(2, staging.size)
      assertEquals(2, staging.map { it.name }.toSet().size)
      assertTrue(staging.all { it.name.startsWith(".immortal-upload-") && it.name.endsWith(".part") })
      assertEquals(setOf("first-", "second-"), staging.map { it.readText() }.toSet())

      releaseFirst.countDown()
      assertEquals(14L, first.get(5, TimeUnit.SECONDS).toLong())
      assertEquals("first-complete", destination.readText())
      val remaining = requireNotNull(temporary.root.listFiles()).filter { it != destination }
      assertEquals(1, remaining.size)
      assertEquals("second-", remaining.single().readText())

      releaseSecond.countDown()
      assertEquals(15L, second.get(5, TimeUnit.SECONDS).toLong())
      assertEquals("second-complete", destination.readText())
      assertEntries(temporary.root, "shared.bin")
    } finally {
      releaseFirst.countDown()
      releaseSecond.countDown()
      writers.shutdownNow()
      assertTrue(writers.awaitTermination(5, TimeUnit.SECONDS))
    }
  }

  private fun assertEntries(directory: File, vararg expected: String) {
    assertEquals(expected.toSet(), requireNotNull(directory.list()).toSet())
  }
}

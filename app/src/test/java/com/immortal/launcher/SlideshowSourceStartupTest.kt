/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class SlideshowSourceStartupTest {
  @Test
  fun nullPreparationReleasesOnceAfterPreparationAndSkipsHandoff() {
    val source = TrackedSource()
    val events = mutableListOf<String>()
    var handedOff = false

    val accepted = prepareSlideshowSource<TrackedSource, Any>(
        source,
        prepare = {
          assertSame(source, it)
          assertEquals(0, source.closeCount)
          events += "prepared"
          null
        },
        release = {
          assertSame(source, it)
          events += "released"
          it.close()
        },
        handoff = { _, _, _ -> handedOff = true },
    )

    assertFalse(accepted)
    assertFalse(handedOff)
    assertEquals(listOf("prepared", "released"), events)
    assertEquals(1, source.closeCount)
  }

  @Test
  fun successfulHandoffKeepsSourceOpenUntilDeferredDiscardAndClosesOnlyOnce() {
    val source = TrackedSource()
    val prepared = Any()
    val events = mutableListOf<String>()
    var discard: (() -> Unit)? = null

    val accepted = prepareSlideshowSource(
        source,
        prepare = {
          assertSame(source, it)
          assertEquals(0, source.closeCount)
          events += "prepared"
          prepared
        },
        release = { it.close() },
        handoff = { actualSource, actualPrepared, onDiscard ->
          assertSame(source, actualSource)
          assertSame(prepared, actualPrepared)
          assertEquals(0, source.closeCount)
          events += "handed off"
          discard = onDiscard
        },
    )

    assertTrue(accepted)
    assertEquals(listOf("prepared", "handed off"), events)
    assertEquals(0, source.closeCount)
    val onDiscard = checkNotNull(discard)
    repeat(3) { onDiscard() }
    assertEquals(1, source.closeCount)
  }

  @Test
  fun synchronousDiscardStillReturnsTrueAndRepeatedDiscardDoesNotCloseAgain() {
    val source = TrackedSource()
    var discard: (() -> Unit)? = null

    val accepted = prepareSlideshowSource(
        source,
        prepare = { Any() },
        release = { it.close() },
        handoff = { _, _, onDiscard ->
          assertEquals(0, source.closeCount)
          discard = onDiscard
          onDiscard()
          onDiscard()
          assertEquals(1, source.closeCount)
        },
    )

    assertTrue(accepted)
    checkNotNull(discard).invoke()
    assertEquals(1, source.closeCount)
  }

  @Test
  fun prepareErrorReleasesSourceAndEscapesWithoutHandoff() {
    val source = TrackedSource()
    val failure = AssertionError("Preparation failed")
    var handedOff = false

    val actual = thrownBy {
      prepareSlideshowSource<TrackedSource, Any>(
          source,
          prepare = { throw failure },
          release = { it.close() },
          handoff = { _, _, _ -> handedOff = true },
      )
    }

    assertSame(failure, actual)
    assertEquals(1, source.closeCount)
    assertFalse(handedOff)
    assertEquals(0, actual.suppressed.size)
  }

  @Test
  fun handoffErrorReleasesSourceAndLaterDiscardDoesNotCloseAgain() {
    val source = TrackedSource()
    val failure = AssertionError("Handoff failed")
    var discard: (() -> Unit)? = null

    val actual = thrownBy {
      prepareSlideshowSource(
          source,
          prepare = { Any() },
          release = { it.close() },
          handoff = { _, _, onDiscard ->
            assertEquals(0, source.closeCount)
            discard = onDiscard
            throw failure
          },
      )
    }

    assertSame(failure, actual)
    assertEquals(1, source.closeCount)
    checkNotNull(discard).invoke()
    assertEquals(1, source.closeCount)
    assertEquals(0, actual.suppressed.size)
  }

  @Test
  fun prepareFailureRemainsPrimaryWhenReleaseThrows() {
    val source = TrackedSource()
    val failure = IOException("Preparation failed")
    val cleanupFailure = IllegalStateException("Release failed")
    var handedOff = false

    val actual = thrownBy {
      prepareSlideshowSource<TrackedSource, Any>(
          source,
          prepare = { throw failure },
          release = {
            it.close()
            throw cleanupFailure
          },
          handoff = { _, _, _ -> handedOff = true },
      )
    }

    assertSame(failure, actual)
    assertEquals(1, source.closeCount)
    assertFalse(handedOff)
    assertEquals(1, actual.suppressed.size)
    assertSame(cleanupFailure, actual.suppressed.single())
  }

  @Test
  fun handoffFailureRemainsPrimaryWhenReleaseThrowsAndDiscardDoesNotRetryRelease() {
    val source = TrackedSource()
    val failure = IllegalStateException("Handoff failed")
    val cleanupFailure = IOException("Release failed")
    var discard: (() -> Unit)? = null

    val actual = thrownBy {
      prepareSlideshowSource(
          source,
          prepare = { Any() },
          release = {
            it.close()
            throw cleanupFailure
          },
          handoff = { _, _, onDiscard ->
            discard = onDiscard
            throw failure
          },
      )
    }

    assertSame(failure, actual)
    assertEquals(1, source.closeCount)
    assertEquals(1, actual.suppressed.size)
    assertSame(cleanupFailure, actual.suppressed.single())
    checkNotNull(discard).invoke()
    assertEquals(1, source.closeCount)
  }

  @Test
  fun handoffDiscardThenThrowDoesNotReleaseTwice() {
    val source = TrackedSource()
    val failure = IllegalStateException("Failed after discard")
    var discard: (() -> Unit)? = null

    val actual = thrownBy {
      prepareSlideshowSource(
          source,
          prepare = { Any() },
          release = { it.close() },
          handoff = { _, _, onDiscard ->
            discard = onDiscard
            onDiscard()
            throw failure
          },
      )
    }

    assertSame(failure, actual)
    assertEquals(1, source.closeCount)
    checkNotNull(discard).invoke()
    assertEquals(1, source.closeCount)
    assertEquals(0, actual.suppressed.size)
  }

  @Test(timeout = 10_000)
  fun prepareIOExceptionClosesRealLoopbackConnectionAndPeerObservesEof() {
    val loopback = InetAddress.getLoopbackAddress()
    ServerSocket().use { server ->
      server.soTimeout = 2_000
      server.bind(InetSocketAddress(loopback, 0))
      Socket().use { connection ->
        connection.connect(InetSocketAddress(loopback, server.localPort), 2_000)
        connection.soTimeout = 2_000
        server.accept().use { peer ->
          peer.soTimeout = 2_000
          val failure = IOException("Listing failed after connection opened")
          var closeCount = 0
          var handedOff = false

          val actual = thrownBy {
            prepareSlideshowSource<Socket, Any>(
                connection,
                prepare = {
                  assertSame(connection, it)
                  assertFalse(it.isClosed)
                  throw failure
                },
                release = {
                  closeCount++
                  it.close()
                },
                handoff = { _, _, _ -> handedOff = true },
            )
          }

          assertSame(failure, actual)
          assertFalse(handedOff)
          assertEquals(1, closeCount)
          assertTrue(connection.isClosed)
          // Check EOF before use closes the connection, so test cleanup cannot mask a leak.
          assertEquals(-1, peer.getInputStream().read())
        }
      }
    }
  }

  @Test
  fun exceptionalSmbEntryListingClosesShareAndSkipsHandoff() {
    val share = mock(DiskShare::class.java)
    val entry = mock(FileIdBothDirectoryInformation::class.java)
    val failure = IllegalStateException("Cannot read entry name")
    `when`(share.list("")).thenReturn(listOf(entry))
    `when`(entry.fileName).thenThrow(failure)
    val source = SmbSource("unused-host", "photos", "", "unused-user", "unused-password")
    SmbSource::class.java.getDeclaredField("share").apply {
      isAccessible = true
      set(source, share)
    }
    var handedOff = false

    try {
      val actual = thrownBy {
        prepareSlideshowSource(
            source,
            prepare = { it.listImages() },
            release = { it.close() },
            handoff = { _, _, _ -> handedOff = true },
        )
      }

      assertSame(failure, actual)
      assertFalse(handedOff)
      verify(share).close()
    } finally {
      source.close()
    }
  }

  private class TrackedSource {
    var closeCount = 0
      private set

    fun close() {
      closeCount++
    }
  }

  private fun thrownBy(action: () -> Unit): Throwable {
    try {
      action()
    } catch (failure: Throwable) {
      return failure
    }
    throw AssertionError("Expected startup to throw")
  }
}

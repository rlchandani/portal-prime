/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** The production receive loop, with TCP read boundaries and PCM sink acceptance controlled. */
class LanAudioPcmWriteTest {
  private val pcm = byteArrayOf(0x10, 0x21, 0x32, 0x43, 0x54, 0x65, 0x76, 0x07)

  @Test fun contiguousFullWritesPreserveEveryCompleteSample() = withFixture { f ->
    assertNull(f.play(FragmentedInput(pcm, listOf(8))))
    assertArrayEquals(pcm, f.accepted.toByteArray())
    f.assertReleasedOnce()
    assertFalse(f.audio.isActive)
  }

  @Test fun oddTcpFragmentsAreJoinedBeforeWritingCompleteSamples() = withFixture { f ->
    assertNull(f.play(FragmentedInput(pcm, listOf(1, 3, 1, 3))))
    assertArrayEquals(pcm, f.accepted.toByteArray())
    assertTrue(f.writeSizes.all { it % 2 == 0 })
    f.assertReleasedOnce()
  }

  @Test fun alignedShortWritesDrainTheRemainderBeforeReadingMorePcm() =
      withFixture(maxAcceptedBytes = 2) { f ->
        assertNull(f.play(FragmentedInput(pcm, listOf(8))))
        assertArrayEquals(pcm, f.accepted.toByteArray())
        f.assertReleasedOnce()
      }

  @Test fun fragmentedReadsAndShortWritesPreserveTheSamePcmSequence() =
      withFixture(maxAcceptedBytes = 2) { f ->
        assertNull(f.play(FragmentedInput(pcm, listOf(3, 1, 3, 1))))
        assertArrayEquals(pcm, f.accepted.toByteArray())
        assertTrue(f.writeSizes.all { it % 2 == 0 })
        f.assertReleasedOnce()
      }

  @Test fun negativeSinkResultEndsPlaybackWithoutReadingLaterPeerData() =
      withFixture(result = AudioTrack.ERROR_DEAD_OBJECT) { f ->
        val input = FragmentedInput(pcm, listOf(4, 4))
        // An error may propagate to startListening's existing runCatching boundary.
        f.play(input)
        assertEquals(1, input.readCalls)
        assertEquals(1, f.writeSizes.size)
        assertEquals(0, f.accepted.size())
        assertFalse(f.audio.isActive)
        f.assertReleasedOnce()
      }

  @Test fun zeroSinkProgressEndsPlaybackWithoutConsumingAnotherPeerChunk() =
      withFixture(result = 0) { f ->
        val input = FragmentedInput(pcm, listOf(4, 4))
        f.play(input)
        assertEquals(1, input.readCalls)
        assertEquals(1, f.writeSizes.size)
        assertEquals(0, f.accepted.size())
        assertFalse(f.audio.isActive)
        f.assertReleasedOnce()
      }

  @Test fun oddEofRetainsCompleteSamplesWithoutSubmittingATruncatedSample() =
      withFixture { f ->
        val input = FragmentedInput(byteArrayOf(0x10, 0x21, 0x32, 0x43, 0x54), listOf(3, 2))
        // A malformed trailing byte may be discarded or reported as a stream error.
        f.play(input)
        assertArrayEquals(byteArrayOf(0x10, 0x21, 0x32, 0x43), f.accepted.toByteArray())
        assertTrue(f.writeSizes.all { it % 2 == 0 })
        f.assertReleasedOnce()
      }

  @Test fun readFailureReleasesPlaybackAndRetiresTheListeningSession() = withFixture { f ->
    val input = FragmentedInput(pcm, listOf(4), failAfterBytes = 4)
    assertTrue(f.play(input) is IOException)
    assertArrayEquals(byteArrayOf(0x10, 0x21, 0x32, 0x43), f.accepted.toByteArray())
    assertFalse(f.audio.isActive)
    f.assertReleasedOnce()
  }

  @Test fun retirementDuringShortWriteDoesNotDrainOrStopTheReplacementSession() =
      withFixture(maxAcceptedBytes = 2) { f ->
        var replacement: LanAudioSessions.Session? = null
        f.afterAccepted = { replacement = f.sessions.start() }
        val input = FragmentedInput(pcm, listOf(8))
        assertNull(f.play(input))
        assertArrayEquals(byteArrayOf(0x10, 0x21), f.accepted.toByteArray())
        assertEquals(1, input.readCalls)
        assertEquals(1, f.writeSizes.size)
        assertTrue(replacement!!.isActive)
        assertTrue(f.audio.isActive)
        f.assertReleasedOnce()
      }

  /** read(byte[]) can split a 16-bit sample anywhere, even when the peer writes aligned PCM. */
  private class FragmentedInput(
      private val bytes: ByteArray,
      private val fragments: List<Int>,
      private val failAfterBytes: Int? = null,
  ) : InputStream() {
    private var offset = 0
    private var fragment = 0
    var readCalls = 0
      private set

    override fun read(): Int = error("The receive loop must use bulk stream reads")

    override fun read(buffer: ByteArray, destinationOffset: Int, length: Int): Int {
      readCalls++
      if (failAfterBytes != null && offset >= failAfterBytes) throw IOException("owned read failure")
      if (offset == bytes.size) return -1
      val count = minOf(length, bytes.size - offset, fragments.getOrElse(fragment++) { bytes.size })
      require(count > 0)
      bytes.copyInto(buffer, destinationOffset, offset, offset + count)
      offset += count
      return count
    }
  }

  private class Fixture(maxAcceptedBytes: Int, result: Int?) {
    val audio = LanAudio()
    val sessions = LanAudio::class.java.getDeclaredField("sessions").let {
      it.isAccessible = true
      it.get(audio) as LanAudioSessions
    }
    private val session = sessions.start()
    val track = Mockito.mock(AudioTrack::class.java)
    val accepted = ByteArrayOutputStream()
    val writeSizes = mutableListOf<Int>()
    var afterAccepted: (() -> Unit)? = null

    init {
      require(maxAcceptedBytes > 0 && maxAcceptedBytes % 2 == 0)
      Mockito.`when`(track.write(
          Mockito.any(ByteArray::class.java), Mockito.anyInt(), Mockito.anyInt())).thenAnswer { call ->
        val bytes = call.getArgument<ByteArray>(0)
        val offset = call.getArgument<Int>(1)
        val length = call.getArgument<Int>(2)
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        writeSizes += length
        // PCM16 mono accepts complete two-byte frames. A short result accepts only its prefix.
        val count = result ?: minOf(length - length % 2, maxAcceptedBytes)
        if (count > 0) {
          require(count <= length && count % 2 == 0)
          accepted.write(bytes, offset, count)
          afterAccepted?.invoke()
        }
        count
      }
    }

    fun play(input: InputStream): Throwable? {
      val method = LanAudio::class.java.getDeclaredMethod(
          "playFrom", LanAudioSessions.Session::class.java, InputStream::class.java)
      method.isAccessible = true
      return try {
        method.invoke(audio, session, input)
        null
      } catch (error: InvocationTargetException) {
        error.targetException
      } finally {
        // The surrounding production startListening worker owns session completion.
        sessions.finish(session)
      }
    }

    fun assertReleasedOnce() {
      Mockito.verify(track, Mockito.times(1)).play()
      Mockito.verify(track, Mockito.times(1)).stop()
      Mockito.verify(track, Mockito.times(1)).release()
    }
  }

  private fun withFixture(
      maxAcceptedBytes: Int = 2048,
      result: Int? = null,
      operation: (Fixture) -> Unit,
  ) {
    val f = Fixture(maxAcceptedBytes, result)
    try {
      Mockito.mockStatic(AudioTrack::class.java).use { audioTrack ->
        audioTrack.`when`<Int> {
          AudioTrack.getMinBufferSize(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())
        }.thenReturn(4096)
        Mockito.mockConstruction(AudioAttributes.Builder::class.java,
            Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)).use {
          Mockito.mockConstruction(AudioFormat.Builder::class.java,
              Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)).use {
            Mockito.mockConstruction(AudioTrack.Builder::class.java,
                Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { builder, _ ->
              Mockito.`when`(builder.build()).thenReturn(f.track)
            }.use { operation(f) }
          }
        }
      }
    } finally {
      f.audio.stop()
    }
  }
}
